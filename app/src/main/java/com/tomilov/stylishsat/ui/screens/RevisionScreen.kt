package com.tomilov.stylishsat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The exact version a saved response answered. Null while an archived package is still being read. */
@Composable
fun rememberExercise(s: StudyUiState, vm: StudyViewModel, exam: Exam, id: String, version: Int): Exercise? {
    val local = s.pack?.exercises?.firstOrNull { it.exam == exam && it.id == id && it.version == version }
    val found by produceState(local, exam, id, version) {
        if (value == null) value = try { vm.findExercise(exam, id, version) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    return local ?: found
}

private enum class RevisionTab { Check, Revise, Compare }

/** Check a written answer against its own task, revise it as new versions, and compare any two versions. No band is given. */
@Composable
fun RevisionScreen(s: StudyUiState, vm: StudyViewModel, workId: String, close: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val thread = remember(s.revisions, workId) { Revisions.thread(s.revisions.values, workId) }
    val first = thread.firstOrNull()
    if (first == null) { LaunchedEffect(workId) { close() }; return }
    val exercise = rememberExercise(s, vm, first.exam, first.exerciseId, first.exerciseVersion)
    val checklist = remember(exercise, s.pack) { exercise?.let { Revisions.checklist(it, s.pack?.exercises.orEmpty()) }.orEmpty() }
    val saved = thread.filter { it.saved }
    val draft = thread.lastOrNull { !it.saved }
    var tabValue by rememberSaveable(workId) { mutableStateOf(if (draft != null) RevisionTab.Revise else RevisionTab.Check) }
    var selectedValue by rememberSaveable(workId) { mutableIntStateOf(saved.lastOrNull()?.number ?: 1) }
    val selected = saved.firstOrNull { it.number == selectedValue } ?: saved.lastOrNull() ?: first
    Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.Close, l.label("Close", "Закрыть"), close)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(l.label("Check and revise", "Проверка и доработка"), style = StudyType.Strong, color = c.ink)
                Meta(l.label("${saved.size} saved version(s)", "сохранённых версий: ${saved.size}") + if (draft != null) l.label(" · draft open", " · есть черновик") else "", maxLines = 1)
            }
        }
        Segmented(listOf(RevisionTab.Check to l.label("Check", "Проверка"), RevisionTab.Revise to l.label("Revise", "Правка"), RevisionTab.Compare to l.label("Compare", "Сравнение")),
            tabValue, { tabValue = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), fill = true)
        when (tabValue) {
            RevisionTab.Check -> CheckTab(s, vm, exercise, checklist, saved, selected, draft, { selectedValue = it }) { vm.startNextVersion(workId); tabValue = RevisionTab.Revise }
            RevisionTab.Revise -> ReviseTab(s, vm, exercise, checklist, saved, draft, workId) { selectedValue = it; tabValue = RevisionTab.Check }
            RevisionTab.Compare -> CompareTab(s, checklist, saved)
        }
    }
}

@Composable
private fun TaskSummary(exercise: Exercise?, l: Language) {
    val c = Study.colors
    if (exercise == null) {
        Text(l.label("Loading the task version you answered…", "Загружается версия задания, на которую вы отвечали…"), style = StudyType.Small, color = c.inkSoft)
        return
    }
    Disclosure(l.label("Task", "Задание"), "v${exercise.version}") {
        SelectionContainer { Text(exercise.prompt, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 25.sp), color = c.ink) }
        exercise.chart?.let { Chart(it, l) }
        exercise.figure?.let { FigureView(it) }
    }
}

@Composable
private fun LengthLine(exercise: Exercise?, text: String, l: Language) {
    val words = AnswerChecker.wordCount(text)
    val target = exercise?.minWords
    Meta(if (target == null) l.label("$words words", "$words слов") else l.label("$words words · the task asks for at least $target", "$words слов · задание просит не меньше $target"),
        color = if (target != null && words < target) Study.colors.warn else Study.colors.inkSoft)
}

@Composable
private fun ColumnScope.CheckTab(
    s: StudyUiState, vm: StudyViewModel, exercise: Exercise?, checklist: List<TaskCheck>, saved: List<WritingRevision>, selected: WritingRevision,
    draft: WritingRevision?, select: (Int) -> Unit, revise: () -> Unit,
) {
    val l = s.language
    val c = Study.colors
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TaskSummary(exercise, l)
        Text(l.label("Check your own response against this task first. A missing view, position, comparison or figure is something grammar edits cannot fix.",
            "Сначала сверьте свой ответ с заданием. Пропущенную точку зрения, позицию, сравнение или данные не исправить правкой грамматики."),
            style = StudyType.Small, color = c.inkSoft)
        VersionChips(saved, selected.number, l, select)
        Disclosure(l.label("Version ${selected.number}", "Версия ${selected.number}"), "${AnswerChecker.wordCount(selected.text)}") {
            SelectionContainer { Text(selected.text, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 25.sp), color = c.ink) }
        }
        LengthLine(exercise, selected.text, l)
        if (checklist.isEmpty() && exercise != null) Text(l.label("This task has no checklist yet. Compare your response with the prompt line by line.",
            "Для этого задания ещё нет списка проверки. Сверьте ответ с формулировкой задания построчно."), style = StudyType.Small, color = c.inkSoft)
        checklist.forEach { check ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Meta(checkKindLabel(check.kind, l))
                Text(check.text.text(l), style = StudyType.Body, color = c.ink)
                MarkChips(selected.checks[check.id], l) { vm.markCheck(selected.workId, selected.number, check.id, it) }
            }
            Hairline()
        }
        key(selected.id) {
            val plan = rememberTextFieldState(selected.plan)
            LaunchedEffect(plan) { snapshotFlow { plan.text.toString() }.collect { vm.revisionPlan(selected.workId, selected.number, it) } }
            OutlinedTextField(state = plan, modifier = Modifier.fillMaxWidth(), label = { Text(l.label("What will you change next?", "Что вы измените дальше?")) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6), shape = RoundedCornerShape(16.dp), colors = studyFieldColors())
        }
        Text(l.label("These are your own marks against the task. The app gives no band or score for writing.",
            "Это ваши собственные отметки по заданию. Приложение не выставляет band или балл за письмо."), style = StudyType.Small, color = c.inkSoft)
    }
    Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        val next = (saved.lastOrNull()?.number ?: 1) + 1
        StudyButton(if (draft != null) l.label("Continue version $next", "Продолжить версию $next") else l.label("Write version $next", "Написать версию $next"),
            revise, Modifier.fillMaxWidth(), arrow = true)
    }
}

@Composable
private fun VersionChips(saved: List<WritingRevision>, selected: Int, l: Language, select: (Int) -> Unit) {
    val c = Study.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        saved.forEach { version ->
            val on = version.number == selected
            Text(if (version.number == 1) l.label("V1 · submitted", "V1 · отправлено") else "V${version.number}",
                Modifier.tapSurface(RoundedCornerShape(50), if (on) c.ink else c.sunken, role = Role.Tab) { select(version.number) }
                    .semantics { this.selected = on }.padding(horizontal = 14.dp, vertical = 9.dp),
                style = StudyType.Button.copy(fontSize = 14.sp), color = if (on) c.paper else c.ink)
        }
    }
}

@Composable
private fun MarkChips(mark: CheckMark?, l: Language, choose: (CheckMark?) -> Unit) {
    val c = Study.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CheckMark.YES to l.label("Yes", "Да"), CheckMark.PARTLY to l.label("Partly", "Частично"), CheckMark.NO to l.label("No", "Нет")).forEach { (value, text) ->
            val on = mark == value
            val fill = when {
                !on -> c.raised
                value == CheckMark.YES -> c.marker
                value == CheckMark.PARTLY -> c.sunken
                else -> c.badSoft
            }
            Text(text, Modifier.heightIn(min = 44.dp).tapSurface(RoundedCornerShape(12.dp), fill, border = BorderStroke(if (on) 2.dp else 1.dp, if (on) c.ink else c.line), role = Role.RadioButton) {
                choose(if (on) null else value)
            }.semantics { this.selected = on }.padding(horizontal = 16.dp, vertical = 11.dp),
                style = StudyType.Button.copy(fontSize = 14.sp), color = if (on && value == CheckMark.YES) c.onMarker else c.ink)
        }
    }
}

fun checkKindLabel(kind: CheckKind, l: Language): String = when (kind) {
    CheckKind.TASK_PART -> l.label("Part of the question", "Часть вопроса")
    CheckKind.VIEW -> l.label("View", "Точка зрения")
    CheckKind.POSITION -> l.label("Your position", "Ваша позиция")
    CheckKind.SUPPORT -> l.label("Support", "Аргументация")
    CheckKind.OVERVIEW -> l.label("Overview", "Обзор")
    CheckKind.COMPARISON -> l.label("Main comparisons", "Главные сравнения")
    CheckKind.DATA -> l.label("Required data", "Нужные данные")
    CheckKind.ACCURACY -> l.label("Accuracy", "Точность")
}

@Composable
private fun ColumnScope.ReviseTab(
    s: StudyUiState, vm: StudyViewModel, exercise: Exercise?, checklist: List<TaskCheck>, saved: List<WritingRevision>, draft: WritingRevision?,
    workId: String, afterSave: (Int) -> Unit,
) {
    val l = s.language
    val c = Study.colors
    val latest = saved.lastOrNull()
    if (draft != null) {
        val lifecycle = LocalLifecycleOwner.current
        LaunchedEffect(draft.id, lifecycle) {
            lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { delay(1000); vm.revisionTick(workId) } }
        }
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TaskSummary(exercise, l)
        val focus = latest?.let { Revisions.focus(checklist, it.checks) }.orEmpty()
        if (latest != null && (focus.isNotEmpty() || latest.plan.isNotBlank())) MarginNote {
            Meta(l.label("Fix first · from version ${latest.number}", "Исправить сначала · по версии ${latest.number}"))
            focus.forEach { Text("• " + it.text.text(l), style = StudyType.Small, color = c.ink) }
            if (latest.plan.isNotBlank()) Text(latest.plan, style = StudyType.Small, color = c.inkSoft)
        } else if (latest != null && checklist.isNotEmpty()) Text(l.label("Mark the checks for version ${latest.number} first to see what to fix.",
            "Сначала отметьте пункты проверки для версии ${latest.number}, чтобы увидеть, что исправлять."), style = StudyType.Small, color = c.inkSoft)
        if (draft == null) {
            StudyButton(l.label("Start version ${(latest?.number ?: 1) + 1} from version ${latest?.number ?: 1}", "Начать версию ${(latest?.number ?: 1) + 1} на основе версии ${latest?.number ?: 1}"),
                { vm.startNextVersion(workId) }, Modifier.fillMaxWidth(), tone = Tone.Ink)
        } else key(draft.id) {
            val editor = rememberTextFieldState(draft.text)
            LaunchedEffect(editor) { snapshotFlow { editor.text.toString() }.collect { vm.revisionDraft(workId, it) } }
            OutlinedTextField(state = editor, modifier = Modifier.fillMaxWidth(), label = { Text(l.label("Version ${draft.number}", "Версия ${draft.number}")) },
                textStyle = StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 27.sp),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 10, maxHeightInLines = 24), shape = RoundedCornerShape(16.dp), colors = studyFieldColors())
            LengthLine(exercise, editor.text.toString(), l)
            Text(l.label("Saved on this device as you type. Version ${latest?.number ?: 1} stays unchanged.",
                "Сохраняется на устройстве при вводе. Версия ${latest?.number ?: 1} остаётся без изменений."), style = StudyType.Small, color = c.inkSoft)
        }
    }
    if (draft != null) Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        StudyButton(l.label("Save version ${draft.number}", "Сохранить версию ${draft.number}"), { if (vm.saveVersion(workId)) afterSave(draft.number) },
            Modifier.fillMaxWidth(), enabled = draft.text.isNotBlank() && draft.text != latest?.text, arrow = true)
    }
}

@Composable
private fun ColumnScope.CompareTab(s: StudyUiState, checklist: List<TaskCheck>, saved: List<WritingRevision>) {
    val l = s.language
    val c = Study.colors
    var fromValue by rememberSaveable { mutableIntStateOf(saved.firstOrNull()?.number ?: 1) }
    var toValue by rememberSaveable { mutableIntStateOf(saved.lastOrNull()?.number ?: 1) }
    val before = saved.firstOrNull { it.number == fromValue } ?: saved.firstOrNull()
    val after = saved.firstOrNull { it.number == toValue } ?: saved.lastOrNull()
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (saved.size < 2 || before == null || after == null) {
            Text(l.label("Save a revised version to compare it with the one you submitted.", "Сохраните исправленную версию, чтобы сравнить её с отправленной."),
                style = StudyType.Body, color = c.inkSoft)
            return@Column
        }
        Meta(l.label("Before", "До"))
        VersionChips(saved, before.number, l) { fromValue = it }
        Meta(l.label("After", "После"))
        VersionChips(saved, after.number, l) { toValue = it }
        val parts = remember(before.text, after.text) { TextDiff.words(before.text, after.text) }
        val (added, removed) = TextDiff.changedWords(parts)
        Row(Modifier.fillMaxWidth()) {
            Stat("${AnswerChecker.wordCount(before.text)} → ${AnswerChecker.wordCount(after.text)}", l.label("words", "слов"))
            Stat("+$added / −$removed", l.label("changed", "изменено"))
        }
        if (checklist.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(l.label("Your checks", "Ваши отметки"))
            checklist.forEach { check ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(check.text.text(l), Modifier.weight(1f), style = StudyType.Small, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(10.dp))
                    Text("${markLabel(before.checks[check.id], l)} → ${markLabel(after.checks[check.id], l)}", style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
                }
            }
        }
        SectionLabel(l.label("Changes", "Изменения"))
        Meta(l.label("Removed text is struck through; added text is highlighted.", "Удалённый текст зачёркнут, добавленный — выделен."))
        val annotated = remember(parts, c) {
            buildAnnotatedString {
                parts.forEachIndexed { index, part ->
                    if (index > 0 && !part.text.startsWith("\n") && !parts[index - 1].text.endsWith("\n")) append(' ')
                    when (part.kind) {
                        TextDiff.Kind.SAME -> append(part.text)
                        TextDiff.Kind.ADDED -> withStyle(SpanStyle(background = c.marker, color = c.onMarker)) { append(part.text) }
                        TextDiff.Kind.REMOVED -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = c.bad)) { append(part.text) }
                    }
                }
            }
        }
        SelectionContainer { Text(annotated, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 26.sp), color = c.ink) }
    }
}

private fun markLabel(mark: CheckMark?, l: Language) = when (mark) {
    CheckMark.YES -> l.label("yes", "да")
    CheckMark.PARTLY -> l.label("partly", "частично")
    CheckMark.NO -> l.label("no", "нет")
    null -> "—"
}
