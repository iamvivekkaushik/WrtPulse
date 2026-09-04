package com.vivekkaushik.wrtpulse.ui

import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingFlow
import com.vivekkaushik.wrtpulse.ui.screens.forgetRouterNotes
import com.vivekkaushik.wrtpulse.ui.screens.routerAddress
import com.vivekkaushik.wrtpulse.ui.screens.routerAddressNotes
import com.vivekkaushik.wrtpulse.ui.screens.routerEditBlock
import com.vivekkaushik.wrtpulse.ui.screens.routerName
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
        val notes = forgetRouterNotes(entity(), connectedHost = null)
        assertTrue(notes.any { it.contains("Nothing changes on the router") })
    }

    /** The app cannot take its key back off the router by deleting a local row. */
    @Test
    fun `a stored key warns that it stays in authorized_keys`() {
        val notes = forgetRouterNotes(entity(key = byteArrayOf(9)), connectedHost = null)
        assertTrue(notes.any { it.contains("authorized_keys") })
    }

    @Test
    fun `no stored key means no key warning`() {
        val notes = forgetRouterNotes(entity(key = null), connectedHost = null)
        assertFalse(notes.any { it.contains("authorized_keys") })
    }

    @Test
    fun `forgetting the router you are connected to says the session survives`() {
        val notes = forgetRouterNotes(entity(host = "192.168.2.1"), connectedHost = "192.168.2.1")
        assertTrue(notes.any { it.contains("session stays open") })
    }

    @Test
    fun `a different connected router raises no session note`() {
        val notes = forgetRouterNotes(entity(host = "192.168.2.1"), connectedHost = "10.0.0.1")
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
        val e = entity()
        assertNotNull(routerEditBlock("", "192.168.1.1", e, listOf(e)))
        assertNotNull(routerEditBlock("home.gw", "", e, listOf(e)))
        assertNull(routerEditBlock("home.gw", "192.168.2.1", e, listOf(e)))
    }

    /**
     * Two rows on the same address, port and user are the same router twice, and which
     * credential gets used then depends on list order.
     */
    @Test
    fun `an address another entry already holds is refused`() {
        val a = entity(id = 1, name = "home.gw", host = "192.168.1.1")
        val b = entity(id = 2, name = "lab", host = "192.168.2.1")
        val block = routerEditBlock("lab", "192.168.1.1", b, listOf(a, b))
        assertNotNull(block)
        assertTrue(block!!.contains("home.gw"))
        // The same address on a different port is a different endpoint.
        assertNull(routerEditBlock("lab", "192.168.1.1:2222", b, listOf(a, b)))
        // And an entry never clashes with itself.
        assertNull(routerEditBlock("home.gw", "192.168.1.1", a, listOf(a, b)))
    }

    /** The confusion worth heading off: this is not the screen that moves the router. */
    @Test
    fun `changing the address says what it does not do`() {
        val e = entity()
        val notes = routerAddressNotes(e, "192.168.2.1", connectedHost = null)
        assertTrue(notes.any { it.contains("not the router's own address") })
        assertTrue(notes.any { it.contains("first contact") })
    }

    @Test
    fun `an unchanged address needs no warning`() {
        val e = entity()
        assertEquals(emptyList<String>(), routerAddressNotes(e, "192.168.1.1", connectedHost = null))
    }

    @Test
    fun `a live session is called out as staying where it is`() {
        val e = entity()
        val notes = routerAddressNotes(e, "192.168.2.1", connectedHost = "192.168.1.1")
        assertTrue(notes.any { it.contains("stays on 192.168.1.1") })
    }
}
