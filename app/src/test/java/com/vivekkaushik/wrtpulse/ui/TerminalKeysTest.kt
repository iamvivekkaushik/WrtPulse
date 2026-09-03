package com.vivekkaushik.wrtpulse.ui

import com.vivekkaushik.wrtpulse.ui.screens.keyPayload
import org.junit.Assert.assertEquals
import org.junit.Test

private const val ESC = ""

class TerminalKeysTest {

    @Test
    fun `an unmodified key goes out as itself`() {
        assertEquals("c", keyPayload("c", ctrl = false, shift = false, alt = false))
        assertEquals("|", keyPayload("|", ctrl = false, shift = false, alt = false))
    }

    @Test
    fun `ctrl folds a letter to its control code`() {
        assertEquals("", keyPayload("c", ctrl = true, shift = false, alt = false))
        assertEquals("", keyPayload("a", ctrl = true, shift = false, alt = false))
        // Already upper-case, and reached with shift held: still ^C.
        assertEquals("", keyPayload("C", ctrl = true, shift = false, alt = false))
    }

    @Test
    fun `shift upper-cases a letter and leaves anything else alone`() {
        assertEquals("C", keyPayload("c", ctrl = false, shift = true, alt = false))
        assertEquals("|", keyPayload("|", ctrl = false, shift = true, alt = false))
    }

    /** Alt used to be a dead key — it drew a latch but put nothing on the wire. */
    @Test
    fun `alt sends the key with an ESC in front, the way meta has always been sent`() {
        assertEquals(ESC + "b", keyPayload("b", ctrl = false, shift = false, alt = true))
        assertEquals(ESC + ".", keyPayload(".", ctrl = false, shift = false, alt = true))
    }

    @Test
    fun `alt composes with the other modifiers rather than replacing them`() {
        assertEquals(ESC + "B", keyPayload("b", ctrl = false, shift = true, alt = true))
        assertEquals(ESC + "", keyPayload("b", ctrl = true, shift = false, alt = true))
    }

    /** The extra-keys row sends whole sequences; alt has to prefix those too. */
    @Test
    fun `alt prefixes a multi-character sequence without mangling it`() {
        assertEquals(ESC + "$ESC[D", keyPayload("$ESC[D", ctrl = false, shift = false, alt = true))
        assertEquals(ESC + "\t", keyPayload("\t", ctrl = false, shift = false, alt = true))
    }
}
