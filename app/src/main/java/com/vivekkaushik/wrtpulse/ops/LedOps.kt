package com.vivekkaushik.wrtpulse.ops

/**
 * What one LED is asked to do inside a [LedPattern]: a colour of a lens is several LEDs at
 * levels, a lone LED's "state" is one LED at a level. The token form is what the connectivity
 * watch's header carries so the app can read its own configuration back off the router.
 */
sealed class LedLevel {
    object Off : LedLevel()
    object On : LedLevel()
    data class Blink(val onMs: Int, val offMs: Int) : LedLevel()
    /** Per-channel intensities of a multicolor LED, in `multi_index` order. */
    data class Multi(val intensity: List<Int>) : LedLevel()

    val lit: Boolean get() = when (this) {
        Off -> false
        On, is Blink -> true
        is Multi -> intensity.any { it > 0 }
    }

    fun token(): String = when (this) {
        Off -> "0"
        On -> "1"
        is Blink -> "b$onMs/$offMs"
        is Multi -> "m" + intensity.joinToString(":")
    }

    companion object {
        fun parse(token: String): LedLevel? = when {
            token == "0" -> Off
            token == "1" -> On
            token.startsWith("b") -> token.drop(1).split('/').mapNotNull { it.toIntOrNull() }
                .takeIf { it.size == 2 }?.let { Blink(it[0], it[1]) }
            token.startsWith("m") -> token.drop(1).split(':').mapNotNull { it.toIntOrNull() }
                .takeIf { it.isNotEmpty() }?.let { Multi(it) }
            else -> null
        }
    }
}

/**
 * One thing the router's LEDs can show together — "white", "yellow", or for a lone LED
 * "slow blink". [levels] covers every LED in the lens, so showing a pattern is complete: an
 * LED not part of the colour is told to go off rather than left as it was.
 */
data class LedPattern(val id: String, val label: String, val swatch: Long, val levels: Map<String, LedLevel>)

/** The three states the connectivity watch tells apart, in the order the screen lists them. */
enum class WatchState(val key: String, val label: String, val body: String) {
    Internet("internet", "Internet", "A default route is up and a public address answers"),
    UpstreamOnly("upstream", "Upstream, no internet", "Route and link are there, nothing answers"),
    NoUpstream("down", "No upstream", "No default route, or its link has no carrier"),
}

data class LedwatchConfig(
    val states: Map<WatchState, LedPattern>,
    val intervalS: Int = 5,
    /** Consecutive failed checks before the lens leaves the internet colour. */
    val downAfter: Int = 2,
) {
    val leds: Set<String> get() = states.values.flatMap { it.levels.keys }.toSet()
}

/** What a uci `led` section can ask of an LED, as the screen offers it. */
sealed class LedMode {
    /** No section: whatever the device tree wired the LED to do. */
    object BoardDefault : LedMode()
    object Off : LedMode()
    object On : LedMode()
    data class Blink(val onMs: Int, val offMs: Int) : LedMode()
    object Heartbeat : LedMode()
    data class Netdev(val dev: String, val link: Boolean = true, val tx: Boolean = true, val rx: Boolean = true) : LedMode()
    /** Any other kernel trigger the LED lists — Wi-Fi traffic, a switch port, USB. */
    data class Raw(val trigger: String) : LedMode()
}

/** The commands one LED change turns into: uci ops for the batch, direct sysfs writes after it. */
data class LedWrite(val uci: List<String>, val direct: List<String>) {
    val isEmpty: Boolean get() = uci.isEmpty() && direct.isEmpty()
}

/**
 * LED logic with no router attached: what a sysfs name says about an LED, which colours a set
 * of LEDs can make together, how a uci `led` section maps to a mode and back, and the text of
 * the connectivity watch. The router is the only source of what exists — nothing here knows a
 * board by name (see the note at the top of [Regulatory]).
 */
object LedOps {

    /** The colour words the kernel's LED naming and OpenWrt's leds.sh both use. */
    val COLOURS: List<String> = listOf(
        "white", "red", "green", "blue", "amber", "violet", "yellow", "ir", "multicolor", "rgb",
        "purple", "orange", "pink", "cyan", "lime", "magenta",
    )

    /** Palette order: the composites people expect first, then the rarer single colours. */
    private val ORDER = listOf(
        "white", "red", "green", "blue", "yellow", "amber", "orange", "cyan", "magenta",
        "violet", "purple", "pink", "lime",
    )

    // ── names ──────────────────────────────────────────────────────────────────

    /** `red:wlan2g` → red, `tp-link:green:power` → green, `ath10k-phy0` → null. */
    fun colourOf(sysfs: String): String? {
        val p = sysfs.split(':')
        return when (p.size) {
            3 -> p[1].lowercase().takeIf { it in COLOURS }
            2 -> p[0].lowercase().takeIf { it in COLOURS }
            else -> null
        }
    }

    fun functionOf(sysfs: String): String? {
        val p = sysfs.split(':')
        return when (p.size) {
            3 -> p[2]
            2 -> p[1]
            else -> null
        }?.ifEmpty { null }
    }

    private val NUMBERED = Regex("^([a-z]+?)[-_]?(\\d+)$")

    /** A reading name for the LED's function: `wlan2g` → "Wi-Fi 2.4G", `lan-2` → "LAN 2". */
    fun label(sysfs: String): String {
        val f = functionOf(sysfs) ?: return sysfs
        val lower = f.lowercase()
        val numbered = NUMBERED.matchEntire(lower)?.takeIf { it.groupValues[1] in setOf("lan", "wan", "usb", "port", "eth", "led", "status", "sim") }
        val base = numbered?.groupValues?.get(1) ?: lower
        val name = when (base) {
            "power" -> "Power"
            "status" -> "Status"
            "system", "sys" -> "System"
            "wlan2g", "wlan-2g", "wifi2g" -> "Wi-Fi 2.4G"
            "wlan5g", "wlan-5g", "wifi5g" -> "Wi-Fi 5G"
            "wlan6g" -> "Wi-Fi 6G"
            "wlan", "wifi" -> "Wi-Fi"
            "wan" -> "WAN"
            "lan" -> "LAN"
            "eth" -> "Ethernet"
            "internet" -> "Internet"
            "usb" -> "USB"
            "wps" -> "WPS"
            "mesh" -> "Mesh"
            "signal" -> "Signal"
            "bluetooth", "bt" -> "Bluetooth"
            "sd", "mmc" -> "SD card"
            "sim" -> "SIM"
            "port" -> "Port"
            "led" -> "LED"
            else -> base.replaceFirstChar { it.uppercase() }
        }
        return numbered?.let { "$name ${it.groupValues[2]}" } ?: name
    }

    /** ARGB for a colour word, for the swatch beside it. Null is "no colour known" (a driver LED). */
    fun swatch(colour: String?): Long = when (colour) {
        "white" -> 0xFFE8F4EF
        "red" -> 0xFFF26D5E
        "green" -> 0xFF46D97E
        "blue" -> 0xFF5CA9F2
        "amber" -> 0xFFF2B24C
        "yellow" -> 0xFFF2E24C
        "orange" -> 0xFFF29A4C
        "cyan" -> 0xFF4CE5E0
        "magenta" -> 0xFFE05CF2
        "violet", "purple" -> 0xFFA06CF2
        "pink" -> 0xFFF28CC8
        "lime" -> 0xFFB8F24C
        null -> 0xFF3A4A44
        else -> 0xFF93A8A0
    }

    private val PHY_TRIGGER = Regex("^phy(\\d+)(tpt|rx|tx|assoc|radio)$")

    /** A kernel trigger name as the screen shows it. */
    fun triggerLabel(t: String): String {
        PHY_TRIGGER.matchEntire(t)?.let { m ->
            val what = when (m.groupValues[2]) {
                "tpt" -> "traffic"
                "rx" -> "receive"
                "tx" -> "transmit"
                "assoc" -> "clients"
                else -> "radio on"
            }
            return "Wi-Fi $what · phy${m.groupValues[1]}"
        }
        return when {
            t == "none" -> "Off"
            t == "default-on" -> "On"
            t == "timer" -> "Blink"
            t == "heartbeat" -> "Heartbeat"
            t == "netdev" -> "Network activity"
            t == "oneshot" -> "One-shot"
            t.startsWith("switch") -> "Switch port activity · $t"
            t == "usbport" -> "USB port"
            t.startsWith("mmc") -> "SD card activity · $t"
            t == "disk-activity" -> "Disk activity"
            t.startsWith("cpu") -> "CPU activity · $t"
            t == "panic" -> "Kernel panic"
            else -> t
        }
    }

    // ── palette ────────────────────────────────────────────────────────────────

    /** The LEDs worth composing a lens from: everything with a colour word, or a multicolor LED. */
    fun defaultLens(leds: List<RouterLed>): Set<String> {
        val coloured = leds.filter { it.multicolor || colourOf(it.sysfs) != null }.map { it.sysfs }
        return (coloured.ifEmpty { leds.map { it.sysfs } }).toSet()
    }

    /**
     * The colours the chosen LEDs can make together — derived from what is there, so a router
     * with a red, a green and a blue LED behind one lens gets seven colours, a router with one
     * green LED gets solid and two blink speeds, and nothing is offered that the hardware
     * cannot show. Off always closes the list.
     */
    fun palette(leds: List<RouterLed>, lens: Set<String>): List<LedPattern> {
        val chosen = leds.filter { it.sysfs in lens }
        if (chosen.isEmpty()) return emptyList()
        val allOff: Map<String, LedLevel> = chosen.associate {
            it.sysfs to (if (it.multicolor) LedLevel.Multi(List(it.channels.size) { 0 }) else LedLevel.Off)
        }
        fun pattern(id: String, label: String, colour: String?, lit: Map<String, LedLevel>) =
            LedPattern(id, label, swatch(colour), allOff + lit)
        fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

        val out = mutableListOf<LedPattern>()
        val multi = chosen.firstOrNull { it.multicolor }
        when {
            multi != null -> {
                fun mix(vararg on: String) = LedLevel.Multi(multi.channels.map { c -> if (c in on) multi.maxBrightness else 0 })
                val have = multi.channels.map { it.lowercase() }.toSet()
                have.forEach { c -> out += pattern(c, cap(c), c, mapOf(multi.sysfs to mix(c))) }
                composites(have) { name, parts -> out += pattern(name, cap(name), name, mapOf(multi.sysfs to mix(*parts))) }
            }
            chosen.size == 1 -> {
                val led = chosen.single()
                val c = colourOf(led.sysfs) ?: "white"
                out += pattern("solid", "Solid", c, mapOf(led.sysfs to LedLevel.On))
                out += pattern("slow", "Slow blink", c, mapOf(led.sysfs to LedLevel.Blink(1000, 1000)))
                out += pattern("fast", "Fast blink", c, mapOf(led.sysfs to LedLevel.Blink(200, 200)))
            }
            else -> {
                val byColour = chosen.mapNotNull { led -> colourOf(led.sysfs)?.let { it to led } }
                    .groupBy({ it.first }, { it.second })
                byColour.forEach { (c, group) -> out += pattern(c, cap(c), c, group.associate { it.sysfs to LedLevel.On }) }
                composites(byColour.keys) { name, parts ->
                    out += pattern(name, cap(name), name, parts.flatMap { byColour[it].orEmpty() }.associate { it.sysfs to LedLevel.On })
                }
                if (byColour.isEmpty()) {
                    // Driver LEDs only (ath9k-phy1 and friends): nothing to compose, still usable.
                    out += pattern("solid", "Solid", "white", chosen.associate { it.sysfs to LedLevel.On })
                    out += pattern("slow", "Slow blink", "white", chosen.associate { it.sysfs to LedLevel.Blink(1000, 1000) })
                }
            }
        }
        out.sortBy { p -> ORDER.indexOf(p.id).let { if (it < 0) ORDER.size else it } }
        out += LedPattern("off", "Off", swatch(null), allOff)
        return out
    }

    /** The mixes of red, green and blue — only where the parts exist and the result is not already an LED of its own. */
    private fun composites(have: Set<String>, add: (String, Array<String>) -> Unit) {
        val r = "red" in have; val g = "green" in have; val b = "blue" in have
        if (r && g && "yellow" !in have) add("yellow", arrayOf("red", "green"))
        if (g && b && "cyan" !in have) add("cyan", arrayOf("green", "blue"))
        if (r && b && "magenta" !in have) add("magenta", arrayOf("red", "blue"))
        if (r && g && b && "white" !in have) add("white", arrayOf("red", "green", "blue"))
    }

    /**
     * A first configuration for the watch: white / yellow / red where the palette has them,
     * the lone LED's solid / slow / fast where it does not, and otherwise the first three
     * entries. Null when the palette cannot tell three states apart.
     */
    fun defaultWatchConfig(palette: List<LedPattern>): LedwatchConfig? {
        if (palette.size < 2) return null
        fun pick(vararg ids: String, fallback: Int): LedPattern =
            ids.firstNotNullOfOrNull { id -> palette.firstOrNull { it.id == id } }
                ?: palette[fallback.coerceAtMost(palette.size - 1)]
        return LedwatchConfig(
            mapOf(
                WatchState.Internet to pick("white", "green", "solid", fallback = 0),
                WatchState.UpstreamOnly to pick("yellow", "amber", "orange", "blue", "slow", fallback = 1),
                WatchState.NoUpstream to pick("red", "magenta", "fast", "off", fallback = 2),
            )
        )
    }

    /**
     * The installed watch's colours, matched back onto the palette so the screen can show what
     * is selected. A state whose levels no palette entry reproduces comes back as a "Custom"
     * pattern so it is still shown and still re-installable unchanged.
     */
    fun watchConfigFrom(state: LedwatchState?, palette: List<LedPattern>): LedwatchConfig? {
        if (state == null || !state.installed || state.states.isEmpty()) return null
        val states = WatchState.values().mapNotNull { st ->
            val levels = state.states[st.key] ?: return@mapNotNull null
            val match = palette.firstOrNull { p -> p.levels.filterValues { it.lit } == levels.filterValues { it.lit } && p.levels.keys == levels.keys }
                ?: palette.firstOrNull { p -> p.levels.filterValues { it.lit } == levels.filterValues { it.lit } }
            st to (match ?: LedPattern("custom-${st.key}", "Custom", 0xFF93A8A0, levels))
        }.toMap()
        if (states.size < WatchState.values().size) return null
        return LedwatchConfig(states, state.intervalS ?: 5, state.downAfter ?: 2)
    }

    // ── modes ──────────────────────────────────────────────────────────────────

    fun modeLabel(m: LedMode): String = when (m) {
        LedMode.BoardDefault -> "Board default"
        LedMode.Off -> "Off"
        LedMode.On -> "On"
        is LedMode.Blink -> "Blink"
        LedMode.Heartbeat -> "Heartbeat"
        is LedMode.Netdev -> "Network activity"
        is LedMode.Raw -> triggerLabel(m.trigger)
    }

    fun describe(m: LedMode): String = when (m) {
        is LedMode.Blink -> "Blink · ${m.onMs}/${m.offMs} ms"
        is LedMode.Netdev -> "Activity on ${m.dev.ifEmpty { "—" }}" +
            listOfNotNull("link".takeIf { m.link }, "tx".takeIf { m.tx }, "rx".takeIf { m.rx })
                .joinToString(" ").let { if (it.isEmpty()) "" else " · $it" }
        else -> modeLabel(m)
    }

    /** What the LED is doing right now, from sysfs alone. */
    fun liveLabel(led: RouterLed): String = when {
        led.trigger != "none" -> triggerLabel(led.trigger)
        led.lit -> "On"
        else -> "Off"
    }

    /** The modes this LED's kernel triggers allow, board default first. */
    fun modesFor(led: RouterLed): List<LedMode> {
        val out = mutableListOf<LedMode>(LedMode.BoardDefault, LedMode.Off, LedMode.On)
        if ("timer" in led.triggers) out += LedMode.Blink(500, 500)
        if ("heartbeat" in led.triggers) out += LedMode.Heartbeat
        if ("netdev" in led.triggers) out += LedMode.Netdev("")
        led.triggers.filter { it !in setOf("none", "default-on", "timer", "heartbeat", "netdev", "oneshot") }
            .forEach { out += LedMode.Raw(it) }
        return out
    }

    /** The mode a uci section expresses; no section is the board default by definition. */
    fun modeOf(section: LedSection?): LedMode {
        if (section == null) return LedMode.BoardDefault
        return when (section.trigger) {
            "none" -> if (section.default == true || (section.brightness ?: 0) > 0) LedMode.On else LedMode.Off
            "default-on" -> LedMode.On
            "timer" -> LedMode.Blink(section.delayOn ?: 500, section.delayOff ?: 500)
            "heartbeat" -> LedMode.Heartbeat
            "netdev" -> LedMode.Netdev(section.dev.orEmpty(), "link" in section.mode, "tx" in section.mode, "rx" in section.mode)
            else -> LedMode.Raw(section.trigger)
        }
    }

    /** A section name for an LED the board file never configured. */
    fun newSection(sysfs: String): String =
        "wrtpulse_led_" + sysfs.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    /**
     * The uci ops (and, for the board default, the sysfs writes) that put an LED into a mode.
     *
     * A section is reused when the board file already made one for this LED — the stock
     * `led_wlan2g` sections are what the router's own defaults live in — and options the new
     * mode does not use are deleted only when they exist, since a `uci batch` stops at the
     * first failing line. "On" is the `default-on` trigger rather than `none` plus brightness:
     * removing a trigger clears the brightness in the kernel, and the init script writes the
     * trigger last, so `none` + `default=1` on an LED that had a trigger ends up dark.
     */
    fun modeOps(led: RouterLed, mode: LedMode, section: LedSection?, uci: Map<String, String>, dt: LedDtDefault?): LedWrite {
        val ops = mutableListOf<String>()
        if (mode is LedMode.BoardDefault) {
            if (section != null) ops += "delete system.${section.section}"
            // `led restart` puts back what it saved before it first configured the LED; the
            // device-tree default covers an LED the watch drove, which it never saved.
            return LedWrite(ops, dtRestore(led, dt))
        }
        val s = section?.section ?: newSection(led.sysfs)
        if (section == null) {
            ops += "set system.$s=led"
            ops += "set system.$s.name='${label(led.sysfs)}'"
            ops += "set system.$s.sysfs='${led.sysfs}'"
        }
        listOf("dev", "mode", "delayon", "delayoff", "brightness", "default", "port_mask", "speed_mask", "interval")
            .filter { uci.containsKey("system.$s.$it") }
            .forEach { ops += "delete system.$s.$it" }
        when (mode) {
            LedMode.Off -> { ops += "set system.$s.trigger='none'"; ops += "set system.$s.default='0'" }
            LedMode.On -> ops += "set system.$s.trigger='default-on'"
            is LedMode.Blink -> {
                ops += "set system.$s.trigger='timer'"
                ops += "set system.$s.delayon='${mode.onMs.coerceIn(20, 60_000)}'"
                ops += "set system.$s.delayoff='${mode.offMs.coerceIn(20, 60_000)}'"
            }
            LedMode.Heartbeat -> ops += "set system.$s.trigger='heartbeat'"
            is LedMode.Netdev -> {
                ops += "set system.$s.trigger='netdev'"
                ops += "set system.$s.dev='${mode.dev}'"
                val modes = listOfNotNull("link".takeIf { mode.link }, "tx".takeIf { mode.tx }, "rx".takeIf { mode.rx })
                ops += "set system.$s.mode='${modes.ifEmpty { listOf("link") }.joinToString(" ")}'"
            }
            is LedMode.Raw -> ops += "set system.$s.trigger='${mode.trigger}'"
            LedMode.BoardDefault -> Unit
        }
        return LedWrite(ops, emptyList())
    }

    /** Direct sysfs writes that give an LED its device-tree behaviour back; nothing when that is unknown. */
    fun dtRestore(led: RouterLed, dt: LedDtDefault?): List<String> {
        dt ?: return emptyList()
        val d = "/sys/class/leds/${led.sysfs}"
        val trig = dt.trigger
        return if (trig != null && trig != "none") listOf("echo $trig > $d/trigger 2>/dev/null")
        else listOf("echo none > $d/trigger", "echo ${if (dt.defaultOn == true) led.maxBrightness else 0} > $d/brightness")
    }

    /** Direct sysfs writes for one level. Trigger first: dropping a trigger zeroes the brightness. */
    fun directWrites(led: RouterLed, level: LedLevel): List<String> {
        val d = "/sys/class/leds/${led.sysfs}"
        return when (level) {
            LedLevel.Off -> listOf("echo none > $d/trigger", "echo 0 > $d/brightness")
            LedLevel.On -> listOf("echo none > $d/trigger", "echo ${led.maxBrightness} > $d/brightness")
            is LedLevel.Blink -> listOf("echo timer > $d/trigger", "echo ${level.onMs} > $d/delay_on", "echo ${level.offMs} > $d/delay_off")
            is LedLevel.Multi -> listOf(
                "echo none > $d/trigger",
                "echo '${level.intensity.joinToString(" ")}' > $d/multi_intensity",
                "echo ${led.maxBrightness} > $d/brightness",
            )
        }
    }

    /**
     * Shows a pattern for a few seconds and puts every touched LED back: trigger and brightness
     * (and colour) are saved first, restored after the sleep. A preview leaves nothing behind.
     */
    fun flashCommand(leds: List<RouterLed>, pattern: LedPattern, seconds: Int, reapplyConfig: Boolean): String {
        val touched = leds.filter { it.sysfs in pattern.levels.keys }
        val save = touched.mapIndexed { i, l ->
            val d = "/sys/class/leds/${l.sysfs}"
            "t$i=\$(sed -n 's/.*\\[\\(.*\\)\\].*/\\1/p' $d/trigger 2>/dev/null); b$i=\$(cat $d/brightness 2>/dev/null)" +
                if (l.multicolor) "; m$i=\$(cat $d/multi_intensity 2>/dev/null)" else ""
        }
        val apply = touched.flatMap { l -> directWrites(l, pattern.levels.getValue(l.sysfs)) }
        val restore = touched.mapIndexed { i, l ->
            val d = "/sys/class/leds/${l.sysfs}"
            "echo \"\${t$i:-none}\" > $d/trigger 2>/dev/null" +
                (if (l.multicolor) "; [ -n \"\$m$i\" ] && echo \"\$m$i\" > $d/multi_intensity" else "") +
                "; echo \"\${b$i:-0}\" > $d/brightness 2>/dev/null"
        }
        return (save + apply + "sleep ${seconds.coerceIn(1, 10)}" + restore +
            (if (reapplyConfig) listOf("/etc/init.d/led restart >/dev/null 2>&1") else emptyList()) +
            "echo flashed").joinToString("; ")
    }

    // ── the connectivity watch ─────────────────────────────────────────────────

    const val WATCH_VERSION = 1

    /** `red:wlan2g=1,green:power=b500/500` → levels. */
    fun decodeLevels(body: String): Map<String, LedLevel> = body.split(',').mapNotNull { item ->
        val eq = item.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        LedLevel.parse(item.substring(eq + 1).trim())?.let { item.substring(0, eq).trim() to it }
    }.toMap()

    private fun encodeLevels(levels: Map<String, LedLevel>): String =
        levels.entries.joinToString(",") { (k, v) -> "$k=${v.token()}" }

    /**
     * The watch script. Written into a quoted heredoc by [Commands.ledwatchInstall], so the
     * text is exactly what lands on the router. `¤` stands for `$` in the template below —
     * a shell script is mostly dollar signs, and `${'$'}` on every one of them buries it.
     */
    fun watchScript(cfg: LedwatchConfig): String {
        // Multi-line pieces carry the template's own indentation so trimIndent below measures
        // the same margin on every line; the pieces' first lines sit where the template puts them.
        val margin = "\n" + " ".repeat(12)
        val header = WatchState.values().joinToString(margin) { st ->
            "# state ${st.key} ${encodeLevels(cfg.states.getValue(st).levels)}"
        }
        val arms = WatchState.values().joinToString(margin) { st ->
            val writes = cfg.states.getValue(st).levels.entries.joinToString("; ") { (sysfs, lvl) -> "led '$sysfs' '${lvl.token()}'" }
            "\t\t${st.key}) $writes ;;"
        }
        return """
            #!/bin/sh
            # wrtpulse-ledwatch v$WATCH_VERSION
            $header
            # interval ${cfg.intervalS}
            # downafter ${cfg.downAfter}
            #
            # WrtPulse: colour this router's LEDs by its connectivity. Installed and configured
            # from the app (System · LEDs), which reads the header above back. Edit it there.
            #   internet  default route with carrier, and a public address answers a ping
            #   upstream  default route with carrier, nothing answers
            #   down      no default route, or its device has no carrier
            # Usage: wrtpulse-ledwatch            the watch loop (procd runs this)
            #        wrtpulse-ledwatch <state>    show one state once and exit

            LEDS=/sys/class/leds
            STATE=/var/run/wrtpulse-ledwatch.state
            INTERVAL=${cfg.intervalS}
            DOWN_AFTER=${cfg.downAfter}
            TARGETS="1.1.1.1 8.8.8.8 9.9.9.9"

            # Write only what differs: re-selecting a trigger restarts it, and a blink stutters.
            put() { [ "¤(cat "¤1" 2>/dev/null)" = "¤2" ] || echo "¤2" > "¤1" 2>/dev/null; }
            trig() { [ "¤(sed -n 's/.*\[\(.*\)\].*/\1/p' "¤1/trigger" 2>/dev/null)" = "¤2" ] || echo "¤2" > "¤1/trigger" 2>/dev/null; }

            # led <sysfs> <level>    level: 0 | 1 | b<on>/<off> | m<i:i:i>   (trigger first: dropping one zeroes the brightness)
            led() {
            	d="¤LEDS/¤1"; [ -d "¤d" ] || return 0
            	case "¤2" in
            		b*) t=¤{2#b}; trig "¤d" timer; put "¤d/delay_on" "¤{t%/*}"; put "¤d/delay_off" "¤{t#*/}" ;;
            		m*) trig "¤d" none; put "¤d/multi_intensity" "¤(echo "¤{2#m}" | tr ':' ' ')"; put "¤d/brightness" "¤(cat "¤d/max_brightness")" ;;
            		0)  trig "¤d" none; put "¤d/brightness" 0 ;;
            		*)  trig "¤d" none; put "¤d/brightness" "¤(cat "¤d/max_brightness")" ;;
            	esac
            }

            show() {
            	case "¤1" in
            $arms
            	esac
            	echo "¤1" > "¤STATE"
            }

            # "<gateway> <device>" of the lowest-metric default route; 1 when there is none or
            # its device has no carrier (cable out, station not associated).
            upstream() {
            	set -- ¤(ip -4 route show default 2>/dev/null | head -n1)
            	gw=""; dev=""
            	while [ ¤# -gt 0 ]; do case "¤1" in via) gw=¤2; shift ;; dev) dev=¤2; shift ;; esac; shift; done
            	[ -n "¤dev" ] || return 1
            	[ -r "/sys/class/net/¤dev/carrier" ] && [ "¤(cat /sys/class/net/¤dev/carrier 2>/dev/null)" = 0 ] && return 1
            	echo "¤gw ¤dev"
            }

            # Pings leave by the upstream device: a LAN that shares the WAN's subnet would
            # otherwise swallow them.
            internet() {
            	for t in ¤TARGETS; do ping -q -c 1 -W 2 -I "¤1" "¤t" >/dev/null 2>&1 && return 0; done
            	return 1
            }

            if [ -n "¤1" ]; then show "¤1"; exit 0; fi

            state=""; fails=0
            while :; do
            	if up=¤(upstream); then
            		dev=¤{up##* }
            		if internet "¤dev"; then
            			fails=0; want=internet
            		else
            			fails=¤((fails + 1))
            			# hysteresis: one lost ping is not an outage
            			if [ "¤state" = internet ] && [ "¤fails" -lt "¤DOWN_AFTER" ]; then want=internet; else want=upstream; fi
            		fi
            	else
            		fails=0; want=down
            	fi
            	if [ "¤want" != "¤state" ]; then
            		logger -t wrtpulse-ledwatch "¤{state:-start} -> ¤want (upstream: ¤{up:-none})"
            		state=¤want
            	fi
            	show "¤want"
            	sleep "¤INTERVAL"
            done
        """.trimIndent().replace('¤', '$')
    }

    /** The procd init script beside it. START=97 lands after `led` (96), so uci LEDs never win. */
    val WATCH_INIT: String = """
        #!/bin/sh /etc/rc.common
        # wrtpulse-ledwatch: connectivity LED watch, installed by WrtPulse (System · LEDs)

        START=97
        STOP=10
        USE_PROCD=1

        start_service() {
        	procd_open_instance
        	procd_set_param command ${Commands.LEDWATCH_PATH}
        	procd_set_param respawn 3600 5 0
        	procd_set_param stderr 1
        	procd_close_instance
        }

        stop_service() {
        	rm -f ${Commands.LEDWATCH_STATE_FILE}
        	# configured LEDs go back to their uci settings; the rest keep the last colour
        	/etc/init.d/led restart >/dev/null 2>&1
        }
    """.trimIndent()
}
