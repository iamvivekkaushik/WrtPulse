package com.vivekkaushik.wrtpulse.ui

import com.vivekkaushik.wrtpulse.ui.screens.heldSocketIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class AddNodeTest {
    @Test
    fun `the held socket's number picks the lit socket in the drawing`() {
        assertEquals(2, heldSocketIndex("lan2"))
        assertEquals(3, heldSocketIndex("port 3"))
        assertEquals(1, heldSocketIndex("lan1"))
        // Off the drawing's four LAN sockets: clamp rather than point at nothing.
        assertEquals(4, heldSocketIndex("lan7"))
        assertEquals(1, heldSocketIndex("port 0"))
        // No number at all: the middle-ish socket, as the design shows.
        assertEquals(2, heldSocketIndex("?"))
        assertEquals(2, heldSocketIndex("eth"))
    }
}
