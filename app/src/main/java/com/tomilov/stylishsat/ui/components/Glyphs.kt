package com.tomilov.stylishsat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tomilov.stylishsat.ui.theme.Study

/** A small line-icon set drawn on a 24-unit grid; no icon font or image assets. */
enum class Glyph {
    Today, Library, Progress, Settings, Close, Check, Cross, Play, Pause, Stop, Mic, ArrowRight, ArrowLeft,
    ChevronRight, ChevronDown, Flame, Clock, Search, More, Plus, Bulb, Headphones, Calendar, Download, Spark, Bolt, Dot,
}

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = Study.colors.ink, size: Dp = 22.dp, filled: Boolean = false) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint, filled) }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color, filled: Boolean) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 1.9f) =
        drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = width * u, cap = StrokeCap.Round)
    fun poly(vararg points: Float, close: Boolean = false, fill: Boolean = false) {
        val path = Path().apply {
            moveTo(points[0] * u, points[1] * u)
            for (i in 2 until points.size step 2) lineTo(points[i] * u, points[i + 1] * u)
            if (close) close()
        }
        if (fill) drawPath(path, tint) else drawPath(path, tint, style = stroke)
    }
    fun ring(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, p(x, y), style = stroke)
    fun dot(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, p(x, y))
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Boolean = false) =
        if (fill) drawRoundRect(tint, p(x, y), Size(w * u, h * u), CornerRadius(r * u))
        else drawRoundRect(tint, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = stroke)
    when (glyph) {
        Glyph.Today -> { ring(12f, 12f, 8.5f); dot(12f, 12f, if (filled) 4.2f else 3f) }
        Glyph.Library -> {
            box(3.5f, 6f, 4.5f, 14f, 1.2f, filled); box(9.5f, 4f, 4.5f, 16f, 1.2f, filled)
            rotate(-16f, p(19f, 20f)) { box(15.5f, 6.5f, 4.5f, 13.5f, 1.2f, filled) }
        }
        Glyph.Progress -> { line(5.5f, 20f, 5.5f, 14f, 2.8f); line(12f, 20f, 12f, 9f, 2.8f); line(18.5f, 20f, 18.5f, 4f, 2.8f) }
        Glyph.Settings -> { line(3.5f, 8f, 20.5f, 8f); line(3.5f, 16f, 20.5f, 16f); dot(9f, 8f, 2.8f); dot(15f, 16f, 2.8f) }
        Glyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        Glyph.Check -> poly(4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 6.5f)
        Glyph.Cross -> { line(7f, 7f, 17f, 17f, 2.2f); line(17f, 7f, 7f, 17f, 2.2f) }
        Glyph.Play -> poly(7.5f, 4.5f, 19.5f, 12f, 7.5f, 19.5f, close = true, fill = true)
        Glyph.Pause -> { box(6.5f, 5f, 4f, 14f, 1.2f, true); box(13.5f, 5f, 4f, 14f, 1.2f, true) }
        Glyph.Stop -> box(6f, 6f, 12f, 12f, 2.5f, true)
        Glyph.Mic -> {
            box(8.5f, 2.5f, 7f, 12f, 3.5f, filled)
            drawArc(tint, 0f, 180f, false, p(5f, 5f), Size(14f * u, 13f * u), style = stroke)
            line(12f, 18f, 12f, 21.5f)
        }
        Glyph.ArrowRight -> { line(4.5f, 12f, 19f, 12f); poly(13f, 6f, 19f, 12f, 13f, 18f) }
        Glyph.ArrowLeft -> { line(19.5f, 12f, 5f, 12f); poly(11f, 6f, 5f, 12f, 11f, 18f) }
        Glyph.ChevronRight -> poly(9f, 5.5f, 15.5f, 12f, 9f, 18.5f)
        Glyph.ChevronDown -> poly(5.5f, 9f, 12f, 15.5f, 18.5f, 9f)
        Glyph.Flame -> {
            val path = Path().apply {
                moveTo(12f * u, 2.5f * u)
                cubicTo(13.5f * u, 7f * u, 19f * u, 9f * u, 19f * u, 14.5f * u)
                cubicTo(19f * u, 18.6f * u, 15.9f * u, 21.5f * u, 12f * u, 21.5f * u)
                cubicTo(8.1f * u, 21.5f * u, 5f * u, 18.6f * u, 5f * u, 15f * u)
                cubicTo(5f * u, 11.8f * u, 7.2f * u, 10.2f * u, 8.4f * u, 8f * u)
                cubicTo(9f * u, 10.2f * u, 10.1f * u, 11.4f * u, 11.3f * u, 11.6f * u)
                cubicTo(11.2f * u, 8.4f * u, 10.9f * u, 5.4f * u, 12f * u, 2.5f * u)
                close()
            }
            if (filled) drawPath(path, tint) else drawPath(path, tint, style = stroke)
        }
        Glyph.Clock -> { ring(12f, 12f, 8.5f); line(12f, 12f, 12f, 7.5f); line(12f, 12f, 15.2f, 14f) }
        Glyph.Search -> { ring(10.5f, 10.5f, 6.2f); line(15.2f, 15.2f, 20f, 20f) }
        Glyph.More -> { dot(5.5f, 12f, 1.8f); dot(12f, 12f, 1.8f); dot(18.5f, 12f, 1.8f) }
        Glyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        Glyph.Bulb -> { ring(12f, 10f, 6.2f); line(9.5f, 18.5f, 14.5f, 18.5f); line(10.5f, 21.5f, 13.5f, 21.5f) }
        Glyph.Headphones -> {
            drawArc(tint, 180f, 180f, false, p(4f, 4.5f), Size(16f * u, 16f * u), style = stroke)
            box(3f, 13f, 4.5f, 8f, 1.6f, true); box(16.5f, 13f, 4.5f, 8f, 1.6f, true)
        }
        Glyph.Calendar -> { box(3.5f, 5f, 17f, 15.5f, 3f); line(3.5f, 10f, 20.5f, 10f); line(8f, 2.8f, 8f, 6.5f); line(16f, 2.8f, 16f, 6.5f) }
        Glyph.Download -> { line(12f, 3.5f, 12f, 15f); poly(7f, 10.5f, 12f, 15.5f, 17f, 10.5f); line(5f, 20f, 19f, 20f) }
        Glyph.Spark -> {
            val path = Path().apply {
                moveTo(12f * u, 2.5f * u)
                quadraticTo(13f * u, 11f * u, 21.5f * u, 12f * u)
                quadraticTo(13f * u, 13f * u, 12f * u, 21.5f * u)
                quadraticTo(11f * u, 13f * u, 2.5f * u, 12f * u)
                quadraticTo(11f * u, 11f * u, 12f * u, 2.5f * u)
                close()
            }
            drawPath(path, tint)
        }
        Glyph.Bolt -> poly(13.5f, 2f, 4.5f, 13.5f, 11.5f, 13.5f, 10.5f, 22f, 19.5f, 10f, 12.5f, 10f, close = true, fill = filled)
        Glyph.Dot -> dot(12f, 12f, 3.5f)
    }
}
