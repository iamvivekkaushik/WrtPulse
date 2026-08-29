package com.vivekkaushik.wrtpulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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

@Composable
fun WrtPulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}
