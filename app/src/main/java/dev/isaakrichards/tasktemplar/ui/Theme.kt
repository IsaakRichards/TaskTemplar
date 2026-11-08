package dev.isaakrichards.tasktemplar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Nebula = Color(0xFFB69CFF)
val Comet = Color(0xFF7FD8FF)
val Void = Color(0xFF0B0820)
val Dust = Color(0xFF151033)
val DustHigh = Color(0xFF221A4A)
val Danger = Color(0xFFFF8A9A)
val Okay = Color(0xFF8DF0B5)

private val scheme = darkColorScheme(
    primary = Nebula,
    onPrimary = Color(0xFF1E0E52),
    primaryContainer = Color(0xFF3B2A7A),
    onPrimaryContainer = Color(0xFFE6DCFF),
    secondary = Comet,
    onSecondary = Color(0xFF00283A),
    secondaryContainer = Color(0xFF14405A),
    onSecondaryContainer = Color(0xFFCDEEFF),
    background = Void,
    onBackground = Color(0xFFE8E4F8),
    surface = Void,
    onSurface = Color(0xFFE8E4F8),
    surfaceVariant = Dust,
    onSurfaceVariant = Color(0xFFB9B2D6),
    surfaceContainer = Dust,
    surfaceContainerHigh = DustHigh,
    surfaceContainerHighest = DustHigh,
    surfaceContainerLow = Dust,
    outline = Color(0xFF5A5180),
    error = Danger,
)

@Composable
fun TemplarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
