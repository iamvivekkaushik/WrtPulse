package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.ScanCell
import kotlinx.coroutines.delay
import com.vivekkaushik.wrtpulse.ops.WifiNetwork
import com.vivekkaushik.wrtpulse.ops.WifiRadio

/**
 * A wifi-iface that does not exist on the router yet. It lives here until the user applies,
 * exactly like an edit to an existing network — the router hears nothing until then.
 */
data class DraftIface(
    val id: Int,
    /**
     * The radios this broadcasts on. One SSID on two bands is two uci sections but a single
     * thing the user made, so it stays one draft and one row.
     */
    val devices: List<String>,
    /** radio section → the uci section name it will be written as. */
    val sections: Map<String, String>,
    val mode: String,            // "ap" broadcasts a new SSID, "sta" joins someone else's
    val ssid: String,
    val encryption: String,      // uci value: "sae", "sae-mixed", "psk2", "none"
    val key: String,
    /** An AP joins an existing network; a station is an uplink, so it gets its own. */
    val network: String,
    val hidden: Boolean = false,
    val isolate: Boolean = false,
    /** Client only: the firewall zone its network joins. Empty means leave it out of one. */
    val zone: String = "",
    /** AP only: 802.11r hand-off with every other AP carrying this SSID and key. */
    val ft: Boolean = false,
) {
    val isClient: Boolean get() = mode == "sta"
}

/** One line of the interface list: a configured wifi-iface, or one that is only staged. */
data class InterfaceRow(
    val key: String,
    val section: String?,
    val draftId: Int?,
    val ssid: String,
    val isClient: Boolean,
    val isUplink: Boolean,
    val isNew: Boolean,
    val bands: String,
    val enabled: Boolean,
    /** False when the radio itself is off, whatever this interface says. */
    val radioOn: Boolean,
    val changed: Boolean,
    val detail: String,
    /** Staged for `uci delete` — shown in place, still undoable, applied with everything else. */
    val deleting: Boolean = false,

) {
    /** What actually reaches the air. */
    val onAir: Boolean get() = enabled && radioOn

    /**
     * Whether the row offers swipe-to-delete: any interface the router has saved, on the air
     * or not.
     *
     * Design screen 3e restricts the swipe to disabled interfaces; this is a deliberate
     * departure on the user's call. Nothing is lost on the spot — the swipe stages a
     * `uci delete` that shows up in the review sheet with [WifiStore.deletionNotes] spelling
     * out what a live network costs, and Revert drops it. Unsaved drafts stay out: they have
     * nothing on the router to delete, and the editor already discards them.
     */
    val deletable: Boolean get() = section != null

}

/**
 * The wireless config plus everything staged against it. The core promise of the design:
 * edits accumulate here, the diff sheet shows the exact uci ops, and NOTHING reaches the
 * router until the user applies.
 */
class WifiStore(private val session: RouterSession) : Refreshable {

    val radios = mutableStateListOf<WifiRadio>()

    /** radio section → channel widths (MHz) its chip can run; absent when the phy did not say. */
    val supportedWidths = mutableStateMapOf<String, Set<Int>>()

    /** radio section → dBm values its driver accepts; absent while the radio has no interface. */
    val txpowerAccepted = mutableStateMapOf<String, List<Int>>()

    /** radio section → its chip can beamform, so the switch is worth showing. */
    val supportsBeamforming = mutableStateMapOf<String, Boolean>()
    val networks = mutableStateListOf<WifiNetwork>()

    /** "section.option" → (saved value, staged value). */
    val staged = mutableStateMapOf<String, Pair<String, String>>()

    /**
     * Sections staged for `uci delete`. Deletion is staged like every other edit rather than
     * run on the spot, so it lands in the same review-and-apply pass — which is what design
     * screen 3e means by "stages uci delete".
     */
    val deletions = mutableStateListOf<String>()

    /** Networks the user has drawn up but not applied. */
    val drafts = mutableStateListOf<DraftIface>()
    private var nextDraftId by mutableIntStateOf(1)

    /** Interface sections in /etc/config/network — the names a new uplink must avoid. */
    val interfaces = mutableStateListOf<String>()

    /** Firewall zones, so a row can say which one an SSID actually lands in. */
    val zones = mutableStateListOf<com.vivekkaushik.wrtpulse.ops.FirewallZone>()

    /** ifname → what `iwinfo` says about it right now (a station's signal lives here). */
    val live = mutableStateMapOf<String, com.vivekkaushik.wrtpulse.ops.IwinfoIface>()

    /** ifname → associated stations. */
    val clientCounts = mutableStateMapOf<String, Int>()

    /** uci section → running ifname. */
    val sectionIfnames = mutableStateMapOf<String, String>()

    /** radio section → running AP ifname, for iwinfo. */
    val ifnames = mutableStateMapOf<String, String>()

    /** radio section → last scan result. */
    val scans = mutableStateMapOf<String, List<ScanCell>>()

    /**
     * radio section → the last scan may not have been a survey. True when it failed, or when
     * every neighbour heard sits on the radio's own channel — which is what ath10k-ct does
     * with the AP up on the reference router: it never leaves the channel, and a rating
     * built on that is a rating of one channel. Cleared by a survey that heard more.
     */
    val partial = mutableStateMapOf<String, Boolean>()
    var scanning by mutableStateOf(false); private set

    /** radio section → phy, as the driver reports it; falls back to the name convention. */
    private val phys = mutableMapOf<String, String>()

    override var loaded by mutableStateOf(false); private set
    override var applying by mutableStateOf(false); private set

    /** A scan may add a temporary station interface; a refresh mid-scan would list it. */
    override val refreshPaused: Boolean get() = scanning
    var error by mutableStateOf<String?>(null)

    val pendingCount: Int get() = staged.size + drafts.size + deletions.size

    /**
     * Runs before the batch — the auto-backup hook. Set by the app when "snapshot before
     * every Apply" is on; the store itself knows nothing about backups. A failure here
     * aborts the apply, because a snapshot that silently didn't happen is not a snapshot.
     */
    var beforeApply: (suspend () -> Unit)? = null

    /** Every uci op an apply would run — what the review sheet counts. */
    val opCount: Int get() = ops().size + networkOps().size

    override suspend fun load() {
        try {
            // One round trip: config, running state, and the live radio facts behind it.
            val batch = listOf(
                "echo ${Commands.SECTION} uci" to Commands.WIRELESS_CONFIG,
                "echo ${Commands.SECTION} status" to "ubus call network.wireless status",
                "echo ${Commands.SECTION} net" to Commands.NETWORK_CONFIG,
                "echo ${Commands.SECTION} fw" to Commands.FIREWALL_CONFIG,
                "echo ${Commands.SECTION} iwinfo" to Commands.IWINFO,
                "echo ${Commands.SECTION} assoc" to Commands.ASSOC_COUNTS,
                "echo ${Commands.SECTION} caps" to Commands.PHY_CAPS,
                "echo ${Commands.SECTION} phys" to Commands.PHY_NAMES,
                "echo ${Commands.SECTION} txpower" to Commands.TXPOWER_LISTS,
            ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }
            val out = session.exec(batch, timeoutMs = 15_000).requireOk("read wireless").stdout
            val sections = Parsers.sections(out)
            val (r, n) = Parsers.wireless(Parsers.uciShow(sections["uci"].orEmpty()))
            radios.clear(); radios.addAll(r)
            networks.clear(); networks.addAll(n)
            // Needed to name a new uplink without landing on an interface that already exists.
            interfaces.clear()
            interfaces.addAll(Parsers.networkInterfaces(Parsers.uciShow(sections["net"].orEmpty())))
            zones.clear()
            zones.addAll(Parsers.firewallZones(Parsers.uciShow(sections["fw"].orEmpty())))
            ifnames.clear()
            sectionIfnames.clear()
            Parsers.wirelessStatus(sections["status"].orEmpty())
                .filter { it.ifname.isNotEmpty() }
                .forEach { iface ->
                    sectionIfnames[iface.section] = iface.ifname
                    if (iface.mode == "ap") ifnames.putIfAbsent(iface.radio, iface.ifname)
                }
            live.clear()
            Parsers.iwinfo(sections["iwinfo"].orEmpty()).forEach { live[it.ifname] = it }
            clientCounts.clear()
            Parsers.stations(sections["assoc"].orEmpty())
                .groupingBy { it.iface }.eachCount()
                .forEach { (iface, count) -> clientCounts[iface] = count }
            phys.clear()
            phys.putAll(Parsers.phyNames(sections["phys"].orEmpty()))
            val lists = Parsers.txpowerLists(sections["txpower"].orEmpty())
            txpowerAccepted.clear()
            ifnames.forEach { (radio, ifname) -> lists[ifname]?.takeIf { it.isNotEmpty() }?.let { txpowerAccepted[radio] = it } }
            val caps = Parsers.phyWidths(sections["caps"].orEmpty())
            supportedWidths.clear()
            radios.forEach { radio -> caps[phyFor(radio.section)]?.let { supportedWidths[radio.section] = it } }
            val beamforming = Parsers.phyBeamforming(sections["caps"].orEmpty())
            supportsBeamforming.clear()
            radios.forEach { radio -> beamforming[phyFor(radio.section)]?.let { supportsBeamforming[radio.section] = it } }
            loaded = true
            error = null
        } catch (e: SshException) {
            error = e.message
        }
    }

    /** The raw uci lines for one section — what a long-press shows. */
    suspend fun showSection(section: String): String = try {
        session.exec(Commands.showSection(section), timeoutMs = 8_000).stdout.trim()
            .ifEmpty { "wireless.$section has no options set." }
    } catch (e: SshException) {
        e.message ?: "could not read wireless.$section"
    }

    /** Neighbour survey on one radio. Takes a few seconds; the radio stays up. */
    /**
     * Neighbour survey. Scans through the radio's own interface when it has one; a band with
     * no SSID configured has no interface, so a station interface is added for the scan and
     * removed straight after.
     */
    suspend fun scan(radio: String) {
        if (scanning) return
        scanning = true
        error = null
        try {
            val ifname = ifnames[radio]
            val command =
                if (ifname != null) Commands.scan(ifname)
                else Commands.scanViaTempInterface(phyFor(radio))
            val out = session.exec(command, timeoutMs = 60_000)
            val cells = withoutOwn(Parsers.scanCells(out.stdout), ownBssids())
            when {
                cells.isNotEmpty() -> {
                    scans[radio] = cells
                    partial[radio] = ifname != null && onlyOwnChannel(cells, live[ifname]?.channel)
                }
                out.stdout.contains("ERR add") ->
                    error = "This radio has no interface to scan with, and one couldn't be created."
                !out.ok || out.stdout.contains("Not supported", true) || out.stdout.contains("failed", true) ||
                    out.stdout.contains("Netlink error", true) -> {
                    error = "Scan failed: ${out.stdout.trim().lines().lastOrNull()?.take(90).orEmpty()}"
                    // A live AP that cannot scan is the case the radio-off survey exists for.
                    if (ifname != null) partial[radio] = true
                }
                else -> {
                    scans[radio] = emptyList()   // scanned fine, genuinely nothing heard
                    partial[radio] = false
                }
            }
        } catch (e: SshException) {
            error = "Scan failed: ${e.message}"
        } finally {
            scanning = false
        }
    }

    /**
     * Survey with the radio off — see [Commands.surveyWithRadioDown]. Every device on the
     * band drops for the duration, this phone possibly included, so the result is collected
     * by polling a file the detached job leaves behind; exec reconnects by itself once the
     * radio is back.
     */
    suspend fun surveyWithRadioDown(radio: String) {
        if (scanning) return
        scanning = true
        error = null
        try {
            session.exec(Commands.surveyWithRadioDown(radio, phyFor(radio)), timeoutMs = 10_000)
            var text = ""
            val deadline = System.nanoTime() + SURVEY_WAIT_NANOS
            while (System.nanoTime() < deadline) {
                delay(3_000)
                text = try {
                    session.exec(Commands.readSurvey(radio), timeoutMs = 8_000).stdout
                } catch (e: SshException) {
                    ""   // the link may be the very band that is down; try again shortly
                }
                if (text.contains("${Commands.SECTION} done")) break
            }
            if (!text.contains("${Commands.SECTION} done")) {
                error = "The survey is taking too long. The radio comes back up on its own when it finishes."
                return
            }
            scans[radio] = withoutOwn(Parsers.scanCells(text), ownBssids())
            partial[radio] = false
            runCatching { session.exec(Commands.forgetSurvey(radio), timeoutMs = 5_000) }
        } catch (e: SshException) {
            error = "Survey failed: ${e.message}"
        } finally {
            scanning = false
        }
    }

    /** The MACs our own APs beacon with; a scan can hear them and they are not neighbours. */
    private fun ownBssids(): Set<String> =
        live.values.filter { !it.isClient && it.bssid.isNotBlank() }.map { it.bssid.uppercase() }.toSet()

    /** The driver's answer when it gave one; otherwise the convention that radio0 sits on phy0. */
    private fun phyFor(radio: String) =
        phys[radio] ?: ("phy" + radio.filter { it.isDigit() }.ifEmpty { "0" })

    /** Stages one option; staging the saved value back un-stages it. */
    fun stageDelete(section: String) {
        // The mesh backhaul comes and goes from the Mesh page, where the nodes it carries are in view.
        if (section == com.vivekkaushik.wrtpulse.ops.MeshOps.MESH_SECTION) return
        if (section !in deletions) deletions.add(section)
    }

    fun undoDelete(section: String) {
        deletions.remove(section)
    }

    fun isDeleting(section: String?): Boolean = section != null && section in deletions

    fun stage(section: String, option: String, saved: String, value: String) {
        val key = "$section.$option"
        if (value == saved) staged.remove(key) else staged[key] = saved to value
    }

    /** True when anything in this section is waiting to be applied. */
    fun changedIn(section: String): Boolean = staged.keys.any { it.startsWith("$section.") }

    /** The beamforming switches this radio has: VHT always, HE on an 802.11ax radio. */
    fun beamformOptions(radio: WifiRadio): List<String> =
        WifiRadio.VHT_BEAMFORM +
            if (radio.htmode.startsWith("HE") || radio.htmode.startsWith("EHT")) WifiRadio.HE_BEAMFORM else emptyList()

    /** On unless any switch is explicitly off — the same reading hostapd makes. */
    fun beamforming(radio: WifiRadio): Boolean =
        beamformOptions(radio).all { value(radio.section, it, radio.beamform[it].orEmpty()) != "0" }

    /**
     * One switch for all of them. Turning on removes the options rather than writing "1":
     * on is the default, and a config that says nothing is a config that follows the driver.
     */
    fun setBeamforming(radio: WifiRadio, on: Boolean) {
        beamformOptions(radio).forEach { stage(radio.section, it, radio.beamform[it].orEmpty(), if (on) "" else "0") }
    }

    /** The value the UI should render: staged if present, else saved. */
    fun value(section: String, option: String, saved: String): String =
        staged["$section.$option"]?.second ?: saved

    /** Queues a new AP or client on one radio. Returns the draft so the UI can name it. */
    fun addDraft(
        devices: List<String>,
        mode: String,
        ssid: String,
        encryption: String,
        key: String,
        hidden: Boolean = false,
        isolate: Boolean = false,
        network: String? = null,
        /** Null takes the safe default: a station with no zone is an uplink to nowhere. */
        zone: String? = null,
        ft: Boolean = false,
    ): DraftIface {
        val trimmed = ssid.trim()
        val taken = (networks.map { it.section } + radios.map { it.section } +
            drafts.flatMap { it.sections.values }).toMutableSet()
        val base = sectionBase(trimmed, mode)
        // One section per radio; the radio goes in the name only when there is more than one,
        // so the common single-band case reads as plain `wrtpulse_guest`.
        val sections = devices.associateWith { device ->
            val wanted = if (devices.size > 1) "${base}_${device.filter { it.isLetterOrDigit() }}" else base
            free(wanted, taken).also { taken += it }
        }
        val takenNetworks = (interfaces + drafts.map { it.network }).toSet()
        val draft = DraftIface(
            id = nextDraftId++,
            devices = devices,
            sections = sections,
            mode = mode,
            ssid = trimmed,
            encryption = encryption,
            key = key,
            network = network ?: if (mode == "sta") free(Commands.WWAN, takenNetworks) else "lan",
            hidden = hidden,
            isolate = isolate,
            zone = zone ?: if (mode == "sta") "wan" else "",
            ft = ft && mode == "ap" && com.vivekkaushik.wrtpulse.ops.MeshOps.roamingCapable(encryption),
        )
        drafts.add(draft)
        return draft
    }

    fun removeDraft(id: Int) {
        drafts.removeAll { it.id == id }
    }

    fun draftsFor(radio: String): List<DraftIface> = drafts.filter { radio in it.devices }

    fun revert() {
        staged.clear()
        drafts.clear()
        deletions.clear()
    }

    /** `- key='old'` / `+ key='new'` pairs for the review sheet, secrets masked. */
    fun diffLines(): List<Pair<String, Boolean>> = staged.entries
        .filterNot { it.key.substringBefore('.') in deletions }
        .sortedBy { it.key }
        .flatMap { (key, change) ->
            val (old, new) = change
            val secret = key.endsWith(".key")
            val removed = secret && new.isEmpty()
            listOf(
                "- $key='${if (secret) "••••••••" else old}'" to false,
            ) + if (removed) emptyList() else listOf(
                "+ $key='${if (secret) mask(new) else new}'" to true,
            )
        } + deletions.sorted().map { "- $it=wifi-iface" to false } + drafts.flatMap { draft ->
            draft.devices.flatMap { device ->
                val section = draft.sections.getValue(device)
                listOf("+ $section=wifi-iface" to true) +
                    draftOptions(draft, device).map { (option, value) ->
                        "+ $section.$option='${if (option == "key") mask(value) else value}'" to true
                    }
            }
        }

    /**
     * What a staged delete leaves behind. Removing a `wifi-iface` does not remove the
     * `network.<name>` interface it pointed at, nor its firewall zone membership. For a
     * client interface that is a whole uplink stanza orphaned, so the sheet says so instead
     * of the app silently reaching into two more config files.
     */
    fun deletionNotes(): List<String> = deletions.sorted().flatMap { section ->
        val net = networks.firstOrNull { it.section == section } ?: return@flatMap emptyList()
        val live = !net.disabled && radioEnabled(net.device)
        val uplink = net.isClient && zoneFor(net.network) == "wan"
        buildList {
            // A live interface can be deleted, so the review sheet is where the cost gets
            // stated. Most severe first, and only one severity line per section.
            // An uplink only carries the internet while it is actually up: claiming a
            // switched-off one would take the router offline is simply false.
            if (uplink && live) {
                add(
                    "$section is this router's upstream link. Deleting it takes the router's " +
                        "internet with it, and everything behind the router loses access."
                )
            } else if (live && !net.isClient) {
                add(
                    "${net.ssid.ifBlank { section }} is on the air. Deleting it removes the " +
                        "network and its clients lose it — including this phone, if it is on " +
                        "that SSID."
                )
            } else if (live) {
                add("$section is connected right now. Deleting it drops that link.")
            }
            if (net.isClient) {
                add(
                    "Deleting $section leaves network.${net.network} and its firewall zone " +
                        "behind — remove those by hand if nothing else uses them."
                )
            }
        }
    }

    /** A draft's options for one radio, in the order they are written. */
    private fun draftOptions(draft: DraftIface, device: String): List<Pair<String, String>> = buildList {
        add("device" to device)
        add("mode" to draft.mode)
        add("ssid" to draft.ssid)
        add("encryption" to draft.encryption)
        if (draft.encryption != "none") add("key" to draft.key)
        add("network" to draft.network)
        if (draft.hidden) add("hidden" to "1")
        if (draft.isolate) add("isolate" to "1")
        if (draft.ft) addAll(com.vivekkaushik.wrtpulse.ops.MeshOps.roamingOptions(draft.ssid, draft.encryption))
    }

    /**
     * The radios as they will be once the pending edits apply — what a mesh node has to be
     * told before this router changes, since the change may take the link with it.
     */
    fun effectiveRadios(): List<WifiRadio> = radios.map { r ->
        r.copy(
            channel = value(r.section, "channel", r.channel),
            htmode = value(r.section, "htmode", r.htmode),
            country = value(r.section, "country", r.country),
            disabled = value(r.section, "disabled", if (r.disabled) "1" else "0") == "1",
        )
    }

    /** The wifi-iface sections as they will be after apply: staged edits laid over, deletions out, drafts in. */
    fun effectiveNetworks(): List<WifiNetwork> {
        val kept = networks.filterNot { it.section in deletions }.map { n ->
            val ssid = value(n.section, "ssid", n.ssid)
            val ftValue = value(n.section, "ieee80211r", if (n.ieee80211r) "1" else "")
            n.copy(
                ssid = ssid,
                encryption = value(n.section, "encryption", n.encryption),
                key = value(n.section, "key", n.key),
                disabled = value(n.section, "disabled", if (n.disabled) "1" else "0") == "1",
                network = value(n.section, "network", n.network),
                hidden = value(n.section, "hidden", if (n.hidden) "1" else "0") == "1",
                isolate = value(n.section, "isolate", if (n.isolate) "1" else "0") == "1",
                ieee80211r = ftValue == "1",
                mobilityDomain = value(n.section, "mobility_domain", n.mobilityDomain),
            )
        }
        val drafted = drafts.filter { !it.isClient }.flatMap { d ->
            d.devices.map { device ->
                WifiNetwork(
                    section = d.sections.getValue(device), device = device, ssid = d.ssid, encryption = d.encryption,
                    key = d.key, disabled = false, mode = d.mode, network = d.network, hidden = d.hidden, isolate = d.isolate,
                    ieee80211r = d.ft, mobilityDomain = if (d.ft) com.vivekkaushik.wrtpulse.ops.MeshOps.mobilityDomain(d.ssid) else "",
                )
            }
        }
        return kept + drafted
    }

    /**
     * Stages 802.11r (and the k/v steering that goes with it) on or off for a saved AP. The
     * mobility domain follows the SSID the way hostapd's own default does, so this AP and a
     * mesh node carrying the same name agree without either knowing about the other.
     */
    fun stageFastTransition(net: WifiNetwork, on: Boolean, ssid: String = value(net.section, "ssid", net.ssid)) {
        val encryption = value(net.section, "encryption", net.encryption)
        val wanted = on && com.vivekkaushik.wrtpulse.ops.MeshOps.roamingCapable(encryption)
        val savedOn = if (net.ieee80211r) "1" else ""
        if (wanted) {
            com.vivekkaushik.wrtpulse.ops.MeshOps.roamingOptions(ssid, encryption).forEach { (option, v) ->
                val saved = when (option) {
                    "ieee80211r" -> savedOn
                    "mobility_domain" -> net.mobilityDomain
                    else -> ""
                }
                stage(net.section, option, saved, v)
            }
        } else {
            // Off is the options' absence. The saved value is taken as set so the delete is
            // emitted; deleting an option that was never there is a no-op on the router.
            FT_OPTIONS.forEach { option ->
                val saved = when (option) {
                    "ieee80211r" -> savedOn
                    "mobility_domain" -> net.mobilityDomain
                    else -> if (net.ieee80211r) "1" else ""
                }
                stage(net.section, option, saved, "")
            }
        }
    }

    fun ops(): List<String> = staged.entries
        // An option set on a section that is about to be deleted is noise at best. The
        // delete supersedes it, so it never reaches the batch.
        .filterNot { it.key.substringBefore('.') in deletions }
        .sortedBy { it.key }
        .map { (key, change) ->
            // Switching a network to open leaves the old passphrase sitting in the config
            // file unless the option goes with it; "auto" TX power and beamforming "on" are
            // likewise the option's absence.
            if (change.second.isEmpty() && key.substringAfterLast('.') in DELETE_WHEN_EMPTY) "delete wireless.$key"
            else "set wireless.$key='${escape(change.second)}'"
        } +
        deletions.sorted().map { "delete wireless.$it" } +
        drafts.flatMap { draft ->
            draft.devices.flatMap { device ->
                val section = draft.sections.getValue(device)
                listOf("set wireless.$section=wifi-iface") +
                    draftOptions(draft, device).map { (option, value) ->
                        "set wireless.$section.$option='${escape(value)}'"
                    }
            }
        }

    /**
     * The `network` config a client draft also needs: a station with no interface behind it
     * associates and then sits there with no address. Empty when nothing joins an uplink.
     */
    fun networkOps(): List<String> = uplinks().flatMap { name ->
        listOf("set network.$name=interface", "set network.$name.proto='dhcp'")
    }

    /** The network sections client drafts will create, in order, without duplicates. */
    private fun uplinks(): List<String> = drafts.filter { it.isClient }.map { it.network }.distinct()

    /** network → firewall zone it should join, for client drafts that asked for one. */
    private fun zoneJoins(): List<Pair<String, String>> = drafts
        .filter { it.isClient && it.zone.isNotEmpty() }
        .map { it.network to it.zone }
        .distinct()

    /**
     * Firewall work happens outside the uci batch because the zone has to be found first.
     * Shown in the review sheet so applying holds no surprises.
     */
    fun firewallLines(): List<String> =
        zoneJoins().map { (network, zone) -> "+ firewall.@zone[$zone].network += '$network'" }

    private fun reload(): String {
        val joins = zoneJoins()
        if (uplinks().isEmpty()) return "wifi reload"
        return joins.joinToString(" ") { (network, zone) -> Commands.attachToZone(network, zone) } +
            (if (joins.isEmpty()) "" else " ") +
            "/etc/init.d/network reload >/dev/null 2>&1; wifi reload"
    }

    // ---- what the interface list draws ----

    /**
     * Whether a radio is switched on, staged edits included. An SSID on a radio that is off
     * is configured and completely silent: hostapd never starts, no netdev appears, and LuCI
     * reports "Wireless is disabled" against the radio rather than the network.
     */
    fun radioEnabled(section: String): Boolean {
        val radio = radios.firstOrNull { it.section == section } ?: return true
        return value(section, "disabled", if (radio.disabled) "1" else "0") != "1"
    }

    /** The first uplink name nothing else holds — what a new client should default to. */
    fun freeUplinkName(): String = free(Commands.WWAN, (interfaces + drafts.map { it.network }).toSet())

    /** True when writing this network would land on one the router already has. */
    fun networkExists(name: String): Boolean = name in interfaces

    /** The firewall zone a uci network sits in, or "" when nothing covers it. */
    fun zoneFor(network: String): String =
        zones.firstOrNull { network in it.networks }?.name.orEmpty()

    /**
     * Where an SSID lands. "lan" is the unremarkable answer and reads better bare; every
     * other zone is worth naming, because that is what decides who the clients can reach.
     */
    private fun placeLabel(network: String): String {
        val zone = zoneFor(network)
        return when {
            zone.isEmpty() -> network.ifEmpty { "—" }
            zone == "lan" -> "lan"
            else -> "$zone zone"
        }
    }

    /**
     * Every wireless interface as one row — configured sections first, then anything staged
     * but not yet applied. Channel is shown once per radio, on the row that sets it.
     */
    fun interfaceRows(): List<InterfaceRow> {
        val bandOf = radios.associate { it.section to it.band }
        val channelOf = radios.associate { it.section to value(it.section, "channel", it.channel) }
        val channelShown = mutableSetOf<String>()
        val rows = networks.map { net ->
            val ifname = sectionIfnames[net.section].orEmpty()
            val band = bandOf[net.device].orEmpty()
            val showChannel = !net.isClient && channelShown.add(net.device)
            val encryption = Parsers.encryptionLabel(value(net.section, "encryption", net.encryption))
            val enabled = value(net.section, "disabled", if (net.disabled) "1" else "0") != "1"
            val radioOn = radioEnabled(net.device)
            val signal = live[ifname]?.signalDbm
            InterfaceRow(
                key = net.section,
                section = net.section,
                draftId = null,
                ssid = if (net.isMesh) "Mesh link · ${net.meshId}" else value(net.section, "ssid", net.ssid),
                isClient = net.isClient,
                isUplink = net.isClient && zoneFor(net.network) == "wan",
                isNew = false,
                bands = band + if (showChannel) " · ch ${channelOf[net.device].orEmpty()}" else "",
                enabled = enabled,
                radioOn = radioOn,
                changed = changedIn(net.section),
                deleting = net.section in deletions,
                detail = when {
                    // The radio being off outranks everything else this row could say: the
                    // network is configured and nothing is on the air.
                    !radioOn -> listOf("${net.device} is off", encryption, placeLabel(net.network))
                        .joinToString(" · ")
                    net.isMesh -> listOfNotNull(
                        "802.11s backhaul",
                        if (ifname.isEmpty()) "not up" else "${clientCounts[ifname] ?: 0} peers",
                        "managed from Network · Mesh",
                    ).joinToString(" · ")
                    net.isClient -> listOfNotNull(
                        "→ ${value(net.section, "ssid", net.ssid)}",
                        if (!enabled) "disabled" else signal?.let { "$it dBm" },
                        placeLabel(net.network),
                    ).joinToString(" · ")
                    !enabled -> listOf("disabled", encryption, placeLabel(net.network)).joinToString(" · ")
                    else -> listOfNotNull(
                        "${clientCounts[ifname] ?: 0} clients",
                        encryption,
                        placeLabel(net.network),
                        if (value(net.section, "isolate", if (net.isolate) "1" else "0") == "1") "isolated" else null,
                        if (net.ieee80211r) "FT" else null,
                    ).joinToString(" · ")
                },
            )
        }
        val draftRows = drafts.map { draft ->
            InterfaceRow(
                key = "draft:${draft.id}",
                section = null,
                draftId = draft.id,
                ssid = draft.ssid,
                isClient = draft.isClient,
                isUplink = draft.isClient && draft.zone == "wan",
                isNew = true,
                bands = draft.devices.mapNotNull { bandOf[it] }.joinToString(" + "),
                enabled = true,
                radioOn = draft.devices.all { radioEnabled(it) },
                changed = true,
                detail = if (draft.devices.none { radioEnabled(it) }) {
                    "${draft.devices.joinToString(" and ")} off · nothing will broadcast"
                } else if (draft.isClient) {
                    "→ ${draft.ssid} · not applied yet · ${draft.zone.ifEmpty { draft.network }}"
                } else {
                    listOfNotNull(
                        "0 clients",
                        Parsers.encryptionLabel(draft.encryption),
                        placeLabel(draft.network),
                        if (draft.isolate) "isolated" else null,
                    ).joinToString(" · ")
                },
            )
        }
        return rows + draftRows
    }

    /**
     * Edits the router would choke on. hostapd refuses to start a WPA network whose
     * passphrase is outside 8..63 characters, so applying one silently takes that SSID
     * off the air — worth stopping before it runs rather than explaining afterwards.
     */
    fun problems(): List<String> = networks.mapNotNull { net ->
        // A mesh point has a mesh id where an SSID would be, and the Mesh page owns its key.
        if (net.isMesh) return@mapNotNull null
        val name = value(net.section, "ssid", net.ssid)
        val encryption = value(net.section, "encryption", net.encryption)
        val key = value(net.section, "key", net.key)
        val label = name.ifBlank { net.section }
        when {
            name.isBlank() -> "$label: a network needs a name."
            encryption == "none" || encryption.isEmpty() -> null
            key.length !in 8..63 -> "$label: a WPA password must be 8–63 characters."
            else -> null
        }
    }

    /** The commit line the review sheet prints under the diff. */
    fun commitLine(): String {
        val packages = listOf("wireless") + if (networkOps().isEmpty()) emptyList() else listOf("network")
        return "$ " + packages.joinToString(" && ") { "uci commit $it" } + " && wifi reload"
    }

    /** Runs the staged batch, then reloads the config so the UI reflects what the router has. */
    suspend fun apply(): Boolean {
        if (pendingCount == 0 || applying) return true
        problems().firstOrNull()?.let { error = it; return false }
        applying = true
        error = null
        return try {
            beforeApply?.invoke()
            val netOps = networkOps()
            val packages = listOf("wireless") + if (netOps.isEmpty()) emptyList() else listOf("network")
            val script = Commands.uciBatch(ops() + netOps, packages, reload = reload())
            // Bringing up a station reloads the network stack, so the reply can be slow.
            session.exec(script, timeoutMs = 60_000).requireOk("uci batch")
            staged.clear()
            drafts.clear()
            deletions.clear()
            load()
            true
        } catch (e: SshException) {
            error = e.message
            false
        } finally {
            applying = false
        }
    }

    companion object {
        /** Options whose empty staged value means "remove it", not "set it to nothing". */
        /** The hand-off options, which come and go together. */
        val FT_OPTIONS = listOf("ieee80211r", "mobility_domain", "ft_over_ds", "ft_psk_generate_local", "ieee80211k", "bss_transition")

        private val DELETE_WHEN_EMPTY: Set<String> =
            setOf("key", "txpower") + WifiRadio.VHT_BEAMFORM + WifiRadio.HE_BEAMFORM + FT_OPTIONS

        /** How long to wait for a detached survey; a scan is seconds, the radio restart most of the rest. */
        private const val SURVEY_WAIT_NANOS = 90_000_000_000L

        /** Our own APs, hidden or not, are not neighbours. */
        fun withoutOwn(cells: List<ScanCell>, ownBssids: Set<String>): List<ScanCell> =
            cells.filterNot { it.bssid.uppercase() in ownBssids }

        /**
         * A scan through a live AP that heard only the AP's own channel. It may be a quiet
         * band, but on ath10k-ct it is the driver never leaving the channel, and the advice
         * that follows would be built on one channel's worth of neighbours — so it is flagged
         * as possibly partial rather than presented as a survey.
         */
        fun onlyOwnChannel(cells: List<ScanCell>, ownChannel: Int?): Boolean =
            ownChannel != null && cells.isNotEmpty() && cells.all { it.channel == ownChannel }
        /**
         * A uci section name built from the SSID — `wrtpulse_` keeps it clear who added it,
         * and only letters, digits and underscores survive because uci accepts nothing else.
         */
        fun sectionBase(ssid: String, mode: String): String {
            val slug = ssid.lowercase()
                .map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }
                .joinToString("")
                .trim('_')
                .replace(Regex("_+"), "_")
                .take(24)
            // An SSID that already says "wrtpulse" doesn't need to say it twice.
            return if (slug.startsWith("wrtpulse")) slug else "wrtpulse_" + slug.ifEmpty { mode }
        }

        /** First name in the `base`, `base_2`, `base_3` … series that nothing else holds. */
        fun free(base: String, taken: Set<String>): String {
            if (base !in taken) return base
            var n = 2
            while ("${base}_$n" in taken) n++
            return "${base}_$n"
        }

        /** uci values travel single-quoted; a quote inside the value must not break out. */
        fun escape(value: String): String = value.replace("'", "'\\''")

        fun mask(value: String): String =
            if (value.length <= 2) "••" else value.first() + "•".repeat(value.length - 2) + value.last()
    }
}
