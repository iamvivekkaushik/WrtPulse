package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermEngineTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun engine() = TermEngine(RouterSession(SshTarget("t"), unusedClient, { error("unused") }))

    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    @Test
    fun `plain lines commit on newline`() {
        val e = engine()
        e.feed("BusyBox v1.36.1 built-in shell (ash)\r\n\r\nroot@gw:~# ")
        assertEquals(listOf("BusyBox v1.36.1 built-in shell (ash)", ""), e.lines.toList())
        assertEquals("root@gw:~# ", e.current)
    }

    @Test
    fun `ansi color codes are stripped`() {
        val e = engine()
        e.feed("${esc}[32mroot@gw${esc}[0m:~# ")
        assertEquals("root@gw:~# ", e.current)
    }

    @Test
    fun `osc window title is swallowed`() {
        val e = engine()
        e.feed("${esc}]0;root@gw: ~${bel}root@gw:~# ")
        assertEquals("root@gw:~# ", e.current)
    }

    @Test
    fun `backspace erase sequence removes the character`() {
        val e = engine()
        e.feed("root@gw:~# lss")
        e.feed("\b${esc}[K") // ash sends BS + erase-to-eol when deleting
        assertEquals("root@gw:~# ls", e.current)
    }

    @Test
    fun `carriage return overwrites from line start`() {
        val e = engine()
        e.feed("progress 10%\rprogress 99%")
        assertEquals("progress 99%", e.current)
    }

    @Test
    fun `cr with shorter rewrite plus erase truncates leftovers`() {
        val e = engine()
        e.feed("a long old line\rnew${esc}[K")
        assertEquals("new", e.current)
    }

    @Test
    fun `logread lines parse`() {
        val entry = Parsers.logread(
            "Sat Aug 30 12:34:56 2026 daemon.notice hostapd: phy0-ap0: AP-STA-CONNECTED aa:5c:1e:88:04:2b"
        )!!
        assertEquals("12:34:56", entry.time)
        assertEquals("notice", entry.severity)
        assertEquals("hostapd", entry.src)
        assertEquals("phy0-ap0: AP-STA-CONNECTED", entry.msg)
        assertEquals("aa:5c:1e:88:04:2b", entry.tok)

        val dhcp = Parsers.logread(
            "Sat Aug 30 12:35:01 2026 daemon.info dnsmasq-dhcp[3121]: DHCPACK(br-lan) 192.168.2.34"
        )!!
        assertEquals("dnsmasq-dhcp", dhcp.src) // [pid] stripped
        assertEquals("192.168.2.34", dhcp.tok)

        val kern = Parsers.logread(
            "Sat Aug 30 12:35:07 2026 kern.err kernel: [162.44] something failed"
        )!!
        assertEquals("kernel", kern.src)
        assertEquals("err", kern.severity)
        assertEquals("", kern.tok)

        assertNull(Parsers.logread("not a syslog line"))
    }

    @Test
    fun `log colors follow severity then source`() {
        assertEquals(com.vivekkaushik.wrtpulse.ui.theme.Wrt.Red, LiveLogs.colorFor("err", "dnsmasq"))
        assertEquals(com.vivekkaushik.wrtpulse.ui.theme.Wrt.Blue, LiveLogs.colorFor("info", "dnsmasq-dhcp"))
        assertEquals(com.vivekkaushik.wrtpulse.ui.theme.Wrt.Accent, LiveLogs.colorFor("info", "dropbear"))
    }
}
