package com.vivekkaushik.wrtpulse.ui

import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingFlow
import com.vivekkaushik.wrtpulse.ui.screens.forgetRouterNotes
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
