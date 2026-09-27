package com.tomilov.stylishsat.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Paper, ink and one highlighter. Secondary text keeps at least 4.5:1 contrast on paper. */
@Immutable
data class Palette(
    val paper: Color,
    val raised: Color,
    val sunken: Color,
    val ink: Color,
    val inkSoft: Color,
    /** Disabled and decorative only; never the sole carrier of information. */
    val inkFaint: Color,
    val line: Color,
    val marker: Color,
    val onMarker: Color,
    val good: Color,
    val bad: Color,
    val badSoft: Color,
    val warn: Color,
    val hero: Color,
    val onHero: Color,
    val onHeroSoft: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    paper = Color(0xFFF3F1EC), raised = Color(0xFFFFFFFF), sunken = Color(0xFFE7E4DC),
    ink = Color(0xFF121211), inkSoft = Color(0xFF5F5C56), inkFaint = Color(0xFF8F8B83), line = Color(0xFFDAD6CD),
    marker = Color(0xFFD5F53E), onMarker = Color(0xFF121211),
    good = Color(0xFF3B7A00), bad = Color(0xFFC8381A), badSoft = Color(0xFFFBE1D8), warn = Color(0xFF9A5B00),
    hero = Color(0xFF121211), onHero = Color(0xFFF3F1EC), onHeroSoft = Color(0xFFA09C93), dark = false,
)

val DarkPalette = Palette(
    paper = Color(0xFF0F0F0E), raised = Color(0xFF1A1A18), sunken = Color(0xFF252522),
    ink = Color(0xFFF1EEE7), inkSoft = Color(0xFFA9A59C), inkFaint = Color(0xFF76726B), line = Color(0xFF2E2D2A),
    marker = Color(0xFFD5F53E), onMarker = Color(0xFF121211),
    good = Color(0xFFD5F53E), bad = Color(0xFFFF6B4A), badSoft = Color(0xFF3A1B13), warn = Color(0xFFFFB547),
    hero = Color(0xFF1E1E1B), onHero = Color(0xFFF1EEE7), onHeroSoft = Color(0xFFA09C93), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

object Study {
    val colors: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current
}
