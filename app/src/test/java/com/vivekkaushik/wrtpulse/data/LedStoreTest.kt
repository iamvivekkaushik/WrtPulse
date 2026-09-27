package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.ExecResult
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.DECO_DT
import com.vivekkaushik.wrtpulse.ops.DECO_LEDS
import com.vivekkaushik.wrtpulse.ops.DECO_SYSTEM_UCI
import com.vivekkaushik.wrtpulse.ops.LedMode
import com.vivekkaushik.wrtpulse.ops.WatchState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedStoreTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun store(
        leds: String = DECO_LEDS,
        uci: String = DECO_SYSTEM_UCI,
        watch: String = "",
        session: RouterSession = RouterSession(SshTarget("t"), unusedClient, { error("unused") }),
    ): LedStore = LedStore(session).apply {
        ingest(mapOf("leds" to leds, "dt" to DECO_DT, "uci" to uci, "netdevs" to "br-lan\neth0\neth0.2\nlo", "watch" to watch))
    }

    @Test
    fun `ingest lists the LEDs, their sections, the devices and a default lens`() {
        val s = store()
        assertEquals(5, s.leds.size)
        assertEquals(2, s.sections.size)
        assertEquals(listOf("br-lan", "eth0", "eth0.2"), s.netdevs)
        assertEquals(setOf("red:wlan2g", "green:power", "blue:wlan5g"), s.lens)
        assertEquals(listOf("white", "red", "green", "blue", "yellow", "cyan", "magenta", "off"), s.palette.map { it.id })
        assertTrue(s.watchedLeds.isEmpty())
        assertEquals(LedMode.Raw("phy1tpt"), s.modeOf(s.leds.first { it.sysfs == "red:wlan2g" }))
        assertEquals(LedMode.BoardDefault, s.modeOf(s.leds.first { it.sysfs == "green:power" }))
        assertNotNull(s.dtOf("green:power"))
        assertNull(s.dtOf("ath9k-phy1"))
    }

    @Test
    fun `an installed watch owns its LEDs and sets the lens`() {
        val s = store(
            watch = "installed\nenabled\nrunning\ncurrent upstream\n# wrtpulse-ledwatch v1\n" +
                "# state internet red:wlan2g=1,green:power=1\n# state upstream red:wlan2g=1,green:power=0\n# state down red:wlan2g=b500/500,green:power=0\n# interval 5\n# downafter 2",
        )
        assertEquals(setOf("red:wlan2g", "green:power"), s.watchedLeds)
        assertEquals(setOf("red:wlan2g", "green:power"), s.lens)
        val cfg = s.watchConfig!!
        assertEquals("yellow", cfg.states[WatchState.Internet]!!.id)
        assertEquals("red", cfg.states[WatchState.UpstreamOnly]!!.id)
        assertEquals("Custom", cfg.states[WatchState.NoUpstream]!!.label)
        assertEquals("upstream", s.watch!!.current)
    }

    @Test
    fun `the lens can be edited and survives a reload while its LEDs exist`() {
        val s = store()
        s.toggleLens("blue:wlan5g")
        assertEquals(setOf("red:wlan2g", "green:power"), s.lens)
        assertEquals(listOf("red", "green", "yellow", "off"), s.palette.map { it.id })
        s.ingest(mapOf("leds" to DECO_LEDS, "dt" to DECO_DT, "uci" to DECO_SYSTEM_UCI, "netdevs" to "", "watch" to ""))
        assertEquals(setOf("red:wlan2g", "green:power"), s.lens)
        // A router with different LEDs drops the stale pick.
        s.ingest(mapOf("leds" to "amber:status\t1\t0\t[none] timer\t\t", "dt" to "", "uci" to "", "netdevs" to "", "watch" to ""))
        assertEquals(setOf("amber:status"), s.lens)
        assertEquals(listOf("solid", "slow", "fast", "off"), s.palette.map { it.id })
    }

    @Test
    fun `no watch on a fresh router still yields a default configuration to install`() {
        val s = store()
        val cfg = s.watchConfig!!
        assertEquals("white", cfg.states[WatchState.Internet]!!.id)
        assertEquals(5, cfg.intervalS)
        assertFalse(s.watch!!.installed)
        assertFalse(s.watch!!.legacy)
        assertTrue(store(watch = "legacy").watch!!.legacy)
    }

    // ---- anonymous sections: the batch run the way uci runs it ----
    //
    // An LED section made in LuCI is `@led[i]`. The index counts the named LED sections too, and
    // uci resolves it line by line, so each delete renumbers the LED sections after it. These run
    // the batch the store sends through FakeUci against a config read from its own `uci show`.

    /** In file order the second delete took ath9k's section, which is outside the lens. */
    @Test
    fun `installing the watch deletes the sections of the LEDs it drives and no other`() = runBlocking {
        val uci = FakeUci("system", usedSystem)
        val session = RecordingSession(unusedClient)
        val s = store(uci = uci.show(), session = session)
        val cfg = s.watchConfig!!
        assertEquals(setOf("red:wlan2g", "green:power", "blue:wlan5g"), cfg.leds)
        assertTrue(s.installWatch(cfg))
        val batch = batchLines(session.execs.first())
        assertEquals(listOf("delete system.@led[1]", "delete system.@led[0]"), batch)
        uci.run(batch).assertLeaves(usedSystem, deleted = setOf("power", "wlan2g"))
    }

    /** In /sys/class/leds order the last delete asked for an `@led[1]` there no longer was, and one LED kept its section. */
    @Test
    fun `resetting every LED deletes all of their sections`() = runBlocking {
        val uci = FakeUci("system", usedSystem)
        val session = RecordingSession(unusedClient)
        val s = store(uci = uci.show(), session = session)
        assertTrue(s.resetAll())
        val batch = batchLines(session.execs.first())
        assertEquals(listOf("delete system.@led[2]", "delete system.@led[1]", "delete system.@led[0]"), batch)
        uci.run(batch).assertLeaves(usedSystem, deleted = setOf("power", "wlan2g", "phy1"))
    }

    /** A session that answers every command with success, and remembers what it was asked to run. */
    private class RecordingSession(client: SshClient) : RouterSession(SshTarget("t"), client, { error("unused") }) {
        val execs = mutableListOf<String>()
        override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
            execs += command
            return ExecResult("", "", 0)
        }
    }

    /** The lines a command feeds `uci batch`, whatever its heredoc is called. */
    private fun batchLines(command: String): List<String> {
        val start = Regex("uci batch <<'(\\w+)'\n").find(command) ?: error("no uci batch in: $command")
        return command.substring(start.range.last + 1).substringBefore("\n${start.groupValues[1]}\n").lines()
    }

    private fun led(id: String, name: String, sysfs: String, vararg options: Pair<String, String>) =
        FakeUci.Section(id, "led", null, mapOf("name" to name, "sysfs" to sysfs, *options))

    /** The Deco after LuCI made three LED sections, `@led[0]` to `[2]`: two for the lens, then the ath9k LED outside it. */
    private val usedSystem = listOf(
        FakeUci.Section("system", "system", null, mapOf("hostname" to "Deco", "timezone" to "UTC")),
        FakeUci.Section("ntp", "timeserver", "ntp", mapOf("enabled" to "1")),
        led("power", "Power", "green:power", "trigger" to "default-on"),
        led("wlan2g", "WLAN2G", "red:wlan2g", "trigger" to "phy1tpt"),
        led("phy1", "ath9k", "ath9k-phy1", "trigger" to "netdev", "dev" to "eth0.2", "mode" to "link tx rx"),
    )
}
