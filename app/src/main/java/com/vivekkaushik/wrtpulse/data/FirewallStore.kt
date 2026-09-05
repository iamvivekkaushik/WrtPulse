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
import com.vivekkaushik.wrtpulse.ops.Lease
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.Parsers.FirewallConfig
import com.vivekkaushik.wrtpulse.ops.Parsers.FwEngine
import com.vivekkaushik.wrtpulse.ops.Parsers.FwForward
import com.vivekkaushik.wrtpulse.ops.Parsers.FwRule
import com.vivekkaushik.wrtpulse.ops.Parsers.FwZone

/** A port forward being written, before it has a section. */
data class ForwardDraft(
    val id: Int,
    val name: String,
    val proto: String,
    val srcPort: String,
    val destIp: String,
    /** Empty means the same as [srcPort]. */
    val destPort: String,
    val src: String = "wan",
    val dest: String = "lan",
    /** Set when this draft replaces an existing section rather than adding one. */
    val replaces: String? = null,
)

/** A traffic rule being written. Weekdays in fw4's three-letter form. */
data class RuleDraft(
    val id: Int,
    val name: String,
    val src: String,
    val dest: String,
    val proto: String,
    val srcIp: String,
    val destIp: String,
    val destPort: String,
    val target: String,
    val weekdays: List<String>,
    val startTime: String,
    val stopTime: String,
)

/** The exposed host, as the DMZ tab edits it. */
data class DmzDraft(
    val enabled: Boolean,
    val targetIp: String,
    val src: String,
    /** Ports the DMZ leaves alone. The router's SSH is always among them. */
    val except: List<Int>,
)

/**
 * The firewall section — design screens 31 to 37.
 *
 * fw4 is configuration all the way down, so every tab here is staged uci edits over one
 * read of `/etc/config/firewall`, reviewed as a diff and applied in one batch. The apply is
 * rollback-armed: a rule that rejects the phone's own SSH is the one mistake this section
 * can make that this section cannot then undo, so the router undoes it on its own unless
 * the app comes back within [ROLLBACK_SECONDS] to say the link survived.
 */
class FirewallStore(private val session: RouterSession) {

    var config by mutableStateOf(FirewallConfig()); private set
    var engine by mutableStateOf<FwEngine?>(null); private set
    var leases by mutableStateOf<List<Lease>>(emptyList()); private set

    /** Ports the router itself listens on — what a forward must not take. */
    var listening by mutableStateOf<Set<Int>>(emptySet()); private set

    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var applying by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set

    /** The watcher fired on the last apply: the router put the old rules back. */
    var rolledBack by mutableStateOf(false); private set

    // ---- staging ----

    /** Scalar edits: uci path → (saved, staged). */
    val staged = mutableStateMapOf<String, Pair<String, String>>()

    /** Whole sections to remove, as `firewall.<section>`. */
    val deletions = mutableStateListOf<String>()

    val forwardDrafts = mutableStateListOf<ForwardDraft>()
    val ruleDrafts = mutableStateListOf<RuleDraft>()

    /** Zone pairs to allow that have no `forwarding` section yet, as src to dest. */
    val forwardingDrafts = mutableStateListOf<Pair<String, String>>()

    /** The DMZ as staged; null means untouched since the last load. */
    var dmzDraft by mutableStateOf<DmzDraft?>(null); private set

    private var nextId = 1

    val pendingCount: Int
        get() = staged.size + deletions.size + forwardDrafts.size + ruleDrafts.size +
            forwardingDrafts.size + (if (dmzChanged) 1 else 0)

    // ---- reading ----

    suspend fun load() {
        if (loading) return
        loading = true
        try {
            val out = session.exec(
                Commands.FIREWALL_STATE + "; echo ${Commands.SECTION} last; ${Commands.FIREWALL_LAST}",
                timeoutMs = 30_000,
            )
            ingest(Parsers.sections(out.stdout))
        } catch (e: SshException) {
            error = "Couldn't read the firewall: ${e.message}"
        } finally {
            loading = false
        }
    }

    /** The parsed sections of [Commands.FIREWALL_STATE] — split out so tests can feed them in. */
    internal fun ingest(parts: Map<String, String>) {
        config = Parsers.firewallConfig(Parsers.uciShow(parts["firewall"].orEmpty()))
        engine = Parsers.firewallEngine(parts)
        leases = Parsers.leases(parts["leases"].orEmpty())
        listening = Parsers.listeningPorts(parts["listen"].orEmpty())
        if (parts["last"].orEmpty().contains("rolled-back")) {
            rolledBack = true
            notice = "The router put the old rules back after the last apply — the app could not reach it in time."
        }
        error = null
        loaded = true
    }

    // ---- what the tabs show: saved state with staged edits laid over it ----

    private fun value(path: String, saved: String): String = staged[path]?.second ?: saved

    private fun bool(path: String, saved: Boolean): Boolean =
        staged[path]?.let { it.second == "1" } ?: saved

    fun isDeleting(section: String) = "firewall.$section" in deletions

    /** Forwards as the list renders them: saved rows with edits laid over, then drafts. */
    fun forwardRows(): List<FwForward> = config.forwards
        .filter { !it.isDmz && !isDeleting(it.section) && forwardDrafts.none { d -> d.replaces == it.section } }
        .map { f ->
            f.copy(enabled = bool("firewall.${f.section}.enabled", f.enabled))
        } + forwardDrafts.map { d ->
        FwForward(
            section = draftSection(d),
            name = d.name, src = d.src, dest = d.dest, proto = d.proto,
            srcPort = d.srcPort, destIp = d.destIp, destPort = d.destPort, enabled = true,
        )
    }

    fun ruleRows(): List<FwRule> = config.rules
        .filter { !isDeleting(it.section) }
        .map { r -> r.copy(enabled = bool("firewall.${r.section}.enabled", r.enabled)) } +
        ruleDrafts.map { d ->
            FwRule(
                section = "wrtpulse_rule_${d.id}",
                name = d.name, src = d.src, dest = d.dest, proto = d.proto,
                srcIp = d.srcIp, destIp = d.destIp, destPort = d.destPort, target = d.target,
                enabled = true, weekdays = d.weekdays, startTime = d.startTime, stopTime = d.stopTime,
                family = "",
            )
        }

    fun zoneRows(): List<FwZone> = config.zones.map { z ->
        val p = "firewall.${z.section}"
        z.copy(
            input = value("$p.input", z.input),
            output = value("$p.output", z.output),
            forward = value("$p.forward", z.forward),
            masq = bool("$p.masq", z.masq),
            mtuFix = bool("$p.mtu_fix", z.mtuFix),
        )
    }

    /** Whether [src] may reach [dest], staged state included. */
    fun forwardingAllowed(src: String, dest: String): Boolean {
        val saved = config.forwardings.firstOrNull { it.src == src && it.dest == dest }
        if (saved != null && !isDeleting(saved.section)) return true
        return (src to dest) in forwardingDrafts
    }

    fun defaults(): Parsers.FwDefaults {
        val d = config.defaults
        val p = "firewall.${d.section}"
        return d.copy(
            input = value("$p.input", d.input),
            output = value("$p.output", d.output),
            forward = value("$p.forward", d.forward),
            synFlood = bool("$p.syn_flood", d.synFlood),
            dropInvalid = bool("$p.drop_invalid", d.dropInvalid),
        )
    }

    /** The default `Allow-Ping` rule, when the config still has one. */
    fun pingRule(): FwRule? = config.rules.firstOrNull {
        it.src == "wan" && it.proto == "icmp" && it.target == "ACCEPT" &&
            (it.name.equals("Allow-Ping", ignoreCase = true) || it.name.isEmpty())
    }

    /** Whether the router answers ping from the WAN, staged state included. */
    fun wanPing(): Boolean {
        val rule = pingRule() ?: return false
        return !isDeleting(rule.section) && bool("firewall.${rule.section}.enabled", rule.enabled)
    }

    /** The DMZ as it stands: the staged draft, else what the config has. */
    fun dmz(): DmzDraft {
        dmzDraft?.let { return it }
        val saved = config.forwards.filter { it.isDmz }
        val first = saved.firstOrNull()
        return DmzDraft(
            enabled = first != null && first.enabled,
            targetIp = first?.destIp.orEmpty(),
            src = first?.src ?: "wan",
            except = savedDmzExcept(saved),
        )
    }

    /** [setDmz] drops a draft equal to what is saved, so any draft at all is a change. */
    private val dmzChanged: Boolean get() = dmzDraft != null

    private fun savedDmz(): DmzDraft {
        val saved = config.forwards.filter { it.isDmz }
        val first = saved.firstOrNull()
        return DmzDraft(
            enabled = first != null && first.enabled,
            targetIp = first?.destIp.orEmpty(),
            src = first?.src ?: "wan",
            except = savedDmzExcept(saved),
        )
    }

    // ---- staging ----

    private fun stage(path: String, saved: String, wanted: String) {
        if (wanted == saved) staged.remove(path) else staged[path] = saved to wanted
    }

    fun toggleForward(section: String) {
        val f = config.forwards.firstOrNull { it.section == section } ?: return
        val path = "firewall.$section.enabled"
        stage(path, if (f.enabled) "1" else "0", if (bool(path, f.enabled)) "0" else "1")
    }

    fun toggleRule(section: String) {
        val r = config.rules.firstOrNull { it.section == section } ?: return
        val path = "firewall.$section.enabled"
        stage(path, if (r.enabled) "1" else "0", if (bool(path, r.enabled)) "0" else "1")
    }

    fun deleteSection(section: String) {
        val path = "firewall.$section"
        if (path !in deletions) deletions.add(path)
        staged.keys.filter { it.startsWith("$path.") }.forEach { staged.remove(it) }
    }

    fun undoDelete(section: String) = deletions.remove("firewall.$section")

    fun removeDraft(id: Int) {
        forwardDrafts.removeAll { it.id == id }
        ruleDrafts.removeAll { it.id == id }
    }

    /** The draft behind a row of [forwardRows], when the row is one — including an edit of a saved forward. */
    fun forwardDraftFor(section: String): ForwardDraft? = forwardDrafts.firstOrNull { draftSection(it) == section }

    fun ruleDraftFor(section: String): RuleDraft? = ruleDrafts.firstOrNull { "wrtpulse_rule_${it.id}" == section }

    /**
     * Takes a forward row off the list. A draft is simply dropped — an edit of a saved forward
     * included, which puts the saved one back — and a saved section is staged for deletion.
     */
    fun removeForward(section: String) {
        val draft = forwardDraftFor(section)
        if (draft != null) forwardDrafts.remove(draft) else deleteSection(section)
    }

    fun removeRule(section: String) {
        val draft = ruleDraftFor(section)
        if (draft != null) ruleDrafts.remove(draft) else deleteSection(section)
    }

    fun newForwardDraft(replaces: FwForward? = null): ForwardDraft = ForwardDraft(
        id = nextId++,
        name = replaces?.name.orEmpty(),
        proto = replaces?.proto ?: "tcp",
        srcPort = replaces?.srcPort.orEmpty(),
        destIp = replaces?.destIp.orEmpty(),
        destPort = replaces?.destPort.orEmpty(),
        src = replaces?.src ?: "wan",
        dest = replaces?.dest ?: "lan",
        replaces = replaces?.section,
    )

    /** Stages a forward once it passes [forwardProblem]. Returns the problem otherwise. */
    fun stageForward(draft: ForwardDraft): String? {
        forwardProblem(draft)?.let { return it }
        forwardDrafts.removeAll { it.id == draft.id }
        forwardDrafts.add(draft)
        return null
    }

    fun newRuleDraft(): RuleDraft = RuleDraft(
        id = nextId++, name = "", src = "lan", dest = "wan", proto = "tcp udp",
        srcIp = "", destIp = "", destPort = "", target = "REJECT",
        weekdays = emptyList(), startTime = "", stopTime = "",
    )

    fun stageRule(draft: RuleDraft): String? {
        ruleProblem(draft)?.let { return it }
        ruleDrafts.removeAll { it.id == draft.id }
        ruleDrafts.add(draft)
        return null
    }

    fun setZonePolicy(section: String, direction: String, policy: String) {
        val z = config.zones.firstOrNull { it.section == section } ?: return
        val saved = when (direction) { "input" -> z.input; "output" -> z.output; else -> z.forward }
        stage("firewall.$section.$direction", saved, policy)
    }

    fun setZoneFlag(section: String, option: String, on: Boolean) {
        val z = config.zones.firstOrNull { it.section == section } ?: return
        val saved = if (option == "masq") z.masq else z.mtuFix
        stage("firewall.$section.$option", if (saved) "1" else "0", if (on) "1" else "0")
    }

    /** Flips whether [src] may reach [dest] — a `forwarding` section added or removed. */
    fun toggleForwarding(src: String, dest: String) {
        val saved = config.forwardings.firstOrNull { it.src == src && it.dest == dest }
        when {
            saved != null && !isDeleting(saved.section) -> deleteSection(saved.section)
            saved != null -> undoDelete(saved.section)
            (src to dest) in forwardingDrafts -> forwardingDrafts.remove(src to dest)
            else -> forwardingDrafts.add(src to dest)
        }
    }

    fun setDefault(direction: String, policy: String) {
        val d = config.defaults
        val saved = when (direction) { "input" -> d.input; "output" -> d.output; else -> d.forward }
        stage("firewall.${d.section}.$direction", saved, policy)
    }

    fun setDefaultFlag(option: String, on: Boolean) {
        val d = config.defaults
        val saved = if (option == "syn_flood") d.synFlood else d.dropInvalid
        stage("firewall.${d.section}.$option", if (saved) "1" else "0", if (on) "1" else "0")
    }

    /** Toggles the Allow-Ping rule; a config without one gets one when turning it on. */
    fun setWanPing(on: Boolean) {
        val rule = pingRule()
        if (rule != null) {
            if (isDeleting(rule.section)) undoDelete(rule.section)
            stage("firewall.${rule.section}.enabled", if (rule.enabled) "1" else "0", if (on) "1" else "0")
            return
        }
        if (on) {
            ruleDrafts.removeAll { it.name == "Allow-Ping" }
            ruleDrafts.add(
                RuleDraft(
                    id = nextId++, name = "Allow-Ping", src = "wan", dest = "", proto = "icmp",
                    srcIp = "", destIp = "", destPort = "", target = "ACCEPT",
                    weekdays = emptyList(), startTime = "", stopTime = "",
                )
            )
        } else {
            ruleDrafts.removeAll { it.name == "Allow-Ping" }
        }
    }

    fun setDmz(draft: DmzDraft) {
        // The router's SSH port is never exposed through the DMZ: with the app reaching the
        // router from the WAN side, that is the lockout this whole section guards against.
        val except = (draft.except + SSH_PORT).filter { it in 1..65535 }.distinct().sorted()
        dmzDraft = draft.copy(except = except)
        if (dmzDraft == savedDmz()) dmzDraft = null
    }

    fun revert() {
        staged.clear()
        deletions.clear()
        forwardDrafts.clear()
        ruleDrafts.clear()
        forwardingDrafts.clear()
        dmzDraft = null
        error = null
    }

    // ---- validation ----

    /**
     * Why a forward may not be staged. The first reason is the design's collision guard:
     * an external port the router itself listens on is taken from the router, and if that
     * port is SSH the app is what gets locked out.
     */
    fun forwardProblem(draft: ForwardDraft): String? {
        val ext = draft.srcPort.trim().toIntOrNull()
        if (ext == null || ext !in 1..65535) return "External port has to be 1–65535."
        val int = draft.destPort.trim().ifEmpty { draft.srcPort.trim() }.toIntOrNull()
        if (int == null || int !in 1..65535) return "Internal port has to be 1–65535."
        if (IpMath.parse(draft.destIp.trim()) == null) return "Internal IP is not an IPv4 address."
        if (ext in listening) {
            return if (ext == SSH_PORT) {
                "Port $ext is the router's own SSH (Dropbear). Forwarding it will lock this app out — pick another external port."
            } else {
                "Port $ext is one the router itself listens on — forwarding it takes it from the router."
            }
        }
        val clash = forwardRows().firstOrNull {
            it.section != draft.replaces && it.section != draftSection(draft) &&
                it.srcPort == draft.srcPort.trim() && it.src == draft.src && protoOverlap(it.proto, draft.proto)
        }
        if (clash != null) return "Port ${draft.srcPort} is already forwarded by ${clash.name.ifEmpty { clash.section }}."
        return null
    }

    /** The port to offer instead when the chosen one collides — the design's "use 2222". */
    fun suggestPort(taken: Int): Int {
        val used = listening + forwardRows().mapNotNull { it.srcPort.toIntOrNull() }
        var candidate = if (taken < 1000) taken * 100 + taken else taken + 1
        while (candidate in used || candidate > 65535) candidate = if (candidate > 65535) 1024 else candidate + 1
        return candidate
    }

    fun ruleProblem(draft: RuleDraft): String? {
        if (draft.src.isEmpty() && draft.dest.isEmpty()) return "A rule needs a source or a destination zone."
        if (draft.destPort.isNotEmpty() && draft.destPort.split(' ', ',').any { p ->
                val range = p.trim().split('-')
                range.size !in 1..2 || range.any { it.toIntOrNull()?.let { n -> n !in 1..65535 } ?: true }
            }
        ) return "Ports have to be 1–65535, or a range like 6000-6010."
        if (draft.srcIp.isNotEmpty() && !cidrOk(draft.srcIp)) return "Source IP is not an IPv4 address or CIDR."
        if (draft.destIp.isNotEmpty() && !cidrOk(draft.destIp)) return "Destination IP is not an IPv4 address or CIDR."
        val start = draft.startTime.takeIf { it.isNotEmpty() }?.let { Parsers.clockMinutes(it) ?: return "Start time has to be HH:MM." }
        val stop = draft.stopTime.takeIf { it.isNotEmpty() }?.let { Parsers.clockMinutes(it) ?: return "Stop time has to be HH:MM." }
        if ((start == null) != (stop == null)) return "A schedule needs both a start and a stop time."
        if (draft.weekdays.any { it !in WEEKDAYS }) return "Weekdays have to be Mon–Sun."
        return null
    }

    /** What stops the batch from being sent at all. */
    fun problems(): List<String> = buildList {
        val d = dmzDraft
        if (d != null && d.enabled && IpMath.parse(d.targetIp) == null) add("The DMZ host is not an IPv4 address.")
    }

    // ---- the batch ----

    private fun draftSection(d: ForwardDraft) = d.replaces ?: "wrtpulse_fwd_${d.id}"

    private fun forwardOptions(d: ForwardDraft): List<Pair<String, String>> = buildList {
        add("name" to d.name.trim().ifEmpty { "Forward ${d.srcPort.trim()}" })
        add("src" to d.src)
        add("src_dport" to d.srcPort.trim())
        add("dest" to d.dest)
        add("dest_ip" to d.destIp.trim())
        val dp = d.destPort.trim()
        if (dp.isNotEmpty() && dp != d.srcPort.trim()) add("dest_port" to dp)
        add("proto" to d.proto)
        add("target" to "DNAT")
    }

    private fun ruleOptions(d: RuleDraft): List<Pair<String, String>> = buildList {
        add("name" to d.name.trim().ifEmpty { "Rule ${d.id}" })
        if (d.src.isNotEmpty()) add("src" to d.src)
        if (d.dest.isNotEmpty()) add("dest" to d.dest)
        if (d.proto.isNotEmpty()) add("proto" to d.proto)
        if (d.srcIp.isNotEmpty()) add("src_ip" to d.srcIp.trim())
        if (d.destIp.isNotEmpty()) add("dest_ip" to d.destIp.trim())
        if (d.destPort.isNotEmpty()) add("dest_port" to d.destPort.trim())
        add("target" to d.target)
        if (d.weekdays.isNotEmpty()) add("weekdays" to d.weekdays.joinToString(" "))
        if (d.startTime.isNotEmpty()) add("start_time" to d.startTime.trim())
        if (d.stopTime.isNotEmpty()) add("stop_time" to d.stopTime.trim())
    }

    /** New sections as (section, type, options), so ops and the diff agree by construction. */
    private fun additions(): List<Triple<String, String, List<Pair<String, String>>>> = buildList {
        forwardDrafts.forEach { d -> add(Triple(draftSection(d), "redirect", forwardOptions(d))) }
        ruleDrafts.forEach { d -> add(Triple("wrtpulse_rule_${d.id}", "rule", ruleOptions(d))) }
        forwardingDrafts.forEach { (src, dest) ->
            add(Triple("wrtpulse_zone_${section(src)}_${section(dest)}", "forwarding", listOf("src" to src, "dest" to dest)))
        }
        dmzDraft?.let { d ->
            if (d.enabled) {
                dmzRanges(d.except).forEachIndexed { i, (from, to) ->
                    add(
                        Triple(
                            "wrtpulse_dmz_${i + 1}", "redirect",
                            listOf(
                                "name" to "DMZ ${d.targetIp}${if (dmzRanges(d.except).size > 1) " · ports $from–$to" else ""}",
                                "src" to d.src,
                                "src_dport" to (if (from == to) "$from" else "$from-$to"),
                                "dest" to "lan",
                                "dest_ip" to d.targetIp.trim(),
                                "proto" to "tcp udp",
                                "target" to "DNAT",
                            ),
                        )
                    )
                }
            }
        }
    }

    /** Sections removed: explicit deletions, replaced forwards, and the old DMZ when it changes. */
    private fun removals(): List<String> = buildList {
        addAll(deletions)
        forwardDrafts.mapNotNull { it.replaces }.forEach { add("firewall.$it") }
        if (dmzDraft != null) config.forwards.filter { it.isDmz }.forEach { add("firewall.${it.section}") }
    }.distinct().sorted()

    fun ops(): List<String> {
        val gone = removals()
        val scalars = staged.entries
            .filter { e -> gone.none { e.key.startsWith("$it.") } }
            .sortedBy { it.key }
            .map { (path, change) ->
                if (change.second.isEmpty()) "delete $path"
                else "set $path='${Commands.escapeValue(change.second)}'"
            }
        val adds = additions().flatMap { (name, type, options) ->
            listOf("set firewall.$name=$type") + options.map { (k, v) ->
                "set firewall.$name.$k='${Commands.escapeValue(v)}'"
            }
        }
        // Deletions go first: a replaced forward's old section must not outlive the new one
        // in the same batch, and a DMZ being re-ranged must not briefly have two.
        return gone.map { "delete $it" } + scalars + adds
    }

    fun diffLines(): List<Pair<String, Boolean>> = buildList {
        removals().forEach { add("- $it" to false) }
        staged.entries.sortedBy { it.key }.forEach { (path, change) ->
            add("- $path='${change.first}'" to false)
            if (change.second.isNotEmpty()) add("+ $path='${change.second}'" to true)
        }
        additions().forEach { (name, type, options) ->
            add("+ firewall.$name=$type" to true)
            options.forEach { (k, v) -> add("+ firewall.$name.$k='$v'" to true) }
        }
    }

    /** What the reviewer should know that the diff does not say. */
    fun warnings(): List<String> = buildList {
        val d = defaults()
        if (d.input == "ACCEPT") add("Default input ACCEPT answers every unsolicited connection from every zone, the WAN included.")
        zoneRows().filter { it.name == "wan" }.forEach {
            if (it.input == "ACCEPT") add("The wan zone accepts input — the router's own services face the internet.")
            if (!it.masq) add("Masquerade is off on wan: LAN clients lose internet unless the ISP routes your subnet.")
        }
        dmzDraft?.let { if (it.enabled) add("The DMZ host takes every inbound WAN connection not forwarded elsewhere, except ports ${it.except.joinToString(", ")}.") }
        if (deletions.any { path -> config.forwardings.any { "firewall.${it.section}" == path && it.src == "lan" && it.dest == "wan" } }) {
            add("Blocking lan → wan cuts every LAN client off the internet.")
        }
    }

    // ---- applying ----

    /**
     * One batch, rollback-armed. The re-read afterwards is the confirmation: if the new
     * rules took the phone's session with them, it never happens, and the router puts the
     * old file back on its own.
     */
    suspend fun apply(seconds: Int = ROLLBACK_SECONDS): Boolean {
        if (pendingCount == 0 || applying) return true
        problems().firstOrNull()?.let { error = it; return false }
        applying = true
        error = null
        notice = null
        rolledBack = false
        val script = Commands.firewallApply(ops(), seconds)
        return try {
            session.exec(script, timeoutMs = 60_000).requireOk("uci batch")
            load()
            session.exec(Commands.FIREWALL_CONFIRM, timeoutMs = 15_000)
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
            session.exec(Commands.FIREWALL_LAST, timeoutMs = 15_000).stdout.contains("rolled-back")
        }.getOrDefault(false)
        if (rolledBack) {
            notice = "The router put the old rules back — the app could not reach it in time."
            revert()
            runCatching { load() }
        }
    }

    companion object {
        const val ROLLBACK_SECONDS = 15
        const val SSH_PORT = 22

        val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val POLICIES = listOf("ACCEPT", "REJECT", "DROP")
        val TARGETS = listOf("ACCEPT", "REJECT", "DROP")
        val PROTOCOLS = listOf("tcp" to "TCP", "udp" to "UDP", "tcp udp" to "T+U")

        /** The design's preset row. */
        val PRESETS = listOf(
            "HTTP" to 80, "HTTPS" to 443, "SSH" to 22, "WireGuard" to 51820, "Minecraft" to 25565,
        )

        /** A zone name as a uci section fragment — uci allows only word characters. */
        fun section(name: String) = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

        fun protoOverlap(a: String, b: String): Boolean {
            val x = a.split(' ').filter { it.isNotEmpty() }.toSet()
            val y = b.split(' ').filter { it.isNotEmpty() }.toSet()
            return x.isEmpty() || y.isEmpty() || (x intersect y).isNotEmpty()
        }

        fun cidrOk(text: String): Boolean {
            val parts = text.trim().split('/')
            if (parts.size > 2) return false
            if (IpMath.parse(parts[0]) == null) return false
            return parts.size == 1 || parts[1].toIntOrNull()?.let { it in 0..32 } == true
        }

        /**
         * The port ranges a DMZ covers once [except] is carved out of 1–65535.
         *
         * fw4 takes one port or one range per redirect and no negated list, so a DMZ that
         * leaves several ports alone is several redirects. The ranges are what makes "except
         * 22 and 8123" a real rule rather than a label.
         */
        fun dmzRanges(except: List<Int>): List<Pair<Int, Int>> {
            val holes = except.filter { it in 1..65535 }.distinct().sorted()
            val out = mutableListOf<Pair<Int, Int>>()
            var from = 1
            for (hole in holes) {
                if (hole > from) out += from to hole - 1
                from = hole + 1
            }
            if (from <= 65535) out += from to 65535
            return out
        }

        /**
         * The inverse: which ports a set of saved DMZ redirects leaves alone. Reading it back
         * this way means the tab shows what is on the router, not what was typed last time.
         */
        fun savedDmzExcept(dmz: List<FwForward>): List<Int> {
            if (dmz.isEmpty()) return listOf(SSH_PORT)
            val covered = dmz.mapNotNull { f ->
                val r = f.srcPort.split('-')
                val a = r.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val b = r.getOrNull(1)?.toIntOrNull() ?: a
                a to b
            }.sortedBy { it.first }
            // A single DMZ with no port at all covers everything; the except list is then empty
            // on the router, whatever the app would have written.
            if (covered.isEmpty()) return emptyList()
            val holes = mutableListOf<Int>()
            var next = 1
            for ((a, b) in covered) {
                for (p in next until a) holes += p
                next = maxOf(next, b + 1)
            }
            for (p in next..65535) holes += p
            return holes.take(64)
        }
    }
}
