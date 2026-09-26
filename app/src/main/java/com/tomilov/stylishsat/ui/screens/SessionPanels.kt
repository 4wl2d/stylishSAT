package com.tomilov.stylishsat.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.ai.*
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.speech.RecorderState
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ListeningPlayer(assetPath: String, l: Language, sample: Boolean = false) {
    val c = Study.colors
    val context = LocalContext.current
    var playerValue by remember(assetPath) { mutableStateOf<MediaPlayer?>(null) }
    var playingValue by remember(assetPath) { mutableStateOf(false) }
    var errorValue by remember(assetPath) { mutableStateOf("") }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(assetPath, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { playerValue?.pause(); playingValue = false } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); playerValue?.release(); playerValue = null }
    }
    val toggle = {
        try {
            if (playingValue) { playerValue?.pause(); playingValue = false }
            else {
                if (playerValue == null) {
                    val player = MediaPlayer()
                    context.assets.openFd(assetPath).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                    player.setOnCompletionListener { playingValue = false }
                    player.setOnErrorListener { _, _, _ -> errorValue = l.label("Audio could not play. Try again.", "Не удалось воспроизвести аудио."); playingValue = false; true }
                    player.prepare(); playerValue = player
                }
                playerValue?.start(); playingValue = true
            }
        } catch (error: Exception) { errorValue = error.message ?: "Audio unavailable" }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(if (playingValue) Glyph.Pause else Glyph.Play, if (playingValue) l.label("Pause audio", "Пауза") else l.label("Play audio", "Слушать"),
            toggle, tint = c.paper, background = c.ink, size = 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Meta(if (sample) l.label("Speaking · synthetic sample", "Speaking · синтетический образец") else l.label("Listening · synthetic audio", "Listening · синтетическая запись"))
            Text(if (sample) l.label("Unreviewed sample, not a band exemplar.", "Образец без экспертной проверки, не эталон band.")
                else l.label("Transcript opens after you answer.", "Транскрипт откроется после ответа."), style = StudyType.Small, color = c.inkSoft)
            if (errorValue.isNotEmpty()) Text(errorValue, style = StudyType.Small, color = c.bad)
        }
        GlyphIcon(Glyph.Headphones, tint = c.inkFaint, size = 20.dp)
    }
}

@Composable
fun Chart(chart: ChartData) {
    val c = Study.colors
    Block {
        Text(chart.title, style = StudyType.Strong, color = c.ink)
        Meta("${chart.xLabel} · ${chart.yLabel} (${chart.unit})")
        val maximum = chart.series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val shades = listOf(c.ink, c.inkSoft, c.inkFaint, c.good)
        if (chart.series.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            chart.series.forEachIndexed { index, series ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(shades[index % shades.size]))
                    Spacer(Modifier.width(6.dp))
                    Text(series.name, style = StudyType.Small, color = c.ink)
                }
            }
        }
        chart.labels.forEachIndexed { index, label ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, style = StudyType.Strong.copy(fontSize = 14.sp), color = c.ink)
                chart.series.forEachIndexed { seriesIndex, series ->
                    val value = series.values.getOrNull(index) ?: 0.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Bar((value / maximum).toFloat(), Modifier.weight(1f), color = shades[seriesIndex % shades.size], height = 10.dp)
                        Text("${if (value % 1 == 0.0) value.toInt() else value}", Modifier.width(56.dp).padding(start = 8.dp), style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
                    }
                }
            }
        }
    }
}

@Composable
fun SpeakingPanel(s: StudyUiState, vm: StudyViewModel) {
    val context = LocalContext.current
    val l = s.language
    val c = Study.colors
    val scope = rememberCoroutineScope()
    val recorderValue by vm.runtime.recorder.state.collectAsStateWithLifecycle()
    val stepKey = s.session?.stepKey
    var messageValue by remember(stepKey) { mutableStateOf("") }
    var transcribingValue by remember(stepKey) { mutableStateOf(false) }
    var recognizedValue by remember(stepKey) { mutableStateOf<String?>(null) }
    val beginRecording = {
        if (vm.state.value.session?.let { it.stepKey == stepKey && it.result == null && !it.finished } == true) {
            runCatching { vm.runtime.recorder.start() }.onSuccess { vm.attachRecording(it.absolutePath, stepKey); messageValue = "" }.onFailure { messageValue = it.message ?: "Recording failed" }
        }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording() else messageValue = l.label("Microphone access was denied. You can type a transcript and keep practising.", "Микрофон недоступен. Можно ввести транскрипт и продолжить практику.")
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.stopRecording() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.stopRecording(); vm.runtime.playback.stop() }
    }
    val active = recorderValue as? RecorderState.RecordingAudio
    val takes = s.session?.recordingPaths.orEmpty().ifEmpty { listOfNotNull(s.session?.recordingPath) }
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.session?.result == null) GlyphButton(if (active != null) Glyph.Stop else Glyph.Mic,
                if (active != null) l.label("Stop recording", "Остановить запись") else l.label("Record", "Записать"), {
                    if (active != null) scope.launch { vm.runtime.recorder.stop()?.let { vm.attachRecording(it.file.absolutePath, stepKey) } }
                    else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }, tint = if (active != null) androidx.compose.ui.graphics.Color.White else c.paper, background = if (active != null) c.bad else c.ink,
                size = 60.dp, enabled = !transcribingValue, filled = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (active != null) l.label("Recording · ${active.elapsedMillis / 1000}s", "Запись · ${active.elapsedMillis / 1000} с")
                    else if (takes.isEmpty()) l.label("Record one answer", "Запишите один ответ") else l.label("${takes.size} take(s) saved", "Сохранено записей: ${takes.size}"),
                    style = StudyType.Strong, color = if (active != null) c.bad else c.ink)
                Text(l.label("Each take is kept. Pick one to transcribe.", "Каждая запись сохраняется. Выберите одну для расшифровки."), style = StudyType.Small, color = c.inkSoft)
            }
        }
        if (takes.isNotEmpty() && active == null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            takes.forEachIndexed { index, path ->
                val chosen = s.session?.recordingPath == path
                Row(Modifier.tapSurface(RoundedCornerShape(50), if (chosen) c.ink else c.sunken) {
                    vm.runtime.playback.play(File(path), onError = { messageValue = it })
                    if (s.session?.result == null && !transcribingValue) vm.attachRecording(path, stepKey)
                }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Play, tint = if (chosen) c.paper else c.ink, size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(l.label("Take ${index + 1}", "Запись ${index + 1}"), style = StudyType.Button.copy(fontSize = 14.sp), color = if (chosen) c.paper else c.ink)
                }
            }
        }
        s.session?.recordingPath?.let { path ->
            if (active == null && s.session!!.result == null) StudyButton(if (transcribingValue) l.label("Transcribing…", "Распознавание…") else l.label("Transcribe selected take offline", "Распознать выбранную запись офлайн"), {
                scope.launch {
                    transcribingValue = true
                    try {
                        val originalDraft = s.session!!.draft
                        val transcript = vm.runtime.speechTranscriber.transcribe(File(path)).text
                        if (vm.state.value.session?.draft == originalDraft && originalDraft.isBlank() && vm.state.value.session?.stepKey == s.session!!.stepKey) {
                            vm.applyTranscript(transcript, s.session!!.stepKey)
                            messageValue = l.label("Review and correct the transcript before submitting.", "Проверьте и исправьте транскрипт перед отправкой.")
                        } else if (vm.state.value.session?.stepKey == stepKey) {
                            recognizedValue = transcript
                            messageValue = l.label("Your existing text is preserved. Review this take before adding it.", "Текущий текст сохранён. Проверьте расшифровку этой записи перед добавлением.")
                        }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { messageValue = runtimeMessage(error.message ?: "Transcription failed", l) }
                    finally { transcribingValue = false }
                }
            }, Modifier.fillMaxWidth(), tone = Tone.Quiet, compact = true, enabled = !transcribingValue)
        }
        recognizedValue?.takeIf { s.session?.result == null }?.let { transcript ->
            MarginNote {
                SelectionContainer { Text(transcript, style = StudyType.Body, color = c.ink) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudyButton(l.label("Add to answer", "Добавить к ответу"), {
                    val current = vm.state.value.session?.takeIf { it.stepKey == stepKey }?.draft.orEmpty()
                    vm.applyTranscript(listOf(current.trimEnd(), transcript).filter { it.isNotBlank() }.joinToString("\n\n"), stepKey)
                    recognizedValue = null
                }, Modifier.weight(1f), compact = true)
                StudyButton(l.label("Replace", "Заменить"), { vm.applyTranscript(transcript, stepKey); recognizedValue = null }, Modifier.weight(1f), tone = Tone.Line, compact = true)
            }
        }
        if (messageValue.isNotBlank()) Text(messageValue, style = StudyType.Small, color = c.ink)
        Text(l.label("Replay your take for clarity, stress and rhythm. A transcript can't measure pronunciation.", "Прослушайте запись: понятность, ударения, ритм. По транскрипту нельзя оценить произношение."),
            style = StudyType.Small, color = c.inkSoft)
    }
}

private fun tutorRequest(s: StudyUiState): TutorRequest {
    val exercise = s.session!!.exercise!!
    return TutorRequestFactory.create(exercise, s.session!!.draft, s.language)
}

@Composable
fun FeedbackPanel(s: StudyUiState, vm: StudyViewModel) {
    val l = s.language
    val c = Study.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accelerationValue by vm.runtime.acceleration.state.collectAsStateWithLifecycle()
    var outputValue by rememberSaveable(s.session!!.id, s.session!!.index) { mutableStateOf("") }
    var busyValue by remember { mutableStateOf(false) }
    var previewValue by remember { mutableStateOf(false) }
    var externalValue by rememberSaveable(s.session!!.id, s.session!!.index) { mutableStateOf(s.session!!.externalFeedback) }
    val request = tutorRequest(s)
    Disclosure(l.label("More help", "Дополнительная помощь"), null, initiallyOpen = outputValue.isNotBlank()) {
        Text(l.label("AI can be wrong. Compare it with the key and explanation. It never changes your result or level.",
            "ИИ может ошибаться: сверяйте с ключом и разбором. Он не меняет результат или уровень."), style = StudyType.Small, color = c.inkSoft)
        StudyButton(if (busyValue) l.label("Working locally…", "Локальный разбор…") else l.label("Explain with local AI", "Разобрать с локальным ИИ"), {
            scope.launch {
                busyValue = true; outputValue = ""
                try {
                    val lessons = vm.contentRepository.contextFor(s.session!!.exercise!!.skillId, s.session!!.exercise!!.prompt)
                    vm.runtime.tutorEngine.explain(request.copy(excerpts = request.excerpts + lessons.map { TutorExcerpt(it.id, it.body.text(l) + "\n" + it.workedExample.text(l)) })).collect { event ->
                        when (event) {
                            TutorEvent.Loading -> outputValue = l.label("Loading local model…\n", "Загрузка локальной модели…\n")
                            is TutorEvent.Text -> {
                                if (outputValue == l.label("Loading local model…\n", "Загрузка локальной модели…\n")) outputValue = ""
                                outputValue += event.delta
                            }
                            is TutorEvent.Complete -> vm.saveFeedback(outputValue, "LOCAL_AI", s.session!!.stepKey)
                            is TutorEvent.Failure -> outputValue += "\n${runtimeMessage(event.message, l)}"
                            is TutorEvent.Unavailable -> outputValue = runtimeMessage(event.reason, l)
                            is TutorEvent.TooLong -> outputValue = l.label("Your complete answer exceeds the local context budget. Edit it or use the ChatGPT preview; nothing was silently cut.", "Полный ответ превышает локальный контекст. Сократите его сами или откройте запрос для ChatGPT. Скрытой обрезки нет.")
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { outputValue = error.message ?: "Feedback unavailable" }
                finally { busyValue = false }
            }
        }, Modifier.fillMaxWidth(), tone = Tone.Ink, compact = true, glyph = Glyph.Spark, enabled = !busyValue && accelerationValue !is LocalAccelerationState.Preparing)
        if (accelerationValue is LocalAccelerationState.Preparing) Text(l.label("Local setup is in progress. The prepared explanation above is available now.", "Идёт локальная подготовка. Готовый разбор выше уже доступен."), style = StudyType.Small, color = c.inkSoft)
        if (outputValue.isNotBlank()) MarginNote(rule = c.inkSoft) { SelectionContainer { Text(outputValue, style = StudyType.Body, color = c.ink) } }
        StudyButton(l.label("Ask ChatGPT yourself ↗", "Спросить ChatGPT самостоятельно ↗"), { previewValue = true }, Modifier.fillMaxWidth(), tone = Tone.Line, compact = true)
        OutlinedTextField(externalValue, { externalValue = it }, label = { Text(l.label("Paste feedback you received (optional)", "Вставьте полученный отзыв (необязательно)")) },
            minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = studyFieldColors())
        if (externalValue.isNotBlank()) StudyButton(l.label("Save as external feedback", "Сохранить как внешний отзыв"), { vm.saveFeedback(externalValue) }, tone = Tone.Quiet, compact = true)
    }
    if (previewValue) AlertDialog(onDismissRequest = { previewValue = false }, containerColor = c.paper,
        title = { Text(l.label("Review before sharing", "Текст перед передачей"), style = StudyType.Title, color = c.ink) }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(l.label("Use your own ChatGPT account. Copy or share it; you send the request yourself.", "Используйте свой аккаунт ChatGPT. Скопируйте или поделитесь; запрос отправляете вы сами."), style = StudyType.Small, color = c.inkSoft)
                SelectionContainer { Text(ChatGptHandoff.preview(request), style = StudyType.Mono.copy(fontSize = 12.sp, lineHeight = 17.sp), color = c.ink) }
            }
        }, confirmButton = { TextButton(onClick = { ChatGptHandoff.copy(context, ChatGptHandoff.preview(request)); previewValue = false }) { Text(l.label("Copy", "Копировать"), style = StudyType.Button, color = c.ink) } },
        dismissButton = { TextButton(onClick = { ChatGptHandoff.share(context, ChatGptHandoff.preview(request)) }) { Text(l.label("Share…", "Поделиться…"), style = StudyType.Button, color = c.ink) } })
}

internal fun runtimeMessage(message: String, language: Language): String {
    if (language == Language.EN) return message
    return when {
        "8 GB" in message -> "Для локального ИИ нужны ARM64 и 8+ ГБ RAM. Уроки, запись и ручной транскрипт доступны на этом устройстве."
        "Download Gemma" in message -> "Сначала скачайте Gemma в настройках. Сейчас доступны готовый разбор и запрос для ChatGPT."
        "Download Whisper" in message -> "Сначала скачайте Whisper в настройках. Запись сохранена; транскрипт можно ввести вручную."
        "free RAM" in message -> "Недостаточно свободной памяти. Закройте другие приложения или используйте готовый разбор."
        "Microphone" in message -> "Не удалось использовать микрофон. Можно ввести транскрипт вручную и продолжить."
        else -> "Не удалось завершить локальную обработку. Ваш ответ остаётся в редакторе. $message"
    }
}
