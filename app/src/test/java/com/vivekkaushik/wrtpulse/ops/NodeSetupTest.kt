package com.vivekkaushik.wrtpulse.ops

import com.vivekkaushik.wrtpulse.data.NodeSetup
import com.vivekkaushik.wrtpulse.net.JumpSession
import com.vivekkaushik.wrtpulse.net.SshTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A DSA primary: four LAN sockets in br-lan, a bridge VLAN over them. */
private val DSA_PRIMARY = """
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.2.1/24'
    network.br_lan=device
    network.br_lan.name='br-lan'
    network.br_lan.type='bridge'
    network.br_lan.ports='lan1' 'lan2' 'lan3' 'lan4'
    network.lanvlan=bridge-vlan
    network.lanvlan.device='br-lan'
    network.lanvlan.vlan='1'
    network.lanvlan.ports='lan1:u*' 'lan2:u*' 'lan3:u*' 'lan4:u*'
""".trimIndent()

/** A swconfig primary: sockets 3 and 5 untagged in VLAN 1 with the CPU tagged. */
private val SW_PRIMARY = """
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.0.1/24'
    network.br_lan=device
    network.br_lan.name='br-lan'
    network.br_lan.type='bridge'
    network.br_lan.ports='eth0.1'
    network.@switch[0]=switch
    network.@switch[0].name='switch0'
    network.@switch_vlan[0]=switch_vlan
    network.@switch_vlan[0].device='switch0'
    network.@switch_vlan[0].vlan='1'
    network.@switch_vlan[0].ports='3 5 0t'
    network.@switch_vlan[1]=switch_vlan
    network.@switch_vlan[1].device='switch0'
    network.@switch_vlan[1].vlan='2'
    network.@switch_vlan[1].ports='4 0t'
""".trimIndent()

class SetupPortTest {

    @Test
    fun `a dsa socket leaves the bridge and its vlan and gets a bridge of its own`() {
        val uci = Parsers.uciShow(DSA_PRIMARY)
        val ops = MeshOps.setupPortOps(uci, "lan3")!!
        assertTrue(ops.contains("delete network.br_lan.ports"))
        assertTrue(ops.contains("add_list network.br_lan.ports='lan4'"))
        assertFalse(ops.contains("add_list network.br_lan.ports='lan3'"))
        assertTrue(ops.contains("add_list network.lanvlan.ports='lan4:u*'"))
        assertFalse(ops.contains("add_list network.lanvlan.ports='lan3:u*'"))
        assertTrue(ops.contains("set network.wrtpulse_setup_dev=device"))
        assertTrue(ops.contains("add_list network.wrtpulse_setup_dev.ports='lan3'"))
        assertTrue(ops.contains("set network.wrtpulse_setup.proto='none'"))
        // fw4 rejects input on an unzoned interface, neighbour discovery included.
        assertTrue(ops.contains("set firewall.wrtpulse_setup=zone"))
        assertTrue(ops.contains("set firewall.wrtpulse_setup.input='ACCEPT'"))
        assertTrue(ops.contains("add_list firewall.wrtpulse_setup.network='wrtpulse_setup'"))
        // A socket that is not in the LAN cannot be held apart.
        assertNull(MeshOps.setupPortOps(uci, "wan"))
    }

    @Test
    fun `the release computed against the predicted config puts the socket back and drops the setup sections`() {
        val uci = Parsers.uciShow(DSA_PRIMARY)
        val after = MeshOps.afterIsolation(uci, "lan3")
        assertEquals("lan3", MeshOps.heldSetupPort(after))
        assertNull(MeshOps.heldSetupPort(uci))
        val release = MeshOps.setupReleaseOps(after, "lan3")
        assertTrue(release.contains("add_list network.br_lan.ports='lan3'"))
        assertTrue(release.contains("add_list network.lanvlan.ports='lan3:u*'"))
        assertTrue(release.contains("delete network.wrtpulse_setup"))
        assertTrue(release.contains("delete network.wrtpulse_setup_dev"))
        assertTrue(release.contains("delete firewall.wrtpulse_setup"))
        // Against the untouched config there is nothing to put back; only the zone delete, which is harmless.
        assertEquals(listOf("delete firewall.wrtpulse_setup"), MeshOps.setupReleaseOps(uci, "lan3"))
    }

    @Test
    fun `a swconfig socket is carved into a vlan of its own and rejoins vlan 1 on release`() {
        val uci = Parsers.uciShow(SW_PRIMARY)
        val ops = MeshOps.setupPortOps(uci, "sw:5")!!
        assertTrue(ops.contains("set network.@switch_vlan[0].ports='0t 3'"))
        assertTrue(ops.contains("set network.wrtpulse_setup_vlan.vlan='3'"))
        assertTrue(ops.contains("set network.wrtpulse_setup_vlan.ports='0t 5'"))
        assertTrue(ops.contains("add_list network.wrtpulse_setup_dev.ports='eth0.3'"))
        val after = MeshOps.afterIsolation(uci, "sw:5")
        assertEquals("sw:5", MeshOps.heldSetupPort(after))
        val release = MeshOps.setupReleaseOps(after, "sw:5")
        assertTrue(release.contains("set network.@switch_vlan[0].ports='0t 3 5'"))
        assertTrue(release.contains("delete network.wrtpulse_setup_vlan"))
        // The WAN socket is in VLAN 2, not the LAN: refused.
        assertNull(MeshOps.setupPortOps(uci, "sw:4"))
    }

    @Test
    fun `the socket a cable went into is the one that was down and is up now`() {
        val uci = Parsers.uciShow(DSA_PRIMARY)
        fun links(vararg up: String) = Parsers.netdevs(
            listOf("wan", "lan1", "lan2", "lan3", "lan4").joinToString("\n") { n ->
                "$n ${if (n in up) "up" else "down"} ${if (n in up) 1 else 0} ${if (n in up) 1000 else "-"} 02:00:00:00:00:01 phy wired"
            } + "\neth0 up 1 1000 02:00:00:00:00:00 phy wired\nphy0-ap0 up 1 - 02:00:00:00:00:02 phy wifi"
        )
        assertNull(MeshOps.newlyUpPort(links("lan1"), links("lan1"), emptyList(), emptyList(), uci))
        assertEquals("lan3", MeshOps.newlyUpPort(links("lan1"), links("lan1", "lan3"), emptyList(), emptyList(), uci))
        // The WAN socket coming up is not a node cable.
        assertNull(MeshOps.newlyUpPort(links("lan1"), links("lan1", "wan"), emptyList(), emptyList(), uci))
        // swconfig: a chip port link.
        val sw = Parsers.uciShow(SW_PRIMARY)
        val before = Parsers.switchDevs(SWCONFIG_OUT)
        val after = before.map { d -> d.copy(links = d.links + (5 to SwitchLink(5, true, 1000, true))) }
        assertEquals("sw:5", MeshOps.newlyUpPort(emptyList(), emptyList(), before, after, sw))
    }
}

class SocketHoldTest {

    private fun links(vararg up: String) = Parsers.netdevs(
        listOf("wan", "lan1", "lan2", "lan3", "lan4").joinToString("\n") { n ->
            "$n ${if (n in up) "up" else "down"} ${if (n in up) 1 else 0} ${if (n in up) 1000 else "-"} 02:00:00:00:00:01 phy wired"
        }
    )

    @Test
    fun `the socket to hold is a free lan socket, never the wan`() {
        val uci = Parsers.uciShow(DSA_PRIMARY)
        assertEquals(listOf("lan2", "lan3", "lan4"), MeshOps.freeLanSockets(links("lan1", "wan"), emptyList(), uci))
        assertTrue(MeshOps.socketUp("lan1", links("lan1"), emptyList()))
        assertFalse(MeshOps.socketUp("lan3", links("lan1"), emptyList()))
        val sw = Parsers.uciShow(SW_PRIMARY)
        val chip = Parsers.switchDevs(SWCONFIG_OUT)
        // Sockets 3 and 5 are the LAN's; whichever has no link is free.
        val free = MeshOps.freeLanSockets(emptyList(), chip, sw)
        assertTrue(free.all { it == "sw:3" || it == "sw:5" })
    }

    @Test
    fun `the router's own timer only releases an empty socket and a manual release refuses a cabled one`() {
        val script = Commands.setupApply(listOf("set network.x=y"), listOf("delete network.x"), Commands.socketLinkCheck("lan3"))
        assertTrue(script.contains("cat /sys/class/net/lan3/carrier"))
        assertTrue(script.contains("uci commit network && uci commit firewall"))
        assertTrue(script.contains("= \"up\" ]; then sleep 300; else sh /tmp/wrtpulse-setup/release.sh; fi"))
        assertTrue(Commands.SETUP_RELEASE.contains("still-cabled"))
        assertFalse(Commands.SETUP_RELEASE_KEEP_CABLE.contains("still-cabled"))
        assertTrue(Commands.socketLinkCheck("sw:5").contains("port 5 get link"))
    }

    /** The stored undo carries a heredoc of its own; the wrapper must not end where that one does. */
    @Test
    fun `the stored release script survives its own heredoc`() {
        val script = Commands.setupApply(listOf("set network.x=y"), listOf("delete network.x"), "echo down")
        val body = script.substringAfter("cat > /tmp/wrtpulse-setup/release.sh <<'${Commands.FILE_EOF}'\n")
            .substringBefore("\n${Commands.FILE_EOF}\n")
        assertTrue(body.contains("uci batch <<'WRTPULSE_EOF'"))
        assertTrue(body.contains("delete network.x"))
        assertTrue(body.contains("echo released"))
        assertTrue(body.contains("rm -f /tmp/wrtpulse-setup/release.sh"))
        // And the shell that runs the wrapper never sees the batch's terminator as its own.
        assertTrue(Commands.FILE_EOF != "WRTPULSE_EOF")
    }
}

class JumpSessionTest {

    @Test
    fun `the hop quotes the password, the address and the command for the primary's shell`() {
        val target = SshTarget("fe80::1%br-setup", 22, "root", "id")
        val line = JumpSession.wrap(target, "it's", "uci get system.@system[0].hostname; echo 'a b'")
        assertTrue(line.startsWith("DROPBEAR_PASSWORD='it'\\''s' dbclient -y -y -p 22 'root@fe80::1%br-setup' '"))
        assertTrue(line.endsWith("echo '\\''a b'\\'''"))
        // An empty password is a fresh install, and is legal.
        assertTrue(JumpSession.wrap(target, "", "true").startsWith("DROPBEAR_PASSWORD='' dbclient"))
    }

    @Test
    fun `host key lines from dropbearkey become pinnable keys`() {
        val keys = NodeSetup.parseHostKeys(
            """
            Public key portion is:
            ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGxJ0XkX9Yv8Jb7yQ0mZ3W8gq2mQ0uVv3o7xk9nQzq1a root@OpenWrt
            Fingerprint: SHA256:abc
            """.trimIndent()
        )
        assertEquals(1, keys.size)
        assertEquals("ssh-ed25519", keys[0].type)
        assertTrue(keys[0].sha256Fingerprint.startsWith("SHA256:"))
    }
}
