package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Commands.LEDS` `leds` section as a TP-Link Deco M4R v1 (OpenWrt 25.12.5, ath79) prints it:
 * one RGB lens behind three GPIO LEDs, plus the two driver LEDs ath9k/ath10k register.
 */
internal val DECO_LEDS = listOf(
    "ath10k-phy0\t255\t0\tnone timer heartbeat default-on netdev switch0 phy0rx phy0tx phy0assoc phy0radio [phy0tpt] phy1rx phy1tx phy1assoc phy1radio phy1tpt\t\t",
    "ath9k-phy1\t255\t0\tnone timer heartbeat default-on netdev switch0 phy0rx phy0tx phy0assoc phy0radio phy0tpt phy1rx phy1tx phy1assoc phy1radio [phy1tpt]\t\t",
    "blue:wlan5g\t1\t0\tnone timer heartbeat default-on netdev switch0 phy0rx phy0tx phy0assoc phy0radio [phy0tpt] phy1rx phy1tx phy1assoc phy1radio phy1tpt\t\t",
    "green:power\t1\t1\t[none] timer heartbeat default-on netdev switch0 phy0rx phy0tx phy0assoc phy0radio phy0tpt phy1rx phy1tx phy1assoc phy1radio phy1tpt\t\t",
    "red:wlan2g\t1\t1\tnone timer heartbeat default-on netdev switch0 phy0rx phy0tx phy0assoc phy0radio phy0tpt phy1rx phy1tx phy1assoc phy1radio [phy1tpt]\t\t",
).joinToString("\n")

/** The same box's device tree: the power node has colour+function and default-state, the others labels and triggers. */
internal val DECO_DT = "green:power\t\ton\nred:wlan2g\tphy1tpt\t\nblue:wlan5g\tphy0tpt\t"

/** `uci show system` with one board-file section and one the app made. */
internal val DECO_SYSTEM_UCI = """
    system.@system[0]=system
    system.@system[0].hostname='OpenWrt'
    system.ntp=timeserver
    system.ntp.enabled='1'
    system.led_wlan2g=led
    system.led_wlan2g.name='WLAN2G'
    system.led_wlan2g.sysfs='red:wlan2g'
    system.led_wlan2g.trigger='phy1tpt'
    system.wrtpulse_led_blue_wlan5g=led
    system.wrtpulse_led_blue_wlan5g.name='Wi-Fi 5G'
    system.wrtpulse_led_blue_wlan5g.sysfs='blue:wlan5g'
    system.wrtpulse_led_blue_wlan5g.trigger='netdev'
    system.wrtpulse_led_blue_wlan5g.dev='eth0.2'
    system.wrtpulse_led_blue_wlan5g.mode='link tx rx'
""".trimIndent()

class LedOpsTest {

    private val leds = Parsers.leds(DECO_LEDS)
    private fun led(name: String) = leds.first { it.sysfs == name }

    // ── parsing ────────────────────────────────────────────────────────────────

    @Test
    fun `sysfs lines become LEDs with their active trigger and the whole trigger list`() {
        assertEquals(5, leds.size)
        val blue = led("blue:wlan5g")
        assertEquals("phy0tpt", blue.trigger)
        assertEquals(1, blue.maxBrightness)
        assertFalse(blue.lit)
        assertTrue("timer" in blue.triggers && "netdev" in blue.triggers && "none" in blue.triggers)
        assertFalse(blue.triggers.any { it.startsWith("[") })
        assertEquals("none", led("green:power").trigger)
        assertTrue(led("green:power").lit)
        assertFalse(blue.multicolor)
    }

    @Test
    fun `a multicolor LED keeps its channels and intensities`() {
        val rgb = Parsers.leds("rgb:status\t255\t255\t[none] timer\tred green blue\t255 0 128").single()
        assertTrue(rgb.multicolor)
        assertEquals(listOf("red", "green", "blue"), rgb.channels)
        assertEquals(listOf(255, 0, 128), rgb.intensity)
    }

    @Test
    fun `device-tree defaults carry trigger or default-state`() {
        val dt = Parsers.ledDtDefaults(DECO_DT)
        assertEquals(3, dt.size)
        val power = dt.first { it.sysfs == "green:power" }
        assertNull(power.trigger); assertEquals(true, power.defaultOn)
        val wlan = dt.first { it.sysfs == "red:wlan2g" }
        assertEquals("phy1tpt", wlan.trigger); assertNull(wlan.defaultOn)
    }

    @Test
    fun `led sections are found whether the board or the app named them`() {
        val sections = Parsers.ledSections(Parsers.uciShow(DECO_SYSTEM_UCI))
        assertEquals(2, sections.size)
        val board = sections.first { it.section == "led_wlan2g" }
        assertEquals("red:wlan2g", board.sysfs); assertEquals("phy1tpt", board.trigger)
        val app = sections.first { it.sysfs == "blue:wlan5g" }
        assertEquals("netdev", app.trigger); assertEquals("eth0.2", app.dev)
        assertEquals(listOf("link", "tx", "rx"), app.mode)
    }

    // ── names and colours ──────────────────────────────────────────────────────

    @Test
    fun `colour and function come from the new and the legacy naming, never from a driver LED`() {
        assertEquals("red", LedOps.colourOf("red:wlan2g"))
        assertEquals("wlan2g", LedOps.functionOf("red:wlan2g"))
        assertEquals("green", LedOps.colourOf("tp-link:green:power"))
        assertEquals("power", LedOps.functionOf("tp-link:green:power"))
        assertNull(LedOps.colourOf("ath10k-phy0"))
        assertNull(LedOps.functionOf("ath10k-phy0"))
        assertNull(LedOps.colourOf("foo:bar"))
        assertEquals("bar", LedOps.functionOf("foo:bar"))
    }

    @Test
    fun `labels read as the function they light`() {
        assertEquals("Wi-Fi 2.4G", LedOps.label("red:wlan2g"))
        assertEquals("Power", LedOps.label("green:power"))
        assertEquals("LAN 2", LedOps.label("green:lan-2"))
        assertEquals("LAN 1", LedOps.label("green:lan1"))
        assertEquals("WAN", LedOps.label("orange:wan"))
        assertEquals("ath10k-phy0", LedOps.label("ath10k-phy0"))
        assertEquals("Wi-Fi traffic · phy0", LedOps.triggerLabel("phy0tpt"))
        assertEquals("Heartbeat", LedOps.triggerLabel("heartbeat"))
        assertEquals("switch0", LedOps.triggerLabel("switch0").substringAfter("· "))
    }

    // ── palette ────────────────────────────────────────────────────────────────

    @Test
    fun `red green and blue behind one lens make seven colours and off`() {
        val lens = LedOps.defaultLens(leds)
        assertEquals(setOf("red:wlan2g", "green:power", "blue:wlan5g"), lens)
        val palette = LedOps.palette(leds, lens)
        assertEquals(listOf("white", "red", "green", "blue", "yellow", "cyan", "magenta", "off"), palette.map { it.id })
        val white = palette.first { it.id == "white" }
        assertEquals(3, white.levels.count { it.value == LedLevel.On })
        val yellow = palette.first { it.id == "yellow" }
        assertEquals(LedLevel.On, yellow.levels["red:wlan2g"])
        assertEquals(LedLevel.On, yellow.levels["green:power"])
        assertEquals(LedLevel.Off, yellow.levels["blue:wlan5g"])
        assertTrue(palette.last().levels.values.all { it == LedLevel.Off })
    }

    @Test
    fun `a real white LED takes the place of the mix, and an existing yellow is not composed twice`() {
        val four = Parsers.leds(
            "white:status\t1\t0\t[none] timer\t\t\nred:a\t1\t0\t[none]\t\t\ngreen:b\t1\t0\t[none]\t\t\nblue:c\t1\t0\t[none]\t\t\nyellow:d\t1\t0\t[none]\t\t",
        )
        val palette = LedOps.palette(four, LedOps.defaultLens(four))
        val white = palette.first { it.id == "white" }
        assertEquals(setOf("white:status"), white.levels.filterValues { it.lit }.keys)
        assertEquals(1, palette.count { it.id == "yellow" })
        assertEquals(setOf("yellow:d"), palette.first { it.id == "yellow" }.levels.filterValues { it.lit }.keys)
    }

    @Test
    fun `one LED gets solid and two blink speeds instead of colours`() {
        val one = Parsers.leds("green:power\t1\t1\t[none] timer heartbeat\t\t")
        val palette = LedOps.palette(one, setOf("green:power"))
        assertEquals(listOf("solid", "slow", "fast", "off"), palette.map { it.id })
        assertEquals(LedLevel.Blink(1000, 1000), palette[1].levels["green:power"])
    }

    @Test
    fun `a multicolor LED mixes its channels through multi_intensity`() {
        val rgb = Parsers.leds("rgb:status\t255\t0\t[none] timer\tred green blue\t0 0 0")
        val palette = LedOps.palette(rgb, setOf("rgb:status"))
        assertEquals(listOf("white", "red", "green", "blue", "yellow", "cyan", "magenta", "off"), palette.map { it.id })
        assertEquals(LedLevel.Multi(listOf(255, 255, 0)), palette.first { it.id == "yellow" }.levels["rgb:status"])
        assertEquals(LedLevel.Multi(listOf(0, 0, 0)), palette.last().levels["rgb:status"])
    }

    @Test
    fun `the default watch colours are white yellow red when the lens has them`() {
        val cfg = LedOps.defaultWatchConfig(LedOps.palette(leds, LedOps.defaultLens(leds)))!!
        assertEquals("white", cfg.states[WatchState.Internet]!!.id)
        assertEquals("yellow", cfg.states[WatchState.UpstreamOnly]!!.id)
        assertEquals("red", cfg.states[WatchState.NoUpstream]!!.id)
        val one = Parsers.leds("green:power\t1\t1\t[none] timer\t\t")
        val lone = LedOps.defaultWatchConfig(LedOps.palette(one, setOf("green:power")))!!
        assertEquals(listOf("solid", "slow", "fast"), WatchState.values().map { lone.states[it]!!.id })
    }

    // ── modes ──────────────────────────────────────────────────────────────────

    @Test
    fun `modes offered follow the LED's own triggers`() {
        val modes = LedOps.modesFor(led("blue:wlan5g"))
        assertEquals(LedMode.BoardDefault, modes[0])
        assertTrue(modes.any { it is LedMode.Blink })
        assertTrue(modes.any { it is LedMode.Netdev })
        assertTrue(modes.contains(LedMode.Raw("phy0tpt")))
        assertFalse(modes.contains(LedMode.Raw("none")))
        assertFalse(modes.contains(LedMode.Raw("default-on")))
        val bare = Parsers.leds("x:y\t1\t0\t[none]\t\t").single()
        assertEquals(listOf(LedMode.BoardDefault, LedMode.Off, LedMode.On), LedOps.modesFor(bare))
    }

    @Test
    fun `a section reads back as the mode that wrote it`() {
        val uci = Parsers.uciShow(DECO_SYSTEM_UCI)
        val sections = Parsers.ledSections(uci)
        assertEquals(LedMode.Raw("phy1tpt"), LedOps.modeOf(sections.first { it.sysfs == "red:wlan2g" }))
        assertEquals(LedMode.Netdev("eth0.2", link = true, tx = true, rx = true), LedOps.modeOf(sections.first { it.sysfs == "blue:wlan5g" }))
        assertEquals(LedMode.BoardDefault, LedOps.modeOf(null))
        assertEquals(LedMode.On, LedOps.modeOf(LedSection("s", "n", "x", "default-on", null, null, emptyList(), null, null, null)))
        assertEquals(LedMode.Off, LedOps.modeOf(LedSection("s", "n", "x", "none", false, null, emptyList(), null, null, null)))
        assertEquals(LedMode.Blink(100, 900), LedOps.modeOf(LedSection("s", "n", "x", "timer", null, null, emptyList(), 100, 900, null)))
    }

    @Test
    fun `an existing board section is edited in place and stale options deleted only when present`() {
        val uci = Parsers.uciShow(DECO_SYSTEM_UCI)
        val section = Parsers.ledSections(uci).first { it.sysfs == "blue:wlan5g" }
        val w = LedOps.modeOps(led("blue:wlan5g"), LedMode.Heartbeat, section, uci, null)
        assertTrue(w.direct.isEmpty())
        assertFalse(w.uci.any { it.startsWith("set system.wrtpulse_led_blue_wlan5g=") })
        assertTrue(w.uci.contains("delete system.wrtpulse_led_blue_wlan5g.dev"))
        assertTrue(w.uci.contains("delete system.wrtpulse_led_blue_wlan5g.mode"))
        assertFalse(w.uci.any { it == "delete system.wrtpulse_led_blue_wlan5g.delayon" })
        assertEquals("set system.wrtpulse_led_blue_wlan5g.trigger='heartbeat'", w.uci.last())
    }

    @Test
    fun `an LED with no section gets a named one, and On is the default-on trigger`() {
        val uci = Parsers.uciShow(DECO_SYSTEM_UCI)
        val w = LedOps.modeOps(led("green:power"), LedMode.On, null, uci, null)
        assertEquals(
            listOf(
                "set system.wrtpulse_led_green_power=led",
                "set system.wrtpulse_led_green_power.name='Power'",
                "set system.wrtpulse_led_green_power.sysfs='green:power'",
                "set system.wrtpulse_led_green_power.trigger='default-on'",
            ),
            w.uci,
        )
        val blink = LedOps.modeOps(led("green:power"), LedMode.Blink(100, 900), null, uci, null)
        assertTrue(blink.uci.contains("set system.wrtpulse_led_green_power.trigger='timer'"))
        assertTrue(blink.uci.contains("set system.wrtpulse_led_green_power.delayon='100'"))
        assertTrue(blink.uci.contains("set system.wrtpulse_led_green_power.delayoff='900'"))
        val net = LedOps.modeOps(led("green:power"), LedMode.Netdev("br-lan", link = true, tx = false, rx = true), null, uci, null)
        assertTrue(net.uci.contains("set system.wrtpulse_led_green_power.dev='br-lan'"))
        assertTrue(net.uci.contains("set system.wrtpulse_led_green_power.mode='link rx'"))
    }

    @Test
    fun `board default deletes the section and writes the device-tree behaviour back`() {
        val uci = Parsers.uciShow(DECO_SYSTEM_UCI)
        val dt = Parsers.ledDtDefaults(DECO_DT)
        val section = Parsers.ledSections(uci).first { it.sysfs == "red:wlan2g" }
        val w = LedOps.modeOps(led("red:wlan2g"), LedMode.BoardDefault, section, uci, dt.first { it.sysfs == "red:wlan2g" })
        assertEquals(listOf("delete system.led_wlan2g"), w.uci)
        assertEquals(listOf("echo phy1tpt > /sys/class/leds/red:wlan2g/trigger 2>/dev/null"), w.direct)
        // The power LED has no trigger, only default-state on: none + max brightness.
        val power = LedOps.modeOps(led("green:power"), LedMode.BoardDefault, null, uci, dt.first { it.sysfs == "green:power" })
        assertTrue(power.uci.isEmpty())
        assertEquals(listOf("echo none > /sys/class/leds/green:power/trigger", "echo 1 > /sys/class/leds/green:power/brightness"), power.direct)
        // Nothing known: nothing written.
        assertTrue(LedOps.modeOps(led("ath9k-phy1"), LedMode.BoardDefault, null, uci, null).isEmpty)
    }

    @Test
    fun `a preview saves, shows, sleeps and restores every touched LED`() {
        val palette = LedOps.palette(leds, LedOps.defaultLens(leds))
        val cmd = LedOps.flashCommand(leds, palette.first { it.id == "yellow" }, 3, reapplyConfig = false)
        assertTrue(cmd.contains("t0=\$(sed -n"))
        assertTrue(cmd.contains("echo none > /sys/class/leds/red:wlan2g/trigger; echo 1 > /sys/class/leds/red:wlan2g/brightness"))
        assertTrue(cmd.contains("echo 0 > /sys/class/leds/blue:wlan5g/brightness"))
        assertTrue(cmd.contains("sleep 3"))
        assertTrue(cmd.indexOf("sleep 3") < cmd.indexOf("echo \"\${t0:-none}\" >"))
        assertFalse(cmd.contains("/etc/init.d/led restart"))
        assertTrue(cmd.endsWith("echo flashed"))
        assertTrue(LedOps.flashCommand(leds, palette.first(), 3, reapplyConfig = true).contains("/etc/init.d/led restart"))
    }

    // ── the watch ──────────────────────────────────────────────────────────────

    @Test
    fun `levels round-trip through their tokens`() {
        val levels = mapOf("a:b" to LedLevel.On, "c:d" to LedLevel.Blink(200, 800), "e:f" to LedLevel.Multi(listOf(255, 0, 9)), "g:h" to LedLevel.Off)
        val body = levels.entries.joinToString(",") { "${it.key}=${it.value.token()}" }
        assertEquals("a:b=1,c:d=b200/800,e:f=m255:0:9,g:h=0", body)
        assertEquals(levels, LedOps.decodeLevels(body))
        assertNull(LedLevel.parse("zz"))
    }

    @Test
    fun `the watch script carries its config in the header and one arm per state`() {
        val palette = LedOps.palette(leds, LedOps.defaultLens(leds))
        val cfg = LedOps.defaultWatchConfig(palette)!!.copy(intervalS = 10, downAfter = 3)
        val script = LedOps.watchScript(cfg)
        assertTrue(script.startsWith("#!/bin/sh\n# wrtpulse-ledwatch v1\n# state internet "))
        assertFalse(script.contains('¤'))
        assertTrue(script.contains("# interval 10"))
        assertTrue(script.contains("INTERVAL=10"))
        assertTrue(script.contains("DOWN_AFTER=3"))
        // LEDs keep sysfs order (alphabetical on this box), so the arm reads blue, green, red.
        assertTrue(script.contains("\t\tinternet) led 'blue:wlan5g' '1'; led 'green:power' '1'; led 'red:wlan2g' '1' ;;"))
        assertTrue(script.contains("\t\tdown) led 'blue:wlan5g' '0'; led 'green:power' '0'; led 'red:wlan2g' '1' ;;"))
        assertTrue(script.contains("ping -q -c 1 -W 2 -I \"\$1\" \"\$t\""))
        assertTrue(script.lines().none { it.startsWith(" ") })
        assertFalse(script.lines().any { it == "WRTPULSE_EOF" })
        // The header the router hands back reads as the same configuration.
        val state = Parsers.ledwatchState("installed\nenabled\nrunning\ncurrent internet\n" + script.lines().take(8).drop(1).joinToString("\n"))
        assertTrue(state.installed && state.enabled && state.running)
        assertEquals(1, state.version)
        assertEquals("internet", state.current)
        assertEquals(10, state.intervalS); assertEquals(3, state.downAfter)
        assertEquals(setOf("red:wlan2g", "green:power", "blue:wlan5g"), state.watched)
        val back = LedOps.watchConfigFrom(state, palette)!!
        assertEquals("white", back.states[WatchState.Internet]!!.id)
        assertEquals("yellow", back.states[WatchState.UpstreamOnly]!!.id)
        assertEquals("red", back.states[WatchState.NoUpstream]!!.id)
        assertEquals(10, back.intervalS)
    }

    @Test
    fun `a colour the palette no longer has comes back as custom rather than nothing`() {
        val palette = LedOps.palette(leds, setOf("red:wlan2g", "green:power"))
        val state = Parsers.ledwatchState(
            "installed\n# wrtpulse-ledwatch v1\n# state internet red:wlan2g=1,green:power=1,blue:wlan5g=1\n# state upstream red:wlan2g=1,green:power=1\n# state down red:wlan2g=1,green:power=0",
        )
        val cfg = LedOps.watchConfigFrom(state, palette)!!
        assertEquals("Custom", cfg.states[WatchState.Internet]!!.label)
        assertEquals("yellow", cfg.states[WatchState.UpstreamOnly]!!.id)
        assertEquals("red", cfg.states[WatchState.NoUpstream]!!.id)
        assertNull(LedOps.watchConfigFrom(Parsers.ledwatchState("legacy"), palette))
        assertTrue(Parsers.ledwatchState("legacy").legacy)
    }

    @Test
    fun `the install command writes both files, retires the manual watch and drops the LEDs' sections`() {
        val cmd = Commands.ledwatchInstall("#!/bin/sh\necho hi", LedOps.WATCH_INIT, listOf("delete system.led_wlan2g"))
        assertTrue(cmd.startsWith("cat > /usr/bin/wrtpulse-ledwatch <<'WRTPULSE_EOF'\n#!/bin/sh\necho hi\nWRTPULSE_EOF\n"))
        assertTrue(cmd.contains("cat > /etc/init.d/wrtpulse-ledwatch <<'WRTPULSE_INIT_EOF'\n#!/bin/sh /etc/rc.common"))
        assertTrue(cmd.contains("rm -f /etc/init.d/ledwatch /usr/bin/ledwatch.sh"))
        assertTrue(cmd.contains("uci batch <<'WRTPULSE_UCI_EOF'\ndelete system.led_wlan2g\nWRTPULSE_UCI_EOF\nuci commit system"))
        assertTrue(cmd.contains("/etc/init.d/wrtpulse-ledwatch enable"))
        assertTrue(cmd.endsWith("echo installed"))
        assertNotNull(Commands.LEDS)
        assertTrue(Commands.safeLedName("red:wlan2g") && Commands.safeLedName("ath10k-phy0"))
        assertFalse(Commands.safeLedName("../x") || Commands.safeLedName("a b") || Commands.safeLedName(""))
    }
}
