package com.tomilov.stylishsat.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/** A section or an exam paper: one clock per part, one playback position per recording. */
@Composable
fun PaperScreen(s: StudyUiState, vm: StudyViewModel, runId: String, openRevision: (String) -> Unit, close: () -> Unit) {
    val run = s.papers[runId]
    if (run == null) { LaunchedEffect(runId) { close() }; return }
    val c = Study.colors
    Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding().imePadding()) {
        if (run.active && run.phase != PaperPhase.FINISHED) PaperWorking(s, vm, run, close) else PaperResults(s, run, openRevision, close)
    }
}

fun paperTitle(run: PaperRun, l: Language): String = when (run.kind) {
    PaperKind.SECTION -> run.parts.firstOrNull()?.title.orEmpty()
    PaperKind.SAT -> when {
        run.parts.all { it.stage?.startsWith("RW") == true } && run.plannedStages.none { it.startsWith("MATH") } -> l.label("SAT · Reading and Writing", "SAT · Reading and Writing")
        run.parts.all { it.stage?.startsWith("MATH") == true } -> l.label("SAT · Math", "SAT · Math")
        else -> l.label("Digital SAT · full paper", "Digital SAT · полный вариант")
    }
    PaperKind.IELTS_READING -> "IELTS Academic Reading"
    PaperKind.IELTS_LISTENING -> "IELTS Listening"
    PaperKind.IELTS_WRITING -> "IELTS Academic Writing"
}

@Composable
private fun PaperClock(run: PaperRun, part: PaperPart) {
    val c = Study.colors
    val remaining = run.remaining(part)
    val (text, color) = when {
        remaining == null -> clock(run.elapsed(part)) to c.inkSoft
        remaining >= 0 -> clock(remaining) to if (remaining <= 60 && run.phase != PaperPhase.BREAK) c.bad else c.ink
        else -> "+" + clock(-remaining) to c.warn
    }
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
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
    var discardValue by remember { mutableStateOf(false) }
    val answered = run.answered(part)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyph.Close, l.label("Close and keep answers", "Закрыть с сохранением"), close)
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(if (run.kind == PaperKind.SECTION) part.title else paperTitle(run, l), style = StudyType.Strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Meta(when {
                run.phase == PaperPhase.BREAK -> l.label("Break", "Перерыв")
                run.kind == PaperKind.SECTION -> listOf(if (run.strict) l.label("Exam conditions", "Экзаменационный режим") else l.label("Section practice", "Практика секции"),
                    l.label("$answered/${part.exercises.size} answered", "отвечено $answered/${part.exercises.size}")).joinToString(" · ")
                else -> listOf(part.title, l.label("$answered/${part.exercises.size} answered", "отвечено $answered/${part.exercises.size}")).joinToString(" · ")
            }, maxLines = 1)
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
    when {
        run.phase == PaperPhase.BREAK -> BreakView(run, vm, l)
        part.oneAtATime -> ModuleView(run, part, vm, l, timeUp)
        else -> SheetView(run, part, vm, l, timeUp)
    }
    if (discardValue) AlertDialog(onDismissRequest = { discardValue = false }, containerColor = c.paper,
        text = { Text(l.label("Leave without marking? Parts you already submitted stay in your history; the current part is not marked and its typed answers stay in the saved record.",
            "Выйти без проверки? Уже сданные части останутся в истории; текущая часть не проверяется, введённые ответы сохранятся в записи."), style = StudyType.Body, color = c.ink) },
        confirmButton = { TextButton(onClick = { discardValue = false; vm.abandonPaper(run.id); close() }) { Text(l.label("Leave", "Выйти"), style = StudyType.Button, color = c.bad) } },
        dismissButton = { TextButton(onClick = { discardValue = false }) { Text(l.label("Stay", "Остаться"), style = StudyType.Button, color = c.ink) } })
}

@Composable
private fun TimeUpBanner(run: PaperRun, vm: StudyViewModel, l: Language) {
    val c = Study.colors
    Block(color = c.badSoft) {
        Text(l.label("Time's up. Your answers are saved.", "Время вышло. Ответы сохранены."), style = StudyType.Title, color = c.ink)
        Text(l.label("Submit now, or keep working: the extra time is recorded and your answers still count as independent evidence.",
            "Сдайте сейчас или продолжайте: дополнительное время записывается, ответы засчитываются как самостоятельные."), style = StudyType.Small, color = c.ink)
        StudyButton(l.label("Keep working", "Продолжить"), { vm.paperOvertime(run.id) }, tone = Tone.Ink, compact = true)
    }
}

@Composable
private fun ColumnScope.SubmitBar(run: PaperRun, part: PaperPart, vm: StudyViewModel, l: Language, label: String? = null) {
    val c = Study.colors
    var confirmValue by remember { mutableStateOf(false) }
    val blank = part.exercises.size - run.answered(part)
    Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        StudyButton(label ?: when {
            run.kind == PaperKind.SECTION && !run.strict -> l.label("Check answers", "Проверить ответы")
            run.kind == PaperKind.SECTION -> l.label("Submit section", "Сдать секцию")
            else -> l.label("Submit ${part.title}", "Сдать: ${part.title}")
        }, { if (blank > 0) confirmValue = true else vm.submitPaperPart(run.id) }, Modifier.fillMaxWidth(), arrow = true)
    }
    if (confirmValue) AlertDialog(onDismissRequest = { confirmValue = false }, containerColor = c.paper,
        text = { Text(l.label("$blank question(s) are blank. A blank is recorded as not answered. You cannot return to this part after submitting.",
            "Без ответа: $blank. Пустой ответ записывается как пропуск. После сдачи вернуться к этой части нельзя."), style = StudyType.Body, color = c.ink) },
        confirmButton = { TextButton(onClick = { confirmValue = false; vm.submitPaperPart(run.id) }) { Text(l.label("Submit", "Сдать"), style = StudyType.Button, color = c.ink) } },
        dismissButton = { TextButton(onClick = { confirmValue = false }) { Text(l.label("Keep working", "Продолжить"), style = StudyType.Button, color = c.inkSoft) } })
}

@Composable
private fun ColumnScope.BreakView(run: PaperRun, vm: StudyViewModel, l: Language) {
    val c = Study.colors
    val next = run.part
    val left = next?.let { run.remaining(it) } ?: 0
    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Meta(l.label("Break before ${next?.title.orEmpty()}", "Перерыв перед: ${next?.title.orEmpty()}"), color = c.ink)
        Text(clock(left.coerceAtLeast(0)), style = StudyType.Display.copy(fontSize = 72.sp, lineHeight = 72.sp), color = c.ink)
        Text(l.label("Stand up, drink some water and rest your eyes. The next module keeps its own clock and starts when you continue.",
            "Встаньте, выпейте воды и дайте отдых глазам. У следующего модуля свой таймер, он начнётся, когда вы продолжите."), style = StudyType.Body, color = c.inkSoft)
    }
    Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        StudyButton(l.label("Continue to ${next?.title.orEmpty()}", "Продолжить: ${next?.title.orEmpty()}"), { vm.endBreak(run.id) }, Modifier.fillMaxWidth(), arrow = true)
    }
}

/** All questions of a part on one scroll, grouped by passage or recording; passages open in their own tab. */
@Composable
private fun ColumnScope.SheetView(run: PaperRun, part: PaperPart, vm: StudyViewModel, l: Language, timeUp: Boolean) {
    val c = Study.colors
    val numbers = remember(part) { Sections.numbers(part.exercises) }
    val sources = part.sourceIds
    val audio = part.audioPaths
    // Under exam conditions the next unplayed recording is the live one; its questions show by default.
    val currentAudio = audio.firstOrNull { run.audio[it]?.completed != true }
    var sourceValue by rememberSaveable(run.id, part.id) { mutableIntStateOf(0) }
    LaunchedEffect(currentAudio) {
        if (run.strict && currentAudio != null) sourceValue = sources.indexOfFirst { source -> part.exercises.any { it.sourceId == source && it.audioAssetPath == currentAudio } }.coerceAtLeast(0)
    }
    val source = sources.getOrElse(sourceValue) { sources.first() }
    val items = part.exercises.filter { it.sourceId == source }
    val passage = items.firstNotNullOfOrNull { it.passage }
    var tabValue by rememberSaveable(run.id, part.id, source) { mutableIntStateOf(if (passage != null) 0 else 1) }
    var highlightingValue by rememberSaveable(run.id) { mutableStateOf(false) }
    if (sources.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEachIndexed { index, id ->
            val on = index == sourceValue
            val group = part.exercises.filter { it.sourceId == id }
            val range = "${numbers.getValue(group.first().id)}–${numbers.getValue(group.last().id)}"
            val label = if (audio.isNotEmpty()) l.label("Part ${index + 1} · $range", "Часть ${index + 1} · $range") else l.label("Passage ${index + 1} · $range", "Текст ${index + 1} · $range")
            Text(label, Modifier.tapSurface(RoundedCornerShape(50), if (on) c.ink else c.sunken, role = Role.Tab) { sourceValue = index }
                .semantics { selected = on }.padding(horizontal = 14.dp, vertical = 8.dp), style = StudyType.Button.copy(fontSize = 13.sp), color = if (on) c.paper else c.ink)
        }
    }
    if (passage != null) Segmented(listOf(0 to l.label("Passage", "Текст"), 1 to l.label("Questions", "Вопросы")), tabValue, { tabValue = it },
        Modifier.fillMaxWidth().padding(horizontal = 16.dp), fill = true)
    val passageScroll = rememberScrollState()
    val questionScroll = rememberScrollState()
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(if (tabValue == 0 && passage != null) passageScroll else questionScroll)
        .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (tabValue == 0 && passage != null) {
            items.firstNotNullOfOrNull { it.sourceTitle }?.let { Text(it, style = StudyType.Title, color = c.ink) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Meta(l.label("${AnswerChecker.wordCount(passage)} words", "${AnswerChecker.wordCount(passage)} слов"), Modifier.weight(1f))
                Row(Modifier.tapSurface(RoundedCornerShape(50), if (highlightingValue) c.ink else c.sunken, role = Role.Switch) { highlightingValue = !highlightingValue }
                    .semantics { selected = highlightingValue }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Marker, tint = if (highlightingValue) c.paper else c.ink, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(l.label("Highlight", "Выделять"), style = StudyType.Button.copy(fontSize = 13.sp), color = if (highlightingValue) c.paper else c.ink)
                }
            }
            HighlightablePassage(passage, run.highlights["source:$source"].orEmpty(), highlightingValue, StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 28.sp)) {
                vm.paperHighlight(run.id, "source:$source", it)
            }
        } else {
            if (timeUp) TimeUpBanner(run, vm, l)
            // Under exam conditions the player stays on the live recording while you look at any part's questions.
            val playerPath = if (run.strict) currentAudio ?: audio.lastOrNull() else items.firstNotNullOfOrNull { it.audioAssetPath }
            playerPath?.let { path ->
                if (audio.size > 1) Meta(l.label("Recording ${audio.indexOf(path) + 1} of ${audio.size}", "Запись ${audio.indexOf(path) + 1} из ${audio.size}"), color = c.ink)
                key(path) {
                    if (run.strict) SectionAudio(path, run.audio[path] ?: AudioProgress(), true, l) { position, completed -> vm.paperAudio(run.id, path, position, completed) }
                    else {
                        // Practice keeps one saved playback position and adds speed and loop; the transcript waits until marking.
                        val clip = rememberClipPlayer(path, run.audio[path]?.positionMs ?: 0) { position, completed -> vm.paperAudio(run.id, path, position, completed) }
                        PracticePlayer(clip, l, l.label("Pause, rewind, slow down or loop while you practise.", "Ставьте на паузу, перематывайте, замедляйте или повторяйте во время практики."))
                    }
                }
                if (run.phase == PaperPhase.TRANSFER) Text(l.label("All recordings have finished. Use the check time to complete and review your answers in every part.",
                    "Все записи прозвучали. Используйте время на проверку, чтобы дописать и проверить ответы во всех частях."), style = StudyType.Small, color = c.inkSoft)
            }
            QuestionList(run, items, numbers, enabled = !timeUp, l) { key, value -> vm.paperAnswer(run.id, key, value) }
        }
    }
    SubmitBar(run, part, vm, l)
}

/** Questions in order; consecutive members of one group share their instruction, list, text or figure. */
@Composable
private fun QuestionList(run: PaperRun, items: List<Exercise>, numbers: Map<String, Int>, enabled: Boolean, l: Language, answer: (String, String) -> Unit) {
    val blocks = remember(items) { items.fold(mutableListOf<MutableList<Exercise>>()) { acc, item ->
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
    val open = StudyPlanner.openResponse(exercise)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            QuestionNumber(number, if (saved.isNotBlank()) Mark.Done else Mark.Todo)
            Spacer(Modifier.width(10.dp))
            Text(exercise.prompt, Modifier.weight(1f).padding(top = 3.dp), style = StudyType.Body.copy(fontSize = 17.sp, lineHeight = 25.sp), color = c.ink)
        }
        exercise.chart?.let { Chart(it, l) }
        exercise.figure?.let { FigureView(it, mapOf(exercise.id to number)) }
        AnswerInput(exercise, saved, enabled, number, l, open) { answer(exercise.versionKey, it) }
    }
}

/** Choice rows, a key grid for shared lists, a one-line field or an essay editor. The editor owns its text. */
@Composable
private fun AnswerInput(exercise: Exercise, saved: String, enabled: Boolean, number: Int, l: Language, open: Boolean,
    eliminated: List<String> = emptyList(), eliminating: Boolean = false, eliminate: (String) -> Unit = {}, answer: (String) -> Unit) {
    when {
        exercise.usesGroupList() -> KeyChips(exercise, saved, enabled, answer)
        exercise.type == ExerciseType.MULTIPLE_CHOICE -> ChoiceList(exercise, saved, enabled, eliminated, eliminating, eliminate, answer)
        else -> {
            val state = rememberTextFieldState(initialText = saved)
            LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { answer(it) } }
            OutlinedTextField(state = state, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (open) l.label("Your response", "Ваш ответ") else l.label("Answer $number", "Ответ $number")) },
                lineLimits = if (open) TextFieldLineLimits.MultiLine(minHeightInLines = 12, maxHeightInLines = 30) else TextFieldLineLimits.SingleLine,
                textStyle = if (open) StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 27.sp) else StudyType.Body.copy(fontSize = 17.sp),
                supportingText = {
                    val text = when {
                        open -> "${AnswerChecker.wordCount(state.text.toString())} ${l.label("words", "слов")}" + (exercise.minWords?.let { l.label(" · at least $it", " · не меньше $it") } ?: "")
                        exercise.wordLimit != null -> l.label("No more than ${exercise.wordLimit} word(s).", "Не более ${exercise.wordLimit} слов.")
                        exercise.type == ExerciseType.NUMERIC -> l.label("A number, decimal or fraction such as 0.5 or 1/2.", "Число, десятичная или обычная дробь: 0.5 или 1/2.")
                        else -> null
                    }
                    text?.let { Text(it, style = StudyType.Small.copy(fontSize = 13.sp)) }
                },
                shape = RoundedCornerShape(14.dp), colors = studyFieldColors())
        }
    }
}

/** One SAT question per screen, with mark-for-review, skipping by moving on, and a review page before submitting. */
@Composable
private fun ColumnScope.ModuleView(run: PaperRun, part: PaperPart, vm: StudyViewModel, l: Language, timeUp: Boolean) {
    val c = Study.colors
    val total = part.exercises.size
    if (run.reviewing) {
        ModuleReview(run, part, vm, l, timeUp)
        return
    }
    val index = run.itemIndex.coerceIn(0, total - 1)
    val exercise = part.exercises[index]
    val flagged = exercise.versionKey in run.flagged
    var toolValue by remember(exercise.versionKey) { mutableStateOf<WorkTool?>(null) }
    var eliminatingValue by rememberSaveable(run.id) { mutableStateOf(false) }
    var highlightingValue by rememberSaveable(run.id) { mutableStateOf(false) }
    when (toolValue) {
        WorkTool.Calculator -> CalculatorSheet(vm, l) { toolValue = null }
        WorkTool.Reference -> ReferenceSheet(l) { toolValue = null }
        WorkTool.Note -> NoteSheet(run.id + exercise.versionKey, run.notes[exercise.versionKey].orEmpty(), l, { vm.paperNote(run.id, exercise.versionKey, it) }) { toolValue = null }
        null -> Unit
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.tapSurface(RoundedCornerShape(50), c.sunken) { vm.paperReview(run.id, true) }.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(l.label("Question ${index + 1} of $total", "Вопрос ${index + 1} из $total"), style = StudyType.Button.copy(fontSize = 14.sp), color = c.ink)
            Spacer(Modifier.width(6.dp))
            GlyphIcon(Glyph.ChevronDown, tint = c.inkSoft, size = 16.dp)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.tapSurface(RoundedCornerShape(50), if (flagged) c.marker else c.paper, border = BorderStroke(1.dp, if (flagged) c.marker else c.line), role = Role.Checkbox) {
            vm.paperFlag(run.id, exercise.versionKey)
        }.semantics { selected = flagged }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Bookmark, tint = if (flagged) c.onMarker else c.ink, size = 16.dp, filled = flagged)
            Spacer(Modifier.width(6.dp))
            Text(l.label("Mark for review", "Отметить"), style = StudyType.Button.copy(fontSize = 13.sp), color = if (flagged) c.onMarker else c.ink)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        ToolBar(exercise, ToolState(eliminatingValue, highlightingValue, run.notes[exercise.versionKey].orEmpty().isNotBlank()), l,
            open = { toolValue = it }, eliminate = { eliminatingValue = !eliminatingValue }, highlight = { highlightingValue = !highlightingValue }, mark = null)
    }
    val scroll = rememberScrollState()
    LaunchedEffect(index) { scroll.scrollTo(0) }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (timeUp) TimeUpBanner(run, vm, l)
        exercise.passage?.let { passage ->
            Block(padding = 18.dp) {
                HighlightablePassage(passage, run.highlights[exercise.versionKey].orEmpty(), highlightingValue && !timeUp, StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 28.sp)) {
                    vm.paperHighlight(run.id, exercise.versionKey, it)
                }
            }
        }
        exercise.chart?.let { Chart(it, l) }
        exercise.figure?.let { FigureView(it) }
        Text(exercise.prompt, style = StudyType.Question.copy(fontSize = 19.sp, lineHeight = 28.sp), color = c.ink)
        key(run.id, exercise.versionKey) {
            AnswerInput(exercise, run.answers[exercise.versionKey].orEmpty(), !timeUp, index + 1, l, false,
                run.eliminated[exercise.versionKey].orEmpty(), eliminatingValue, { vm.paperEliminate(run.id, exercise.versionKey, it) }) { vm.paperAnswer(run.id, exercise.versionKey, it) }
        }
    }
    Row(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StudyButton(l.label("Back", "Назад"), { vm.paperGo(run.id, index - 1) }, Modifier.weight(1f), tone = Tone.Quiet, enabled = index > 0)
        StudyButton(if (index + 1 == total) l.label("Review", "К проверке") else l.label("Next", "Далее"),
            { if (index + 1 == total) vm.paperReview(run.id, true) else vm.paperGo(run.id, index + 1) }, Modifier.weight(1f), arrow = true)
    }
}

@Composable
private fun ColumnScope.ModuleReview(run: PaperRun, part: PaperPart, vm: StudyViewModel, l: Language, timeUp: Boolean) {
    val c = Study.colors
    val blank = part.exercises.count { run.answers[it.versionKey].orEmpty().isBlank() }
    val marked = part.exercises.count { it.versionKey in run.flagged }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (timeUp) TimeUpBanner(run, vm, l)
        Text(l.label("Check your work", "Проверьте работу"), style = StudyType.Headline.copy(fontSize = 26.sp, lineHeight = 30.sp), color = c.ink)
        Text(l.label("$blank unanswered · $marked marked for review. Tap a number to go back to that question.",
            "Без ответа: $blank · отмечено: $marked. Нажмите номер, чтобы вернуться к вопросу."), style = StudyType.Small, color = c.inkSoft)
        if (part.shortfall > 0) Text(l.label("This module has ${part.shortfall} fewer question(s) than the standard length because fewer unseen items remain.",
            "В модуле на ${part.shortfall} вопр. меньше стандарта: новых заданий осталось меньше."), style = StudyType.Small, color = c.warn)
        part.exercises.chunked(6).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { column, exercise ->
                    val index = row * 6 + column
                    val answered = run.answers[exercise.versionKey].orEmpty().isNotBlank()
                    val flagged = exercise.versionKey in run.flagged
                    Box(Modifier.size(46.dp).tapSurface(RoundedCornerShape(12.dp), if (answered) c.ink else c.paper,
                        border = BorderStroke(if (flagged) 2.dp else 1.dp, if (flagged) c.warn else c.line)) { vm.paperGo(run.id, index) }
                        .semantics { contentDescription = "${index + 1}" + (if (answered) "" else l.label(", unanswered", ", без ответа")) + (if (flagged) l.label(", marked", ", отмечен") else "") },
                        contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = StudyType.Mono, color = if (answered) c.paper else c.ink)
                        if (flagged) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).clip(CircleShape).background(c.warn))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Meta(l.label("■ answered", "■ отвечено")); Meta(l.label("□ unanswered", "□ без ответа")); Meta(l.label("● marked", "● отмечено"), color = c.warn)
        }
        StudyButton(l.label("Back to question ${run.itemIndex + 1}", "Вернуться к вопросу ${run.itemIndex + 1}"), { vm.paperReview(run.id, false) }, Modifier.fillMaxWidth(), tone = Tone.Quiet, compact = true)
    }
    SubmitBar(run, part, vm, l, l.label("Submit module", "Сдать модуль"))
}

@Composable
private fun ColumnScope.PaperResults(s: StudyUiState, run: PaperRun, openRevision: (String) -> Unit, close: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val submitted = run.parts.filter { it.id in run.submittedParts }
    val outcomes = remember(run) { submitted.associateWith { PaperScoring.score(it, run.answers) } }
    val raw = remember(outcomes) { PaperScoring.raw(outcomes.values.flatten()) }
    val seconds = submitted.sumOf { run.elapsed(it) + run.transferElapsed(it) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyph.Close, l.label("Close", "Закрыть"), close)
        Text(paperTitle(run, l), Modifier.weight(1f).padding(horizontal = 6.dp), style = StudyType.Strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Meta(when {
            run.abandoned -> l.label("Left without marking", "Завершено без проверки")
            run.kind == PaperKind.SECTION -> (if (run.strict) l.label("Exam conditions", "Экзаменационный режим") else l.label("Section practice", "Практика секции")) + " · " + l.label("marked", "проверено")
            else -> l.label("Exam mode · uncalibrated", "Экзаменационный режим · без калибровки")
        }, color = c.ink)
        if (raw.closed > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${raw.correct}", style = StudyType.Display.copy(fontSize = 96.sp, lineHeight = 90.sp), color = c.ink)
                Text("/${raw.closed}", Modifier.padding(bottom = 12.dp), style = StudyType.Display.copy(fontSize = 40.sp, lineHeight = 40.sp), color = c.inkFaint)
            }
            MarkedText(l.label("raw correct", "верных ответов"), StudyType.Title)
        }
        Row(Modifier.fillMaxWidth()) {
            Stat(clock(seconds), l.label("working time", "время работы"))
            Stat("${raw.answered}/${outcomes.values.sumOf { it.size }}", l.label("answered", "отвечено"))
        }
        Text(if (run.kind == PaperKind.SECTION) l.label("A raw count of this section only. It is not an IELTS band.", "Только число верных ответов в этой секции. Это не IELTS band.")
            else l.label("Raw counts and time only. No scaled SAT score or IELTS band is calculated, and the independent accuracy in Progress stays a training indicator.",
                "Только число верных ответов и время. Шкальный балл SAT или IELTS band не рассчитываются, а точность без подсказок в «Прогрессе» остаётся учебным показателем."),
            style = StudyType.Small, color = c.inkSoft)
        if (run.overtimeParts.isNotEmpty()) Text(l.label("You kept working past a clock. The extra time is included; those answers count as usual.",
            "Вы продолжили после окончания времени. Дополнительное время учтено; эти ответы засчитаны как обычно."), style = StudyType.Small, color = c.inkSoft)
        outcomes.forEach { (part, items) ->
            PartResult(s, run, part, items, openRevision)
        }
        if (run.abandoned && run.plannedStages.isNotEmpty()) Text(l.label("Unstarted modules were not built.", "Не начатые модули не собирались."), style = StudyType.Small, color = c.inkSoft)
    }
    Column(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        StudyButton(l.label("Done", "Готово"), close, Modifier.fillMaxWidth(), arrow = true)
    }
}

@Composable
private fun PartResult(s: StudyUiState, run: PaperRun, part: PaperPart, items: List<PaperScoring.Outcome>, openRevision: (String) -> Unit) {
    val l = s.language
    val c = Study.colors
    val raw = PaperScoring.raw(items)
    val numbers = Sections.numbers(part.exercises)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Hairline()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(part.title, Modifier.weight(1f), style = StudyType.Title.copy(fontSize = 18.sp), color = c.ink)
            if (raw.closed > 0) Text("${raw.correct}/${raw.closed}", style = StudyType.Numeral.copy(fontSize = 24.sp, lineHeight = 26.sp), color = c.ink)
        }
        Meta(listOfNotNull(
            part.timeLimitSeconds?.let { l.label("${clock(run.elapsed(part))} of ${clock(it)}", "${clock(run.elapsed(part))} из ${clock(it)}") } ?: clock(run.elapsed(part)),
            if (part.transferSeconds > 0) l.label("check time ${clock(run.transferElapsed(part))}", "проверка ${clock(run.transferElapsed(part))}") else null,
            if (part.id in run.overtimeParts) l.label("overtime", "сверх времени") else null,
            when (part.route) {
                ModuleRoute.HIGHER -> l.label("harder second module", "более сложный второй модуль")
                ModuleRoute.LOWER -> l.label("easier second module", "более лёгкий второй модуль")
                null -> null
            },
            if (part.shortfall > 0) l.label("${part.shortfall} short of standard length", "короче стандарта на ${part.shortfall}") else null,
        ).joinToString(" · "))
        if (part.route != null) Text(l.label("Routed by a practice rule (${(ExamPapers.HIGHER_ROUTE_SHARE * 100).toInt()}% correct in module 1), not the official adaptive design.",
            "Маршрут выбран учебным правилом (${(ExamPapers.HIGHER_ROUTE_SHARE * 100).toInt()}% верных в модуле 1), а не официальной адаптивной схемой."), style = StudyType.Small, color = c.inkSoft)
        items.filter { StudyPlanner.openResponse(it.exercise) }.forEach { outcome ->
            val attempt = s.attempts.lastOrNull { it.runId == run.id && it.exerciseId == outcome.exercise.id }
            Text(l.label("${AnswerChecker.wordCount(outcome.answer)} words", "${AnswerChecker.wordCount(outcome.answer)} слов") +
                (outcome.exercise.minWords?.let { l.label(" · task asks for at least $it", " · задание: не меньше $it") } ?: ""), style = StudyType.Body, color = c.ink)
            if (attempt != null && outcome.answer.isNotBlank()) StudyButton(l.label("Check against the task and revise", "Сверить с заданием и доработать"),
                { openRevision(attempt.id) }, Modifier.fillMaxWidth(), tone = Tone.Ink, compact = true, arrow = true)
        }
        val closed = items.filterNot { StudyPlanner.openResponse(it.exercise) }
        if (closed.isNotEmpty()) Disclosure(l.label("Questions and keys", "Вопросы и ключи"), "${closed.size}", initiallyOpen = run.kind == PaperKind.SECTION) {
            closed.forEach { outcome ->
                val exercise = outcome.exercise
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        QuestionNumber(numbers.getValue(exercise.id), when (outcome.correct) { true -> Mark.Right; false -> Mark.Wrong; null -> Mark.Todo })
                        Spacer(Modifier.width(10.dp))
                        Text(exercise.prompt, Modifier.weight(1f).padding(top = 3.dp), style = StudyType.Small.copy(fontSize = 15.sp, lineHeight = 21.sp), color = c.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    AnswerPair(l.label("You", "Вы"), if (outcome.answer.isBlank()) l.label("(blank)", "(пусто)") else optionLabel(exercise, outcome.answer), wrong = outcome.correct?.not())
                    if (exercise.acceptedAnswers.isNotEmpty()) AnswerPair(l.label("Key", "Ключ"), exercise.acceptedAnswers.joinToString(" / ") { optionLabel(exercise, it) }, wrong = false)
                    Text(exercise.explanation.text(l), style = StudyType.Small, color = c.inkSoft)
                }
            }
        }
        // After marking, each recording can be replayed with speed, loop and clickable transcript times.
        part.audioPaths.forEachIndexed { index, path ->
            val item = part.exercises.first { it.audioAssetPath == path }
            Disclosure(if (part.audioPaths.size > 1) l.label("Replay and transcript · part ${index + 1}", "Повтор и транскрипт · часть ${index + 1}") else l.label("Replay and transcript", "Повтор и транскрипт"), null) {
                val clip = rememberClipPlayer(path)
                PracticePlayer(clip, l, l.label("Tap a time to replay that part.", "Нажмите на время, чтобы переслушать фрагмент."))
                if (item.transcriptSegments.isNotEmpty()) TranscriptTimes(clip, item.transcriptSegments, l)
                else item.transcript?.let { SelectionContainer { Text(it, style = StudyType.Reading.copy(fontSize = 16.sp, lineHeight = 25.sp), color = c.ink) } }
            }
        }
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
