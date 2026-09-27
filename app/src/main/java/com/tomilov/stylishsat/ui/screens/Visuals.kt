package com.tomilov.stylishsat.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

private fun number(value: Double): String = if (value % 1 == 0.0) "${value.toLong()}" else "%.1f".format(java.util.Locale.ROOT, value)

/** Series shades plus a second cue (dash, marker or number), so colour never carries meaning alone. */
@Composable
private fun seriesShades(): List<Color> {
    val c = Study.colors
    return listOf(c.ink, c.good, c.warn, c.inkSoft, c.bad, c.inkFaint)
}

/** Task 1 visuals: bar, line, pie and table views of the same labelled data. */
@Composable
fun Chart(chart: ChartData, l: Language) {
    val c = Study.colors
    Block {
        Text(chart.title, style = StudyType.Strong, color = c.ink)
        Meta(if (chart.kind == ChartKind.PIE) "${chart.yLabel} (${chart.unit})" else "${chart.xLabel} · ${chart.yLabel} (${chart.unit})")
        when (chart.kind) {
            ChartKind.BAR -> BarChart(chart)
            ChartKind.LINE -> { LineChart(chart); Legend(chart.series.map { it.name }, lines = true) }
            ChartKind.PIE -> PieCharts(chart)
            ChartKind.TABLE -> DataTable(chart)
        }
        if (chart.kind == ChartKind.LINE || chart.kind == ChartKind.PIE) {
            Disclosure(l.label("Values as a table", "Значения таблицей"), null) { DataTable(chart) }
        }
    }
}

@Composable
private fun Legend(names: List<String>, lines: Boolean) {
    if (names.size < 2) return
    val c = Study.colors
    val shades = seriesShades()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        names.forEachIndexed { index, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 22.dp, height = 10.dp)) {
                    val color = shades[index % shades.size]
                    if (lines) {
                        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                            pathEffect = dash(index, this), cap = StrokeCap.Round)
                        marker(index, Offset(size.width / 2, size.height / 2), color, 3.dp.toPx())
                    } else drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx()))
                }
                Spacer(Modifier.width(6.dp))
                Text(name, style = StudyType.Small, color = c.ink)
            }
        }
    }
}

@Composable
private fun BarChart(chart: ChartData) {
    val c = Study.colors
    val maximum = chart.series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val shades = seriesShades()
    Legend(chart.series.map { it.name }, lines = false)
    chart.labels.forEachIndexed { index, label ->
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = StudyType.Strong.copy(fontSize = 14.sp), color = c.ink)
            chart.series.forEachIndexed { seriesIndex, series ->
                val value = series.values.getOrNull(index) ?: 0.0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Bar((value / maximum).toFloat(), Modifier.weight(1f), color = shades[seriesIndex % shades.size], height = 10.dp)
                    Text(number(value), Modifier.width(56.dp).padding(start = 8.dp), style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
                }
            }
        }
    }
}

private fun dash(index: Int, scope: DrawScope): PathEffect? = with(scope) {
    when (index % 3) {
        0 -> null
        1 -> PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
        else -> PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
    }
}

private fun DrawScope.marker(index: Int, at: Offset, color: Color, radius: Float) {
    when (index % 3) {
        0 -> drawCircle(color, radius, at)
        1 -> drawRect(color, Offset(at.x - radius, at.y - radius), Size(radius * 2, radius * 2))
        else -> drawPath(Path().apply {
            moveTo(at.x, at.y - radius * 1.2f); lineTo(at.x + radius * 1.2f, at.y + radius); lineTo(at.x - radius * 1.2f, at.y + radius); close()
        }, color)
    }
}

/** Round the axis top to 1, 2, 2.5 or 5 × a power of ten. */
private fun niceCeiling(value: Double): Double {
    if (value <= 0) return 1.0
    val magnitude = 10.0.pow(kotlin.math.floor(log10(value)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * magnitude >= value }
    return step * magnitude
}

@Composable
private fun LineChart(chart: ChartData) {
    val c = Study.colors
    val shades = seriesShades()
    val measurer = rememberTextMeasurer()
    val top = niceCeiling(chart.series.flatMap { it.values }.maxOrNull() ?: 1.0)
    val labelStyle = StudyType.Mono.copy(fontSize = 10.sp, color = c.inkSoft)
    val description = chart.series.joinToString("; ") { series ->
        series.name + ": " + chart.labels.zip(series.values).joinToString(", ") { (label, value) -> "$label ${number(value)}" }
    }
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.35f).semantics { contentDescription = "${chart.title}. $description" }) {
        val left = 40.dp.toPx(); val bottom = 28.dp.toPx(); val right = 10.dp.toPx(); val topPad = 10.dp.toPx()
        val width = size.width - left - right; val height = size.height - bottom - topPad
        for (step in 0..4) {
            val y = topPad + height - height * step / 4f
            drawLine(c.line, Offset(left, y), Offset(left + width, y), 1.dp.toPx())
            val text = measurer.measure(number(top * step / 4), labelStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), y - text.size.height / 2f))
        }
        val count = chart.labels.size
        fun x(index: Int) = left + if (count == 1) width / 2 else width * index / (count - 1f)
        chart.labels.forEachIndexed { index, label ->
            val text = measurer.measure(label, labelStyle)
            val at = (x(index) - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
            drawText(text, topLeft = Offset(at, topPad + height + 8.dp.toPx()))
        }
        chart.series.forEachIndexed { seriesIndex, series ->
            val color = shades[seriesIndex % shades.size]
            val points = series.values.mapIndexed { index, value -> Offset(x(index), topPad + height - (value / top * height).toFloat()) }
            val path = Path().apply { points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) } }
            drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash(seriesIndex, this)))
            points.forEach { marker(seriesIndex, it, color, 3.5.dp.toPx()) }
        }
    }
}

@Composable
private fun PieCharts(chart: ChartData) {
    val c = Study.colors
    val shades = seriesShades()
    val measurer = rememberTextMeasurer()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chart.series.forEach { series ->
            val total = series.values.sum().takeIf { it > 0 } ?: 1.0
            Text(series.name, style = StudyType.Strong.copy(fontSize = 15.sp), color = c.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(140.dp).semantics {
                    contentDescription = series.name + ": " + chart.labels.zip(series.values).joinToString(", ") { (label, value) -> "$label ${number(value)} ${chart.unit}" }
                }) {
                    var start = -90f
                    val numberStyle = StudyType.Mono.copy(fontSize = 11.sp, color = c.paper)
                    series.values.forEachIndexed { index, value ->
                        val sweep = (value / total * 360).toFloat()
                        drawArc(shades[index % shades.size], start, sweep, useCenter = true)
                        drawArc(c.paper, start, sweep, useCenter = true, style = Stroke(1.5.dp.toPx()))
                        if (sweep > 18f) {
                            val middle = Math.toRadians((start + sweep / 2).toDouble())
                            val radius = size.minDimension * 0.32f
                            val text = measurer.measure("${index + 1}", numberStyle)
                            drawText(text, topLeft = Offset(center.x + (radius * cos(middle)).toFloat() - text.size.width / 2f,
                                center.y + (radius * sin(middle)).toFloat() - text.size.height / 2f))
                        }
                        start += sweep
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    chart.labels.forEachIndexed { index, label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(shades[index % shades.size]))
                            Spacer(Modifier.width(6.dp))
                            Text("${index + 1}. $label · ${number(series.values[index])}", style = StudyType.Small.copy(fontSize = 13.sp), color = c.ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DataTable(chart: ChartData) {
    val c = Study.colors
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(1.dp, c.line, RoundedCornerShape(10.dp))) {
        @Composable fun Cell(text: String, header: Boolean, width: Int) = Text(text, Modifier.width(width.dp).padding(horizontal = 8.dp, vertical = 7.dp),
            style = if (header) StudyType.Strong.copy(fontSize = 13.sp) else StudyType.Mono.copy(fontSize = 13.sp), color = c.ink,
            textAlign = if (header) TextAlign.Start else TextAlign.End)
        Row(Modifier.background(c.sunken)) {
            Cell(chart.xLabel, true, 128)
            chart.series.forEach { Cell(it.name, true, 96) }
        }
        chart.labels.forEachIndexed { index, label ->
            Hairline()
            Row {
                Cell(label, true, 128)
                chart.series.forEach { Cell(number(it.values[index]), false, 96) }
            }
        }
    }
    Meta("${chart.yLabel} (${chart.unit})")
}

/** Short gap labels inside figures: "(14)". */
fun figureLabel(label: String, numbers: Map<String, Int>): String =
    Regex("\\[\\[([^\\]]+)]]").replace(label) { match -> numbers[match.groupValues[1]]?.let { "($it) ……" } ?: "……" }

/** Process diagrams, before/after maps and labelled diagrams drawn from node coordinates. */
@Composable
fun FigureView(figure: Figure, numbers: Map<String, Int> = emptyMap()) {
    val c = Study.colors
    val measurer = rememberTextMeasurer()
    Block {
        Text(figure.title, style = StudyType.Strong, color = c.ink)
        figure.panels.forEach { panel ->
            if (panel.title.isNotBlank()) Meta(panel.title, color = c.ink)
            val labels = remember(panel, numbers) { panel.nodes.associate { it.id to figureLabel(it.label, numbers) } }
            val description = remember(panel, labels) {
                panel.nodes.joinToString("; ") { labels.getValue(it.id) } + if (panel.links.isEmpty()) "" else ". " +
                    panel.links.joinToString("; ") { "${labels[it.from]} → ${labels[it.to]}" + if (it.label.isBlank()) "" else " (${it.label})" }
            }
            Canvas(Modifier.fillMaxWidth().aspectRatio(if (figure.kind == FigureKind.MAP) 1.1f else 0.95f)
                .clip(RoundedCornerShape(12.dp)).background(if (figure.kind == FigureKind.MAP) c.sunken else c.raised)
                .semantics { contentDescription = "${figure.title}${if (panel.title.isBlank()) "" else ", " + panel.title}: $description" }) {
                val nodes = panel.nodes.associateBy { it.id }
                fun rect(node: FigureNode) = androidx.compose.ui.geometry.Rect(node.x * size.width, node.y * size.height,
                    (node.x + node.width) * size.width, (node.y + node.height) * size.height)
                panel.links.forEach { link ->
                    val from = rect(nodes.getValue(link.from)); val to = rect(nodes.getValue(link.to))
                    val start = edgePoint(from, to.center); val end = edgePoint(to, from.center)
                    drawLine(c.inkSoft, start, end, 2.dp.toPx(), cap = StrokeCap.Round)
                    val angle = atan2(end.y - start.y, end.x - start.x)
                    val head = 9.dp.toPx()
                    drawPath(Path().apply {
                        moveTo(end.x, end.y)
                        lineTo(end.x - head * cos(angle - 0.45f), end.y - head * sin(angle - 0.45f))
                        lineTo(end.x - head * cos(angle + 0.45f), end.y - head * sin(angle + 0.45f)); close()
                    }, c.inkSoft)
                    if (link.label.isNotBlank()) {
                        val text = measurer.measure(link.label, StudyType.Mono.copy(fontSize = 10.sp, color = c.inkSoft))
                        val middle = Offset((start.x + end.x) / 2, (start.y + end.y) / 2)
                        drawText(text, topLeft = Offset(middle.x - text.size.width / 2f, middle.y - text.size.height - 2.dp.toPx()))
                    }
                }
                panel.nodes.forEach { node ->
                    val box = rect(node)
                    when (node.shape) {
                        NodeShape.BOX -> {
                            drawRoundRect(c.raised, box.topLeft, box.size, CornerRadius(8.dp.toPx()))
                            drawRoundRect(c.ink, box.topLeft, box.size, CornerRadius(8.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        }
                        NodeShape.ROUND -> {
                            drawOval(c.raised, box.topLeft, box.size)
                            drawOval(c.ink, box.topLeft, box.size, style = Stroke(1.5.dp.toPx()))
                        }
                        NodeShape.LABEL -> Unit
                    }
                    val gap = labels.getValue(node.id) != node.label
                    val text = measurer.measure(labels.getValue(node.id), StudyType.Small.copy(fontSize = 11.sp, lineHeight = 14.sp,
                        color = if (gap) c.bad else c.ink, textAlign = TextAlign.Center),
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = box.width.toInt().coerceAtLeast(1)))
                    drawText(text, topLeft = Offset(box.center.x - text.size.width / 2f, box.center.y - text.size.height / 2f))
                }
            }
        }
        if (figure.caption.isNotBlank()) Text(figure.caption, style = StudyType.Small, color = c.inkSoft)
    }
}

private fun edgePoint(rect: androidx.compose.ui.geometry.Rect, toward: Offset): Offset {
    val dx = toward.x - rect.center.x; val dy = toward.y - rect.center.y
    if (dx == 0f && dy == 0f) return rect.center
    val scale = 1f / maxOf(abs(dx) / (rect.width / 2), abs(dy) / (rect.height / 2))
    val gap = 1.04f
    return Offset(rect.center.x + dx * scale * gap, rect.center.y + dy * scale * gap)
}
