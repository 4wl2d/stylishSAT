package com.tomilov.stylishsat.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.delay

/** A passage or recording with all of its questions on one page, one clock and one playback position. */
@Composable
fun PaperScreen(s: StudyUiState, vm: StudyViewModel, runId: String, close: () -> Unit) {
    val run = s.papers[runId]
    if (run == null) { LaunchedEffect(runId) { close() }; return }
    val c = Study.colors
    Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding().imePadding()) {
        if (run.active && run.phase != PaperPhase.FINISHED) PaperWorking(s, vm, run, close) else PaperResults(s, run, close)
    }
}

@Composable
private fun PaperClock(run: PaperRun, part: PaperPart) {
    val c = Study.colors
    val remaining = run.remaining(part)
    val (text, color) = when {
        remaining == null -> clock(run.elapsed(part)) to c.inkSoft
        remaining >= 0 -> clock(remaining) to if (remaining <= 60) c.bad else c.ink
        else -> "+" + clock(-remaining) to c.warn
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.Clock, tint = color, size = 15.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, style = StudyType.Mono, color = color)
    }
}

@Composable
private fun ColumnScope.PaperWorking(s: StudyUiState, vm: StudyViewModel, run: PaperRun, close: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val part = run.part ?: return
    val lifecycle = LocalLifecycleOwner.current
    val timeUp = run.timeUp(part)
    if (!timeUp) LaunchedEffect(run.id, part.id, run.phase, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(1000); vm.paperTick(run.id, part.id) }
        }
    }
    var menuValue by remember { mutableStateOf(false) }
    var confirmValue by remember { mutableStateOf(false) }
    var discardValue by remember { mutableStateOf(false) }
    var tabValue by rememberSaveable(run.id, part.id) { mutableIntStateOf(if (part.passage != null) 0 else 1) }
    val numbers = remember(part) { Sections.numbers(part.exercises) }
    val answered = run.answered(part)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyph.Close, l.label("Close and keep answers", "Закрыть с сохранением"), close)
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(part.title, style = StudyType.Strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Meta(listOf(if (run.strict) l.label("Exam conditions", "Экзаменационный режим") else l.label("Section practice", "Практика секции"),
                l.label("$answered/${part.exercises.size} answered", "отвечено $answered/${part.exercises.size}")).joinToString(" · "), maxLines = 1)
        }
        PaperClock(run, part)
        Box {
            GlyphButton(Glyph.More, l.label("More", "Ещё"), { menuValue = true })
            DropdownMenu(menuValue, { menuValue = false }, containerColor = c.raised) {
                DropdownMenuItem(text = { Text(l.label("Leave without marking", "Выйти без проверки"), style = StudyType.Body) },
                    onClick = { menuValue = false; discardValue = true })
            }
        }
    }
    if (part.passage != null) Segmented(listOf(0 to l.label("Passage", "Текст"), 1 to l.label("Questions", "Вопросы")), tabValue, { tabValue = it },
        Modifier.fillMaxWidth().padding(horizontal = 16.dp), fill = true)
    val passageScroll = rememberScrollState()
    val questionScroll = rememberScrollState()
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(if (tabValue == 0) passageScroll else questionScroll)
        .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (tabValue == 0 && part.passage != null) {
            Meta(l.label("${AnswerChecker.wordCount(part.passage!!)} words", "${AnswerChecker.wordCount(part.passage!!)} слов"))
            SelectionContainer { Text(part.passage!!, style = StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 28.sp), color = c.ink) }
        } else {
            if (timeUp) Block(color = c.badSoft) {
                Text(l.label("Time's up. Your answers are saved.", "Время вышло. Ответы сохранены."), style = StudyType.Title, color = c.ink)
                Text(l.label("Submit now, or keep working: the extra time is recorded and your answers still count.",
                    "Отправьте сейчас или продолжайте: дополнительное время записывается, ответы засчитываются."), style = StudyType.Small, color = c.ink)
                StudyButton(l.label("Keep working", "Продолжить"), { vm.paperOvertime(run.id) }, tone = Tone.Ink, compact = true)
            }
            part.audioAssetPath?.let { path ->
                SectionAudio(path, run.audio[path] ?: AudioProgress(), run.strict, l) { position, completed -> vm.paperAudio(run.id, position, completed) }
                if (run.phase == PaperPhase.TRANSFER) Text(l.label("The recording has finished. Use the check time to complete and review your answers.",
                    "Запись закончилась. Используйте время на проверку, чтобы дописать и проверить ответы."), style = StudyType.Small, color = c.inkSoft)
            }
            QuestionList(run, part, numbers, enabled = !timeUp, l) { key, value -> vm.paperAnswer(run.id, key, value) }
        }
    }
    Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        StudyButton(if (run.strict) l.label("Submit section", "Сдать секцию") else l.label("Check answers", "Проверить ответы"),
            { if (answered < part.exercises.size) confirmValue = true else vm.submitPaperPart(run.id) }, Modifier.fillMaxWidth(), arrow = true)
    }
    if (confirmValue) AlertDialog(onDismissRequest = { confirmValue = false }, containerColor = c.paper,
        text = { Text(l.label("${part.exercises.size - answered} question(s) are blank. A blank is recorded as not answered.",
            "Без ответа: ${part.exercises.size - answered}. Пустой ответ записывается как пропуск."), style = StudyType.Body, color = c.ink) },
        confirmButton = { TextButton(onClick = { confirmValue = false; vm.submitPaperPart(run.id) }) { Text(l.label("Submit", "Отправить"), style = StudyType.Button, color = c.ink) } },
        dismissButton = { TextButton(onClick = { confirmValue = false }) { Text(l.label("Keep working", "Продолжить"), style = StudyType.Button, color = c.inkSoft) } })
    if (discardValue) AlertDialog(onDismissRequest = { discardValue = false }, containerColor = c.paper,
        text = { Text(l.label("Leave this sitting without marking? Nothing is added to your history; the typed answers stay in the saved record.",
            "Выйти без проверки? В историю ничего не добавится; введённые ответы останутся в сохранённой записи."), style = StudyType.Body, color = c.ink) },
        confirmButton = { TextButton(onClick = { discardValue = false; vm.abandonPaper(run.id); close() }) { Text(l.label("Leave", "Выйти"), style = StudyType.Button, color = c.bad) } },
        dismissButton = { TextButton(onClick = { discardValue = false }) { Text(l.label("Stay", "Остаться"), style = StudyType.Button, color = c.ink) } })
}

/** Questions in order; consecutive members of one group share their instruction, list, text or figure. */
@Composable
private fun QuestionList(run: PaperRun, part: PaperPart, numbers: Map<String, Int>, enabled: Boolean, l: Language, answer: (String, String) -> Unit) {
    val blocks = remember(part) { part.exercises.fold(mutableListOf<MutableList<Exercise>>()) { acc, item ->
        val previous = acc.lastOrNull()?.lastOrNull()
        if (previous != null && item.group != null && previous.group?.id == item.group.id) acc.last().add(item) else acc.add(mutableListOf(item))
        acc
    } }
    blocks.forEach { block ->
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            block.first().group?.let { group ->
                block.first().format?.let { Meta(formatLabel(it, l)) }
                GroupContext(group, numbers)
            }
            block.forEach { exercise ->
                key(run.id, exercise.versionKey) {
                    PaperQuestion(run, exercise, numbers.getValue(exercise.id), enabled, l, answer)
                }
            }
        }
        Hairline()
    }
}

@Composable
private fun PaperQuestion(run: PaperRun, exercise: Exercise, number: Int, enabled: Boolean, l: Language, answer: (String, String) -> Unit) {
    val c = Study.colors
    val saved = run.answers[exercise.versionKey].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            QuestionNumber(number, if (saved.isNotBlank()) Mark.Done else Mark.Todo)
            Spacer(Modifier.width(10.dp))
            Text(exercise.prompt, Modifier.weight(1f).padding(top = 3.dp), style = StudyType.Body.copy(fontSize = 17.sp, lineHeight = 25.sp), color = c.ink)
        }
        exercise.chart?.let { Chart(it, l) }
        exercise.figure?.let { FigureView(it, mapOf(exercise.id to number)) }
        when {
            exercise.usesGroupList() -> KeyChips(exercise, saved, enabled) { answer(exercise.versionKey, it) }
            exercise.type == ExerciseType.MULTIPLE_CHOICE -> ChoiceList(exercise, saved, enabled) { answer(exercise.versionKey, it) }
            else -> {
                // The editor owns live text; persistence follows it and never re-seeds it while this question is shown.
                val state = rememberTextFieldState(initialText = saved)
                LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { answer(exercise.versionKey, it) } }
                OutlinedTextField(state = state, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    label = { Text(l.label("Answer $number", "Ответ $number")) },
                    lineLimits = TextFieldLineLimits.SingleLine, textStyle = StudyType.Body.copy(fontSize = 17.sp),
                    supportingText = exercise.wordLimit?.let { limit -> { Text(l.label("No more than $limit word(s).", "Не более $limit слов."), style = StudyType.Small.copy(fontSize = 13.sp)) } },
                    shape = RoundedCornerShape(14.dp), colors = studyFieldColors())
            }
        }
    }
}

@Composable
private fun ColumnScope.PaperResults(s: StudyUiState, run: PaperRun, close: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val outcomes = remember(run) { run.parts.filter { it.id in run.submittedParts }.associateWith { PaperScoring.score(it, run.answers) } }
    val raw = remember(outcomes) { PaperScoring.raw(outcomes.values.flatten()) }
    val seconds = run.parts.sumOf { run.elapsed(it) + run.transferElapsed(it) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyph.Close, l.label("Close", "Закрыть"), close)
        Text(run.parts.firstOrNull()?.title.orEmpty(), Modifier.weight(1f).padding(horizontal = 6.dp), style = StudyType.Strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Meta(if (run.abandoned) l.label("Left without marking", "Завершено без проверки")
            else (if (run.strict) l.label("Exam conditions", "Экзаменационный режим") else l.label("Section practice", "Практика секции")) + " · " + l.label("marked", "проверено"), color = c.ink)
        if (!run.abandoned) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${raw.correct}", style = StudyType.Display.copy(fontSize = 96.sp, lineHeight = 90.sp), color = c.ink)
                Text("/${raw.closed}", Modifier.padding(bottom = 12.dp), style = StudyType.Display.copy(fontSize = 40.sp, lineHeight = 40.sp), color = c.inkFaint)
            }
            MarkedText(l.label("raw correct", "верных ответов"), StudyType.Title)
            Row(Modifier.fillMaxWidth()) {
                Stat(clock(seconds), l.label("time", "время"))
                Stat("${raw.answered}/${outcomes.values.sumOf { it.size }}", l.label("answered", "отвечено"))
            }
            if (run.overtimeParts.isNotEmpty()) Text(l.label("You kept working past the clock. The extra time is included; the answers count as usual.",
                "Вы продолжили после окончания времени. Дополнительное время учтено; ответы засчитаны как обычно."), style = StudyType.Small, color = c.inkSoft)
            Text(l.label("A raw count of this section only. It is not an IELTS band.", "Только число верных ответов в этой секции. Это не IELTS band."),
                style = StudyType.Small, color = c.inkSoft)
        }
        outcomes.forEach { (part, items) ->
            val numbers = Sections.numbers(part.exercises)
            items.forEach { outcome ->
                val exercise = outcome.exercise
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        QuestionNumber(numbers.getValue(exercise.id), when (outcome.correct) { true -> Mark.Right; false -> Mark.Wrong; null -> Mark.Todo })
                        Spacer(Modifier.width(10.dp))
                        Text(exercise.prompt, Modifier.weight(1f).padding(top = 3.dp), style = StudyType.Small.copy(fontSize = 15.sp, lineHeight = 21.sp), color = c.ink)
                    }
                    AnswerPair(l.label("You", "Вы"), if (outcome.answer.isBlank()) l.label("(blank)", "(пусто)") else optionLabel(exercise, outcome.answer), wrong = outcome.correct?.not())
                    if (exercise.acceptedAnswers.isNotEmpty()) AnswerPair(l.label("Key", "Ключ"), exercise.acceptedAnswers.joinToString(" / ") { optionLabel(exercise, it) }, wrong = false)
                    Disclosure(l.label("Why", "Почему"), null) {
                        SelectionContainer { Text(exercise.explanation.text(l), style = StudyType.Small, color = c.ink) }
                        exercise.evidence?.let { Text("${l.label("Evidence", "Подтверждение")}: $it", style = StudyType.Small, color = c.inkSoft) }
                    }
                }
            }
            part.exercises.firstNotNullOfOrNull { it.transcript }?.let { transcript ->
                Disclosure(l.label("Audio transcript", "Транскрипт аудио"), null) {
                    SelectionContainer { Text(transcript, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 25.sp), color = c.ink) }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        StudyButton(l.label("Done", "Готово"), close, Modifier.fillMaxWidth(), arrow = true)
    }
}

/** One player per recording. Practice can pause and rewind; exam conditions play once, resuming only where it paused. */
@Composable
fun SectionAudio(assetPath: String, progress: AudioProgress, strict: Boolean, l: Language, report: (Long, Boolean) -> Unit) {
    val c = Study.colors
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var playerValue by remember(assetPath) { mutableStateOf<MediaPlayer?>(null) }
    var playingValue by remember(assetPath) { mutableStateOf(false) }
    var positionValue by remember(assetPath) { mutableLongStateOf(progress.positionMs) }
    var durationValue by remember(assetPath) { mutableLongStateOf(0L) }
    var errorValue by remember(assetPath) { mutableStateOf("") }
    val finished = progress.completed
    DisposableEffect(assetPath, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playerValue?.let { player ->
                if (playingValue) { player.pause(); playingValue = false; report(player.currentPosition.toLong(), false) }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            playerValue?.let { if (playingValue) report(it.currentPosition.toLong(), false); it.release() }
            playerValue = null
        }
    }
    if (playingValue) LaunchedEffect(playerValue) {
        while (true) {
            delay(1000)
            val player = playerValue ?: break
            positionValue = player.currentPosition.toLong()
            report(positionValue, false)
        }
    }
    fun ensure(): MediaPlayer? = playerValue ?: try {
        MediaPlayer().also { player ->
            context.assets.openFd(assetPath).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            player.setOnCompletionListener { playingValue = false; positionValue = durationValue; report(durationValue, true) }
            player.setOnErrorListener { _, _, _ -> errorValue = l.label("Audio could not play. Try again.", "Не удалось воспроизвести аудио."); playingValue = false; true }
            player.prepare()
            durationValue = player.duration.toLong()
            player.seekTo(progress.positionMs.coerceIn(0, durationValue).toInt())
            playerValue = player
        }
    } catch (error: Exception) { errorValue = error.message ?: "Audio unavailable"; null }
    val play = { ensure()?.let { if (!strict && finished && positionValue >= durationValue) it.seekTo(0); it.start(); playingValue = true } }
    val pause = { playerValue?.let { it.pause(); playingValue = false; positionValue = it.currentPosition.toLong(); report(positionValue, false) } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                strict && finished -> GlyphIcon(Glyph.Check, tint = c.good, size = 28.dp)
                strict && playingValue -> GlyphIcon(Glyph.Headphones, tint = c.ink, size = 28.dp)
                else -> GlyphButton(if (playingValue) Glyph.Pause else Glyph.Play, if (playingValue) l.label("Pause audio", "Пауза") else l.label("Play audio", "Слушать"),
                    { if (playingValue) pause() else play() }, tint = c.paper, background = c.ink, size = 52.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Meta(l.label("Listening · synthetic audio · expert review pending", "Listening · синтетическая запись · экспертная проверка ожидается"))
                Text(when {
                    strict && finished -> l.label("Recording finished. It plays once.", "Запись закончилась. Она звучит один раз.")
                    strict && playingValue -> l.label("Playing once. Answer as you listen.", "Звучит один раз. Отвечайте по ходу.")
                    strict && progress.started -> l.label("Paused when you left. Resume from the same point.", "Пауза при выходе. Продолжите с того же места.")
                    strict -> l.label("Plays once, without pausing or rewinding.", "Звучит один раз, без паузы и перемотки.")
                    else -> l.label("Pause, rewind and replay as needed.", "Можно ставить на паузу, перематывать и слушать снова.")
                }, style = StudyType.Small, color = c.inkSoft)
                if (errorValue.isNotEmpty()) Text(errorValue, style = StudyType.Small, color = c.bad)
            }
            if (!strict) GlyphButton(Glyph.ArrowLeft, l.label("Back 10 seconds", "Назад на 10 секунд"), {
                ensure()?.let { player -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); positionValue = player.currentPosition.toLong(); report(positionValue, false) }
            })
        }
        if (strict && !finished && !playingValue) StudyButton(if (progress.started) l.label("Resume recording", "Продолжить запись") else l.label("Start recording", "Начать запись"),
            { play() }, Modifier.fillMaxWidth(), compact = true, glyph = Glyph.Play)
        if (durationValue > 0) {
            Bar((positionValue.toFloat() / durationValue).coerceIn(0f, 1f), height = 4.dp)
            Text("${clock((positionValue / 1000).toInt())} / ${clock((durationValue / 1000).toInt())}", style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
        }
    }
}
