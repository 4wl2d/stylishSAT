package com.tomilov.stylishsat.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tomilov.stylishsat.ui.theme.LocalReducedMotion
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * Every effect here runs in the draw or layer phase, so animating never re-lays out a screen. Entrances play
 * once per composition; pops and shakes play on a change, not when a finished state is merely shown again.
 * Nothing loops on an idle screen, which keeps the frame clock quiet and the battery cool.
 */

/**
 * Fades and lifts content into place; [order] staggers siblings by a few frames each. [enabled] is read once, so
 * list items that scroll in later can opt out without cutting short an entrance already playing.
 */
fun Modifier.rise(order: Int = 0, distance: Dp = 16.dp, enabled: Boolean = true): Modifier = composed {
    val active = remember { enabled }
    if (!active || LocalReducedMotion.current) return@composed Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        delay(StudyMotion.StaggerMillis * order.coerceIn(0, StudyMotion.MaxStagger))
        progress.animateTo(1f, StudyMotion.settle())
    }
    Modifier.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * distance.toPx()
    }
}

/** Scales content in from [from] with a small overshoot after [delayMillis]; for dots, cells and badges. */
fun Modifier.popIn(delayMillis: Long = 0, from: Float = 0.6f): Modifier = composed {
    if (LocalReducedMotion.current) return@composed Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        delay(delayMillis)
        progress.animateTo(1f, StudyMotion.bounce())
    }
    Modifier.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        val scale = from + (1f - from) * p
        scaleX = scale; scaleY = scale
    }
}

/** A quick scale pop each time [key] changes to a new non-null value after the first composition. */
fun Modifier.pop(key: Any?, from: Float = 0.8f): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (key == null || reduced) return@LaunchedEffect
        scale.snapTo(from)
        scale.animateTo(1f, StudyMotion.bounce())
    }
    Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** A short head-shake each time [trigger] changes to a new non-null value after the first composition. */
fun Modifier.shake(trigger: Any?): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val offset = remember { Animatable(0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (trigger == null || reduced) return@LaunchedEffect
        offset.animateTo(0f, keyframes {
            durationMillis = 420
            -10f at 45; 9f at 110; -7f at 175; 5f at 240; -2f at 310
        })
    }
    Modifier.graphicsLayer { translationX = offset.value * density }
}

/** Rings spreading from a live control, such as the record button. Runs only while [active]. */
fun Modifier.pulseRing(active: Boolean, color: Color): Modifier = composed {
    if (!active || LocalReducedMotion.current) return@composed Modifier
    val t by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulseT")
    Modifier.drawBehind {
        val radius = size.minDimension / 2
        for (ring in 0..1) {
            val p = (t + ring * 0.5f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.35f), radius * (1f + 0.55f * p))
        }
    }
}

/** A small level meter that moves while audio plays and rests as still bars otherwise. */
@Composable
fun Waves(playing: Boolean, modifier: Modifier = Modifier, color: Color = Study.colors.ink) {
    val live = playing && !LocalReducedMotion.current
    val phase = if (live) rememberInfiniteTransition(label = "waves")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "wavesT") else null
    Canvas(modifier.size(20.dp)) {
        val bars = 4
        val width = size.width / (bars * 2 - 1)
        for (bar in 0 until bars) {
            val level = phase?.let { (sin((it.value + bar * 0.27f) * 2f * PI.toFloat()) + 1f) / 2f } ?: listOf(0.35f, 0.7f, 0.5f, 0.85f)[bar]
            val height = size.height * (0.25f + 0.75f * level)
            drawRoundRect(color, Offset(bar * 2 * width, size.height - height), Size(width, height), CornerRadius(width / 2))
        }
    }
}

/** A number that counts to its value; screen readers hear only the final value. */
@Composable
fun CountUp(
    value: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Study.colors.ink,
    format: (Int) -> String = { "$it" },
) {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(if (reduced) value.toFloat() else 0f) }
    LaunchedEffect(value, reduced) {
        if (reduced) shown.snapTo(value.toFloat())
        else shown.animateTo(value.toFloat(), tween((280 + abs(value - shown.value.roundToInt()).coerceAtMost(50) * 10), easing = StudyMotion.Emphasized))
    }
    val final = format(value)
    Text(format(shown.value.roundToInt()), modifier.clearAndSetSemantics { contentDescription = final }, style = style, color = color, maxLines = 1)
}

private class Piece(
    val angle: Float, val speed: Float, val spin: Float, val turn: Float,
    val width: Float, val height: Float, val color: Int, val sway: Float, val phase: Float, val round: Boolean,
)

/**
 * A confetti burst drawn in one Canvas. It plays when [trigger] becomes non-null and is skipped entirely when
 * motion is reduced. Decorative only: no semantics and no touch handling.
 */
@Composable
fun Burst(
    trigger: Any?, modifier: Modifier = Modifier, count: Int = 40, spread: Float = 0.75f, power: Float = 1f,
    originX: Float = 0.5f, originY: Float = 0.3f, lifetime: Float = 1.8f,
) {
    if (trigger == null || LocalReducedMotion.current) return
    val c = Study.colors
    val palette = listOf(c.marker, c.good, c.warn, c.ink, c.bad, c.inkSoft)
    val pieces = remember(trigger) {
        val random = Random(trigger.hashCode())
        List(count) {
            // Mostly upward, fanning out to both sides.
            Piece(
                angle = (-PI / 2 + (random.nextFloat() - 0.5f) * PI * 0.9f * spread).toFloat(),
                speed = (420f + random.nextFloat() * 480f) * power,
                spin = (random.nextFloat() - 0.5f) * 900f, turn = random.nextFloat() * 360f,
                width = 5f + random.nextFloat() * 4f, height = 8f + random.nextFloat() * 7f,
                color = random.nextInt(palette.size), sway = 6f + random.nextFloat() * 14f,
                phase = random.nextFloat() * 6.28f, round = random.nextFloat() < 0.3f,
            )
        }
    }
    var seconds by remember(trigger) { mutableFloatStateOf(0f) }
    var running by remember(trigger) { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        val start = withFrameNanos { it }
        while (seconds < lifetime) withFrameNanos { seconds = (it - start) / 1e9f }
        running = false
    }
    if (!running) return
    Canvas(modifier) {
        val t = seconds
        val origin = Offset(size.width * originX, size.height * originY)
        val drag = 2.2f
        val fall = 600f * power // terminal velocity, dp/s
        val fade = 1f - ((t - lifetime * 0.6f) / (lifetime * 0.4f)).coerceIn(0f, 1f)
        pieces.forEach { p ->
            val vx = cos(p.angle) * p.speed
            val vy = sin(p.angle) * p.speed
            val decay = (1f - exp(-drag * t)) / drag
            val x = vx * decay + sin(t * 7f + p.phase) * p.sway * t
            val y = (vy - fall) * decay + fall * t
            val center = origin + Offset(x.dp.toPx(), y.dp.toPx())
            if (center.y > size.height + 20f) return@forEach
            rotate(p.turn + p.spin * t, center) {
                val w = p.width.dp.toPx()
                val h = if (p.round) w else p.height.dp.toPx() * abs(cos(t * 6f + p.phase)).coerceAtLeast(0.25f)
                drawRoundRect(palette[p.color].copy(alpha = palette[p.color].alpha * fade), center - Offset(w / 2, h / 2), Size(w, h),
                    CornerRadius(if (p.round) w / 2 else 1.5.dp.toPx()))
            }
        }
    }
}

/**
 * Predictive back: the page shrinks and leans with the gesture, then leaves from where the gesture ended instead
 * of snapping back first. Android 14+ sends progress; older versions simply navigate back.
 */
@Stable
class BackGesture {
    internal var progress by mutableFloatStateOf(0f)
    internal var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
    internal var page by mutableStateOf<Any?>(null)
    /** Gesture progress of the page it started on; zero when no gesture is in flight. */
    val depth: Float get() = if (page != null) progress else 0f
}

@Composable
fun rememberBackGesture(): BackGesture = remember { BackGesture() }

@Composable
fun PredictiveBack(gesture: BackGesture, page: Any, enabled: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled) { events ->
        gesture.page = page
        try {
            events.collect { event ->
                gesture.edge = event.swipeEdge
                gesture.progress = event.progress
            }
            onBack()
        } catch (cancelled: CancellationException) {
            scope.launch {
                animate(gesture.progress, 0f, animationSpec = StudyMotion.settle()) { value, _ -> gesture.progress = value }
                if (gesture.page == page) gesture.page = null
            }
            throw cancelled
        }
    }
}

/** Applies the gesture to the page it started on, and releases it once that page has left the screen. */
fun Modifier.followBack(gesture: BackGesture, page: Any): Modifier = composed {
    DisposableEffect(gesture, page) {
        onDispose { if (gesture.page == page) { gesture.page = null; gesture.progress = 0f } }
    }
    Modifier.graphicsLayer {
        if (gesture.page != page) return@graphicsLayer
        val p = gesture.progress
        val scale = 1f - 0.1f * p
        scaleX = scale; scaleY = scale
        val lean = 8.dp.toPx() * p
        translationX = if (gesture.edge == BackEventCompat.EDGE_RIGHT) -lean else lean
        shape = RoundedCornerShape((28 * p).dp)
        clip = p > 0f
    }
}
