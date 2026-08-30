package com.vivekkaushik.wrtpulse.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSessionsTest {

    /** Closing a tab should not jump the user to an unrelated shell. */
    @Test
    fun `selection follows the tabs that remain`() {
        // Three tabs, viewing the last one; closing an earlier tab shifts it left.
        assertEquals(1, TerminalSessions.nextSelection(remaining = 2, closed = 0, selected = 2))
        // Closing the tab to the right of the one being viewed leaves it where it is.
        assertEquals(0, TerminalSessions.nextSelection(remaining = 2, closed = 2, selected = 0))
        // Closing the viewed tab keeps the index, which is now the tab that slid into place.
        assertEquals(1, TerminalSessions.nextSelection(remaining = 2, closed = 1, selected = 1))
        // Closing the last tab in the list steps back onto the new last one.
        assertEquals(1, TerminalSessions.nextSelection(remaining = 2, closed = 2, selected = 2))
        // Nothing left to select.
        assertEquals(0, TerminalSessions.nextSelection(remaining = 0, closed = 0, selected = 0))
    }
}
