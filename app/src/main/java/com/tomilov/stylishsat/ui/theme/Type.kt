package com.tomilov.stylishsat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** System faces only: heavy sans for numbers, serif for reading, mono for labels and clocks. */
object StudyType {
    val Display = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 72.sp, lineHeight = 70.sp, letterSpacing = (-3).sp)
    val Numeral = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-1.2).sp)
    val Headline = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 35.sp, letterSpacing = (-1).sp)
    val Title = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.3).sp)
    val Body = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp)
    val Small = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val Strong = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val Reading = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 29.sp)
    val Question = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 30.sp)
    val Meta = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.6.sp)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    val Button = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
}

val Typography = Typography(
    displaySmall = StudyType.Headline,
    headlineLarge = StudyType.Headline,
    headlineMedium = StudyType.Headline.copy(fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = StudyType.Title.copy(fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = StudyType.Title,
    titleMedium = StudyType.Strong,
    titleSmall = StudyType.Strong.copy(fontSize = 14.sp),
    bodyLarge = StudyType.Body,
    bodyMedium = StudyType.Small,
    bodySmall = StudyType.Small.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = StudyType.Button.copy(fontSize = 14.sp),
    labelMedium = StudyType.Meta.copy(fontSize = 12.sp),
    labelSmall = StudyType.Meta,
)
