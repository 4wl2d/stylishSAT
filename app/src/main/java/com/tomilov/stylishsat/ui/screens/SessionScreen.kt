package com.tomilov.stylishsat.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tomilov.stylishsat.StudySession
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.speech.RecorderState
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.delay

private enum class StepKind { Finished, Review, Lesson, Exercise }

private fun StudySession.kind(): StepKind = when {
    finished -> StepKind.Finished
    activity?.kind == ActivityKind.REVIEW && activity?.lessonId == null -> StepKind.Review
    !lessonSeen -> StepKind.Lesson
    else -> StepKind.Exercise
}

/** One animated page per step phase; a lesson and its exercise share a step but not a page. */
private fun StudySession.phase(): String = "$id:$index:${kind()}"

@Composable
fun SessionScreen(s: StudyUiState, vm: StudyViewModel, modifier: Modifier, close: () -> Unit, again: () -> Unit) {
    val session = s.session ?: return
    val exercise = session.exercise ?: return
    val l = s.language
    val c = Study.colors
    // Compose owns live editing. Persistence follows the editor, and a new draft revision recreates the editor once.
    val answerState = key(session.id, session.index, session.draftRevision) { rememberTextFieldState(initialText = session.draft) }
    if (exercise.type != ExerciseType.MULTIPLE_CHOICE && session.result == null && !session.finished) {
        LaunchedEffect(answerState) { snapshotFlow { answerState.text.toString() }.collect { vm.draft(it, session.stepKey) } }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    if (session.kind() == StepKind.Exercise && session.result == null && !session.answerLockedByTimeLimit) {
        LaunchedEffect(session.stepKey, answerState, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(1000)
                    vm.tick(session.stepKey, answerState.text.toString().takeIf { exercise.type != ExerciseType.MULTIPLE_CHOICE }, session.draftRevision)
                }
            }
        }
    }
    val captureValue by vm.runtime.recorder.state.collectAsStateWithLifecycle()
    val recording = captureValue is RecorderState.RecordingAudio
    var menuValue by remember { mutableStateOf(false) }
    // The clock ticks every second; step colours only change with attempts or the step itself.
    val marks = remember(s.attempts, session.id, session.index, session.result, session.finished) { sessionMarks(s, session) }

    Column(modifier.background(c.paper).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.Close, l.label("Close", "Закрыть"), close)
            StepTrack(marks, Modifier.weight(1f).padding(horizontal = 8.dp))
            if (session.kind() == StepKind.Exercise) SessionClock(session, exercise)
            if (!session.finished) Box {
                GlyphButton(Glyph.More, l.label("More", "Ещё"), { menuValue = true })
                DropdownMenu(menuValue, { menuValue = false }, containerColor = c.raised) {
                    DropdownMenuItem(text = { Text(l.label("Save and end for today", "Сохранить и закончить на сегодня"), style = StudyType.Body) }, enabled = !recording, onClick = {
                        menuValue = false
                        if (exercise.type != ExerciseType.MULTIPLE_CHOICE && session.result == null && session.lessonSeen && session.activity?.isLesson != true)
                            vm.draft(answerState.text.toString(), session.stepKey)
                        vm.finishForNow()
                    })
                }
            }
        }
        AnimatedContent(session, Modifier.weight(1f), contentKey = { it.phase() }, transitionSpec = {
            (fadeIn(tween(180)) + slideInHorizontally(tween(240)) { it / 10 }) togetherWith fadeOut(tween(100))
        }, label = "step") { target ->
            // An outgoing page renders its own snapshot; only the live page gets the live editor.
            val live = target.phase() == session.phase()
            val current = if (live) session else target
            when (current.kind()) {
                StepKind.Finished -> FinishedStep(s, current, close, again)
                StepKind.Review -> ReviewStep(s, vm, current)
                StepKind.Lesson -> LessonStep(s, vm, current)
                StepKind.Exercise -> ExerciseStep(s, vm, current, if (live) answerState else null, recording)
            }
        }
    }
}

@Composable
private fun SessionClock(session: StudySession, exercise: Exercise) {
    val c = Study.colors
    val elapsed = session.previousWorkSeconds + session.activeSeconds
    val limit = session.timeLimitSeconds
    val (text, color) = if (limit != null && session.result == null && !session.continuedWithoutTimeLimit) {
        val remaining = (limit - elapsed).coerceAtLeast(0)
        clock(remaining) to if (remaining <= 10) c.bad else c.ink
    } else clock(elapsed) to if (exercise.expectedSeconds in 1 until elapsed) c.warn else c.inkSoft
    Row(Modifier.padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.Clock, tint = color, size = 15.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, style = StudyType.Mono, color = color)
    }
}

/** Scrolling body plus a bottom action area that stays above the keyboard and navigation bar. */
@Composable
private fun StepFrame(scroll: ScrollState = rememberScrollState(), actions: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Study.colors
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
        Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
private fun LessonStep(s: StudyUiState, vm: StudyViewModel, session: StudySession) {
    val l = s.language
    val c = Study.colors
    val exercise = session.exercise ?: return
    val pack = s.pack ?: return
    val lesson = session.activity?.lessonId?.let { id -> session.lessonSnapshots.firstOrNull { it.id == id } ?: pack.lessons.firstOrNull { it.id == id } }
        ?: pack.lessons.firstOrNull { it.skillId == exercise.skillId }
    val skill = pack.skills.find { it.id == exercise.skillId }
    StepFrame(actions = {
        if (session.activity?.remainingMinutes?.let { it > 0 } == true)
            StudyButton(l.label("Finish this lesson tomorrow", "Дочитать завтра"), { vm.checkpoint(session.stepKey) }, Modifier.fillMaxWidth(), tone = Tone.Quiet, compact = true)
        StudyButton(if (session.activity != null) l.label("Got it", "Понятно") else l.label("Start practice", "К практике"),
            { vm.lessonSeen(session.stepKey) }, Modifier.fillMaxWidth(), arrow = true)
    }) {
        Meta(listOfNotNull(skill?.title?.text(l), l.label("Lesson", "Урок"), (session.activity?.minutes ?: lesson?.estimatedMinutes)?.let { minutes(it, l) }).joinToString(" · "))
        MarkedText(lesson?.title?.text(l) ?: l.label("Before you start", "Перед практикой"), StudyType.Headline)
        if (session.activity?.continuation == true) Meta(l.label("Continued from last time", "Продолжение с прошлого раза"), color = c.ink)
        lesson?.let {
            SelectionContainer { Text(it.body.text(l), style = StudyType.Reading, color = c.ink) }
            MarginNote {
                Meta(l.label("Worked example", "Разобранный пример"))
                SelectionContainer { Text(it.workedExample.text(l), style = StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 27.sp), color = c.ink) }
            }
        }
    }
}

@Composable
private fun ReviewStep(s: StudyUiState, vm: StudyViewModel, session: StudySession) {
    val l = s.language
    val c = Study.colors
    val exercise = session.exercise ?: return
    val attempt = s.attempts.lastOrNull { it.exam == session.exam && it.exerciseId == exercise.id && it.exerciseVersion == exercise.version }
    StepFrame(actions = {
        StudyButton(l.label("Review done", "Разбор завершён"), { vm.lessonSeen(session.stepKey) }, Modifier.fillMaxWidth(), arrow = true)
    }) {
        Meta(listOfNotNull(s.pack?.skills?.find { it.id == exercise.skillId }?.title?.text(l), l.label("Review", "Разбор")).joinToString(" · "))
        MarkedText(l.label("Look back at your answer.", "Вернитесь к своему ответу."), StudyType.Headline)
        if (attempt != null) {
            Text(exercise.prompt, style = StudyType.Question.copy(fontSize = 18.sp, lineHeight = 27.sp), color = c.ink)
            SelectionContainer { AnswerPair(l.label("You", "Вы"), attempt.answer, wrong = attempt.correct?.not()) }
            if (exercise.acceptedAnswers.isNotEmpty()) AnswerPair(l.label("Key", "Ключ"), exercise.acceptedAnswers.joinToString(" / "), wrong = false)
            Explanation(exercise, l)
            exercise.sampleAnswer?.let { SampleAnswer(it, l) }
            exercise.sampleAudioAssetPath?.let { ListeningPlayer(it, l, sample = true) }
            Text(l.label("Say the rule in your own words and pick one correction to practise next. This review isn't graded.",
                "Сформулируйте правило своими словами и выберите одно исправление для практики. Разбор не оценивается."), style = StudyType.Small, color = c.inkSoft)
        } else {
            Text(l.label("No saved answer is available for this review. Revisit the skill lesson in the library first.",
                "Для разбора нет сохранённого ответа. Сначала повторите урок в библиотеке."), style = StudyType.Body, color = c.inkSoft)
        }
    }
}

@Composable
private fun ExerciseStep(s: StudyUiState, vm: StudyViewModel, session: StudySession, answerState: TextFieldState?, recording: Boolean) {
    val l = s.language
    val c = Study.colors
    val exercise = session.exercise ?: return
    val pack = s.pack ?: return
    val result = session.result
    val answerEnabled = answerState != null && result == null && !session.answerLockedByTimeLimit
    val haptic = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    val resultAnchor = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    var announcedValue by remember(session.stepKey) { mutableStateOf(result != null) }
    LaunchedEffect(result) {
        if (result != null && !announcedValue) {
            announcedValue = true
            haptic.performHapticFeedback(if (result.correct == false) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
            delay(60)
            resultAnchor.bringIntoView(Rect(0f, 0f, 1f, with(density) { 220.dp.toPx() }))
        }
    }
    val typed = answerState?.text?.toString() ?: session.draft
    val saveTyped = { if (answerState != null && exercise.type != ExerciseType.MULTIPLE_CHOICE) vm.draft(answerState.text.toString(), session.stepKey) }
    val open = exercise.type == ExerciseType.WRITING || exercise.type == ExerciseType.SPEAKING

    StepFrame(scroll, actions = {
        if (result == null) {
            val secondary = mutableListOf<Triple<String, Boolean, () -> Unit>>()
            if (session.mode == ContentSplit.PRACTICE && exercise.hints.isNotEmpty() && session.hintsUsed < exercise.hints.size)
                secondary += Triple(l.label("Hint ${session.hintsUsed + 1}/${exercise.hints.size}", "Подсказка ${session.hintsUsed + 1}/${exercise.hints.size}"), !session.answerLockedByTimeLimit) { vm.hint() }
            if (session.activity?.remainingMinutes?.let { it > 0 } == true)
                secondary += Triple(l.label("Save draft for next day", "Черновик на завтра"), !recording) { saveTyped(); vm.checkpoint(session.stepKey) }
            if (session.mode == ContentSplit.DIAGNOSTIC)
                secondary += Triple(l.label("Skip · don't know yet", "Пропустить · пока не знаю"), true) { saveTyped(); vm.submit(skip = true) }
            if (secondary.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                secondary.forEach { (text, enabled, action) -> StudyButton(text, action, Modifier.weight(1f), tone = Tone.Quiet, compact = true, enabled = enabled) }
            }
            val ready = (if (exercise.type == ExerciseType.MULTIPLE_CHOICE) session.draft.isNotBlank() else typed.isNotBlank()) && !recording && answerState != null
            StudyButton(if (open) l.label("Submit for review", "Сохранить и разобрать") else l.label("Check", "Проверить"),
                { saveTyped(); vm.submit() }, Modifier.fillMaxWidth(), enabled = ready)
        } else ResultBar(result, exercise, session, l, vm::next)
    }) {
        val skill = pack.skills.find { it.id == exercise.skillId }
        Meta(listOfNotNull(skill?.title?.text(l), modeLabel(session.mode, l), if (session.mode == ContentSplit.PRACTICE) l.label("level ${exercise.difficulty}", "уровень ${exercise.difficulty}") else null).joinToString(" · "))
        val chips = buildList {
            if (session.activity?.continuation == true || session.resumingDraft) add(l.label("Draft restored", "Черновик восстановлен"))
            if (session.timeLimitSeconds != null && result == null) add(if (session.continuedWithoutTimeLimit) l.label("No limit now", "Без лимита") else l.label("Timed · pauses when you leave", "На время · пауза при выходе"))
            if (session.activity?.let { !it.isLesson && it.remainingMinutes > 0 } == true) add(l.label("Spans several days", "На несколько дней"))
        }
        if (chips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { Meta(it, Modifier.clip(RoundedCornerShape(50)).background(c.sunken).padding(horizontal = 10.dp, vertical = 5.dp), color = c.ink, maxLines = 1) }
        }
        if (session.answerLockedByTimeLimit && result == null) Block(color = c.badSoft) {
            Text(l.label("Time's up. Your answer is saved.", "Время вышло. Ответ сохранён."), style = StudyType.Title, color = c.ink)
            Text(l.label("Check it now, keep going without the limit, or save it for later. Nothing is graded automatically.",
                "Проверьте его, продолжите без лимита или сохраните на потом. Автоматически ничего не оценивается."), style = StudyType.Small, color = c.ink)
            StudyButton(l.label("Keep going without limit", "Продолжить без лимита"), { vm.continueWithoutTimeLimit(session.stepKey) }, tone = Tone.Ink, compact = true)
        }
        if (session.mode == ContentSplit.PRACTICE && result == null) {
            val checks = remember(s.pack, s.attempts, exercise) { StudyPlanner.practiceChecks(pack, exercise, s.attempts) }
            if (checks.isNotEmpty()) {
                if (session.reviewGuidanceViewed) MarginNote(rule = c.inkSoft) {
                    Meta(l.label("Check before answering · counted as a hint", "Проверьте перед ответом · считается подсказкой"))
                    checks.forEach { Text("• ${it.text(l)}", style = StudyType.Small, color = c.ink) }
                } else Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(16.dp), c.sunken, enabled = !session.answerLockedByTimeLimit) { vm.reviewGuidance(session.stepKey) }
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Bulb, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(l.label("You missed this before. Show a checklist? Counts as a hint.", "Здесь уже была ошибка. Показать список проверки? Считается подсказкой."),
                        Modifier.weight(1f), style = StudyType.Small, color = c.ink)
                }
            }
        }
        exercise.passage?.let { passage ->
            Block(padding = 20.dp) { SelectionContainer { Text(passage, style = StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 28.sp), color = c.ink) } }
        }
        exercise.audioAssetPath?.let { ListeningPlayer(it, l) }
        exercise.chart?.let { Chart(it) }
        Text(exercise.prompt, style = StudyType.Question, color = c.ink)
        if (exercise.type == ExerciseType.SPEAKING) SpeakingPanel(s, vm)
        if (exercise.type == ExerciseType.MULTIPLE_CHOICE) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                exercise.options.forEachIndexed { index, option ->
                    val selected = session.draft == option
                    val isKey = result != null && exercise.acceptedAnswers.any { AnswerChecker.normalize(it) == AnswerChecker.normalize(option) }
                    OptionRow(('A'.code + index).toChar(), option, when {
                        result == null -> if (selected) OptionState.Selected else OptionState.Idle
                        isKey -> OptionState.Key
                        selected -> OptionState.Wrong
                        else -> OptionState.Dimmed
                    }, answerEnabled) {
                        if (!selected) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        vm.draft(option, session.stepKey)
                    }
                }
            }
        } else if (answerState != null) {
            OutlinedTextField(state = answerState, enabled = answerEnabled,
                label = { Text(if (exercise.type == ExerciseType.SPEAKING) l.label("Transcript · review or type", "Транскрипт · проверьте или введите") else l.label("Your answer", "Ваш ответ")) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = if (open) StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 27.sp) else StudyType.Body.copy(fontSize = 18.sp),
                lineLimits = if (open) TextFieldLineLimits.MultiLine(minHeightInLines = 7, maxHeightInLines = 14) else TextFieldLineLimits.SingleLine,
                supportingText = { Text(when {
                    exercise.wordLimit != null -> l.label("No more than ${exercise.wordLimit} word(s).", "Не более ${exercise.wordLimit} слов.")
                    exercise.type == ExerciseType.WRITING -> "${Regex("\\S+").findAll(answerState.text.toString().trim()).count()} ${l.label("words", "слов")} · ${l.label("target", "ориентир")} ${exercise.minWords ?: 150}+"
                    exercise.type == ExerciseType.NUMERIC -> if (exercise.prompt.contains("exact fraction", ignoreCase = true)) l.label("Enter the exact fraction requested, without decimal rounding.", "Введите точную дробь, без десятичного округления.") else l.label("Decimals and fractions accepted, e.g. 0.5 or 1/2.", "Можно вводить дроби: 0.5 или 1/2.")
                    else -> l.label("Saved on this device as you type.", "Сохраняется на устройстве при вводе.")
                }, style = StudyType.Small.copy(fontSize = 13.sp)) }, shape = RoundedCornerShape(16.dp), colors = studyFieldColors())
        } else Block { Text(session.draft.ifBlank { "—" }, style = StudyType.Body, color = c.ink) }
        if (exercise.criteria.isNotEmpty()) Disclosure(l.label("Review criteria", "Критерии разбора"), "${exercise.criteria.size}") {
            exercise.criteria.forEach { Text("• ${it.text(l)}", style = StudyType.Small, color = c.ink) }
        }
        if (session.mode == ContentSplit.PRACTICE && result == null) exercise.hints.take(session.hintsUsed).forEachIndexed { index, hint ->
            MarginNote {
                Meta(l.label("Hint ${index + 1}", "Подсказка ${index + 1}"))
                Text(hint.text(l), style = StudyType.Body, color = c.ink)
            }
        }
        if (result != null) Column(Modifier.bringIntoViewRequester(resultAnchor), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Hairline()
            // The bar already says right or wrong; show the checker's message only when it adds a reason.
            if (result.status == AnswerStatus.NEEDS_REVIEW || result.status == AnswerStatus.INCORRECT && result.errorType != "KEY_MISMATCH")
                Text(result.message.text(l), style = StudyType.Strong, color = c.ink)
            if (exercise.type != ExerciseType.MULTIPLE_CHOICE && exercise.acceptedAnswers.isNotEmpty()) {
                AnswerPair(l.label("You", "Вы"), session.draft, wrong = result.correct?.not())
                AnswerPair(l.label("Key", "Ключ"), exercise.acceptedAnswers.joinToString(" / "), wrong = false)
            }
            Explanation(exercise, l)
            if (session.hintsUsed > 0 || session.reviewGuidanceViewed) Text(l.label("Answered with a hint: practice, not independent evidence.", "Ответ с подсказкой — это практика, а не самостоятельное подтверждение."), style = StudyType.Small, color = c.inkSoft)
            if (session.continuedWithoutTimeLimit) Text(l.label("You continued past the limit. The full working time is saved.", "Вы продолжили после лимита. Полное время работы сохранено."), style = StudyType.Small, color = c.inkSoft)
            exercise.transcript?.let { transcript ->
                Disclosure(l.label("Audio transcript", "Транскрипт аудио"), null) {
                    SelectionContainer { Text(transcript, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 25.sp), color = c.ink) }
                    exercise.transcriptSegments.forEach { Text("${it.startMs / 1000}–${it.endMs / 1000}s · ${it.text}", style = StudyType.Small, color = c.inkSoft) }
                }
            }
            exercise.sampleAnswer?.let { SampleAnswer(it, l) }
            exercise.sampleAudioAssetPath?.let { ListeningPlayer(it, l, sample = true) }
            FeedbackPanel(s, vm)
        }
        Text("v${exercise.version} · ${exercise.author}", style = StudyType.Small.copy(fontSize = 11.sp, lineHeight = 15.sp), color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class OptionState { Idle, Selected, Key, Wrong, Dimmed }

@Composable
private fun OptionRow(letter: Char, text: String, state: OptionState, enabled: Boolean, onClick: () -> Unit) {
    val c = Study.colors
    val background by animateColorAsState(when (state) {
        OptionState.Key -> c.marker
        OptionState.Wrong -> c.badSoft
        OptionState.Dimmed -> c.paper
        else -> c.raised
    }, tween(160), label = "option")
    val border = when (state) {
        OptionState.Selected -> BorderStroke(2.dp, c.ink)
        OptionState.Wrong -> BorderStroke(2.dp, c.bad)
        OptionState.Key -> BorderStroke(2.dp, c.marker)
        else -> BorderStroke(1.dp, c.line)
    }
    val content = when (state) {
        OptionState.Key -> c.onMarker
        OptionState.Dimmed -> c.inkSoft
        else -> c.ink
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .tapSurface(RoundedCornerShape(18.dp), background, enabled, border, role = Role.RadioButton, onClick = onClick)
        .semantics { selected = state == OptionState.Selected || state == OptionState.Wrong }
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val (badge, badgeContent) = when (state) {
            OptionState.Idle -> c.sunken to c.ink
            OptionState.Selected -> c.ink to c.paper
            OptionState.Key -> c.onMarker to c.marker
            OptionState.Wrong -> c.bad to c.paper
            OptionState.Dimmed -> c.paper to c.inkFaint
        }
        Box(Modifier.size(32.dp).clip(CircleShape).background(badge).then(if (state == OptionState.Dimmed) Modifier.border(1.dp, c.line, CircleShape) else Modifier),
            contentAlignment = Alignment.Center) {
            when (state) {
                OptionState.Key -> GlyphIcon(Glyph.Check, tint = badgeContent, size = 16.dp)
                OptionState.Wrong -> GlyphIcon(Glyph.Cross, tint = badgeContent, size = 16.dp)
                else -> Text("$letter", style = StudyType.Mono.copy(fontSize = 14.sp), color = badgeContent)
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(text, Modifier.weight(1f), style = StudyType.Body, color = content)
    }
}

@Composable
private fun ResultBar(result: AnswerResult, exercise: Exercise, session: StudySession, l: Language, next: () -> Unit) {
    val c = Study.colors
    val (background, glyph, title) = when {
        result.correct == true -> Triple(c.marker, Glyph.Check, l.label("Correct", "Верно"))
        result.correct == false -> Triple(c.badSoft, Glyph.Cross, l.label("Not quite", "Пока неверно"))
        exercise.type == ExerciseType.WRITING || exercise.type == ExerciseType.SPEAKING -> Triple(c.sunken, Glyph.Dot, l.label("Saved for review", "Сохранено для разбора"))
        else -> Triple(c.sunken, Glyph.Dot, l.label("Skipped", "Пропущено"))
    }
    val content = if (result.correct == true) c.onMarker else c.ink
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, enter = slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(160))) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(background).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(start = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(glyph, tint = if (result.correct == false) c.bad else content, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(title, style = StudyType.Title, color = content)
            }
            StudyButton(if (session.index + 1 == session.stepCount) l.label("Finish", "Завершить") else l.label("Continue", "Дальше"), next,
                Modifier.fillMaxWidth(), tone = if (result.correct == true) Tone.Night else Tone.Ink, arrow = true)
        }
    }
}

@Composable
private fun Explanation(exercise: Exercise, l: Language) {
    val c = Study.colors
    SelectionContainer { Text(exercise.explanation.text(l), style = StudyType.Body, color = c.ink) }
    exercise.evidence?.let { MarginNote(rule = c.inkSoft) { Meta(l.label("Evidence", "Подтверждение")); Text(it, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 25.sp), color = c.ink) } }
    if (exercise.typicalErrors.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Meta(l.label("Common slips", "Типичные ошибки"))
        exercise.typicalErrors.forEach { Text("• ${it.text(l)}", style = StudyType.Small, color = c.ink) }
    }
}

@Composable
private fun SampleAnswer(text: String, l: Language) {
    val c = Study.colors
    Disclosure(l.label("Sample answer", "Пример ответа"), null) {
        SelectionContainer { Text(text, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 25.sp), color = c.ink) }
        Text(l.label("Pilot sample, not yet expert-annotated.", "Пилотный образец, ещё без экспертной аннотации."), style = StudyType.Small, color = c.inkSoft)
    }
}

@Composable
fun Disclosure(title: String, badge: String?, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Study.colors
    var openValue by remember(title) { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised)) {
        Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(18.dp), c.raised) { openValue = !openValue }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = StudyType.Strong, color = c.ink)
            badge?.let { Meta(it); Spacer(Modifier.width(8.dp)) }
            val turn by animateFloatAsState(if (openValue) 180f else 0f, tween(180), label = "chevron")
            GlyphIcon(Glyph.ChevronDown, Modifier.rotate(turn), tint = c.inkSoft, size = 18.dp)
        }
        AnimatedVisibility(openValue) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun FinishedStep(s: StudyUiState, session: StudySession, close: () -> Unit, again: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val rhythm = rememberRhythm(s)
    val ids = remember(session) { session.stepWorkIds().toSet() }
    val attempts = remember(s.attempts, ids) { s.attempts.filter { it.exam == session.exam && it.workId in ids } }
    val checked = attempts.count { it.correct != null }
    val right = attempts.count { it.correct == true }
    val seconds = attempts.sumOf { it.elapsedSeconds }
    val marks = remember(s.attempts, session) { sessionMarks(s, session) }
    StepFrame(actions = {
        if (!session.stoppedEarly && session.mode != ContentSplit.DIAGNOSTIC)
            StudyButton(l.label("Another round", "Ещё раунд"), again, Modifier.fillMaxWidth(), tone = Tone.Quiet, glyph = Glyph.Plus)
        StudyButton(l.label("Done", "Готово"), close, Modifier.fillMaxWidth(), arrow = true)
    }) {
        Spacer(Modifier.height(24.dp))
        Meta(when {
            session.stoppedEarly -> l.label("Saved for later", "Сохранено на потом")
            session.courseDay != null -> l.label("Day ${session.courseDay} complete", "День ${session.courseDay} пройден")
            else -> modeLabel(session.mode, l) + " · " + l.label("complete", "завершено")
        }, color = c.ink)
        if (!session.stoppedEarly && checked > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$right", style = StudyType.Display.copy(fontSize = 112.sp, lineHeight = 104.sp), color = c.ink)
                Text("/$checked", Modifier.padding(bottom = 14.dp), style = StudyType.Display.copy(fontSize = 44.sp, lineHeight = 44.sp), color = c.inkFaint)
            }
            MarkedText(l.label("correct", "верно"), StudyType.Title)
        } else {
            val done = marks.count { it != Mark.Todo && it != Mark.Now }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$done", style = StudyType.Display.copy(fontSize = 112.sp, lineHeight = 104.sp), color = c.ink)
                Text("/${session.stepCount}", Modifier.padding(bottom = 14.dp), style = StudyType.Display.copy(fontSize = 44.sp, lineHeight = 44.sp), color = c.inkFaint)
            }
            MarkedText(l.label("steps done", "шагов пройдено"), StudyType.Title)
        }
        StepTrack(marks)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Stat(if (seconds > 0) clock(seconds) else "—", l.label("answer time", "время ответов"))
            Stat("${rhythm.streak}", l.label("day streak", "дней подряд"))
        }
        if (session.stoppedEarly) Text(if (session.courseDay != null) l.label("Unfinished work continues in the next days of your route. It stays ungraded until you submit it.", "Незавершённая работа продолжится в следующих днях маршрута и останется без оценки до отправки.")
            else l.label("Unfinished answers stay ungraded. Continue them from Today.", "Незаконченные ответы остаются без оценки. Продолжите их на странице «Сегодня»."), style = StudyType.Body, color = c.inkSoft)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.pendingWrites == 0) c.good else c.warn))
            Spacer(Modifier.width(8.dp))
            Meta(if (s.pendingWrites == 0) l.label("Saved on this device", "Сохранено на устройстве") else l.label("Saving…", "Сохраняем…"))
        }
    }
}

/** Work ids of every step, matching the ids its attempts were saved with. */
internal fun StudySession.stepWorkIds(): List<String> = (0 until stepCount).map { index ->
    activities.getOrNull(index)?.workId ?: if (index == this.index) workId else "$id:$index"
}

/** Segment colours come from saved attempts only; steps without one show as neutral progress. */
internal fun sessionMarks(s: StudyUiState, session: StudySession): List<Mark> {
    val ids = session.stepWorkIds()
    val byWork = s.attempts.asSequence().filter { it.exam == session.exam && it.workId != null && it.workId in ids }.associateBy { it.workId }
    return ids.mapIndexed { index, id ->
        if (!session.finished && index == session.index) return@mapIndexed when (session.result?.correct) {
            null -> if (session.result == null) Mark.Now else Mark.Open
            true -> Mark.Right
            false -> Mark.Wrong
        }
        val attempt = byWork[id]
        when {
            attempt != null -> when (attempt.correct) { true -> Mark.Right; false -> Mark.Wrong; null -> Mark.Open }
            index < session.index -> Mark.Done
            session.finished && !session.stoppedEarly && index == session.index -> Mark.Done
            else -> Mark.Todo
        }
    }
}
