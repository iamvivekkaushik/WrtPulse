package com.vivekkaushik.wrtpulse.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivekkaushik.wrtpulse.ui.screens.MIN_OUTPUT
import com.vivekkaushik.wrtpulse.ui.screens.TerminalRoomyHeight
import com.vivekkaushik.wrtpulse.ui.screens.chromeHeight
import com.vivekkaushik.wrtpulse.ui.screens.termMetricsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the output pane is left with once the chrome has taken its share. */
private fun outputHeight(available: Dp): Dp {
    val m = termMetricsFor(available)
    return available - chromeHeight(m.squeeze, m.showExtraKeys)
}

class TerminalMetricsTest {

    @Test
    fun `portrait leaves the chrome at full size`() {
        val m = termMetricsFor(700.dp)
        assertEquals(1f, m.squeeze, 0.001f)
        assertTrue(m.showExtraKeys)
        assertEquals(36.dp, m.key)
        assertEquals(32.dp, m.extraKey)
    }

    @Test
    fun `landscape squeezes the chrome instead of the output`() {
        // 3120x1440 at 640dpi, less the status bar and with the bottom nav dropped.
        val m = termMetricsFor(307.dp)
        assertTrue("extra keys survive a phone landscape window", m.showExtraKeys)
        assertTrue("chrome shrinks", m.squeeze < 1f)
        assertTrue("keys stay tappable", m.key >= 26.dp)
        assertTrue(outputHeight(307.dp) >= MIN_OUTPUT)
    }

    @Test
    fun `output keeps its minimum across every window that can hold it`() {
        // Below this the chrome is already at its floor with the extra keys gone, so there is
        // nothing left to give up; everything above it must keep the output whole.
        val floor = chromeHeight(squeeze = 0f, extraKeys = false) + MIN_OUTPUT
        var h = TerminalRoomyHeight
        while (h >= floor) {
            assertTrue("output collapsed at $h", outputHeight(h) >= MIN_OUTPUT - 0.5.dp)
            h -= 1.dp
        }
    }

    @Test
    fun `extra keys are the last thing dropped`() {
        // Only once the fully squeezed chrome plus extra keys no longer fits.
        val cutoff = chromeHeight(squeeze = 0f, extraKeys = true) + MIN_OUTPUT
        assertTrue(termMetricsFor(cutoff).showExtraKeys)
        assertTrue(!termMetricsFor(cutoff - 1.dp).showExtraKeys)
        assertTrue(outputHeight(cutoff - 1.dp) >= MIN_OUTPUT)
    }

    @Test
    fun `a window too short for anything still hands the output what is left`() {
        val m = termMetricsFor(120.dp)
        assertEquals(0f, m.squeeze, 0.001f)
        assertTrue(!m.showExtraKeys)
        assertTrue("chrome is at its floor", chromeHeight(0f, false) > 120.dp - MIN_OUTPUT)
    }
}

/**
 * The sparkline was drawn upside down: `y = v / maxY * h` puts zero at the TOP of a canvas
 * whose y grows downward, so every rising series sloped downhill — the CPU and RAM gauges on
 * the dashboard included.
 */
class SparklineTest {

    @Test
    fun `a bigger value is drawn higher`() {
        val low = com.vivekkaushik.wrtpulse.ui.sparkY(10f, 100f, 100f)
        val high = com.vivekkaushik.wrtpulse.ui.sparkY(90f, 100f, 100f)
        assertTrue("higher value must have the smaller y", high < low)
    }

    @Test
    fun `zero sits on the floor and the ceiling touches the top`() {
        assertEquals(100f, com.vivekkaushik.wrtpulse.ui.sparkY(0f, 100f, 100f), 0.01f)
        assertEquals(0f, com.vivekkaushik.wrtpulse.ui.sparkY(100f, 100f, 100f), 0.01f)
        assertEquals(50f, com.vivekkaushik.wrtpulse.ui.sparkY(50f, 100f, 100f), 0.01f)
    }

    /** A series past its ceiling clamps to the box instead of drawing outside it. */
    @Test
    fun `values outside the range clamp to the edges`() {
        assertEquals(0f, com.vivekkaushik.wrtpulse.ui.sparkY(400f, 18f, 100f), 0.01f)
        assertEquals(100f, com.vivekkaushik.wrtpulse.ui.sparkY(-5f, 18f, 100f), 0.01f)
    }
}
