package com.vivekkaushik.wrtpulse.ops

import com.vivekkaushik.wrtpulse.data.ServiceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Commands.SERVICES normalises /etc/init.d down to: `name|start|enabled|daemon`, where
 * the last field is set only for a script that asks procd to respawn it.
 */
private val SCRIPTS = """
    dnsmasq|19|enabled|procd
    done|95|enabled|
    dropbear|19|enabled|procd
    firewall|19|enabled|procd
    nlbwmon|80||procd
    sysfixtime|00|enabled|
    uhttpd|50|enabled|procd
""".trimIndent()

/** `ubus call service list`, trimmed to the fields the parser reads. */
private val UBUS = """
    {
      "dnsmasq": { "instances": { "cfg01411c": { "running": true, "pid": 3218 } } },
      "dropbear": { "instances": { "instance1": { "running": true, "pid": 1204 } } },
      "firewall": { "instances": { "instance1": { "running": true, "pid": 1500 } } },
      "nlbwmon": { "instances": { "instance1": { "running": false, "pid": 0 } } },
      "uhttpd": {
        "instances": {
          "instance1": { "running": true, "pid": 2801 },
          "instance2": { "running": true, "pid": 2802 }
        }
      },
      "ghost": { "instances": { "instance1": { "running": true, "pid": 999 } } },
      "empty": { "instances": {} }
    }
""".trimIndent()

class InitScriptsTest {

    private val parsed = Parsers.initScripts(SCRIPTS).associateBy { it.name }

    @Test
    fun `reads the four fields the shell emits`() {
        val dnsmasq = parsed.getValue("dnsmasq")
        assertEquals(19, dnsmasq.start)
        assertTrue(dnsmasq.enabled)
        assertTrue(dnsmasq.procd)
    }

    @Test
    fun `an empty enabled field means no rc_d symlink`() {
        assertFalse(parsed.getValue("nlbwmon").enabled)
        assertEquals(80, parsed.getValue("nlbwmon").start)
    }

    @Test
    fun `a script procd is not asked to respawn is a one-shot, not a stopped daemon`() {
        val done = parsed.getValue("done")
        assertFalse(done.procd)
        assertTrue(done.oneShot)
        assertEquals("boot script", done.statusLabel)
    }

    @Test
    fun `nothing is running until procd is consulted`() {
        assertTrue(parsed.values.none { it.running })
    }

    @Test
    fun `lines without a separator are skipped rather than parsed as names`() {
        assertTrue(Parsers.initScripts("ls: /etc/init.d: No such file").isEmpty())
        assertTrue(Parsers.initScripts("").isEmpty())
    }
}

class RunningServicesTest {

    private val live = Parsers.runningServices(UBUS)

    @Test
    fun `a running instance reports its pid`() {
        assertEquals(1 to 3218, live["dnsmasq"])
    }

    @Test
    fun `instances are counted, and the lowest pid represents the service`() {
        assertEquals(2 to 2801, live["uhttpd"])
    }

    @Test
    fun `the representative pid does not depend on JSON key order`() {
        val reordered = UBUS.replace("\"instance1\": { \"running\": true, \"pid\": 2801 },\n          \"instance2\": { \"running\": true, \"pid\": 2802 }",
            "\"instance2\": { \"running\": true, \"pid\": 2802 },\n          \"instance1\": { \"running\": true, \"pid\": 2801 }")
        assertEquals(live["uhttpd"], Parsers.runningServices(reordered)["uhttpd"])
    }

    @Test
    fun `an instance with no pid is not running`() {
        assertNull(live["nlbwmon"])
    }

    @Test
    fun `a service procd holds with no instances is not running`() {
        assertNull(live["empty"])
    }

    @Test
    fun `unparseable output leaves the list empty rather than throwing`() {
        assertTrue(Parsers.runningServices("").isEmpty())
        assertTrue(Parsers.runningServices("Command failed: Not found").isEmpty())
    }
}

class ServiceMergeTest {

    private val merged = Parsers.services(SCRIPTS, UBUS).associateBy { it.name }

    @Test
    fun `a script procd is running comes back running, with its pid`() {
        val dnsmasq = merged.getValue("dnsmasq")
        assertTrue(dnsmasq.running)
        assertEquals(3218, dnsmasq.pid)
        assertEquals("running", dnsmasq.statusLabel)
    }

    @Test
    fun `a procd script procd has dropped is stopped`() {
        val nlbwmon = merged.getValue("nlbwmon")
        assertFalse(nlbwmon.running)
        assertTrue(nlbwmon.procd)
        assertEquals("stopped", nlbwmon.statusLabel)
    }

    @Test
    fun `a one-shot keeps its own status rather than borrowing stopped`() {
        assertEquals("boot script", merged.getValue("sysfixtime").statusLabel)
    }

    @Test
    fun `a service procd runs without an init script is still listed`() {
        val ghost = merged.getValue("ghost")
        assertTrue(ghost.running)
        assertFalse(ghost.enabled)
        assertEquals(999, ghost.pid)
    }

    @Test
    fun `the list is sorted by name so rows don't jump between reads`() {
        assertEquals(
            Parsers.services(SCRIPTS, UBUS).map { it.name }.sorted(),
            Parsers.services(SCRIPTS, UBUS).map { it.name },
        )
    }

    @Test
    fun `no router means an empty list, not a crash`() {
        assertTrue(Parsers.services("", "").isEmpty())
    }
}

/** The guards exist because these commands would work — and take the session with them. */
class ServiceGuardTest {

    @Test
    fun `stopping the ssh server the app is using is refused`() {
        assertNotNull(ServiceStore.actionBlock("dropbear", ServiceAction.Stop))
        assertNotNull(ServiceStore.actionBlock("dropbear", ServiceAction.Restart))
        assertNotNull(ServiceStore.actionBlock("dropbear", ServiceAction.Disable))
    }

    @Test
    fun `starting or enabling dropbear is not the dangerous direction`() {
        assertNull(ServiceStore.actionBlock("dropbear", ServiceAction.Start))
        assertNull(ServiceStore.actionBlock("dropbear", ServiceAction.Enable))
    }

    @Test
    fun `network may be reloaded but not restarted`() {
        assertNull(ServiceStore.actionBlock("network", ServiceAction.Reload))
        assertNotNull(ServiceStore.actionBlock("network", ServiceAction.Restart))
    }

    @Test
    fun `the firewall may be restarted but not left off`() {
        assertNull(ServiceStore.actionBlock("firewall", ServiceAction.Restart))
        assertNotNull(ServiceStore.actionBlock("firewall", ServiceAction.Stop))
        assertNotNull(ServiceStore.actionBlock("firewall", ServiceAction.Disable))
    }

    @Test
    fun `an ordinary service is not guarded at all`() {
        ServiceAction.entries.forEach { assertNull(ServiceStore.actionBlock("nlbwmon", it)) }
    }

    @Test
    fun `restarting the radios warns about losing the connection carrying the command`() {
        assertNotNull(ServiceStore.actionWarning("wpad", ServiceAction.Restart))
        assertNotNull(ServiceStore.actionWarning("hostapd", ServiceAction.Restart))
    }

    @Test
    fun `disabling anything says it stays off after a reboot`() {
        val warning = ServiceStore.actionWarning("nlbwmon", ServiceAction.Disable)
        assertNotNull(warning)
        assertTrue(warning!!.contains("reboot"))
    }

    @Test
    fun `turning something on needs no warning`() {
        assertNull(ServiceStore.actionWarning("dnsmasq", ServiceAction.Start))
        assertNull(ServiceStore.actionWarning("dnsmasq", ServiceAction.Enable))
    }
}

class ServiceActionsOfferedTest {

    private fun offered(service: RouterService) = ServiceStore.actionsFor(service)

    @Test
    fun `a running service is offered restart, reload, stop and disable`() {
        val running = RouterService("uhttpd", enabled = true, running = true, procd = true)
        assertEquals(
            listOf(
                ServiceAction.Restart,
                ServiceAction.Reload,
                ServiceAction.Stop,
                ServiceAction.Disable,
            ),
            offered(running),
        )
    }

    @Test
    fun `a stopped service is offered start and enable`() {
        val stopped = RouterService("nlbwmon", procd = true)
        assertEquals(listOf(ServiceAction.Start, ServiceAction.Restart, ServiceAction.Enable), offered(stopped))
    }

    @Test
    fun `a boot script has nothing to restart`() {
        val oneShot = RouterService("sysfixtime", enabled = true)
        assertEquals(listOf(ServiceAction.Start, ServiceAction.Disable), offered(oneShot))
    }

    @Test
    fun `nothing that would act on a service is offered without it being running`() {
        val stopped = RouterService("nlbwmon", procd = true)
        assertFalse(ServiceAction.Stop in offered(stopped))
        assertFalse(ServiceAction.Reload in offered(stopped))
    }
}

class ServiceCommandTest {

    @Test
    fun `the verb is quoted against the script path, and the exit code survives the settle`() {
        val cmd = Commands.serviceAction("dnsmasq", ServiceAction.Restart)
        assertEquals("'/etc/init.d/dnsmasq' restart; rc=\$?; sleep 1; exit \$rc", cmd)
    }

    /**
     * Two false-positive rounds on the reference router, both fixed here. `USE_PROCD=1`
     * marks use of procd's helpers, which six healthy one-shots also set. A supervised
     * `command` is not enough either — `urandom_seed` declares one and exits by design.
     * Asking procd to respawn the process is the only claim that means "should be up".
     */
    @Test
    fun `daemons are told from one-shots by respawn, not USE_PROCD or a bare command`() {
        assertTrue(Commands.SERVICES.contains("procd_set_param respawn"))
        assertFalse(Commands.SERVICES.contains("grep -q USE_PROCD"))
        assertFalse(Commands.SERVICES.contains("'procd_set_param command'"))
    }

    @Test
    fun `a name that could carry shell syntax is rejected before it reaches a command`() {
        assertFalse(Commands.safeServiceName("dnsmasq; reboot"))
        assertFalse(Commands.safeServiceName("../../bin/sh"))
        assertFalse(Commands.safeServiceName("-rf"))
        assertFalse(Commands.safeServiceName(""))
        assertTrue(Commands.safeServiceName("nlbwmon"))
        assertTrue(Commands.safeServiceName("luci_dhcp_migrate"))
    }

    @Test
    fun `the batched read carries both halves of the answer`() {
        assertTrue(Commands.SERVICES.contains("/etc/init.d/*"))
        assertTrue(Commands.SERVICES.contains("ubus call service list"))
        val sections = Parsers.sections("${Commands.SECTION} scripts\na|1|enabled|procd\n${Commands.SECTION} running\n{}")
        assertEquals(setOf("scripts", "running"), sections.keys)
    }
}
