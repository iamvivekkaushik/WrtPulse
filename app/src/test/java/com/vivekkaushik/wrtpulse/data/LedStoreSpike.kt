package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.HostKeyStore
import com.vivekkaushik.wrtpulse.net.JschSshClient
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.LedMode
import com.vivekkaushik.wrtpulse.ops.LedOps
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * JVM-side spike against a real router, skipped unless a host is supplied:
 *
 *   WRTPULSE_HOST=192.168.1.1 WRTPULSE_KEY=$HOME/.ssh/vk \
 *     ./gradlew testDebugUnitTest --tests '*LedStoreSpike*' -i
 *
 * Walks the LED store through everything the screen can ask of it — read, set a mode, back to
 * board default, preview, install the connectivity watch, remove it — and checks the router's
 * answer after each step, so the shell the app generates is proven on a box before the UI is.
 * Set WRTPULSE_LED_KEEP_WATCH=1 to leave the watch installed at the end.
 */
class LedStoreSpike {

    @Test
    fun `led store round-trips against a router`() = runBlocking {
        val host = System.getenv("WRTPULSE_HOST")
        assumeTrue("set WRTPULSE_HOST to run this spike", !host.isNullOrBlank())
        val keyPath = System.getenv("WRTPULSE_KEY")
        assumeTrue("set WRTPULSE_KEY to a private key the router accepts", !keyPath.isNullOrBlank())

        val target = SshTarget(host!!, 22, "root")
        val hostKeys = HostKeyStore(File.createTempFile("wrtpulse-known-hosts", ".txt"))
        val client = JschSshClient(hostKeys)
        hostKeys.trust(target, client.probeHostKey(target))
        val pem = File(keyPath!!).readBytes()
        val session = RouterSession(target, client, { SshAuth.PrivateKey(pem) })
        val store = LedStore(session)

        fun step(name: String) = println("\n=== $name")
        fun state() = println("  error=${store.error} notice=${store.notice}")

        step("load")
        store.load()
        state()
        check(store.loaded && store.leds.isNotEmpty()) { "no LEDs read" }
        store.leds.forEach { println("  ${it.sysfs.padEnd(14)} ${it.label.padEnd(12)} max=${it.maxBrightness} br=${it.brightness} trig=${it.trigger} mode=${LedOps.describe(store.modeOf(it))} dt=${store.dtOf(it.sysfs)}") }
        println("  lens=${store.lens}")
        println("  palette=${store.palette.map { it.id }}")
        println("  watch=${store.watch}")
        println("  netdevs=${store.netdevs}")

        val candidate = store.leds.firstOrNull { it.colour != null && "heartbeat" in it.triggers && it.sysfs !in store.watchedLeds }
        if (candidate != null) {
            step("setMode ${candidate.sysfs} -> Heartbeat")
            check(store.setMode(candidate.sysfs, LedMode.Heartbeat)) { "setMode failed: ${store.error}" }
            state()
            val after = store.leds.first { it.sysfs == candidate.sysfs }
            println("  live trigger=${after.trigger} section=${store.sectionOf(after.sysfs)}")
            check(after.trigger == "heartbeat") { "router did not switch to heartbeat" }
            check(store.modeOf(after) == LedMode.Heartbeat) { "section does not read back as heartbeat" }

            step("setMode ${candidate.sysfs} -> Blink 100/900")
            check(store.setMode(candidate.sysfs, LedMode.Blink(100, 900))) { "blink failed: ${store.error}" }
            println("  live trigger=${store.leds.first { it.sysfs == candidate.sysfs }.trigger} mode=${LedOps.describe(store.modeOf(store.leds.first { it.sysfs == candidate.sysfs }))}")

            step("setMode ${candidate.sysfs} -> BoardDefault")
            check(store.setMode(candidate.sysfs, LedMode.BoardDefault)) { "board default failed: ${store.error}" }
            state()
            val back = store.leds.first { it.sysfs == candidate.sysfs }
            println("  live trigger=${back.trigger} brightness=${back.brightness} section=${store.sectionOf(back.sysfs)} dt=${store.dtOf(back.sysfs)}")
            check(store.sectionOf(back.sysfs) == null) { "section still there" }
            val dt = store.dtOf(back.sysfs)
            if (dt?.trigger != null) check(back.trigger == dt.trigger) { "trigger ${back.trigger} != dt ${dt.trigger}" }
        }

        val palette = store.palette
        val preview = palette.firstOrNull { it.id == "cyan" } ?: palette.first()
        step("flash ${preview.id}")
        check(store.flash(preview, seconds = 2)) { "flash failed: ${store.error}" }
        store.load()
        println("  after flash: " + store.leds.joinToString { "${it.sysfs}=${it.trigger}/${it.brightness}" })

        val cfg = store.watchConfig
        if (cfg != null) {
            step("installWatch ${cfg.states.mapValues { it.value.id }}")
            check(store.installWatch(cfg)) { "install failed: ${store.error}" }
            state()
            println("  watch=${store.watch}")
            check(store.watch?.installed == true) { "not installed" }
            check(store.watch?.enabled == true) { "not enabled" }
            check(store.watch?.legacy != true) { "legacy ledwatch still present" }
            Thread.sleep(8_000)
            store.load()
            println("  after 8 s: running=${store.watch?.running} current=${store.watch?.current}")
            println("  leds: " + store.leds.joinToString { "${it.sysfs}=${it.trigger}/${it.brightness}" })
            check(store.watch?.running == true) { "watch not running" }
            println("  read-back config=${store.watchConfig?.states?.mapValues { it.value.id }} interval=${store.watchConfig?.intervalS}")

            step("setWatchEnabled(false) then (true)")
            check(store.setWatchEnabled(false)) { store.error ?: "stop failed" }
            println("  running=${store.watch?.running} enabled=${store.watch?.enabled}")
            check(store.watch?.running != true && store.watch?.enabled != true) { "still running/enabled" }
            check(store.setWatchEnabled(true)) { store.error ?: "start failed" }
            println("  running=${store.watch?.running} enabled=${store.watch?.enabled}")

            if (System.getenv("WRTPULSE_LED_KEEP_WATCH") != "1") {
                step("removeWatch")
                check(store.removeWatch()) { "remove failed: ${store.error}" }
                state()
                println("  watch=${store.watch}")
                check(store.watch?.installed != true) { "still installed" }
                println("  leds: " + store.leds.joinToString { "${it.sysfs}=${it.trigger}/${it.brightness}" })
            }
        }
        println("\nspike done")
    }
}
