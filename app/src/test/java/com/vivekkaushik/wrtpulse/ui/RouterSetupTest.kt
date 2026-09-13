package com.vivekkaushik.wrtpulse.ui

import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.net.HostKey
import com.vivekkaushik.wrtpulse.net.HostKeyStore
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingFlow
import com.vivekkaushik.wrtpulse.ui.screens.forgetRouterNotes
import com.vivekkaushik.wrtpulse.ui.screens.routerAddress
import com.vivekkaushik.wrtpulse.ui.screens.routerAddressNotes
import com.vivekkaushik.wrtpulse.ui.screens.routerEditBlock
import com.vivekkaushik.wrtpulse.ui.screens.routerName
import com.vivekkaushik.wrtpulse.ui.screens.sameAddressNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A freshly flashed OpenWrt has NO root password, which is exactly the router someone is
 * adding to this app. Requiring one made those unreachable.
 */
class ConnectGateTest {

    @Test
    fun `an empty password is allowed`() {
        assertNull(OnboardingFlow.connectBlock("192.168.1.1"))
    }

    /**
     * A rename has to survive reconnecting. The row was rewritten with the router's hostname
     * on every connect, so renaming a router and then reconnecting — which is exactly what a
     * subnet move forces you to do — put the old name straight back.
     */
    @Test
    fun `a saved name is not overwritten by the hostname`() {
        assertEquals("Deco", OnboardingFlow.savedName("Deco", "OpenWrt"))
        assertEquals("Deco", OnboardingFlow.savedName("  Deco  ", "OpenWrt"))
    }

    @Test
    fun `a router with nothing saved takes the derived name`() {
        assertEquals("OpenWrt", OnboardingFlow.savedName(null, "OpenWrt"))
        assertEquals("OpenWrt", OnboardingFlow.savedName("", "OpenWrt"))
        assertEquals("OpenWrt", OnboardingFlow.savedName("   ", "OpenWrt"))
    }

    @Test
    fun `the address is still required`() {
        assertNotNull(OnboardingFlow.connectBlock(""))
        assertTrue(OnboardingFlow.connectBlock("")!!.contains("address"))
    }

    @Test
    fun `nothing in the gate mentions a password any more`() {
        assertFalse(OnboardingFlow.connectBlock("").orEmpty().contains("password", ignoreCase = true))
    }
}

class ForgetRouterNotesTest {

    private fun entity(host: String = "192.168.2.1", key: ByteArray? = null) = RouterEntity(
        id = 7,
        name = "OpenWrt",
        host = host,
        port = 22,
        username = "root",
        model = "",
        summary = "",
        credential = byteArrayOf(1, 2, 3),
        lastSeenEpoch = 0,
        privateKey = key,
    )

    @Test
    fun `forgetting always says the router itself is untouched`() {
        val notes = forgetRouterNotes(entity(), connectedIdentity = null)
        assertTrue(notes.any { it.contains("Nothing changes on the router") })
    }

    /** The app cannot take its key back off the router by deleting a local row. */
    @Test
    fun `a stored key warns that it stays in authorized_keys`() {
        val notes = forgetRouterNotes(entity(key = byteArrayOf(9)), connectedIdentity = null)
        assertTrue(notes.any { it.contains("authorized_keys") })
    }

    @Test
    fun `no stored key means no key warning`() {
        val notes = forgetRouterNotes(entity(key = null), connectedIdentity = null)
        assertFalse(notes.any { it.contains("authorized_keys") })
    }

    @Test
    fun `forgetting the router you are connected to says the session survives`() {
        val notes = forgetRouterNotes(entity(host = "192.168.2.1"), connectedIdentity = "192.168.2.1:22")
        assertTrue(notes.any { it.contains("session stays open") })
    }

    @Test
    fun `a different connected router raises no session note`() {
        val notes = forgetRouterNotes(entity(host = "192.168.2.1"), connectedIdentity = "10.0.0.1:22")
        assertFalse(notes.any { it.contains("session stays open") })
    }
}

/** The name is the only thing telling two saved routers apart, so a blank one is refused. */
class RouterNameTest {

    @Test
    fun `a typed name is trimmed`() {
        assertEquals("Study AP", routerName("  Study AP  "))
    }

    @Test
    fun `blank input is refused rather than saved as an empty card`() {
        assertNull(routerName(""))
        assertNull(routerName("   "))
        assertNull(routerName("\t\n"))
    }

    @Test
    fun `an absurd name is capped rather than rejected`() {
        val long = "x".repeat(200)
        assertEquals(48, routerName(long)!!.length)
    }

    @Test
    fun `punctuation and non-latin names are kept as typed`() {
        assertEquals("Bhaiya's AX6000", routerName("Bhaiya's AX6000"))
        assertEquals("राउटर", routerName(" राउटर "))
    }
}

/**
 * The saved list is a Room Flow read through `collectAsState`, whose structural equality
 * decides whether Compose recomposes. Getting this wrong made a successful rename look like
 * a no-op on screen.
 */
class RouterEntityEqualityTest {

    private fun entity(
        id: Long = 1,
        name: String = "OpenWrt",
        seen: Long = 100,
        credential: ByteArray? = byteArrayOf(1, 2, 3),
        key: ByteArray? = null,
    ) = RouterEntity(
        id = id, name = name, host = "192.168.1.1", port = 22, username = "root",
        model = "", summary = "", credential = credential, lastSeenEpoch = seen, privateKey = key,
    )

    /** The bug: a renamed row must not compare equal to its old self. */
    @Test
    fun `a renamed row is not equal to the old one`() {
        assertNotEquals(entity(name = "Deco"), entity(name = "DecoHall"))
    }

    @Test
    fun `a touched row is not equal to the old one`() {
        assertNotEquals(entity(seen = 100), entity(seen = 200))
    }

    /** The other easy mistake: identity comparison on the sealed blobs. */
    @Test
    fun `equal blob CONTENT compares equal across separate reads`() {
        assertEquals(
            entity(credential = byteArrayOf(1, 2, 3), key = byteArrayOf(9)),
            entity(credential = byteArrayOf(1, 2, 3), key = byteArrayOf(9)),
        )
    }

    @Test
    fun `different blob content compares unequal`() {
        assertNotEquals(entity(credential = byteArrayOf(1)), entity(credential = byteArrayOf(2)))
        assertNotEquals(entity(key = null), entity(key = byteArrayOf(1)))
    }

    @Test
    fun `hashCode agrees with equals on both sides`() {
        val a = entity(credential = byteArrayOf(1, 2, 3))
        val b = entity(credential = byteArrayOf(1, 2, 3))
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(entity(name = "a").hashCode(), entity(name = "b").hashCode())
    }

    /** A list of one renamed row must not compare equal, or the screen never updates. */
    @Test
    fun `a list carrying a rename compares unequal`() {
        assertNotEquals(listOf(entity(name = "Deco")), listOf(entity(name = "DecoHall")))
    }
}

/**
 * Editing a saved entry's address — the router list's own version of "change the router's
 * IP", which moves where the app knocks and never touches the router.
 */
class RouterAddressTest {

    private fun entity(
        id: Long = 1,
        name: String = "home.gw",
        host: String = "192.168.1.1",
        port: Int = 22,
        username: String = "root",
    ) = RouterEntity(
        id = id, name = name, host = host, port = port, username = username,
        model = "", summary = "", credential = null, lastSeenEpoch = 0,
    )

    @Test
    fun `a bare address is taken as it is`() {
        assertEquals("192.168.1.1" to null, routerAddress("192.168.1.1"))
        assertEquals("router.lan" to null, routerAddress("  router.lan  "))
    }

    /** People paste what is in the browser bar, scheme and trailing slash included. */
    @Test
    fun `a pasted url is reduced to its host`() {
        assertEquals("192.168.1.1" to null, routerAddress("http://192.168.1.1/"))
        assertEquals("192.168.1.1" to null, routerAddress("ssh://192.168.1.1"))
    }

    @Test
    fun `host colon port is split`() {
        assertEquals("192.168.1.1" to 2222, routerAddress("192.168.1.1:2222"))
        assertEquals("router.lan" to 22, routerAddress("router.lan:22"))
    }

    /** A port outside the range is a typo, not a port. */
    @Test
    fun `an impossible port is refused outright`() {
        assertNull(routerAddress("192.168.1.1:70000"))
        assertNull(routerAddress("192.168.1.1:0"))
    }

    /** An IPv6 address is full of colons, and none of them is a port separator. */
    @Test
    fun `ipv6 keeps its colons`() {
        assertEquals("fd8e:1f4f:3c9d::1" to null, routerAddress("fd8e:1f4f:3c9d::1"))
    }

    @Test
    fun `nothing usable is null`() {
        assertNull(routerAddress(""))
        assertNull(routerAddress("   "))
        assertNull(routerAddress("192.168.1.1 backup"))
    }

    @Test
    fun `a name and an address are both required`() {
        assertNotNull(routerEditBlock("", "192.168.1.1"))
        assertNotNull(routerEditBlock("home.gw", ""))
        assertNull(routerEditBlock("home.gw", "192.168.2.1"))
    }

    /**
     * Two routers on two networks can both answer at 192.168.1.1. Each row carries its own
     * credential and pinned host key, so an address another entry holds is no clash — the
     * key tells them apart when the app connects.
     */
    @Test
    fun `an address another entry already holds is allowed`() {
        assertNull(routerEditBlock("lab", "192.168.1.1"))
    }

    /** The confusion worth heading off: this is not the screen that moves the router. */
    @Test
    fun `changing the address says what it does not do`() {
        val e = entity()
        val notes = routerAddressNotes(e, "192.168.2.1", connectedIdentity = null)
        assertTrue(notes.any { it.contains("not the router's own address") })
        // The pin moves with the entry: no re-accepting a fingerprint, and a mismatch at the
        // new address is a different router, said in those words.
        assertTrue(notes.any { it.contains("moves with it") && it.contains("changed-key warning") })
        assertFalse(notes.any { it.contains("first contact:") })
    }

    @Test
    fun `an unchanged address needs no warning`() {
        val e = entity()
        assertEquals(emptyList<String>(), routerAddressNotes(e, "192.168.1.1", connectedIdentity = null))
    }

    @Test
    fun `a live session is called out as staying where it is`() {
        val e = entity()
        val notes = routerAddressNotes(e, "192.168.2.1", connectedIdentity = e.identity)
        assertTrue(notes.any { it.contains("stays on 192.168.1.1") })
    }

    /** Which row is live is decided by identity, since two rows can share an address. */
    @Test
    fun `a session on another router at the same address does not count as this one`() {
        val e = entity()
        val notes = routerAddressNotes(e, "192.168.2.1", connectedIdentity = "some-other-router")
        assertFalse(notes.any { it.contains("stays on") })
    }
}

/**
 * Two routers, one address. Each saved row is its own router, told apart by the host key it
 * presents — never by where it answers.
 */
class RouterIdentityTest {

    private fun key(body: String) = HostKey("ssh-ed25519", body, HostKeyStore.fingerprint(body.toByteArray()))

    private fun row(id: Long, name: String, identity: String) = RouterEntity(
        id = id, name = name, host = "192.168.1.1", port = 22, username = "root",
        model = "", summary = "", credential = null, lastSeenEpoch = 0, identity = identity,
    )

    @Test
    fun `a fresh row gets its own identity and a legacy row keeps host and port`() {
        assertNotEquals(RouterEntity.newIdentity(), RouterEntity.newIdentity())
        val legacy = RouterEntity(
            id = 1, name = "home", host = "192.168.1.1", port = 2222, username = "root",
            model = "", summary = "", credential = null, lastSeenEpoch = 0,
        )
        assertEquals("192.168.1.1:2222", legacy.identity)
        assertEquals("192.168.1.1:2222", legacy.sshTarget.pinScope)
        assertEquals("id-x", row(1, "home", "id-x").sshTarget.identity)
    }

    /** Typing a saved router's address again finds the row that holds its key. */
    @Test
    fun `the same key at the same address is the same router`() {
        val home = row(1, "home", "id-home")
        val office = row(2, "office", "id-office")
        val pins = mapOf("id-home" to key("HOME"), "id-office" to key("OFFICE"))
        assertEquals(office, OnboardingFlow.twinOf(key("OFFICE"), listOf(home, office)) { pins[it.identity] })
        assertEquals(home, OnboardingFlow.twinOf(key("HOME"), listOf(home, office)) { pins[it.identity] })
    }

    /** A new key at a taken address is a new router, not the old one changing. */
    @Test
    fun `a different key at the same address is a different router`() {
        val home = row(1, "home", "id-home")
        assertNull(OnboardingFlow.twinOf(key("OTHER"), listOf(home)) { key("HOME") })
        assertNull(OnboardingFlow.twinOf(key("OTHER"), emptyList()) { null })
    }

    @Test
    fun `the first-contact screen names who else lives at the address`() {
        val one = sameAddressNote(listOf("home.gw"), "192.168.1.1")
        assertTrue(one, one.contains("\u201chome.gw\u201d is also saved at 192.168.1.1"))
        assertTrue(one.contains("reflash"))
        val two = sameAddressNote(listOf("home.gw", "lab"), "192.168.1.1")
        assertTrue(two, two.contains("\u201chome.gw\u201d, \u201clab\u201d are also saved"))
    }

    /** Identity is part of equality, so a row re-keyed to a different router recomposes. */
    @Test
    fun `identity takes part in row equality`() {
        assertNotEquals(row(1, "home", "a"), row(1, "home", "b"))
        assertEquals(row(1, "home", "a"), row(1, "home", "a"))
    }
}
