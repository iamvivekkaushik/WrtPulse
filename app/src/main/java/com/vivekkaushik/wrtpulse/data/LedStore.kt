package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.LedDtDefault
import com.vivekkaushik.wrtpulse.ops.LedMode
import com.vivekkaushik.wrtpulse.ops.LedOps
import com.vivekkaushik.wrtpulse.ops.LedPattern
import com.vivekkaushik.wrtpulse.ops.LedSection
import com.vivekkaushik.wrtpulse.ops.LedwatchConfig
import com.vivekkaushik.wrtpulse.ops.LedwatchState
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.RouterLed
import com.vivekkaushik.wrtpulse.ops.ServiceAction

/**
 * The router's LEDs: what /sys/class/leds lists, what the device tree and /etc/config/system
 * say about each, and the connectivity watch that colours them. Read when the screen opens —
 * an LED changes when somebody changes it.
 *
 * Changes apply at once rather than being staged: a wrong LED colour is the most reversible
 * thing this app can do to a router, and the screen shows the router's answer after every
 * write by reading it all back.
 */
class LedStore(private val session: RouterSession) {

    var leds by mutableStateOf<List<RouterLed>>(emptyList()); private set
    var sections by mutableStateOf<List<LedSection>>(emptyList()); private set
    var dtDefaults by mutableStateOf<List<LedDtDefault>>(emptyList()); private set
    var netdevs by mutableStateOf<List<String>>(emptyList()); private set
    var uci by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var watch by mutableStateOf<LedwatchState?>(null); private set

    /** The LEDs behind one lens — what the palette is composed from. The user can edit it. */
    var lens by mutableStateOf<Set<String>>(emptySet()); private set

    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var applying by mutableStateOf(false); private set
    var previewing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set

    /** The colours the lens can show, derived from the LEDs in it. */
    val palette: List<LedPattern> get() = LedOps.palette(leds, lens)

    /** LEDs the installed watch drives; their rows are read-only. */
    val watchedLeds: Set<String> get() = watch?.takeIf { it.installed }?.watched.orEmpty()

    /** The watch's current colours matched onto the palette, or a fresh default when it is not installed. */
    val watchConfig: LedwatchConfig? get() = LedOps.watchConfigFrom(watch, palette) ?: LedOps.defaultWatchConfig(palette)

    fun sectionOf(sysfs: String): LedSection? = sections.firstOrNull { it.sysfs == sysfs }
    fun dtOf(sysfs: String): LedDtDefault? = dtDefaults.firstOrNull { it.sysfs == sysfs }
    fun modeOf(led: RouterLed): LedMode = LedOps.modeOf(sectionOf(led.sysfs))

    suspend fun load() {
        loading = true
        try {
            val out = session.exec(Commands.LEDS, timeoutMs = 30_000)
            ingest(Parsers.sections(out.stdout))
            error = if (leds.isEmpty()) "No LEDs under /sys/class/leds on this router." else null
            loaded = true
        } catch (e: SshException) {
            error = "Couldn't read the LEDs: ${e.message}"
        } finally {
            loading = false
        }
    }

    /** Test seam: the batch's sections, keyed by marker. */
    fun ingest(parts: Map<String, String>) {
        leds = Parsers.leds(parts["leds"].orEmpty())
        dtDefaults = Parsers.ledDtDefaults(parts["dt"].orEmpty())
        uci = Parsers.uciShow(parts["uci"].orEmpty())
        sections = Parsers.ledSections(uci)
        netdevs = parts["netdevs"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && it != "lo" }
        watch = Parsers.ledwatchState(parts["watch"].orEmpty())
        val known = leds.map { it.sysfs }.toSet()
        val watched = watchedLeds
        lens = when {
            lens.isNotEmpty() && lens.all { it in known } -> lens
            watched.isNotEmpty() && watched.all { it in known } -> watched
            else -> LedOps.defaultLens(leds)
        }
    }

    fun toggleLens(sysfs: String) {
        lens = if (sysfs in lens) lens - sysfs else lens + sysfs
    }

    /** Puts one LED into a mode; the board default deletes its section. */
    suspend fun setMode(sysfs: String, mode: LedMode): Boolean {
        val led = leds.firstOrNull { it.sysfs == sysfs } ?: return fail("No LED called $sysfs.")
        if (!Commands.safeLedName(sysfs)) return fail("Unsupported LED name.")
        if (sysfs in watchedLeds) return fail("${led.label} is driven by the connectivity watch. Remove the watch, or take the LED out of its lens.")
        if (mode is LedMode.Netdev && mode.dev.isBlank()) return fail("Pick a network device for the LED to follow.")
        if (mode is LedMode.Netdev && mode.dev !in netdevs) return fail("${mode.dev} is not a device on this router.")
        val write = LedOps.modeOps(led, mode, sectionOf(sysfs), uci, dtOf(sysfs))
        if (write.isEmpty) { notice = "${led.label} already has its board default."; return true }
        return run(Commands.ledApply(write.uci, write.direct), "${led.label} → ${LedOps.describe(mode)}")
    }

    /** Every configured LED back to its board default; the watch's LEDs are left to the watch. */
    suspend fun resetAll(): Boolean {
        val watched = watchedLeds
        val ops = mutableListOf<String>()
        val direct = mutableListOf<String>()
        for (led in leds) {
            if (led.sysfs in watched) continue
            val write = LedOps.modeOps(led, LedMode.BoardDefault, sectionOf(led.sysfs), uci, dtOf(led.sysfs))
            ops += write.uci; direct += write.direct
        }
        if (ops.isEmpty() && direct.isEmpty()) { notice = "Every LED is already at its board default."; return true }
        return run(Commands.ledApply(ops, direct), "LEDs back to their board defaults.")
    }

    /** Shows a pattern for a few seconds, then puts the LEDs back. Nothing is written to config. */
    suspend fun flash(pattern: LedPattern, seconds: Int = 3): Boolean {
        if (previewing) return true
        val touched = leds.filter { it.sysfs in pattern.levels.keys }
        if (touched.isEmpty()) return fail("None of that pattern's LEDs are on this router.")
        if (!touched.all { Commands.safeLedName(it.sysfs) }) return fail("Unsupported LED name.")
        previewing = true
        error = null
        return try {
            val configured = touched.any { sectionOf(it.sysfs) != null }
            session.exec(LedOps.flashCommand(touched, pattern, seconds, configured), timeoutMs = 30_000).requireOk("led flash")
            true
        } catch (e: SshException) {
            error = "Preview failed: ${e.message}"
            false
        } finally {
            previewing = false
        }
    }

    /** Installs the watch, or rewrites it with new colours. */
    suspend fun installWatch(cfg: LedwatchConfig): Boolean {
        if (cfg.leds.isEmpty()) return fail("The lens has no LEDs in it.")
        if (!cfg.leds.all { Commands.safeLedName(it) }) return fail("Unsupported LED name.")
        val deletes = sections.filter { it.sysfs in cfg.leds }.map { "delete system.${it.section}" }
        val existed = watch?.installed == true
        return run(
            Commands.ledwatchInstall(LedOps.watchScript(cfg), LedOps.WATCH_INIT, deletes),
            if (existed) "Connectivity watch updated." else "Connectivity watch installed and running.",
            timeoutMs = 90_000,
        )
    }

    suspend fun removeWatch(): Boolean {
        val restore = leds.filter { it.sysfs in watchedLeds }.flatMap { LedOps.dtRestore(it, dtOf(it.sysfs)) }
        return run(Commands.ledwatchRemove(restore), "Connectivity watch removed.")
    }

    /** Start + enable at boot, or stop + disable — the watch stays installed either way. */
    suspend fun setWatchEnabled(on: Boolean): Boolean {
        val verbs = if (on) listOf(ServiceAction.Enable, ServiceAction.Start) else listOf(ServiceAction.Stop, ServiceAction.Disable)
        val cmd = verbs.joinToString("; ") { "${Commands.LEDWATCH_INIT} ${it.verb} >/dev/null 2>&1" } + "; sleep 1; echo done"
        return run(cmd, if (on) "Connectivity watch running." else "Connectivity watch stopped.")
    }

    private fun fail(message: String): Boolean {
        error = message
        return false
    }

    private suspend fun run(command: String, done: String, timeoutMs: Long = 60_000): Boolean {
        if (applying) return false
        applying = true
        error = null
        notice = null
        return try {
            session.exec(command, timeoutMs = timeoutMs).requireOk("led apply")
            load()
            notice = done
            true
        } catch (e: SshException) {
            error = e.message
            false
        } finally {
            applying = false
        }
    }
}
