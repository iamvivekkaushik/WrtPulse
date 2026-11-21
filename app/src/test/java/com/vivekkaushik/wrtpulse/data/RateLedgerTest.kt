package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.data.RateLedger.Companion.MAX_SHOWN_DAYS
import com.vivekkaushik.wrtpulse.data.RateLedger.Companion.MIN_CONNECT_DAYS
import com.vivekkaushik.wrtpulse.data.RateLedger.Companion.MIN_INSTALL_DAYS
import com.vivekkaushik.wrtpulse.data.RateLedger.Companion.MIN_OPENS
import com.vivekkaushik.wrtpulse.data.RateLedger.Companion.OPEN_GAP_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLedgerTest {

    private val installed = 20_000L
    private val day = installed + MIN_INSTALL_DAYS

    /** A week-old install with its opens and connect days in, waiting only on kept applies. */
    private fun regular(): RateLedger {
        var l = RateLedger()
        repeat(MIN_OPENS) { i -> l = l.foreground(i * 2 * OPEN_GAP_MS).background(i * 2 * OPEN_GAP_MS + 1) }
        repeat(MIN_CONNECT_DAYS) { i -> l = l.connected(installed + i) }
        return l
    }

    @Test
    fun `the first open of a fresh install never shows it`() {
        val first = RateLedger().foreground(0).connected(installed).applied(true, installed, installed)
        assertFalse(first.due(installed, installed))
        assertFalse(first.due(installed + 1, installed))
    }

    @Test
    fun `an open is a return after the gap, not a rotation or a glance away`() {
        val l = RateLedger().foreground(0).background(1_000)
        assertEquals(1, l.opens)
        assertEquals(1, l.foreground(1_000 + OPEN_GAP_MS - 1).opens)
        assertEquals(2, l.foreground(1_000 + OPEN_GAP_MS).opens)
    }

    @Test
    fun `a connect day counts once per calendar day`() {
        val l = RateLedger().connected(5).connected(5).connected(6)
        assertEquals(2, l.connectDays)
    }

    @Test
    fun `a kept apply arms it only once the rest is earned, and it shows the day after`() {
        var l = regular().applied(true, day, installed)
        assertEquals(RateLedger.NEVER, l.armedDay)
        l = l.applied(true, day, installed)
        assertEquals(day, l.armedDay)
        assertFalse("not on the day of the apply", l.due(day, installed))
        assertTrue(l.due(day + 1, installed))
    }

    @Test
    fun `too few opens keeps it unarmed however many applies`() {
        var l = RateLedger()
        repeat(MIN_CONNECT_DAYS) { i -> l = l.connected(installed + i) }
        repeat(MIN_OPENS - 1) { i -> l = l.foreground(i * 2 * OPEN_GAP_MS).background(i * 2 * OPEN_GAP_MS + 1) }
        repeat(5) { l = l.applied(true, day, installed) }
        assertEquals(RateLedger.NEVER, l.armedDay)
        assertFalse(l.due(day + 1, installed))
    }

    @Test
    fun `an install younger than a week keeps it unarmed`() {
        var l = regular()
        repeat(3) { l = l.applied(true, installed + MIN_INSTALL_DAYS - 1, installed) }
        assertFalse(l.due(installed + MIN_INSTALL_DAYS, installed))
    }

    @Test
    fun `a rollback disarms it until the next clean apply`() {
        var l = regular().applied(true, day, installed).applied(true, day, installed)
        l = l.applied(false, day, installed)
        assertFalse(l.due(day + 1, installed))
        l = l.applied(true, day + 1, installed)
        assertFalse(l.due(day + 1, installed))
        assertTrue(l.due(day + 2, installed))
    }

    @Test
    fun `any answer retires it for good`() {
        var l = regular().applied(true, day, installed).applied(true, day, installed).retired()
        assertFalse(l.due(day + 1, installed))
        l = l.applied(true, day + 2, installed).connected(day + 2).foreground(Long.MAX_VALUE / 2)
        assertFalse(l.due(day + 3, installed))
    }

    @Test
    fun `left alone it shows on three days, then stops`() {
        var l = regular().applied(true, day, installed).applied(true, day, installed)
        for (d in 1..MAX_SHOWN_DAYS) {
            assertTrue(l.due(day + d, installed))
            l = l.shown(day + d).shown(day + d)
        }
        assertEquals(MAX_SHOWN_DAYS, l.shownDays)
        assertTrue("still there for the rest of the last day", l.due(day + MAX_SHOWN_DAYS, installed))
        assertFalse(l.due(day + MAX_SHOWN_DAYS + 1, installed))
    }
}
