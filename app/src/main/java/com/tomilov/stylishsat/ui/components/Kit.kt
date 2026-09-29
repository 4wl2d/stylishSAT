package com.tomilov.stylishsat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomilov.stylishsat.ui.theme.LocalReducedMotion
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyMotion
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Clip, fill and click in one node chain. The surface sinks by about the same physical depth whatever its size,
 * springs back with a small overshoot, and cross-fades its fill when [color] changes.
 */
fun Modifier.tapSurface(
    shape: Shape,
    color: Color,
    enabled: Boolean = true,
    border: BorderStroke? = null,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    interaction: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interaction ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val down = pressed && enabled
    val press by animateFloatAsState(if (down) 1f else 0f, if (down) StudyMotion.press() else StudyMotion.bounce(), label = "press")
    val fill by animateColorAsState(color, StudyMotion.fade(180), label = "fill")
    graphicsLayer {
        val depth = (10.dp.toPx() / maxOf(size.width, size.height, 1f)).coerceAtMost(0.06f)
        val scale = 1f - press * depth
        scaleX = scale; scaleY = scale
    }
        .clip(shape)
        .drawBehind { drawRect(fill) }
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
        .clickable(source, ripple(), enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink by animateColorAsState(content, StudyMotion.fade(180), label = "buttonInk")
    // The arrow leans forward while pressed: the button says where it goes before it goes there.
    val nudge by animateFloatAsState(if (pressed && enabled) 1f else 0f, if (pressed) StudyMotion.press() else StudyMotion.bounce(), label = "nudge")
    Row(
        modifier
            .heightIn(min = if (compact) 44.dp else 56.dp)
            .tapSurface(shape, background, enabled, if (tone == Tone.Line) BorderStroke(1.5.dp, if (enabled) c.ink else c.line) else null, interaction = interaction, onClick = onClick)
            .padding(horizontal = if (compact) 16.dp else 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        glyph?.let { GlyphIcon(it, tint = ink, size = 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = if (compact) StudyType.Button.copy(fontSize = 14.sp) else StudyType.Button, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (arrow) {
            Spacer(Modifier.width(10.dp))
            GlyphIcon(Glyph.ArrowRight, Modifier.graphicsLayer { translationX = nudge * 5.dp.toPx() }, tint = ink, size = 18.dp)
        }
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
    ) {
        // Play becomes pause, record becomes stop: the old glyph shrinks away as the new one springs in.
        AnimatedContent(glyph, transitionSpec = {
            (fadeIn(StudyMotion.fade(120)) + scaleIn(StudyMotion.bounce(), 0.5f)) togetherWith (fadeOut(StudyMotion.fade(90)) + scaleOut(StudyMotion.settle(), 0.5f))
        }, contentAlignment = Alignment.Center, label = "glyph") { shown ->
            GlyphIcon(shown, tint = if (enabled) tint else Study.colors.inkFaint, size = size * 0.5f, filled = filled)
        }
    }
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
    // One ink thumb slides between measured options instead of each option fading its own fill.
    val index = options.indexOfFirst { it.first == selected }
    val lefts = remember(options.size) { mutableStateListOf(*Array(options.size) { 0f }) }
    val widths = remember(options.size) { mutableStateListOf(*Array(options.size) { 0f }) }
    val thumbLeft = remember { Animatable(0f) }
    val thumbWidth = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val targetLeft = lefts.getOrElse(index) { 0f }
    val targetWidth = widths.getOrElse(index) { 0f }
    LaunchedEffect(targetLeft, targetWidth) {
        if (targetWidth == 0f) return@LaunchedEffect
        if (!placed) {
            thumbLeft.snapTo(targetLeft); thumbWidth.snapTo(targetWidth); placed = true
        } else coroutineScope {
            launch { thumbLeft.animateTo(targetLeft, StudyMotion.settle()) }
            thumbWidth.animateTo(targetWidth, StudyMotion.settle())
        }
    }
    val thumb = c.ink
    Row(modifier.clip(RoundedCornerShape(50)).background(c.sunken).padding(3.dp).drawBehind {
        if (placed) drawRoundRect(thumb, Offset(thumbLeft.value, 0f), Size(thumbWidth.value, size.height), CornerRadius(size.height / 2))
    }) {
        options.forEachIndexed { position, (value, text) ->
            val on = value == selected
            val content by animateColorAsState(if (on) c.paper else c.inkSoft, StudyMotion.fade(140), label = "segmentText")
            Box(
                (if (fill) Modifier.weight(1f) else Modifier)
                    .onPlaced {
                        val left = it.positionInParent().x
                        val width = it.size.width.toFloat()
                        if (lefts[position] != left) lefts[position] = left
                        if (widths[position] != width) widths[position] = width
                    }
                    .clip(RoundedCornerShape(50))
                    // Until the first layout has measured the options, the selected one paints its own fill.
                    .then(if (on && !placed) Modifier.background(thumb) else Modifier)
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

/**
 * Highlighter strokes behind each laid-out line; dark mode tones the marker down to keep light text legible.
 * The stroke draws itself left to right, line after line, the first time the text appears.
 */
@Composable
fun MarkedText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Study.colors.ink, delayMillis: Int = 120) {
    val c = Study.colors
    val marker = if (c.dark) c.marker.copy(alpha = 0.3f) else c.marker
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val reduced = LocalReducedMotion.current
    val sweep = remember(text) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(sweep) { sweep.animateTo(1f, tween(460 + 60 * text.length.coerceAtMost(8), delayMillis, StudyMotion.Emphasized)) }
    Text(text, modifier.drawBehind {
        val result = layout ?: return@drawBehind
        val pad = 4.dp.toPx()
        val total = (0 until result.lineCount).sumOf { (result.getLineRight(it) - result.getLineLeft(it) + 2 * pad).toDouble() }.toFloat()
        var budget = total * sweep.value
        for (line in 0 until result.lineCount) {
            if (budget <= 0f) break
            val top = result.getLineTop(line)
            val height = result.getLineBottom(line) - top
            val left = result.getLineLeft(line)
            val width = minOf(result.getLineRight(line) - left + 2 * pad, budget)
            budget -= width
            drawRoundRect(marker, Offset(left - pad, top + height * 0.46f), Size(width, height * 0.44f), CornerRadius(2.dp.toPx()))
        }
    }, color = color, style = style, onTextLayout = { layout = it })
}

/** Grows from empty when first shown, then follows [progress]. */
@Composable
fun Bar(progress: Float, modifier: Modifier = Modifier, color: Color = Study.colors.ink, track: Color = Study.colors.sunken, height: Dp = 6.dp) {
    val target = progress.coerceIn(0f, 1f)
    val value = rememberGrowth(target, 520)
    Canvas(modifier.fillMaxWidth().height(height).progressSemantics(target)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val shown = value.value
        if (shown > 0f) drawRoundRect(color, size = Size(size.width * shown, size.height), cornerRadius = radius)
    }
}

/** Animates from zero on first composition and between later values; instant when motion is reduced. */
@Composable
internal fun rememberGrowth(target: Float, durationMillis: Int): State<Float> {
    val reduced = LocalReducedMotion.current
    val value = remember { Animatable(if (reduced) target else 0f) }
    LaunchedEffect(target, reduced) {
        if (reduced) value.snapTo(target) else value.animateTo(target, tween(durationMillis, easing = StudyMotion.Emphasized))
    }
    return value.asState()
}

@Composable
fun Ring(progress: Float, modifier: Modifier = Modifier, color: Color = Study.colors.ink, track: Color = Study.colors.sunken, width: Dp = 5.dp) {
    val target = progress.coerceIn(0f, 1f)
    val animated = rememberGrowth(target, 760)
    Canvas(modifier.progressSemantics(target)) {
        val value = animated.value
        val stroke = width.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (value > 0f) drawArc(color, -90f, 360f * value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

enum class Mark { Todo, Now, Done, Right, Wrong, Open }

/**
 * One segment per step, coloured by the saved outcome of that step. A segment cross-fades when its outcome is
 * saved; with [reveal] the track also wipes in from the left on first composition.
 */
@Composable
fun StepTrack(marks: List<Mark>, modifier: Modifier = Modifier, onHero: Boolean = false, reveal: Boolean = false) {
    val c = Study.colors
    val colors = marks.mapIndexed { index, mark ->
        key(index) {
            animateColorAsState(if (onHero) when (mark) {
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
            }, StudyMotion.fade(280), label = "step")
        }
    }
    val wipe = if (reveal) rememberGrowth(1f, 200 + 30 * marks.size.coerceAtMost(20)) else null
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val count = marks.size.coerceAtLeast(1)
        val gap = (if (count > 24) 1.dp else 3.dp).toPx()
        val width = (size.width - gap * (count - 1)) / count
        val limit = size.width * (wipe?.value ?: 1f)
        colors.forEachIndexed { index, color ->
            val left = index * (width + gap)
            if (left >= limit) return@forEachIndexed
            drawRoundRect(color.value, Offset(left, 0f), Size(minOf(width, limit - left), size.height), CornerRadius(size.height / 2))
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

/** A [Stat] whose number counts up to [value]. */
@Composable
fun RowScope.StatCount(value: Int, label: String, modifier: Modifier = Modifier, format: (Int) -> String = { "$it" }) {
    Column(modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CountUp(value, StudyType.Numeral, format = format)
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
