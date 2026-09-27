package com.tomilov.stylishsat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private fun Palette.scheme() = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = ink, onPrimary = paper, primaryContainer = marker, onPrimaryContainer = onMarker,
    inversePrimary = marker, secondary = inkSoft, onSecondary = paper,
    secondaryContainer = sunken, onSecondaryContainer = ink,
    tertiary = warn, onTertiary = paper, tertiaryContainer = sunken, onTertiaryContainer = ink,
    background = paper, onBackground = ink, surface = paper, onSurface = ink,
    surfaceVariant = sunken, onSurfaceVariant = inkSoft, surfaceTint = Color.Transparent,
    surfaceBright = raised, surfaceDim = sunken, surfaceContainerLowest = raised, surfaceContainerLow = raised,
    surfaceContainer = raised, surfaceContainerHigh = raised, surfaceContainerHighest = sunken,
    inverseSurface = ink, inverseOnSurface = paper, error = bad, onError = paper,
    errorContainer = badSoft, onErrorContainer = ink, outline = inkFaint, outlineVariant = line, scrim = Color.Black,
)

private val StudyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun StylishSATTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = palette.scheme(), typography = Typography, shapes = StudyShapes, content = content)
    }
}
