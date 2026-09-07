package com.tomilov.stylishsat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudyColors = lightColorScheme(
    primary = Color(0xFF244C3B), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EDDD), onPrimaryContainer = Color(0xFF203E30),
    secondary = Color(0xFF566746), secondaryContainer = Color(0xFFE4EDAA),
    tertiary = Color(0xFF955C3E), tertiaryContainer = Color(0xFFF7DDC9),
    background = Color(0xFFF6F7F2), onBackground = Color(0xFF202B23),
    surface = Color(0xFFFFFEFA), onSurface = Color(0xFF202B23),
    surfaceVariant = Color(0xFFEBEEE5), onSurfaceVariant = Color(0xFF657163),
    outline = Color(0xFF798474), outlineVariant = Color(0xFFDEE3D8),
    error = Color(0xFFAA3B32),
)

@Composable
fun StylishSATTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudyColors, typography = Typography, content = content)
}
