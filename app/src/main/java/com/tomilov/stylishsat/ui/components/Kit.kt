package com.tomilov.stylishsat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType

/** Clip, fill and click in one node chain; the whole surface dips slightly while pressed. */
fun Modifier.tapSurface(
    shape: Shape,
    color: Color,
    enabled: Boolean = true,
    border: BorderStroke? = null,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .background(color)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
        .clickable(interaction, ripple(), enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
}

enum class Tone { Ink, Marker, Quiet, Line, Inverse, Danger, Night }

@Composable
fun StudyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Ink,
    enabled: Boolean = true,
    glyph: Glyph? = null,
    arrow: Boolean = false,
    compact: Boolean = false,
) {
    val c = Study.colors
    val (background, content) = when {
        !enabled -> c.sunken to c.inkFaint
        tone == Tone.Ink -> c.ink to c.paper
        tone == Tone.Marker -> c.marker to c.onMarker
        tone == Tone.Quiet -> c.sunken to c.ink
        tone == Tone.Line -> Color.Transparent to c.ink
        tone == Tone.Danger -> c.bad to Color.White
        tone == Tone.Night -> c.onMarker to c.marker
        else -> c.onHero to c.hero
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = if (compact) 44.dp else 56.dp)
            .tapSurface(shape, background, enabled, if (tone == Tone.Line) BorderStroke(1.5.dp, if (enabled) c.ink else c.line) else null, onClick = onClick)
            .padding(horizontal = if (compact) 16.dp else 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        glyph?.let { GlyphIcon(it, tint = content, size = 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = if (compact) StudyType.Button.copy(fontSize = 14.sp) else StudyType.Button, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (arrow) { Spacer(Modifier.width(10.dp)); GlyphIcon(Glyph.ArrowRight, tint = content, size = 18.dp) }
    }
}

@Composable
fun GlyphButton(
    glyph: Glyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Study.colors.ink,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    filled: Boolean = false,
) {
    Box(
        modifier.size(size).tapSurface(CircleShape, background, enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, tint = if (enabled) tint else Study.colors.inkFaint, size = size * 0.5f, filled = filled) }
}

@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fill: Boolean = false,
) {
    val c = Study.colors
    Row(modifier.clip(RoundedCornerShape(50)).background(c.sunken).padding(3.dp)) {
        options.forEach { (value, text) ->
            val on = value == selected
            val background by animateColorAsState(if (on) c.ink else Color.Transparent, tween(140), label = "segment")
            val content by animateColorAsState(if (on) c.paper else c.inkSoft, tween(140), label = "segmentText")
            Box(
                (if (fill) Modifier.weight(1f) else Modifier)
                    .clip(RoundedCornerShape(50)).background(background)
                    .selectable(on, enabled, role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(text, style = StudyType.Button.copy(fontSize = 14.sp), color = content, maxLines = 1) }
        }
    }
}

@Composable
fun Meta(text: String, modifier: Modifier = Modifier, color: Color = Study.colors.inkSoft, maxLines: Int = 2) {
    Text(text.uppercase(), modifier, color = color, style = StudyType.Meta, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) { Box(modifier.fillMaxWidth().height(1.dp).background(Study.colors.line)) }

/** Highlighter strokes behind each laid-out line; dark mode tones the marker down to keep light text legible. */
@Composable
fun MarkedText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Study.colors.ink) {
    val c = Study.colors
    val marker = if (c.dark) c.marker.copy(alpha = 0.3f) else c.marker
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Text(text, modifier.drawBehind {
        val result = layout ?: return@drawBehind
        for (line in 0 until result.lineCount) {
            val top = result.getLineTop(line)
            val height = result.getLineBottom(line) - top
            val left = result.getLineLeft(line)
            val right = result.getLineRight(line)
            drawRoundRect(marker, Offset(left - 4.dp.toPx(), top + height * 0.46f),
                Size(right - left + 8.dp.toPx(), height * 0.44f), CornerRadius(2.dp.toPx()))
        }
    }, color = color, style = style, onTextLayout = { layout = it })
}

@Composable
fun Bar(progress: Float, modifier: Modifier = Modifier, color: Color = Study.colors.ink, track: Color = Study.colors.sunken, height: Dp = 6.dp) {
    val value by animateFloatAsState(progress.coerceIn(0f, 1f), tween(450, easing = FastOutSlowInEasing), label = "bar")
    Canvas(modifier.fillMaxWidth().height(height).progressSemantics(progress.coerceIn(0f, 1f))) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (value > 0f) drawRoundRect(color, size = Size(size.width * value, size.height), cornerRadius = radius)
    }
}

@Composable
fun Ring(progress: Float, modifier: Modifier = Modifier, color: Color = Study.colors.ink, track: Color = Study.colors.sunken, width: Dp = 5.dp) {
    val value by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600, easing = FastOutSlowInEasing), label = "ring")
    Canvas(modifier.progressSemantics(progress.coerceIn(0f, 1f))) {
        val stroke = width.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (value > 0f) drawArc(color, -90f, 360f * value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

enum class Mark { Todo, Now, Done, Right, Wrong, Open }

/** One segment per step, coloured by the saved outcome of that step. */
@Composable
fun StepTrack(marks: List<Mark>, modifier: Modifier = Modifier, onHero: Boolean = false) {
    val c = Study.colors
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val count = marks.size.coerceAtLeast(1)
        val gap = (if (count > 24) 1.dp else 3.dp).toPx()
        val width = (size.width - gap * (count - 1)) / count
        marks.forEachIndexed { index, mark ->
            val color = if (onHero) when (mark) {
                Mark.Todo -> c.onHero.copy(alpha = 0.16f)
                Mark.Now -> c.onHeroSoft
                Mark.Done -> c.onHero
                Mark.Right -> c.marker
                Mark.Wrong -> c.bad
                Mark.Open -> c.onHeroSoft
            } else when (mark) {
                Mark.Todo -> c.sunken
                Mark.Now -> c.inkFaint
                Mark.Done -> c.ink
                Mark.Right -> c.good
                Mark.Wrong -> c.bad
                Mark.Open -> c.inkSoft
            }
            drawRoundRect(color, Offset(index * (width + gap), 0f), Size(width, size.height), CornerRadius(size.height / 2))
        }
    }
}

/** A quiet raised block for grouped content; no shadows. */
@Composable
fun Block(modifier: Modifier = Modifier, color: Color = Study.colors.raised, padding: Dp = 18.dp, spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/** Margin note: a marker rule to the left of supporting text (hints, worked examples, key answers). */
@Composable
fun MarginNote(modifier: Modifier = Modifier, rule: Color = Study.colors.marker, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(rule))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun RowScope.Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = StudyType.Numeral, color = Study.colors.ink, maxLines = 1)
        Meta(label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Study.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.paper,
        contentColor = c.ink,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(50)).background(c.line)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
fun studyFieldColors(): TextFieldColors {
    val c = Study.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.ink, unfocusedTextColor = c.ink, disabledTextColor = c.inkSoft,
        focusedContainerColor = c.raised, unfocusedContainerColor = c.raised, disabledContainerColor = c.sunken,
        focusedBorderColor = c.ink, unfocusedBorderColor = c.line, disabledBorderColor = c.line,
        cursorColor = c.ink, focusedLabelColor = c.ink, unfocusedLabelColor = c.inkSoft,
        focusedSupportingTextColor = c.inkSoft, unfocusedSupportingTextColor = c.inkSoft,
    )
}
