package com.tomilov.stylishsat.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * One motion vocabulary. Touch feedback tracks the finger, content settles with a short spring and fades stay
 * under 200 ms, so nothing waits on an animation. Compose already scales finite animations by the system
 * animator scale; [LocalReducedMotion] also removes decorative motion and turns slides into fades.
 */
object StudyMotion {
    /** Press feedback: follows the finger without wobble. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1800f)
    /** Release, selection and small pops: one quick overshoot. */
    fun <T> bounce(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = 650f)
    /** Content moving into place: fast and barely overshooting. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 520f)
    fun <T> fade(duration: Int = 160, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, Emphasized)

    val page: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 720f, visibilityThreshold = IntOffset.VisibilityThreshold)
    val size: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntSize.VisibilityThreshold)
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Entrance stagger per item, and the item after which the rest arrive together. */
    const val StaggerMillis = 32L
    const val MaxStagger = 8
}

/** True when the system removes animations or the learner asked for calmer motion. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Follows Settings → Accessibility → Remove animations while the app is open. */
@Composable
fun rememberSystemAnimationsOff(): Boolean {
    val context = LocalContext.current
    var off by remember { mutableStateOf(animationsOff(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { off = animationsOff(context) }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return off
}

private fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
