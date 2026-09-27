package com.tomilov.stylishsat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType

private enum class NotebookFilter { Open, Resolved, All }

/** Every wrong or skipped answer for the selected exam, each reopening the exact version that was answered. */
@Composable
fun NotebookScreen(s: StudyUiState, vm: StudyViewModel, openAttempt: (String) -> Unit, practise: () -> Unit, close: () -> Unit) {
    val l = s.language
    val c = Study.colors
    var filterValue by rememberSaveable(s.exam) { mutableStateOf(NotebookFilter.Open) }
    val marked = remember(s.marks) { s.marks.values.filter { it.marked }.map { it.workId }.toSet() }
    val mistakes = remember(s.attempts, s.exam, marked) { Notebook.mistakes(s.attempts, s.exam, marked) }
    val shown = mistakes.filter { attempt ->
        val resolved = s.notebook[attempt.id]?.resolved == true
        when (filterValue) { NotebookFilter.Open -> !resolved; NotebookFilter.Resolved -> resolved; NotebookFilter.All -> true }
    }
    val pending = remember(s.notebook, s.attempts, s.exam) { Notebook.pending(s.notebook.values, s.attempts, s.exam) }
    Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.ArrowLeft, l.label("Back", "Назад"), close)
            Text(l.label("Mistake notebook", "Тетрадь ошибок"), Modifier.weight(1f).padding(horizontal = 6.dp), style = StudyType.Title, color = c.ink)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(l.label("Wrong, skipped and marked answers reopen the exact question you saw: prompt, your answer, key and explanation. Write why it went wrong, then queue a fresh question from the same family.",
                    "Неверные, пропущенные и отмеченные ответы открывают то же задание, что вы видели: вопрос, ваш ответ, ключ и объяснение. Запишите, почему ошиблись, и поставьте в очередь новое задание той же семьи."),
                    style = StudyType.Small, color = c.inkSoft)
            }
            if (pending.isNotEmpty()) item {
                Block(color = c.sunken) {
                    Text(l.label("${pending.size} follow-up(s) queued", "В очереди: ${pending.size}"), style = StudyType.Title, color = c.ink)
                    Text(l.label("Fresh questions chosen from the families you missed.", "Новые задания из семей, где были ошибки."), style = StudyType.Small, color = c.ink)
                    StudyButton(l.label("Practise queued", "Решить очередь"), practise, Modifier.fillMaxWidth(), tone = Tone.Ink, compact = true, arrow = true)
                }
            }
            item {
                Segmented(listOf(NotebookFilter.Open to l.label("Open", "Открытые"), NotebookFilter.Resolved to l.label("Resolved", "Разобранные"), NotebookFilter.All to l.label("All", "Все")),
                    filterValue, { filterValue = it }, Modifier.fillMaxWidth(), fill = true)
            }
            if (shown.isEmpty()) item {
                Text(if (mistakes.isEmpty()) l.label("No wrong or skipped answers yet.", "Пока нет неверных или пропущенных ответов.")
                    else l.label("Nothing in this view.", "Здесь пока пусто."), style = StudyType.Body, color = c.inkSoft)
            }
            items(shown, key = { it.id }) { attempt ->
                val entry = s.notebook[attempt.id]
                val skill = s.pack?.skills?.firstOrNull { it.id == attempt.skillId }?.title?.text(l) ?: attempt.skillId
                val prompt = s.pack?.exercises?.firstOrNull { it.id == attempt.exerciseId }?.prompt
                Column(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(18.dp), c.raised) { openAttempt(attempt.id) }.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Meta(listOfNotNull(skill, shortDate(StudyRhythm.day(attempt, java.time.ZoneId.systemDefault()), l),
                        if (attempt.errorType == "SKIPPED") l.label("skipped", "пропуск") else null,
                        if (attempt.workId in marked) l.label("marked", "отмечено") else null,
                        entry?.cause?.let { causeLabel(it, l) }).joinToString(" · "))
                    prompt?.let { Text(it, style = StudyType.Small, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Text(l.label("You: ", "Вы: ") + attempt.answer.ifBlank { "—" }, style = StudyType.Small.copy(fontSize = 13.sp),
                        color = if (attempt.correct == true) c.ink else c.bad, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = listOfNotNull(
                        if (entry?.note?.isNotBlank() == true) l.label("note written", "заметка") else null,
                        if (entry?.queuedExerciseId != null) l.label("follow-up queued", "в очереди") else null,
                        if (entry?.resolved == true) l.label("resolved", "разобрано") else null)
                    if (status.isNotEmpty()) Meta(status.joinToString(" · "), color = c.ink)
                }
            }
        }
    }
}

fun causeLabel(cause: MistakeCause, l: Language): String = when (cause) {
    MistakeCause.MISREAD_QUESTION -> l.label("Misread the question", "Неверно понял вопрос")
    MistakeCause.MISSED_EVIDENCE -> l.label("Missed the evidence", "Упустил подтверждение")
    MistakeCause.RULE_OR_CONCEPT -> l.label("Rule or concept", "Правило или понятие")
    MistakeCause.CALCULATION_OR_SLIP -> l.label("Calculation or slip", "Вычисление или описка")
    MistakeCause.ANSWER_FORM -> l.label("Answer form or limit", "Форма ответа или лимит")
    MistakeCause.TIME_PRESSURE -> l.label("Time pressure", "Нехватка времени")
    MistakeCause.GUESSED -> l.label("Guessed", "Угадывал")
}

/** Reopen one saved answer against the exact version it answered. Wrong or skipped answers add notebook tools. */
@Composable
fun AttemptReviewScreen(s: StudyUiState, vm: StudyViewModel, attemptId: String, openRevision: (String) -> Unit, close: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val attempt = s.attempts.firstOrNull { it.id == attemptId }
    if (attempt == null) { LaunchedEffect(attemptId) { close() }; return }
    val exercise = rememberExercise(s, vm, attempt.exam, attempt.exerciseId, attempt.exerciseVersion)
    val entry = s.notebook[attempt.id]
    val marked = attempt.workId?.let { s.marks[it]?.marked } == true
    val mistake = attempt.correct == false || attempt.errorType == "SKIPPED" || marked
    Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.ArrowLeft, l.label("Back", "Назад"), close)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(s.pack?.skills?.firstOrNull { it.id == attempt.skillId }?.title?.text(l) ?: attempt.skillId, style = StudyType.Strong, color = c.ink, maxLines = 1)
                Meta(l.label("Saved answer · question v${attempt.exerciseVersion}", "Сохранённый ответ · задание v${attempt.exerciseVersion}"), maxLines = 1)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (exercise == null) {
                Text(l.label("Loading the version you answered. A newer version never replaces its key.", "Загружается версия, на которую вы отвечали. Новая версия не подменяет её ключ."),
                    style = StudyType.Body, color = c.inkSoft)
                return@Column
            }
            Meta(listOfNotNull(when (attempt.correct) { true -> l.label("Correct", "Верно"); false -> l.label("Not correct", "Неверно"); null -> if (attempt.errorType == "SKIPPED") l.label("Skipped", "Пропущено") else l.label("Saved for review", "Сохранено для разбора") },
                "${attempt.elapsedSeconds}s", if (attempt.hintsUsed > 0) l.label("with hint", "с подсказкой") else null,
                if (attempt.isRepeat) l.label("repeat", "повтор") else null).joinToString(" · "), color = if (attempt.correct == false) c.bad else c.ink)
            exercise.passage?.let { passage ->
                Disclosure(l.label("Passage", "Текст"), "${AnswerChecker.wordCount(passage)}") {
                    SelectionContainer { Text(passage, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 26.sp), color = c.ink) }
                }
            }
            exercise.audioAssetPath?.let { ListeningPlayer(it, l) }
            exercise.chart?.let { Chart(it, l) }
            exercise.figure?.let { FigureView(it) }
            exercise.group?.let { GroupContext(it, mapOf(exercise.id to 1)) }
            Text(exercise.prompt, style = StudyType.Question.copy(fontSize = 19.sp, lineHeight = 28.sp), color = c.ink)
            if (exercise.type == ExerciseType.MULTIPLE_CHOICE) ReviewOptions(exercise, attempt.answer)
            AnswerPair(l.label("You", "Вы"), if (attempt.answer.isBlank()) l.label("(blank)", "(пусто)") else optionLabel(exercise, attempt.answer), wrong = attempt.correct?.not())
            if (exercise.acceptedAnswers.isNotEmpty()) AnswerPair(l.label("Key", "Ключ"), exercise.acceptedAnswers.joinToString(" / ") { optionLabel(exercise, it) }, wrong = false)
            Explanation(exercise, l)
            exercise.transcript?.let { transcript ->
                Disclosure(l.label("Audio transcript", "Транскрипт аудио"), null) {
                    SelectionContainer { Text(transcript, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 25.sp), color = c.ink) }
                }
            }
            if (exercise.type == ExerciseType.WRITING && attempt.answer.isNotBlank())
                StudyButton(l.label("Check and revise", "Проверить и доработать"), { openRevision(attempt.id) }, Modifier.fillMaxWidth(), tone = Tone.Quiet, compact = true, arrow = true)
            if (marked) StudyButton(l.label("Marked for review · unmark", "Отмечено для повторения · снять отметку"), { attempt.workId?.let(vm::toggleMark) },
                Modifier.fillMaxWidth(), tone = Tone.Quiet, compact = true, glyph = Glyph.Bookmark)
            if (mistake) NotebookTools(s, vm, attempt, exercise, entry)
        }
    }
}

@Composable
private fun ReviewOptions(exercise: Exercise, answer: String) {
    val c = Study.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        exercise.options.forEachIndexed { index, option ->
            val key = exercise.acceptedAnswers.any { AnswerChecker.normalize(it) == AnswerChecker.normalize(option) }
            val chosen = option == answer
            Row(Modifier.fillMaxWidth().background(if (key) c.marker else if (chosen) c.badSoft else c.raised, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${'A' + index}", Modifier.width(26.dp), style = StudyType.Mono, color = if (key) c.onMarker else c.inkSoft)
                Text(optionLabel(exercise, option), Modifier.weight(1f), style = StudyType.Body, color = if (key) c.onMarker else c.ink)
                when {
                    key -> GlyphIcon(Glyph.Check, tint = c.onMarker, size = 16.dp)
                    chosen -> GlyphIcon(Glyph.Cross, tint = c.bad, size = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun NotebookTools(s: StudyUiState, vm: StudyViewModel, attempt: Attempt, exercise: Exercise, entry: NotebookEntry?) {
    val l = s.language
    val c = Study.colors
    Hairline()
    SectionLabel(l.label("Why was it wrong?", "Почему ошибка?"))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MistakeCause.entries.forEach { cause ->
            val on = entry?.cause == cause
            Text(causeLabel(cause, l), Modifier.tapSurface(RoundedCornerShape(50), if (on) c.ink else c.raised, border = BorderStroke(1.dp, if (on) c.ink else c.line), role = Role.RadioButton) {
                vm.notebookCause(attempt.id, if (on) null else cause)
            }.semantics { selected = on }.padding(horizontal = 14.dp, vertical = 9.dp), style = StudyType.Button.copy(fontSize = 14.sp), color = if (on) c.paper else c.ink)
        }
    }
    key(attempt.id) {
        val note = rememberTextFieldState(entry?.note.orEmpty())
        LaunchedEffect(note) { snapshotFlow { note.text.toString() }.collect { vm.notebookNote(attempt.id, it) } }
        OutlinedTextField(state = note, modifier = Modifier.fillMaxWidth(), label = { Text(l.label("In your words: what went wrong, and what will you check next time?", "Своими словами: в чём ошибка и что проверить в следующий раз?")) },
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8), shape = RoundedCornerShape(16.dp), colors = studyFieldColors())
    }
    val queued = entry?.queuedExerciseId?.let { id -> s.pack?.exercises?.firstOrNull { it.id == id } }
    if (queued != null) MarginNote {
        Meta(if (entry.queuedFromFamily) l.label("Queued · same family", "В очереди · та же семья") else l.label("Queued · similar question in this skill", "В очереди · похожее задание навыка"))
        Text(queued.prompt, style = StudyType.Small, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val answered = s.attempts.any { it.exerciseId == queued.id && it.timestampEpochMillis >= (entry.queuedAt ?: 0) }
        if (answered) Meta(l.label("Answered", "Решено"), color = c.good)
    } else StudyButton(l.label("Queue a fresh question from this family", "Поставить в очередь новое задание этой семьи"), { vm.queueFollowUp(attempt.id, exercise) },
        Modifier.fillMaxWidth(), tone = Tone.Ink, compact = true, glyph = Glyph.Plus)
    StudyButton(if (entry?.resolved == true) l.label("Resolved · reopen", "Разобрано · вернуть") else l.label("Mark as resolved", "Отметить как разобранное"),
        { vm.resolveMistake(attempt.id, entry?.resolved != true) }, Modifier.fillMaxWidth(), tone = Tone.Quiet, compact = true, glyph = if (entry?.resolved == true) Glyph.Check else null)
    Text(l.label("Notes and follow-ups never change the saved result, the key or your skill level.", "Заметки и повторения не меняют сохранённый результат, ключ или уровень навыка."),
        style = StudyType.Small, color = c.inkSoft)
}
