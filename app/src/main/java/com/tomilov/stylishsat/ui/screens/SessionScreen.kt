package com.tomilov.stylishsat.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.ai.*
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.speech.RecorderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(s: StudyUiState, vm: StudyViewModel, modifier: Modifier, close: () -> Unit) {
    val session = s.session ?: return
    val exercise = session.exercise ?: return
    val l = s.language
    val answerEnabled = session.result == null && !session.answerLockedByTimeLimit
    val answerState = key(session.id, session.index, session.draftRevision) { rememberTextFieldState(initialText = session.draft) }
    if (exercise.type != ExerciseType.MULTIPLE_CHOICE && session.result == null && !session.finished) {
        LaunchedEffect(answerState) { snapshotFlow { answerState.text.toString() }.collect { vm.draft(it, session.stepKey) } }
    }
    val scrollState = rememberScrollState()
    val captureValue by vm.runtime.recorder.state.collectAsStateWithLifecycle()
    LaunchedEffect(session.id, session.index, session.lessonSeen, session.finished) { scrollState.scrollTo(0) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = close) { Text(l.label("← Today", "← Сегодня")) }
            Spacer(Modifier.weight(1f))
            Text("${session.index + 1} / ${session.stepCount}", style = MaterialTheme.typography.labelLarge)
        }
        if (!session.finished) TextButton(onClick = {
            if (exercise.type != ExerciseType.MULTIPLE_CHOICE && session.result == null && session.lessonSeen && session.activity?.isLesson != true)
                vm.draft(answerState.text.toString(), session.stepKey)
            vm.finishForNow()
        }, enabled = captureValue !is RecorderState.RecordingAudio, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(l.label("Save and finish for today", "Сохранить и закончить на сегодня"))
        }
        LinearProgressIndicator(progress = { if (session.finished && !session.stoppedEarly) 1f else (session.index + if (session.result != null) 1 else 0).toFloat() / session.stepCount }, modifier = Modifier.fillMaxWidth())
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (session.finished) {
                PageTitle(if (session.stoppedEarly) l.label("SAVED FOR LATER", "СОХРАНЕНО НА ПОТОМ") else l.label("SESSION COMPLETE", "СЕССИЯ ЗАВЕРШЕНА"), if (session.stoppedEarly) l.label("Continue when\nyou're ready.", "Продолжите, когда\nбудете готовы.") else l.label("One step\nfurther.", "Ещё один\nшаг вперёд."), if (s.pendingWrites == 0) l.label("Your answers and next steps are saved on this device.", "Ответы и следующие шаги сохранены на устройстве.") else l.label("Saving your answers and next steps…", "Сохраняем ответы и следующие шаги…"))
                StudyCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(if (session.stoppedEarly) l.label("Unfinished answers stay ungraded", "Незаконченные ответы остаются без оценки") else l.label("${session.stepCount} study steps completed", "Пройдено учебных шагов: ${session.stepCount}"), style = MaterialTheme.typography.titleLarge)
                    if (session.stoppedEarly) SmallNote(if (session.courseDay != null) l.label("Unfinished work continues in the next days of your course.", "Незавершённая работа продолжится в следующих днях курса.") else l.label("Open your saved drafts on Today to continue an answer.", "Откройте сохранённые черновики на странице «Сегодня», чтобы продолжить ответ."))
                    if (!session.stoppedEarly) SmallNote(l.label("This is a preliminary training observation. Practise new items to confirm what you know; open responses need review.", "Это предварительное учебное наблюдение. Подтверждайте знания новыми заданиями; открытые ответы требуют разбора."))
                    Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text(l.label("See my route →", "К моему маршруту →")) }
                }
                return@Column
            }
            val skill = s.pack!!.skills.find { it.id == exercise.skillId }
            Eyebrow("${s.exam} · ${skill?.title?.text(l) ?: exercise.skillId}")
            val plannedActivity = session.activity
            if (plannedActivity?.kind == ActivityKind.REVIEW && plannedActivity.lessonId == null) {
                val attempt = s.attempts.lastOrNull { it.exam == session.exam && it.exerciseId == exercise.id && it.exerciseVersion == exercise.version }
                PageTitle(l.label("REVIEW YOUR CHECK", "РАЗБОР ПРОВЕРКИ"), l.label("Explain your next step.", "Объясните следующий шаг."))
                if (attempt != null) {
                    Text(exercise.prompt, style = MaterialTheme.typography.titleMedium)
                    StudyCard { Eyebrow(l.label("Your saved answer", "Ваш сохранённый ответ")); SelectionContainer { Text(attempt.answer) } }
                    StudyCard(color = MaterialTheme.colorScheme.primaryContainer) {
                        if (exercise.acceptedAnswers.isNotEmpty()) Text("${l.label("Prepared answer", "Ответ из ключа")}: ${exercise.acceptedAnswers.joinToString(" / ")}")
                        Text(exercise.explanation.text(l), lineHeight = 25.sp)
                        exercise.evidence?.let { Text(it) }
                        exercise.typicalErrors.forEach { SmallNote("• ${it.text(l)}") }
                    }
                    exercise.sampleAnswer?.let { StudyCard { Eyebrow(l.label("Illustrative answer", "Пример ответа")); Text(it) } }
                    exercise.sampleAudioAssetPath?.let { ListeningPlayer(it, l, sample = true) }
                    SmallNote(l.label("Explain the rule in your own words. Identify one useful correction, then choose what to practise next. This review does not create another graded attempt.", "Объясните правило своими словами. Выберите одно полезное исправление и тему для следующей практики. Разбор не создаёт новую оценённую попытку."))
                } else {
                    SmallNote(l.label("No saved answer is available for this review. Use the skill lesson in your library before continuing.", "Для разбора нет сохранённого ответа. Повторите урок по навыку в библиотеке перед продолжением."))
                }
                Button(onClick = { vm.lessonSeen(session.stepKey) }, modifier = Modifier.fillMaxWidth()) { Text(l.label("Review complete →", "Разбор завершён →")) }
                return@Column
            }
            if (!session.lessonSeen) {
                val lesson = session.activity?.lessonId?.let { id -> session.lessonSnapshots.firstOrNull { it.id == id } ?: s.pack.lessons.firstOrNull { it.id == id } }
                    ?: s.pack.lessons.firstOrNull { it.skillId == exercise.skillId }
                PageTitle(l.label("A RULE TO TAKE WITH YOU", "ПРАВИЛО ПЕРЕД ПРАКТИКОЙ"), lesson?.title?.text(l) ?: l.label("Prepare, then try.", "Разберитесь и попробуйте."))
                lesson?.let {
                    StudyCard { Text(it.body.text(l), lineHeight = 26.sp) }
                    StudyCard(color = MaterialTheme.colorScheme.primaryContainer) { Eyebrow(l.label("Worked example", "Разобранный пример")); Text(it.workedExample.text(l), lineHeight = 25.sp) }
                }
                session.activity?.let { activity -> SmallNote(l.label("${activity.minutes} minutes planned${if (activity.continuation) " · continuing yesterday's work" else ""}", "По плану ${activity.minutes} мин${if (activity.continuation) " · продолжение работы" else ""}")) }
                Button(onClick = { vm.lessonSeen(session.stepKey) }, modifier = Modifier.fillMaxWidth()) { Text(if (session.activity != null) l.label("I've completed this lesson →", "Урок пройден →") else l.label("I'm ready to practise →", "Перейти к практике →")) }
                if (session.activity?.remainingMinutes?.let { it > 0 } == true) TextButton(onClick = { vm.checkpoint(session.stepKey) }) { Text(l.label("Continue the lesson next day", "Продолжить урок в следующий день")) }
                return@Column
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            if (session.result == null && !session.answerLockedByTimeLimit) LaunchedEffect(session.stepKey, answerState, lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        delay(1000)
                        vm.tick(session.stepKey, answerState.text.toString().takeIf { exercise.type != ExerciseType.MULTIPLE_CHOICE }, session.draftRevision)
                    }
                }
            }
            val elapsed = session.previousWorkSeconds + session.activeSeconds
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when (session.mode) { ContentSplit.DIAGNOSTIC -> l.label("Diagnostic", "Диагностика"); ContentSplit.PRACTICE -> l.label("Practice · level ${exercise.difficulty}", "Практика · уровень ${exercise.difficulty}"); ContentSplit.ASSESSMENT -> l.label("Fresh timed check", "Проверка на новых заданиях") }, style = MaterialTheme.typography.labelMedium)
                Text("${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')} / ${exercise.expectedSeconds / 60}:${(exercise.expectedSeconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelMedium, color = if (elapsed > exercise.expectedSeconds) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (session.timeLimitSeconds != null && session.result == null) {
                val remaining = (session.timeLimitSeconds - elapsed).coerceAtLeast(0)
                StudyCard(color = if (session.answerLockedByTimeLimit) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                    Text(when {
                        session.continuedWithoutTimeLimit -> l.label("Continuing without a time limit", "Продолжение без ограничения времени")
                        session.answerLockedByTimeLimit -> l.label("Time is up. Your answer is saved.", "Время вышло. Ответ сохранён.")
                        else -> l.label("Independent practice · ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')} left", "Самостоятельная практика · осталось ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}")
                    }, style = MaterialTheme.typography.titleMedium)
                    SmallNote(l.label("The timer counts time on this answer while the screen is active. Leaving the screen pauses it. Saved work keeps its remaining time.", "Таймер считает время работы над ответом на активном экране. При выходе он приостанавливается. Сохранённая работа сохраняет остаток времени."))
                    if (session.answerLockedByTimeLimit) {
                        SmallNote(l.label("Check the saved answer below, continue without the limit, or save it for later. No result is recorded automatically.", "Проверьте сохранённый ответ ниже, продолжите без лимита или сохраните на потом. Результат не записывается автоматически."))
                        OutlinedButton(onClick = { vm.continueWithoutTimeLimit(session.stepKey) }) { Text(l.label("Continue without limit", "Продолжить без лимита")) }
                    }
                }
            }
            session.activity?.takeUnless { it.isLesson }?.let { activity ->
                StudyCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(l.label("Today's work: ${activity.minutes} minutes", "Сегодня на работу: ${activity.minutes} мин"), style = MaterialTheme.typography.titleMedium)
                    if (activity.continuation || session.resumingDraft) SmallNote(l.label("Your previous draft, recording and hints are restored.", "Предыдущий черновик, запись и подсказки восстановлены."))
                    if (activity.remainingMinutes > 0) SmallNote(l.label("A longer task spans several study days. Save today's draft without grading; continue it in the next day of your course.", "Длинное задание занимает несколько дней. Сохраните сегодняшний черновик без оценки и продолжите в следующем дне курса."))
                }
            }
            if (session.activity == null && session.resumingDraft) SmallNote(l.label("Continuing your saved draft. It has not been graded yet.", "Продолжаем сохранённый черновик. Он ещё не оценён."))
            if (session.mode == ContentSplit.PRACTICE && session.result == null) {
                val checks = remember(s.pack, s.attempts, exercise) { StudyPlanner.practiceChecks(s.pack, exercise, s.attempts) }
                if (checks.isNotEmpty()) StudyCard {
                    Eyebrow(l.label("Check before answering", "Проверьте перед ответом"))
                    if (session.reviewGuidanceViewed) {
                        SmallNote(l.label("This task-specific guidance counts as a hint. Possible mistakes are a checklist, not a diagnosis of your reasoning.", "Эта помощь по заданию учитывается как подсказка. Возможные ошибки — список для проверки, а не оценка причины вашей ошибки."))
                        checks.forEach { SmallNote("• ${it.text(l)}") }
                    } else {
                        SmallNote(l.label("Guidance is available for the difficulty observed in an earlier answer. You can try independently or reveal it as a hint.", "Есть помощь по затруднению из предыдущего ответа. Попробуйте самостоятельно или откройте её как подсказку."))
                        TextButton(onClick = { vm.reviewGuidance(session.stepKey) }, enabled = !session.answerLockedByTimeLimit) {
                            Text(l.label("Show guidance · counts as a hint", "Показать помощь · считается подсказкой"))
                        }
                    }
                }
            }
            exercise.passage?.let { StudyCard { SelectionContainer { Text(it, lineHeight = 26.sp) } } }
            exercise.audioAssetPath?.let { ListeningPlayer(it, l) }
            exercise.chart?.let { Chart(it) }
            Text(exercise.prompt, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
            if (exercise.type == ExerciseType.SPEAKING) SpeakingPanel(s, vm)
            if (exercise.type == ExerciseType.MULTIPLE_CHOICE) {
                exercise.options.forEachIndexed { index, option ->
                    Surface(shape = RoundedCornerShape(16.dp), color = if (session.draft == option) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, if (session.draft == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().clickable(enabled = answerEnabled) { vm.draft(option, session.stepKey) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = session.draft == option, onClick = { vm.draft(option, session.stepKey) }, enabled = answerEnabled)
                            Text("${('A'.code + index).toChar()}. $option", modifier = Modifier.padding(start = 7.dp), lineHeight = 23.sp)
                        }
                    }
                }
            } else {
                OutlinedTextField(state = answerState, enabled = answerEnabled,
                    label = { Text(if (exercise.type == ExerciseType.SPEAKING) l.label("Review or type your transcript", "Исправьте или введите транскрипт") else l.label("Your answer", "Ваш ответ")) },
                    modifier = Modifier.fillMaxWidth(), lineLimits = if (exercise.type == ExerciseType.WRITING || exercise.type == ExerciseType.SPEAKING) TextFieldLineLimits.MultiLine(minHeightInLines = 7, maxHeightInLines = 12) else TextFieldLineLimits.SingleLine,
                    supportingText = { Text(when {
                        exercise.wordLimit != null -> l.label("No more than ${exercise.wordLimit} word(s).", "Не более ${exercise.wordLimit} слов.")
                        exercise.type == ExerciseType.WRITING -> "${Regex("\\S+").findAll(answerState.text.toString().trim()).count()} ${l.label("words", "слов")} · ${l.label("target", "ориентир")} ${exercise.minWords ?: 150}+"
                        exercise.type == ExerciseType.NUMERIC -> if (exercise.prompt.contains("exact fraction", ignoreCase = true)) l.label("Enter the exact fraction requested, without decimal rounding.", "Введите точную дробь, без десятичного округления.") else l.label("Decimals and fractions accepted, e.g. 0.5 or 1/2.", "Можно вводить дроби: 0.5 или 1/2.")
                        else -> l.label("Saved locally as you type.", "Сохраняется на устройстве при вводе.")
                    }) }, shape = RoundedCornerShape(16.dp))
            }
            if (exercise.criteria.isNotEmpty()) StudyCard {
                Eyebrow(l.label("Review criteria", "Критерии разбора"))
                exercise.criteria.forEach { SmallNote("• ${it.text(l)}") }
                SmallNote(l.label("Training feedback only. No official band is calculated.", "Только тренировочная обратная связь. Официальный band не рассчитывается."))
            }
            if (session.mode == ContentSplit.PRACTICE && session.result == null && exercise.hints.isNotEmpty()) {
                exercise.hints.take(session.hintsUsed).forEachIndexed { index, hint -> StudyCard(color = MaterialTheme.colorScheme.tertiaryContainer) { Eyebrow("${l.label("Hint", "Подсказка")} ${index + 1}"); Text(hint.text(l)) } }
                if (session.hintsUsed < exercise.hints.size) TextButton(onClick = vm::hint, enabled = !session.answerLockedByTimeLimit) { Text(l.label("Show a hint (${session.hintsUsed + 1}/${exercise.hints.size})", "Подсказка (${session.hintsUsed + 1}/${exercise.hints.size})")) }
            }
            if (session.result == null) {
                if (session.activity?.remainingMinutes?.let { it > 0 } == true) {
                    Button(onClick = { if (exercise.type != ExerciseType.MULTIPLE_CHOICE) vm.draft(answerState.text.toString(), session.stepKey); vm.checkpoint(session.stepKey) }, enabled = captureValue !is RecorderState.RecordingAudio, modifier = Modifier.fillMaxWidth()) { Text(l.label("Save draft for the next study day →", "Сохранить черновик до следующего дня →")) }
                    SmallNote(l.label("No attempt or skill result is recorded until you submit the finished answer.", "Попытка и результат навыка появятся только после отправки готового ответа."))
                }
                Button(onClick = { if (exercise.type != ExerciseType.MULTIPLE_CHOICE) vm.draft(answerState.text.toString(), session.stepKey); vm.submit() }, enabled = (if (exercise.type == ExerciseType.MULTIPLE_CHOICE) session.draft.isNotBlank() else answerState.text.isNotBlank()) && captureValue !is RecorderState.RecordingAudio, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(17.dp)) { Text(if (exercise.type in listOf(ExerciseType.WRITING, ExerciseType.SPEAKING)) l.label("Save & review →", "Сохранить и разобрать →") else l.label("Check my answer →", "Проверить ответ →")) }
                if (session.mode == ContentSplit.DIAGNOSTIC) TextButton(onClick = { if (exercise.type != ExerciseType.MULTIPLE_CHOICE) vm.draft(answerState.text.toString(), session.stepKey); vm.submit(skip = true) }) { Text(l.label("Skip — I don't know yet", "Пропустить — пока не знаю")) }
            } else {
                StudyCard(color = if (session.result.correct == false) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                    Text(session.result.message.text(l), style = MaterialTheme.typography.titleLarge)
                    if (exercise.acceptedAnswers.isNotEmpty()) Text("${l.label("Prepared answer", "Ответ из ключа")}: ${exercise.acceptedAnswers.joinToString(" / ")}", fontWeight = FontWeight.SemiBold)
                    Text(exercise.explanation.text(l), lineHeight = 25.sp)
                    exercise.evidence?.let { Text("${l.label("Evidence", "Подтверждение")}: $it", fontWeight = FontWeight.Medium) }
                    exercise.typicalErrors.forEach { SmallNote("• ${it.text(l)}") }
                    if (session.hintsUsed > 0 || session.reviewGuidanceViewed) SmallNote(l.label("An answer with a hint is practice, not independent mastery evidence.", "Ответ с подсказкой не считается самостоятельным подтверждением освоения."))
                    if (session.continuedWithoutTimeLimit) SmallNote(l.label("You continued after the time limit. The full working time is saved in your progress.", "Вы продолжили после лимита. Полное время работы сохранено в прогрессе."))
                }
                exercise.transcript?.let { transcript ->
                    StudyCard { Eyebrow(l.label("Audio transcript", "Транскрипт аудио")); Text(transcript); exercise.transcriptSegments.forEach { SmallNote("${it.startMs / 1000}–${it.endMs / 1000}s · ${it.text}") } }
                }
                exercise.sampleAnswer?.let { StudyCard { Eyebrow(l.label("Illustrative answer", "Пример ответа")); Text(it); SmallNote(l.label("Pilot sample awaiting expert annotation.", "Пилотный образец, ожидает экспертной аннотации.")) } }
                exercise.sampleAudioAssetPath?.let { ListeningPlayer(it, l, sample = true) }
                FeedbackPanel(s, vm)
                Button(onClick = vm::next, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(17.dp)) { Text(if (session.index + 1 == session.stepCount) l.label("Finish session →", "Завершить сессию →") else l.label("Next task →", "Следующее задание →")) }
            }
            SmallNote("${l.label("Original pilot material", "Оригинальный пилотный материал")} · ${exercise.author} · v${exercise.version}")
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ListeningPlayer(assetPath: String, l: Language, sample: Boolean = false) {
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
    StudyCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow(if (sample) l.label("Speaking · synthetic sample", "Speaking · синтетический образец") else l.label("Listening · original synthetic audio", "Listening · синтетическая учебная запись"))
        Button(onClick = {
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
        }) { Text(if (playingValue) l.label("Pause audio", "Пауза") else l.label("▶ Listen", "▶ Слушать")) }
        if (errorValue.isNotEmpty()) SmallNote(errorValue)
        SmallNote(if (sample) l.label("An illustrative answer awaiting expert listening review. Listen for clear phrasing and pauses; it is not a calibrated pronunciation or band exemplar.", "Учебный образец ожидает прослушивания специалистом. Обратите внимание на фразы и паузы; это не калиброванный эталон произношения или band.") else l.label("The transcript is revealed after submitting your answer.", "Транскрипт откроется после отправки ответа."))
    }
}

@Composable
private fun Chart(chart: ChartData) {
    StudyCard {
        Text(chart.title, style = MaterialTheme.typography.titleMedium)
        SmallNote("${chart.xLabel} · ${chart.yLabel} (${chart.unit})")
        val maximum = chart.series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        chart.labels.forEachIndexed { index, label ->
            Text(label, fontWeight = FontWeight.SemiBold)
            chart.series.forEach { series ->
                val value = series.values.getOrNull(index) ?: 0.0
                SmallNote("${series.name}: ${if (value % 1 == 0.0) value.toInt() else value} ${chart.unit}")
                Box(Modifier.fillMaxWidth().height(9.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))) {
                    Box(Modifier.fillMaxWidth((value / maximum).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
                }
            }
        }
    }
}

@Composable
private fun SpeakingPanel(s: StudyUiState, vm: StudyViewModel) {
    val context = LocalContext.current
    val l = s.language
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
    StudyCard {
        Eyebrow(l.label("Record one answer", "Запишите один ответ"))
        SmallNote(l.label("Record each answer separately. Select a take to transcribe it, then add its text to your response and correct it below.", "Записывайте ответы по отдельности. Выберите запись для расшифровки, добавьте текст к ответу и исправьте его ниже."))
        val active = recorderValue as? RecorderState.RecordingAudio
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.session?.result == null) Button(onClick = {
                if (active != null) scope.launch { vm.runtime.recorder.stop()?.let { vm.attachRecording(it.file.absolutePath, stepKey) } }
                else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = !transcribingValue) { Text(if (active != null) l.label("■ Stop · ${active.elapsedMillis / 1000}s", "■ Стоп · ${active.elapsedMillis / 1000}с") else l.label("● Record", "● Записать")) }
            s.session?.recordingPath?.let { path ->
                if (active == null) OutlinedButton(onClick = { vm.runtime.playback.play(File(path), onError = { messageValue = it }) }) { Text(l.label("▶ Replay", "▶ Прослушать")) }
            }
        }
        val takes = s.session?.recordingPaths.orEmpty().ifEmpty { listOfNotNull(s.session?.recordingPath) }
        if (takes.size > 1 && active == null) {
            takes.forEachIndexed { index, path ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.runtime.playback.play(File(path), onError = { messageValue = it }) }) { Text(l.label("▶ Answer ${index + 1}", "▶ Ответ ${index + 1}")) }
                    if (s.session?.result == null) TextButton(onClick = { vm.attachRecording(path, stepKey) }, enabled = !transcribingValue) { Text(if (s.session?.recordingPath == path) l.label("✓ Selected", "✓ Выбрана") else l.label("Select for transcript", "Выбрать для расшифровки")) }
                }
            }
        }
        s.session?.recordingPath?.let { path ->
            if (active == null && s.session!!.result == null) OutlinedButton(onClick = {
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
            }, enabled = !transcribingValue) { Text(if (transcribingValue) l.label("Transcribing…", "Распознавание…") else l.label("Transcribe offline", "Распознать офлайн")) }
        }
        recognizedValue?.takeIf { s.session?.result == null }?.let { transcript ->
            SelectionContainer { Text(transcript) }
            Button(onClick = {
                val current = vm.state.value.session?.takeIf { it.stepKey == stepKey }?.draft.orEmpty()
                vm.applyTranscript(listOf(current.trimEnd(), transcript).filter { it.isNotBlank() }.joinToString("\n\n"), stepKey)
                recognizedValue = null
            }) { Text(l.label("Add to my response", "Добавить к моему ответу")) }
            TextButton(onClick = { vm.applyTranscript(transcript, stepKey); recognizedValue = null }) { Text(l.label("Replace transcript with this take", "Заменить текст этой расшифровкой")) }
        }
        if (messageValue.isNotBlank()) SmallNote(messageValue)
        SmallNote(l.label("Your original recording is kept. Replay it and check intelligibility, stress and rhythm. A text transcript cannot establish a pronunciation band.", "Оригинальная запись сохраняется. Прослушайте её: понятность, ударения и ритм. По тексту нельзя определить pronunciation band."))
    }
}

private fun tutorRequest(s: StudyUiState): TutorRequest {
    val exercise = s.session!!.exercise!!
    return TutorRequestFactory.create(exercise, s.session!!.draft, s.language)
}

@Composable
private fun FeedbackPanel(s: StudyUiState, vm: StudyViewModel) {
    val l = s.language
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accelerationValue by vm.runtime.acceleration.state.collectAsStateWithLifecycle()
    var outputValue by rememberSaveable(s.session!!.id, s.session!!.index) { mutableStateOf("") }
    var busyValue by remember { mutableStateOf(false) }
    var previewValue by remember { mutableStateOf(false) }
    var externalValue by rememberSaveable(s.session!!.id, s.session!!.index) { mutableStateOf(s.session!!.externalFeedback) }
    val request = tutorRequest(s)
    StudyCard {
        Eyebrow(l.label("Understand your answer", "Разберите свой ответ"))
        SmallNote(l.label("Prepared explanations work offline. AI feedback can be wrong: compare it with the key and prepared explanation. It does not change your result or level.", "Готовый разбор доступен офлайн. ИИ может ошибаться: сверяйте его выводы с ключом и готовым разбором. ИИ не меняет результат или уровень."))
        OutlinedButton(onClick = {
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
        }, enabled = !busyValue && accelerationValue !is LocalAccelerationState.Preparing) { Text(if (busyValue) l.label("Working locally…", "Локальный разбор…") else l.label("Explain with local AI", "Разобрать с локальным ИИ")) }
        if (accelerationValue is LocalAccelerationState.Preparing) SmallNote(l.label("Local setup is in progress. The prepared explanation above is available now.", "Идёт локальная подготовка. Готовый разбор выше уже доступен."))
        if (outputValue.isNotBlank()) SelectionContainer { Text(outputValue, lineHeight = 24.sp) }
        OutlinedButton(onClick = { previewValue = true }) { Text(l.label("Preview request for ChatGPT ↗", "Запрос для ChatGPT ↗")) }
        OutlinedTextField(externalValue, { externalValue = it }, label = { Text(l.label("Paste external feedback (optional)", "Вставить внешний отзыв (необязательно)")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
        if (externalValue.isNotBlank()) TextButton(onClick = { vm.saveFeedback(externalValue) }) { Text(l.label("Save as external feedback", "Сохранить как внешний отзыв")) }
    }
    if (previewValue) AlertDialog(onDismissRequest = { previewValue = false }, title = { Text(l.label("Review before sharing", "Текст перед передачей")) }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallNote(l.label("Use your ChatGPT account or subscription. Copy is always available; send the request yourself.", "Продолжите в своём ChatGPT. Копирование доступно всегда; запрос отправляете вы сами."))
            SelectionContainer { Text(ChatGptHandoff.preview(request)) }
        }
    }, confirmButton = { TextButton(onClick = { ChatGptHandoff.copy(context, ChatGptHandoff.preview(request)); previewValue = false }) { Text(l.label("Copy", "Копировать")) } }, dismissButton = { TextButton(onClick = { ChatGptHandoff.share(context, ChatGptHandoff.preview(request)) }) { Text(l.label("Share…", "Поделиться…")) } })
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
