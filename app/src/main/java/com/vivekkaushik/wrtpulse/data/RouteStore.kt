package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.IpMath
import com.vivekkaushik.wrtpulse.ops.KernelRoute
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.StaticRoute
import com.vivekkaushik.wrtpulse.ops.WanLink
import java.net.Inet6Address
import java.net.InetAddress

/** A static route being written, before it has a section — or an edit of one that has. */
data class RouteDraft(
    val id: Int,
    /** Set when this draft rewrites an existing section rather than adding one. */
    val replaces: String? = null,
    val ipv6: Boolean = false,
    val target: String = "",
    val gateway: String = "",
    val iface: String = "",
    val metric: String = "",
    val mtu: String = "",
    val table: String = "",
    val type: String = "unicast",
    val onlink: Boolean = false,
    val disabled: Boolean = false,
)

/** One row of the configured list: a saved route as it will be after the batch, or a draft. */
data class RouteRow(
    val section: String,
    val ipv6: Boolean,
    val target: String,
    val gateway: String,
    val iface: String,
    val metric: String,
    val type: String,
    val disabled: Boolean,
    val isDraft: Boolean,
    /** A draft that rewrites a saved section, as opposed to one adding a new one. */
    val editing: Boolean,
    val deleting: Boolean,
    val changed: Boolean,
    /** The kernel entry this route produced, when the kernel carries one. */
    val live: KernelRoute?,
)

/**
 * Static routes — `config route` and `config route6` in `/etc/config/network`.
 *
 * The same shape as [FirewallStore]: one read, staged edits over it, a diff to review, one
 * batch. Two things are its own. It is [Refreshable], because the point of the second tab is
 * what the kernel holds right now, and that changes under the app. And a route can be an
 * anonymous section, `@route[0]`, whose index is the only handle uci gives — so an edit
 * rewrites the section in place instead of deleting and re-adding it, and deletions run last
 * and highest index first, because removing `@route[0]` renumbers `@route[1]`.
 */
class RouteStore(private val session: RouterSession) : Refreshable {

    var routes by mutableStateOf<List<StaticRoute>>(emptyList()); private set
    var kernel by mutableStateOf<List<KernelRoute>>(emptyList()); private set

    /** Interface names a route can leave through, loopback excluded. */
    var interfaces by mutableStateOf<List<String>>(emptyList()); private set

    /** What netifd says each interface holds — device and address, for the gateway check. */
    var links by mutableStateOf<List<WanLink>>(emptyList()); private set

    private var lanCidr by mutableStateOf<Pair<Long, Int>?>(null)

    override var loaded by mutableStateOf(false); private set
    override var applying by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var rolledBack by mutableStateOf(false); private set

    /** The auto-snapshot hook; a failure here aborts the apply. */
    var beforeApply: (suspend () -> Unit)? = null

    // ---- staging ----

    /** Scalar edits on saved sections: uci path → (saved, staged). The disabled toggle. */
    val staged = mutableStateMapOf<String, Pair<String, String>>()

    /** Whole sections to remove, as `network.<section>`. */
    val deletions = mutableStateListOf<String>()

    val drafts = mutableStateListOf<RouteDraft>()

    private var nextId = 1

    /** Moves the counter past every `wrtpulse_route[6]_N` already on the router. */
    private fun seedNextId() {
        val used = routes.mapNotNull { r ->
            Regex("""^wrtpulse_route6?_(\d+)$""").find(r.section)?.groupValues?.get(1)?.toIntOrNull()
        }
        nextId = maxOf(nextId, (used.maxOrNull() ?: 0) + 1)
    }

    val pendingCount: Int get() = staged.size + deletions.size + drafts.size

    // ---- reading ----

    override suspend fun load() {
        try {
            val out = session.exec(Commands.ROUTES_STATE, timeoutMs = 20_000).requireOk("read routes").stdout
            ingest(Parsers.sections(out))
            loaded = true
            error = null
        } catch (e: SshException) {
            error = e.message
        }
    }

    /** [load] without the round trip — where the parsing lives, and what the tests drive. */
    fun ingest(parts: Map<String, String>) {
        val uci = Parsers.uciShow(parts["net"].orEmpty())
        routes = Parsers.staticRoutes(uci)
        interfaces = Parsers.networkInterfaces(uci).filter { it != "loopback" }.sorted()
        links = Parsers.wanLinks(parts["dump"].orEmpty())
        kernel = Parsers.kernelRoutes(parts["v4"].orEmpty(), ipv6 = false) +
            Parsers.kernelRoutes(parts["v6"].orEmpty(), ipv6 = true)
        lanCidr = Parsers.lanNet(uci)?.let { lan ->
            val prefix = IpMath.prefixOf(lan.netmask) ?: lan.cidrPrefix ?: 24
            IpMath.parse(lan.ipaddr)?.let { IpMath.networkOf(it, prefix) to prefix }
        }
        seedNextId()
    }

    // ---- what the screen shows ----

    /** The netdev behind an interface name, from the live dump — `lan` is `br-lan`. */
    private fun deviceOf(iface: String): String? =
        links.firstOrNull { it.name == iface }?.device?.ifBlank { null }

    /** `10.1.1.1/32` and `10.1.1.1` are the same host route; the kernel prints the second. */
    private fun normalTarget(target: String, ipv6: Boolean): String {
        val t = target.trim()
        return when {
            t == "default" -> if (ipv6) "::/0" else "0.0.0.0/0"
            !ipv6 && t.endsWith("/32") -> t.removeSuffix("/32")
            ipv6 && t.endsWith("/128") -> t.removeSuffix("/128")
            else -> t
        }
    }

    /** The kernel entry a configured route produced, matched on destination, device and gateway. */
    fun liveFor(target: String, iface: String, gateway: String, ipv6: Boolean): KernelRoute? {
        val dst = normalTarget(target, ipv6)
        val dev = deviceOf(iface)
        val gw = gateway.trim()
        return kernel.firstOrNull { k ->
            k.ipv6 == ipv6 && k.dst == dst &&
                (dev == null || k.dev.isEmpty() || k.dev == dev) &&
                (gw.isEmpty() || k.via == gw)
        }
    }

    fun rows(): List<RouteRow> {
        val saved = routes
            .filter { r -> drafts.none { it.replaces == r.section } }
            .map { r ->
                val path = "network.${r.section}"
                val disabled = staged["$path.disabled"]?.second?.let { it == "1" } ?: r.disabled
                RouteRow(
                    section = r.section, ipv6 = r.ipv6, target = r.target, gateway = r.gateway,
                    iface = r.iface, metric = r.metric, type = r.type, disabled = disabled,
                    isDraft = false, editing = false, deleting = path in deletions,
                    changed = "$path.disabled" in staged,
                    live = liveFor(r.target, r.iface, r.gateway, r.ipv6),
                )
            }
        val drafted = drafts.map { d ->
            RouteRow(
                section = draftSection(d), ipv6 = d.ipv6, target = d.target.trim(), gateway = d.gateway.trim(),
                iface = d.iface, metric = d.metric.trim(), type = d.type, disabled = d.disabled,
                isDraft = true, editing = d.replaces != null, deleting = false, changed = true,
                live = liveFor(d.target, d.iface, d.gateway, d.ipv6),
            )
        }
        return saved + drafted
    }

    /** The kernel table, each entry flagged when a configured route is what put it there. */
    fun kernelRows(): List<Pair<KernelRoute, Boolean>> {
        val produced = rows().mapNotNull { it.live }.toSet()
        return kernel
            .sortedWith(compareBy({ it.ipv6 }, { it.dst != "0.0.0.0/0" && it.dst != "::/0" }, { it.metric ?: 0 }, { it.dst }))
            .map { it to (it in produced) }
    }

    // ---- staging ----

    private fun stage(path: String, saved: String, wanted: String) {
        if (wanted == saved) staged.remove(path) else staged[path] = saved to wanted
    }

    fun toggleDisabled(section: String) {
        val r = routes.firstOrNull { it.section == section } ?: return
        val path = "network.$section.disabled"
        val saved = if (r.disabled) "1" else "0"
        val now = staged[path]?.second ?: saved
        stage(path, saved, if (now == "1") "0" else "1")
    }

    fun deleteSection(section: String) {
        val path = "network.$section"
        if (path !in deletions) deletions.add(path)
        staged.keys.filter { it.startsWith("$path.") }.forEach { staged.remove(it) }
    }

    fun undoDelete(section: String) = deletions.remove("network.$section")

    fun removeDraft(id: Int) = drafts.removeAll { it.id == id }

    /** The draft behind a row, when the row is one — an edit of a saved route included. */
    fun draftFor(section: String): RouteDraft? = drafts.firstOrNull { draftSection(it) == section }

    /** Takes a row off the list: a draft is dropped, a saved section is staged for deletion. */
    fun removeRoute(section: String) {
        val draft = draftFor(section)
        if (draft != null) drafts.remove(draft) else deleteSection(section)
    }

    fun newDraft(replaces: StaticRoute? = null, ipv6: Boolean = false): RouteDraft = RouteDraft(
        id = nextId++,
        replaces = replaces?.section,
        ipv6 = replaces?.ipv6 ?: ipv6,
        target = replaces?.target.orEmpty(),
        gateway = replaces?.gateway.orEmpty(),
        iface = replaces?.iface ?: interfaces.firstOrNull { it == "lan" } ?: interfaces.firstOrNull().orEmpty(),
        metric = replaces?.metric.orEmpty(),
        mtu = replaces?.mtu.orEmpty(),
        table = replaces?.table.orEmpty(),
        type = replaces?.type ?: "unicast",
        onlink = replaces?.onlink ?: false,
        disabled = replaces?.disabled ?: false,
    )

    /** Stages a route once it passes [routeProblem]. Returns the problem otherwise. */
    fun stageRoute(draft: RouteDraft): String? {
        routeProblem(draft)?.let { return it }
        drafts.removeAll { it.id == draft.id }
        drafts.add(draft)
        // An edit supersedes any toggle staged on the same section.
        draft.replaces?.let { section -> staged.keys.filter { it.startsWith("network.$section.") }.forEach { staged.remove(it) } }
        return null
    }

    fun revert() {
        staged.clear()
        deletions.clear()
        drafts.clear()
        error = null
    }

    // ---- refusals and warnings ----

    fun routeProblem(d: RouteDraft): String? {
        val family = if (d.ipv6) "IPv6" else "IPv4"
        val target = d.target.trim()
        val example = if (d.ipv6) "2001:db8::/32, or ::/0 for everything" else "10.20.0.0/16, or 0.0.0.0/0 for everything"
        if (target.isEmpty()) return "A route needs a destination — $example."
        if (!validPrefix(target, d.ipv6)) {
            return if (looksIpv6(target) != d.ipv6) "'$target' is an ${if (d.ipv6) "IPv4" else "IPv6"} destination — switch the route to ${if (d.ipv6) "IPv4" else "IPv6"}."
            else "'$target' is not an $family ${if (d.ipv6) "prefix" else "network"} — $example."
        }
        if (d.iface.isBlank()) return "Pick the interface the route leaves through."
        if (d.iface !in interfaces) return "There is no interface called '${d.iface}' — netifd would ignore the route without a word."
        val gw = d.gateway.trim()
        if (gw.isNotEmpty() && !validAddress(gw, d.ipv6)) return "'$gw' is not an $family address."
        if (d.type != "unicast" && gw.isNotEmpty()) return "A ${d.type} route has no gateway — the kernel answers for the destination itself."
        if (d.type !in ROUTE_TYPES) return "'${d.type}' is not a route type."
        d.metric.trim().takeIf { it.isNotEmpty() }?.let { m ->
            if (m.toLongOrNull()?.let { it in 0..4_294_967_295L } != true) return "'$m' is not a metric. Lower wins; 0 to a few hundred is usual."
        }
        d.mtu.trim().takeIf { it.isNotEmpty() }?.let { m ->
            val n = m.toIntOrNull() ?: return "'$m' is not an MTU."
            if (n < 576) return "An MTU below 576 breaks IPv4 — 1500 is standard."
            if (n > 9200) return "$n is past what the drivers here will take."
        }
        d.table.trim().takeIf { it.isNotEmpty() }?.let { t ->
            if (t.toLongOrNull()?.let { it in 1..4_294_967_295L } != true && t !in TABLE_NAMES) {
                return "'$t' is not a routing table — a number, or main, local, default."
            }
        }
        return null
    }

    /** What stops the batch from being sent at all — a draft that went bad after staging. */
    fun problems(): List<String> = drafts.mapNotNull { routeProblem(it) }.distinct()

    /** What the reviewer should know that the diff does not say. */
    fun warnings(): List<String> = buildList {
        drafts.forEach { d ->
            val gw = d.gateway.trim()
            val target = normalTarget(d.target, d.ipv6)
            if (!d.ipv6 && gw.isNotEmpty() && !d.onlink && d.type == "unicast") {
                val link = links.firstOrNull { it.name == d.iface }
                val gwIp = IpMath.parse(gw)
                if (link != null && link.address.isNotEmpty() && gwIp != null) {
                    val net = IpMath.parse(link.address)?.let { IpMath.networkOf(it, link.prefix) }
                    if (net != null && IpMath.networkOf(gwIp, link.prefix) != net) {
                        add(
                            "$gw is not inside ${d.iface}'s subnet (${link.cidr}), so the kernel will reject " +
                                "the route as unreachable. Turn on onlink, or pick the interface that owns it."
                        )
                    }
                }
            }
            if (target == "0.0.0.0/0" || target == "::/0") {
                val existing = kernel.filter { it.ipv6 == d.ipv6 && it.dst == target }
                if (existing.isNotEmpty()) {
                    add(
                        "This is a second default route. The kernel already has one via " +
                            "${existing.first().via.ifEmpty { existing.first().dev }} (metric ${existing.first().metric ?: 0}); " +
                            "the lower metric wins, and this one has metric ${d.metric.trim().ifEmpty { "0" }}."
                    )
                }
            }
            if (!d.ipv6) {
                lanCidr?.let { (lanNet, prefix) ->
                    val (addr, len) = splitPrefix(target)
                    val ip = IpMath.parse(addr)
                    if (ip != null && len != null && len <= prefix && IpMath.networkOf(lanNet, len) == IpMath.networkOf(ip, len)) {
                        add("$target covers the LAN's own subnet. Clients on the LAN would be routed away from it.")
                    }
                }
            }
        }
        deletions.forEach { path ->
            val r = routes.firstOrNull { "network.${it.section}" == path } ?: return@forEach
            if (liveFor(r.target, r.iface, r.gateway, r.ipv6) != null) {
                add("The kernel carries ${r.target} right now; without it, that traffic follows the default route instead.")
            }
        }
    }

    // ---- the batch ----

    private fun draftSection(d: RouteDraft) = d.replaces ?: "wrtpulse_route${if (d.ipv6) "6" else ""}_${d.id}"

    private fun routeOptions(d: RouteDraft): List<Pair<String, String>> = buildList {
        add("interface" to d.iface)
        add("target" to d.target.trim())
        d.gateway.trim().takeIf { it.isNotEmpty() }?.let { add("gateway" to it) }
        d.metric.trim().takeIf { it.isNotEmpty() }?.let { add("metric" to it) }
        d.mtu.trim().takeIf { it.isNotEmpty() }?.let { add("mtu" to it) }
        d.table.trim().takeIf { it.isNotEmpty() }?.let { add("table" to it) }
        if (d.type != "unicast") add("type" to d.type)
        if (d.onlink) add("onlink" to "1")
        if (d.disabled) add("disabled" to "1")
    }

    /** New sections as (section, type, options), so ops and the diff agree by construction. */
    private fun additions(): List<Triple<String, String, List<Pair<String, String>>>> =
        drafts.filter { it.replaces == null }.map { d -> Triple(draftSection(d), if (d.ipv6) "route6" else "route", routeOptions(d)) }

    /**
     * Edits of saved sections, rewritten in place: the options to set, and the ones the old
     * section carried that the new one does not. A `netmask` from a pre-CIDR config goes too,
     * because the target now carries the mask.
     */
    private fun rewrites(): List<Triple<String, List<Pair<String, String>>, List<String>>> =
        drafts.mapNotNull { d ->
            val section = d.replaces ?: return@mapNotNull null
            val saved = routes.firstOrNull { it.section == section } ?: return@mapNotNull null
            val options = routeOptions(d)
            val keep = options.map { it.first }.toSet()
            val gone = saved.options.filter { it !in keep && it in ROUTE_OPTIONS }.sorted()
            val changed = options.filter { (k, v) -> savedOption(saved, k) != v }
            Triple(section, changed, gone)
        }

    private fun savedOption(r: StaticRoute, key: String): String = when (key) {
        "interface" -> r.iface
        "target" -> if ("netmask" in r.options) "" else r.target
        "gateway" -> r.gateway
        "metric" -> r.metric
        "mtu" -> r.mtu
        "table" -> r.table
        "type" -> if (r.type == "unicast") "" else r.type
        "onlink" -> if (r.onlink) "1" else ""
        "disabled" -> if (r.disabled) "1" else ""
        else -> ""
    }

    /**
     * Deletions, highest anonymous index first: `delete network.@route[0]` turns `@route[1]`
     * into `@route[0]`, so the batch has to take them from the top.
     */
    private fun removals(): List<String> = deletions.sortedWith(
        compareBy<String>({ it.substringBefore('[') }, { -(Regex("""\[(\d+)]""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: -1) }, { it })
    )

    fun ops(): List<String> {
        val gone = removals()
        val scalars = staged.entries
            .filter { e -> gone.none { e.key.startsWith("$it.") } }
            .sortedBy { it.key }
            .map { (path, change) ->
                if (change.second.isEmpty()) "delete $path"
                else "set $path='${Commands.escapeValue(change.second)}'"
            }
        val edits = rewrites().flatMap { (section, sets, dels) ->
            dels.map { "delete network.$section.$it" } +
                sets.map { (k, v) -> "set network.$section.$k='${Commands.escapeValue(v)}'" }
        }
        val adds = additions().flatMap { (name, type, options) ->
            listOf("set network.$name=$type") + options.map { (k, v) -> "set network.$name.$k='${Commands.escapeValue(v)}'" }
        }
        // Deletions last: everything above may name an anonymous section by its index.
        return scalars + edits + adds + gone.map { "delete $it" }
    }

    fun diffLines(): List<Pair<String, Boolean>> = buildList {
        staged.entries.sortedBy { it.key }.forEach { (path, change) ->
            add("- $path='${change.first}'" to false)
            if (change.second.isNotEmpty()) add("+ $path='${change.second}'" to true)
        }
        rewrites().forEach { (section, sets, dels) ->
            val saved = routes.firstOrNull { it.section == section }
            dels.forEach { add("- network.$section.$it" to false) }
            sets.forEach { (k, v) ->
                saved?.let { s -> savedOption(s, k).takeIf { it.isNotEmpty() }?.let { add("- network.$section.$k='$it'" to false) } }
                add("+ network.$section.$k='$v'" to true)
            }
        }
        additions().forEach { (name, type, options) ->
            add("+ network.$name=$type" to true)
            options.forEach { (k, v) -> add("+ network.$name.$k='$v'" to true) }
        }
        removals().forEach { add("- $it" to false) }
    }

    fun commitLine(): String = "$ uci commit network && /etc/init.d/network reload"

    // ---- applying ----

    /**
     * One batch, rollback-armed the way the WAN screen's is: the router keeps a copy of
     * `/etc/config/network` and puts it back unless the app re-reads it within [seconds].
     * A route can take the phone's own path to the router with it — a default route out of
     * the wrong interface, say — and that is the case the watcher exists for.
     */
    suspend fun apply(seconds: Int = WanStore.ROLLBACK_SECONDS): Boolean {
        if (pendingCount == 0 || applying) return true
        problems().firstOrNull()?.let { error = it; return false }
        applying = true
        error = null
        notice = null
        rolledBack = false
        val script = Commands.wanApply(ops(), listOf("network"), Commands.NETWORK_RELOAD, seconds)
        return try {
            beforeApply?.invoke()
            session.exec(script, timeoutMs = 60_000).requireOk("uci batch")
            load()
            session.exec(Commands.WAN_CONFIRM, timeoutMs = 15_000)
            revert()
            notice = "Applied — rollback disarmed"
            true
        } catch (e: SshException) {
            error = e.message
            checkRollback()
            false
        } finally {
            applying = false
        }
    }

    suspend fun checkRollback() {
        rolledBack = runCatching {
            session.exec(Commands.WAN_ROLLBACK_STATE, timeoutMs = 15_000).stdout.contains("rolled-back")
        }.getOrDefault(false)
        if (rolledBack) {
            notice = "The router put the old routes back — the app could not reach it in time."
            revert()
            runCatching { load() }
        }
    }

    companion object {
        val ROUTE_TYPES = listOf("unicast", "local", "broadcast", "multicast", "anycast", "unreachable", "prohibit", "blackhole", "throw")

        /** The names `/etc/iproute2/rt_tables` ships with. */
        val TABLE_NAMES = setOf("main", "local", "default", "unspec", "prelocal")

        /** The options this screen writes — the ones an in-place rewrite may remove. */
        val ROUTE_OPTIONS = setOf("interface", "target", "netmask", "gateway", "metric", "mtu", "table", "type", "onlink", "disabled")

        /** `10.20.0.0/16` → ("10.20.0.0", 16); no slash → (text, null). */
        fun splitPrefix(text: String): Pair<String, Int?> {
            val slash = text.indexOf('/')
            if (slash < 0) return text to null
            return text.substring(0, slash) to text.substring(slash + 1).toIntOrNull()
        }

        fun looksIpv6(text: String): Boolean = text.substringBefore('/').contains(':')

        /** An address literal of the family, with no DNS behind it. */
        fun validAddress(text: String, ipv6: Boolean): Boolean {
            if (!ipv6) return IpMath.valid(text)
            if (!text.contains(':') || text.any { !(it.isLetterOrDigit() || it == ':' || it == '.') }) return false
            if (text.any { it.isLetter() && it.lowercaseChar() !in 'a'..'f' }) return false
            return runCatching { InetAddress.getByName(text) is Inet6Address }.getOrDefault(false)
        }

        /** A network in CIDR form, or a bare address as a host route, in the given family. */
        fun validPrefix(text: String, ipv6: Boolean): Boolean {
            if (text == "default") return true
            val (addr, len) = splitPrefix(text)
            if (text.contains('/') && len == null) return false
            if (len != null && len !in 0..(if (ipv6) 128 else 32)) return false
            return validAddress(addr, ipv6)
        }
    }
}
