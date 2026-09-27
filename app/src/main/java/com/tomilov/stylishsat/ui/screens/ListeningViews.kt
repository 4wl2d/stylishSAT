package com.tomilov.stylishsat.ui.screens

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.delay

/** One practice recording: position, speed and an optional loop over the whole clip or a selected transcript segment. */
@Stable
class ClipPlayer(private val context: Context, val assetPath: String, startMs: Long = 0, private val report: (Long, Boolean) -> Unit = { _, _ -> }) {
    var playing by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(startMs); private set
    var durationMs by mutableLongStateOf(0L); private set
    var speed by mutableFloatStateOf(1f); private set
    var loop by mutableStateOf(false); private set
    var segment by mutableStateOf<TranscriptSegment?>(null); private set
    var error by mutableStateOf(""); private set
    private var player: MediaPlayer? = null

    private fun ensure(): MediaPlayer? = player ?: try {
        MediaPlayer().also { created ->
            context.assets.openFd(assetPath).use { created.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            created.setOnCompletionListener { finished ->
                val range = segment
                if (loop) { finished.seekTo((range?.startMs ?: 0L).toInt()); applySpeed(finished); finished.start(); return@setOnCompletionListener }
                playing = false; positionMs = durationMs; report(durationMs, true)
            }
            created.setOnErrorListener { _, _, _ -> error = "Audio could not play."; playing = false; true }
            created.prepare()
            durationMs = created.duration.toLong()
            created.seekTo(positionMs.coerceIn(0, durationMs).toInt())
            player = created
        }
    } catch (failure: Exception) { error = failure.message ?: "Audio unavailable"; null }

    /** Changing speed on a paused player can start it on some devices, so speed is applied only when playing. */
    private fun applySpeed(target: MediaPlayer) {
        runCatching { target.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
    }

    fun play() {
        val target = ensure() ?: return
        if (positionMs >= durationMs && durationMs > 0) target.seekTo((segment?.startMs ?: 0L).toInt())
        target.start(); applySpeed(target); playing = true
    }

    fun pause() {
        val target = player ?: return
        if (target.isPlaying) target.pause()
        playing = false; positionMs = target.currentPosition.toLong(); report(positionMs, false)
    }

    fun seek(ms: Long) {
        val target = ensure() ?: return
        positionMs = ms.coerceIn(0, durationMs); target.seekTo(positionMs.toInt()); report(positionMs, false)
    }

    fun rewind(seconds: Int = 10) = seek((player?.currentPosition?.toLong() ?: positionMs) - seconds * 1000L)

    fun changeSpeed(value: Float) { speed = value; player?.let { if (playing) applySpeed(it) } }

    fun toggleLoop() { loop = !loop; if (!loop) segment = null }

    /** Play from a transcript segment; while looping, that segment repeats. */
    fun playSegment(selected: TranscriptSegment) { segment = selected; seek(selected.startMs); play() }

    /** Called once a tick while playing: tracks position and closes the segment loop. */
    fun poll() {
        val target = player ?: return
        positionMs = target.currentPosition.toLong()
        val range = segment
        if (loop && range != null && positionMs >= range.endMs) target.seekTo(range.startMs.toInt())
        report(positionMs, false)
    }

    fun release() {
        player?.let { if (playing) report(it.currentPosition.toLong(), false); it.release() }
        player = null; playing = false
    }
}

@Composable
fun rememberClipPlayer(assetPath: String, startMs: Long = 0, report: (Long, Boolean) -> Unit = { _, _ -> }): ClipPlayer {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(assetPath) { ClipPlayer(context.applicationContext, assetPath, startMs, report) }
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.release() }
    }
    if (player.playing) LaunchedEffect(player) { while (true) { delay(250); player.poll() } }
    return player
}

/** Practice controls: play, rewind, speed and loop. The synthetic, unreviewed status stays visible. */
@Composable
fun PracticePlayer(player: ClipPlayer, l: Language, detail: String? = null) {
    val c = Study.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(if (player.playing) Glyph.Pause else Glyph.Play, if (player.playing) l.label("Pause audio", "Пауза") else l.label("Play audio", "Слушать"),
                { if (player.playing) player.pause() else player.play() }, tint = c.paper, background = c.ink, size = 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Meta(l.label("Listening · synthetic voices · expert review pending", "Listening · синтетические голоса · экспертная проверка ожидается"))
                Text(detail ?: l.label("Transcript opens after you answer.", "Транскрипт откроется после ответа."), style = StudyType.Small, color = c.inkSoft)
                if (player.error.isNotEmpty()) Text(l.label("Audio could not play. Try again.", "Не удалось воспроизвести аудио."), style = StudyType.Small, color = c.bad)
            }
            GlyphButton(Glyph.ArrowLeft, l.label("Back 10 seconds", "Назад на 10 секунд"), { player.rewind() })
        }
        if (player.durationMs > 0) {
            Bar((player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f), height = 4.dp)
            Text("${clock((player.positionMs / 1000).toInt())} / ${clock((player.durationMs / 1000).toInt())}", style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(0.75f, 1f, 1.25f).forEach { value ->
                val on = player.speed == value
                Text(if (value == 1f) "1×" else "$value×", Modifier.tapSurface(RoundedCornerShape(50), if (on) c.ink else c.paper, border = BorderStroke(1.dp, if (on) c.ink else c.line), role = Role.RadioButton) {
                    player.changeSpeed(value)
                }.semantics { selected = on; contentDescription = l.label("Speed $value", "Скорость $value") }.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = StudyType.Mono.copy(fontSize = 13.sp), color = if (on) c.paper else c.ink)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.tapSurface(RoundedCornerShape(50), if (player.loop) c.ink else c.paper, border = BorderStroke(1.dp, if (player.loop) c.ink else c.line), role = Role.Switch) {
                player.toggleLoop()
            }.semantics { selected = player.loop }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Loop, tint = if (player.loop) c.paper else c.ink, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(when {
                    !player.loop -> l.label("Loop", "Повтор")
                    player.segment != null -> l.label("Looping segment", "Повтор фрагмента")
                    else -> l.label("Looping clip", "Повтор записи")
                }, style = StudyType.Button.copy(fontSize = 13.sp), color = if (player.loop) c.paper else c.ink)
            }
        }
    }
}

/** Transcript with speaker names and clickable start times; tapping a line plays from there (and loops it when loop is on). */
@Composable
fun TranscriptTimes(player: ClipPlayer?, segments: List<TranscriptSegment>, l: Language) {
    val c = Study.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (player != null) Meta(l.label("Tap a time to replay that part.", "Нажмите на время, чтобы переслушать фрагмент."))
        segments.forEach { segment ->
            val current = player != null && player.positionMs in segment.startMs until segment.endMs
            Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(10.dp), if (current) c.sunken else c.paper, enabled = player != null) { player?.playSegment(segment) }
                .padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Text(clock((segment.startMs / 1000).toInt()), Modifier.width(48.dp), style = StudyType.Mono.copy(fontSize = 13.sp), color = if (player != null) c.ink else c.inkSoft)
                Column(Modifier.weight(1f)) {
                    segment.speaker?.let { Text(it, style = StudyType.Strong.copy(fontSize = 13.sp), color = c.ink) }
                    Text(segment.text, style = StudyType.Small.copy(fontSize = 15.sp, lineHeight = 22.sp), color = c.ink)
                }
            }
        }
    }
}
