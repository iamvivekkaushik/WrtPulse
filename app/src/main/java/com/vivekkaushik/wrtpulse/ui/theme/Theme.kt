package com.vivekkaushik.wrtpulse.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// WrtPulse is dark-only by design.
private val DarkColorScheme = darkColorScheme(
    primary = Wrt.Accent,
    onPrimary = Wrt.OnAccent,
    secondary = Wrt.TextTertiary,
    background = Wrt.BgScreen,
    onBackground = Wrt.TextPrimary,
    surface = Wrt.BgCard,
    onSurface = Wrt.TextPrimary,
    surfaceVariant = Wrt.BgDeep,
    onSurfaceVariant = Wrt.TextSecondary,
    outline = Wrt.BorderCard,
    error = Wrt.Red,
)

/**
 * The dp width the screens were drawn against — the Android baseline, and the width of the
 * phone the design was tuned on.
 *
 * Every size in the UI is a literal dp from that drawing, so the layout is only in proportion
 * on a window this many dp across, and phones disagree by more than their glass suggests: a
 * OnePlus 7 Pro at 480 dpi is 360 dp wide, a Galaxy S23 Ultra at 420 dpi is 411 dp wide, and
 * both are 1080 px. The same card therefore covers 14% less of the Samsung, which is what
 * makes the app read small there.
 */
private const val DesignWidthDp = 360f

/**
 * Bend the whole UI beyond the design. Left at 1.0 the app matches the drawing; raise it to
 * make every screen larger on every device.
 */
private const val ExtraUiScale = 1f

/**
 * How far the density may be bent to reach [DesignWidthDp]. A window outside this keeps its
 * own scale and lets the `fillMaxWidth` layouts take up the slack, which is safer than
 * blowing the text up on a foldable or shrinking it below legibility.
 */
private const val MinWidthScale = 0.85f
private const val MaxWidthScale = 1.35f

/** Past this the UI stops growing and sits centred — a tablet does not want 24 dp body text. */
private val MaxContentWidth = 520.dp

/**
 * How much wider than [MaxContentWidth] a tablet window has to be before it is worth centring.
 * The column's margins are where the top bar and the tab bar stop, so a thin margin reads as
 * the bars being chopped, not as a layout choice: a Pixel Fold's inner screen is 631 scaled dp
 * across, and its 55 dp strips looked exactly like that. Below this the window is filled and
 * the bars reach the edges; a 12" tablet, twice the column wide, still gets it centred.
 */
private const val CentreWhenWiderBy = 1.4f

/**
 * The shorter side a window has to have before it counts as a tablet and gets the centred
 * column. A phone turned sideways is wide but not tall; capping and centring it left the top
 * bar and the tab bar short of the edges, and scaling it to its new width blew the whole UI
 * up by a third for a screen that had just lost half its height.
 */
private const val TabletShortSideDp = 600f

@Composable
fun WrtPulseTheme(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val size = LocalWindowInfo.current.containerSize
    // The SHORTER side, so turning the phone does not change the scale. The design was drawn
    // for a phone's width; sideways, that width is now the height, and it is still the
    // dimension the layout has to fit — a row of tab labels has more room, not less.
    val shortPx = minOf(size.width, size.height)
    val shortDp = if (shortPx <= 0) 0f else shortPx / base.density

    // Before the window is measured there is nothing to scale against; 1f keeps that first
    // frame at the platform density rather than collapsing it to zero.
    val widthScale = if (shortPx <= 0) 1f else (shortDp / DesignWidthDp).coerceIn(MinWidthScale, MaxWidthScale)
    val tablet = shortDp >= TabletShortSideDp

    // The design is drawn at a font scale of 1.0. A request to enlarge text is honoured; a
    // request to shrink it is not, because the dp chrome around the text does not shrink with
    // it — below 1.0 the labels just come loose from the boxes they sit in. Above it the font
    // scale is spent on the density too, so text and chrome grow together and nothing outgrows
    // its row ("866.7 Mb…" on Clients at 130% before this).
    val fontScale = base.fontScale.coerceAtLeast(1f)

    val scaled = Density(
        density = base.density * widthScale * fontScale * ExtraUiScale,
        fontScale = 1f,
    )

    // The window's width in the dp the layout will actually be measured in. Only when the
    // column would leave real margins is it worth having; see CentreWhenWiderBy.
    val widthScaledDp = if (size.width <= 0) 0f else size.width / scaled.density
    val centred = tablet && widthScaledDp >= MaxContentWidth.value * CentreWhenWiderBy

    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = Typography,
        ) {
            // Painted in the screens' own background so a column's margins are the same dark as
            // the page beside them, never the window's bare black.
            Box(Modifier.fillMaxSize().background(Wrt.BgScreen), contentAlignment = Alignment.TopCenter) {
                // A phone either way up, and a tablet or foldable not much wider than the column,
                // get the whole width: the bars reach the edges and the cards stretch.
                Box((if (centred) Modifier.widthIn(max = MaxContentWidth) else Modifier).fillMaxSize()) { content() }
            }
        }
    }
}
