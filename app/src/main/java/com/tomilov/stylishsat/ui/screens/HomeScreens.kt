package com.tomilov.stylishsat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.ai.DownloadState
import com.tomilov.stylishsat.ai.LocalAccelerationState
import com.tomilov.stylishsat.ai.ModelCatalog
import com.tomilov.stylishsat.ai.RuntimeServices
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun TodayScreen(s: StudyUiState, vm: StudyViewModel, begin: (ContentSplit, String?) -> Unit, plannedDay: (Int) -> Unit, resume: () -> Unit) {
    val l = s.language
    val pack = s.pack!!
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Exam.entries.forEach { exam -> FilterChip(selected = s.exam == exam, onClick = { vm.selectExam(exam) }, label = { Text(if (exam == Exam.IELTS) "IELTS Academic" else "SAT") }) }
    }
    PageTitle(l.label("YOUR PERSONAL COURSE", "ВАШ ЛИЧНЫЙ КУРС"), l.label("A little progress.\nEvery day.", "Ближе к цели.\nКаждый день."), l.label("Learn the rule. Try it. Make it yours.", "Разберитесь в правиле. Попробуйте. Закрепите."))
    if (s.profile.target.isNotBlank()) SmallNote("${l.label("Goal", "Цель")}: ${s.profile.target}")
    s.profile.examDateEpochDay?.let { date ->
        val days = date - LocalDate.now().toEpochDay()
        SmallNote(if (days >= 0) l.label("Exam: ${LocalDate.ofEpochDay(date)} · $days days left", "Экзамен: ${LocalDate.ofEpochDay(date)} · осталось $days дн.") else l.label("Exam date: ${LocalDate.ofEpochDay(date)} · update it in Settings", "Дата экзамена: ${LocalDate.ofEpochDay(date)} · можно обновить в настройках"))
    }
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(23.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (s.profile.diagnosticCompleted) l.label("YOUR NEXT STEP", "ВАШ СЛЕДУЮЩИЙ ШАГ") else l.label("START WITH A CHECK-IN", "НАЧНИТЕ С ДИАГНОСТИКИ"), letterSpacing = 1.3.sp, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondaryContainer)
            Text(if (s.profile.diagnosticCompleted) l.label("Build on what\nyou know.", "Закрепите то,\nчто уже знаете.") else l.label("Find your\nstarting point.", "Найдите свою\nточку старта."), fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 34.sp)
            Text(if (s.profile.diagnosticCompleted) l.label("Spaced review · focused practice · prepared explanations", "Повторение · слабые темы · готовые разборы") else if (s.exam == Exam.SAT) l.label("16 questions · 8 domains · a preliminary picture", "16 заданий · 8 доменов · предварительный результат") else l.label("Reading, Listening, Writing & Speaking", "Reading, Listening, Writing и Speaking"), fontSize = 13.sp)
            Button(onClick = {
                if (s.session?.let { !it.finished } == true) resume()
                else begin(if (s.profile.diagnosticCompleted) ContentSplit.PRACTICE else ContentSplit.DIAGNOSTIC, null)
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer), modifier = Modifier.fillMaxWidth()) {
                Text(if (s.session?.let { !it.finished } == true) l.label("Continue session →", "Продолжить сессию →") else if (s.profile.diagnosticCompleted) l.label("Start practice →", "Начать практику →") else l.label("Start diagnostic →", "Пройти диагностику →"), modifier = Modifier.padding(5.dp))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StudyCard(Modifier.weight(1f)) { Eyebrow(l.label("Daily rhythm", "Ежедневно")); Text("${s.profile.dailyMinutes}", fontSize = 28.sp, fontWeight = FontWeight.SemiBold); SmallNote(l.label("minutes for yourself", "минут для себя")) }
        StudyCard(Modifier.weight(1f), MaterialTheme.colorScheme.primaryContainer) { Eyebrow(l.label("Practice", "Практика")); Text("${s.examAttempts.size}", fontSize = 28.sp, fontWeight = FontWeight.SemiBold); SmallNote(l.label("answers saved", "ответов сохранено")) }
    }
    SavedDrafts(s, vm, resume)
    StudyCard {
        Eyebrow(l.label("Before exam day", "Перед экзаменом"))
        Text(l.label("A focused intensive", "Интенсив без перегрузки"), style = MaterialTheme.typography.titleLarge)
        SmallNote(l.label("Choose up to three priorities. Practise timing and your most important mistakes.", "До трёх приоритетных тем: важные ошибки и работа на время. Без обещаний освоить экзамен за день."))
        IntensiveOfflineReadiness(l, pack.version, vm.runtime)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 4, 6).forEach { hours -> OutlinedButton(onClick = { vm.makePlan(hours) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(5.dp)) { Text("$hours ${l.label("hours", if (hours == 6) "часов" else "часа")}") } }
        }
    }
    s.plan?.let { plan ->
        StudyCard {
            Eyebrow(if (plan.mode == PlanMode.COURSE) l.label("${plan.days.size}-day route", "Маршрут на ${plan.days.size} дней") else l.label("Your intensive", "Ваш интенсив"))
            if (plan.mode == PlanMode.COURSE && plan.days.size > 28) SmallNote(l.label("${plan.days.size - 28} extra study days preserve your unfinished work and final checks within the new daily time limit.", "Дополнительных дней: ${plan.days.size - 28}. Они сохраняют незавершённую работу и итоговую проверку в пределах нового ежедневного лимита."))
            Text("${plan.totalMinutes} ${l.label("minutes", "минут")}", style = MaterialTheme.typography.titleLarge)
            if (plan.mode == PlanMode.INTENSIVE) {
                SmallNote(plan.prioritySkillIds.mapNotNull { id -> pack.skills.find { it.id == id }?.title?.text(l) }.joinToString(" · "))
            }
            if (plan.mode == PlanMode.INTENSIVE) {
                plan.blocks.forEach { block ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { vm.completeBlock(block.id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                            Text("${if (block.completed) "✓ " else "○ "}${blockTitle(block.type, l)}", modifier = Modifier.fillMaxWidth())
                        }
                        Text("${block.minutes} ${l.label("min", "мин")}", modifier = Modifier.padding(top = 13.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                var remainingValue by rememberSaveable(plan.id) { mutableFloatStateOf(120f) }
                Text(l.label("Time left: ${remainingValue.toInt()} min", "Осталось времени: ${remainingValue.toInt()} мин"))
                Slider(value = remainingValue, onValueChange = { remainingValue = it }, valueRange = 30f..360f, steps = 10)
                OutlinedButton(onClick = { vm.recalculate(remainingValue.toInt()) }, enabled = plan.blocks.any { !it.completed }) { Text(l.label("Recalculate remaining day", "Пересчитать остаток дня")) }
            } else {
                var expandedValue by rememberSaveable(plan.id) { mutableStateOf(false) }
                (if (expandedValue) plan.days else plan.days.take(4)).forEach { day ->
                    val names = day.skillIds.mapNotNull { id -> pack.skills.find { it.id == id }?.title?.text(l) }.joinToString(" · ")
                    Text("${l.label("Day", "День")} ${day.dayNumber} · ${day.minutes} ${l.label("min", "мин")}", fontWeight = FontWeight.Medium)
                    SmallNote(names)
                    if (day.activities.isNotEmpty()) {
                        SmallNote(l.label("${day.activities.sumOf { it.minutes }} minutes of planned work", "Запланировано ${day.activities.sumOf { it.minutes }} мин работы"))
                        day.activities.forEach { activity ->
                            val kind = when (activity.kind) {
                                ActivityKind.LESSON -> l.label("Lesson", "Урок")
                                ActivityKind.REVIEW -> l.label("Review", "Повторение")
                                ActivityKind.PRACTICE -> l.label("Practice", "Практика")
                                ActivityKind.ASSESSMENT -> l.label("Fresh check", "Новая проверка")
                            }
                            SmallNote("$kind · ${activity.minutes} ${l.label("min", "мин")}${if (activity.continuation) l.label(" · continuation", " · продолжение") else ""}")
                        }
                    }
                    if (day.contentExhausted) SmallNote(l.label("Suitable new content is exhausted for this day.", "Подходящие новые материалы для этого дня закончились."))
                    val completedDays = s.courseProgress[s.exam]?.takeIf { it.planId == plan.id }?.completedDays.orEmpty()
                    val completed = day.dayNumber in completedDays
                    val nextDay = plan.days.firstOrNull { it.dayNumber !in completedDays }?.dayNumber
                    TextButton(onClick = { plannedDay(day.dayNumber) }, enabled = !completed && day.dayNumber == nextDay && !day.contentExhausted) { Text(if (completed) l.label("✓ Completed", "✓ Завершено") else if (day.dayNumber == nextDay) l.label("Start this day →", "Заняться по плану →") else l.label("After day ${day.dayNumber - 1}", "После дня ${day.dayNumber - 1}")) }
                }
                TextButton(onClick = { expandedValue = !expandedValue }) { Text(if (expandedValue) l.label("Show less", "Свернуть") else l.label("See all ${plan.days.size} days", "Все ${plan.days.size} дней")) }
                SmallNote(l.label("The full draft bank is included. Independent human editorial review and student testing are pending.", "Полный черновой банк включён. Независимая редакционная проверка специалистом и испытания с учениками ожидаются."))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { begin(ContentSplit.PRACTICE, null) }, modifier = Modifier.weight(1f)) { Text(l.label("Practise", "Практика")) }
                OutlinedButton(onClick = { begin(ContentSplit.ASSESSMENT, null) }, modifier = Modifier.weight(1f)) { Text(l.label("Fresh check", "Проверка")) }
            }
        }
    } ?: OutlinedButton(onClick = { vm.makePlan() }, modifier = Modifier.fillMaxWidth()) { Text(l.label("Build a 28-day route", "Составить маршрут на 28 дней")) }
    if (s.plan?.mode == PlanMode.INTENSIVE) IntensiveSupport(s, vm, begin)
    if (s.plan?.mode == PlanMode.INTENSIVE) TextButton(onClick = { vm.makePlan() }) { Text(l.label("Return to daily course", "Вернуться к ежедневному курсу")) }
    SmallNote(l.label("Pilot materials · original AI-authored drafts · expert review pending. SAT and IELTS are trademarks of their owners; this is independent practice.", "Пилотные материалы · оригинальные ИИ-черновики · ожидают экспертизы. Независимое приложение для подготовки к SAT и IELTS."))
}

private data class IntensiveOfflineFiles(
    val modelsSupported: Boolean,
    val gemmaInstalled: Boolean,
    val whisperInstalled: Boolean,
    val accelerationEligible: Boolean,
)

/** Read download/preparation state only here, so progress cannot recompose the whole course. */
@Composable
private fun IntensiveOfflineReadiness(l: Language, packVersion: Int, runtime: RuntimeServices) {
    val owner = LocalLifecycleOwner.current
    val downloadValue by runtime.downloads.state.collectAsStateWithLifecycle()
    val accelerationValue by runtime.acceleration.state.collectAsStateWithLifecycle()
    val installationValue by produceState<Result<IntensiveOfflineFiles>?>(null, runtime, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runtime.downloads.state.map { state ->
                // Byte progress changes frequently; installation receipts need checking only at phase changes.
                when (state) {
                    is DownloadState.Downloading -> "downloading:${state.modelId}"
                    is DownloadState.Verifying -> "verifying:${state.modelId}"
                    is DownloadState.Installed -> "installed:${state.modelId}"
                    is DownloadState.Paused -> "paused:${state.modelId}"
                    is DownloadState.Failed -> "failed:${state.modelId}"
                    DownloadState.Idle -> "idle"
                }
            }.distinctUntilChanged().collect {
                value = try {
                    Result.success(withContext(Dispatchers.IO) {
                        // Existing lightweight receipt/file-stat checks. No native initialization or full-file hash.
                        runtime.acceleration.refresh()
                        IntensiveOfflineFiles(runtime.capability.supported,
                            runtime.downloads.isInstalled(ModelCatalog.gemma),
                            runtime.downloads.isInstalled(ModelCatalog.whisper), runtime.acceleration.eligible)
                    })
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { Result.failure(error) }
            }
        }
    }
    val files = installationValue?.getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Eyebrow(l.label("Before starting · offline", "Перед запуском · без интернета"))
        Text(l.label("Package v$packVersion: lessons and audio on this device", "Пакет v$packVersion: уроки и аудио на устройстве"), style = MaterialTheme.typography.bodySmall)
        Text("Gemma: ${intensiveModelStatus(l, ModelCatalog.gemma.id, files?.gemmaInstalled, files?.modelsSupported, installationValue?.isFailure == true, downloadValue)}", style = MaterialTheme.typography.bodySmall)
        Text("Whisper ASR: ${intensiveModelStatus(l, ModelCatalog.whisper.id, files?.whisperInstalled, files?.modelsSupported, installationValue?.isFailure == true, downloadValue)}", style = MaterialTheme.typography.bodySmall)
        if (files?.accelerationEligible == true) Text(l.label("AI acceleration: ", "Ускорение ИИ: ") + when (accelerationValue) {
            is LocalAccelerationState.Ready -> l.label("prepared", "подготовлено")
            is LocalAccelerationState.Preparing -> l.label("preparing; lessons remain available", "идёт подготовка; уроки доступны")
            is LocalAccelerationState.Failed -> l.label("setup needs attention in Settings", "проверьте подготовку в «Настройках»")
            is LocalAccelerationState.Idle -> l.label("not prepared", "не подготовлено")
        }, style = MaterialTheme.typography.bodySmall)
        SmallNote(if (files?.modelsSupported == false) l.label(
            "Local AI and transcription require 8 GB+ RAM and ARM64. You can start lessons, audio and self-check on this device.",
            "Для локального ИИ и распознавания нужны 8+ ГБ RAM и ARM64. Уроки, аудио и самопроверку можно начать на этом устройстве.")
        else l.label("You can start lessons, audio and self-check without models. Downloads and optional setup are in Settings.",
            "Уроки, аудио и самопроверка доступны без моделей. Загрузки и дополнительная подготовка — в «Настройках»."))
    }
}

private fun intensiveModelStatus(l: Language, modelId: String, installed: Boolean?, supported: Boolean?, failed: Boolean, state: DownloadState): String = when {
    failed -> l.label("installation status unavailable; check Settings", "статус установки недоступен; проверьте «Настройки»")
    supported == false -> if (installed == true) l.label("installed; this device cannot run it", "файл установлен; запуск на этом устройстве недоступен")
        else l.label("local processing unavailable on this device", "локальная обработка на этом устройстве недоступна")
    state is DownloadState.Downloading && state.modelId == modelId -> l.label("downloading", "загружается")
    state is DownloadState.Verifying && state.modelId == modelId -> l.label("download is being verified", "скачанный файл проверяется")
    installed == null -> l.label("checking installation…", "проверяем установку…")
    installed == true -> l.label("installed", "установлено")
    state is DownloadState.Paused && state.modelId == modelId -> l.label("download paused", "загрузка приостановлена")
    state is DownloadState.Failed && state.modelId == modelId -> l.label("not installed; retry in Settings", "не установлено; повторите загрузку в «Настройках»")
    else -> l.label("not installed", "не установлено")
}

@Composable
private fun SavedDrafts(s: StudyUiState, vm: StudyViewModel, resume: () -> Unit) {
    val drafts = s.drafts.values.filter { draft ->
        draft.exam == s.exam && !draft.submitted && draft.supersededByWorkId == null &&
            (draft.text.isNotBlank() || draft.recordingPath != null || draft.recordingPaths.isNotEmpty() || draft.hintsUsed > 0 || draft.reviewGuidanceViewed || draft.elapsedSeconds > 0)
    }.sortedByDescending { it.updatedAt }
    if (drafts.isEmpty()) return
    val l = s.language
    var expandedValue by rememberSaveable(s.exam) { mutableStateOf(false) }
    val active = s.session?.finished == false
    StudyCard {
        Eyebrow(l.label("Saved drafts", "Сохранённые черновики"))
        SmallNote(l.label("${drafts.size} unfinished responses · no grade yet", "Незаконченных ответов: ${drafts.size} · без оценки"))
        if (active) SmallNote(l.label("Save and finish your active session before opening another draft.", "Сначала сохраните и закончите активную сессию, чтобы открыть другой черновик."))
        (if (expandedValue) drafts else drafts.take(3)).forEach { draft ->
            val exercise = s.pack?.exercises?.firstOrNull { it.id == draft.exerciseId && it.version == draft.exerciseVersion }
                ?: s.sessions[s.exam]?.exercises?.firstOrNull { it.id == draft.exerciseId && it.version == draft.exerciseVersion }
                ?: (s.plans.values + s.dailyPlans.values).asSequence().flatMap { it.days.asSequence() }
                    .flatMap { it.activities.asSequence() }.mapNotNull { it.exerciseSnapshot }
                    .firstOrNull { it.id == draft.exerciseId && it.version == draft.exerciseVersion }
            val withinPriorities = s.plan?.mode != PlanMode.INTENSIVE || exercise == null ||
                exercise.split == ContentSplit.DIAGNOSTIC || exercise.skillId in s.plan!!.prioritySkillIds.take(3)
            val title = exercise?.let { ex -> s.pack?.skills?.firstOrNull { it.id == ex.skillId }?.title?.text(l) }
                ?: l.label("Saved response", "Сохранённый ответ")
            HorizontalDivider()
            Text(title, style = MaterialTheme.typography.titleMedium)
            exercise?.prompt?.let { SmallNote(it.take(160)) }
            SmallNote(draft.text.take(160).ifBlank { if (draft.recordingPath != null || draft.recordingPaths.isNotEmpty())
                l.label("Original recording saved", "Исходная запись сохранена") else l.label("Time and hints saved", "Время и подсказки сохранены") })
            if (!withinPriorities) SmallNote(l.label("Available when you return to the daily course; this intensive focuses on other skills.", "Можно продолжить в ежедневном курсе: у этого интенсива другие приоритеты."))
            OutlinedButton(onClick = { if (vm.resumeDraft(draft.key)) resume() }, enabled = !active && withinPriorities && !s.loading) {
                Text(l.label("Continue draft →", "Продолжить черновик →"))
            }
        }
        if (drafts.size > 3) TextButton(onClick = { expandedValue = !expandedValue }) {
            Text(if (expandedValue) l.label("Show fewer drafts", "Свернуть черновики") else l.label("Show all drafts", "Показать все черновики"))
        }
    }
}

fun blockTitle(type: PlanBlockType, l: Language) = when (type) {
    PlanBlockType.DIAGNOSTIC -> l.label("Diagnostic", "Диагностика")
    PlanBlockType.LESSON_PRACTICE -> l.label("Lessons & practice", "Уроки и практика")
    PlanBlockType.BREAK -> l.label("Break", "Перерыв")
    PlanBlockType.TIMED_CHECK -> l.label("Timed check", "Проверка на время")
    PlanBlockType.REVIEW -> l.label("Review", "Разбор ошибок")
    PlanBlockType.CHECKLIST -> l.label("Exam-day checklist", "Итоговый чек-лист")
}

@Composable
fun LibraryScreen(s: StudyUiState, begin: (ContentSplit, String?) -> Unit) {
    val l = s.language
    val pack = s.pack!!
    var searchValue by rememberSaveable { mutableStateOf("") }
    PageTitle(l.label("THE LIBRARY", "БИБЛИОТЕКА"), l.label("Understand first.", "Сначала понять."), l.label("Short rules, worked examples, deliberate practice.", "Короткие правила, разобранные примеры и практика."))
    OutlinedTextField(searchValue, { searchValue = it }, label = { Text(l.label("Find a skill or lesson", "Найти навык или урок")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
    pack.skills.filter { it.exam == s.exam }.forEach { skill ->
        val lessons = pack.lessons.filter { it.skillId == skill.id }
        if (searchValue.isBlank() || "${skill.title.en} ${skill.title.ru} ${lessons.joinToString { it.body.en + it.body.ru }}".contains(searchValue, true)) {
            StudyCard {
                Eyebrow(skill.section)
                Text(skill.title.text(l), style = MaterialTheme.typography.titleLarge)
                lessons.forEach { lesson ->
                    var openValue by rememberSaveable(lesson.id) { mutableStateOf(false) }
                    TextButton(onClick = { openValue = !openValue }, contentPadding = PaddingValues(0.dp)) { Text("${if (openValue) "−" else "+"} ${lesson.title.text(l)} · ${lesson.estimatedMinutes} ${l.label("min", "мин")}") }
                    if (openValue) { Text(lesson.body.text(l)); HorizontalDivider(); Text(lesson.workedExample.text(l), color = MaterialTheme.colorScheme.primary) }
                }
                OutlinedButton(onClick = { begin(ContentSplit.PRACTICE, skill.id) }) { Text(l.label("Practise this skill →", "Отработать навык →")) }
            }
        }
    }
}

@Composable
fun ProgressScreen(s: StudyUiState) {
    val l = s.language
    val attempts = s.examAttempts.filter { it.correct != null }
    val independent = attempts.filter { it.independent }
    PageTitle(l.label("YOUR PROGRESS", "ВАШ ПРОГРЕСС"), l.label("Evidence,\nnot guesses.", "Результат виден\nв практике."), l.label("SAT and IELTS histories stay separate.", "Истории SAT и IELTS сохраняются отдельно."))
    StudyCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Text(if (independent.isEmpty()) "—" else "${independent.count { it.correct == true } * 100 / independent.size}%", fontSize = 48.sp, fontFamily = FontFamily.Serif)
        Text(l.label("Independent accuracy", "Точность самостоятельных ответов"), fontWeight = FontWeight.Medium)
        SmallNote(l.label("${independent.size} independent answers · ${attempts.size} checked answers", "${independent.size} самостоятельных ответов · ${attempts.size} проверенных ответов"))
        SmallNote(l.label("A preliminary training picture. This is not an official SAT score or IELTS band.", "Предварительная учебная картина. Это не официальный SAT score или IELTS band."))
    }
    StudyCard {
        Text(l.label("Independence", "Самостоятельность"), style = MaterialTheme.typography.titleMedium)
        Text(if (attempts.isEmpty()) "—" else "${independent.size * 100 / attempts.size}%", fontSize = 30.sp)
        SmallNote(l.label("Repeats and hints stay in your history without inflating this measure.", "Повторы и подсказки остаются в истории, но не повышают этот показатель."))
    }
    val states = s.skillStates.associateBy { it.skillId }
    s.pack!!.skills.filter { it.exam == s.exam }.forEach { skill ->
        val skillState = states[skill.id] ?: SkillState(skill.id)
        StudyCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(skill.title.text(l), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(if (skillState.mastered) "✓" else "${skillState.independentCorrectCount}/${skillState.independentCount}")
            }
            LinearProgressIndicator(progress = { if (skillState.independentCount == 0) 0f else skillState.independentCorrectCount.toFloat() / skillState.independentCount }, modifier = Modifier.fillMaxWidth())
            SmallNote(l.label("Level ${skillState.difficulty} · ${skillState.attemptsCount} checked · ${if (skillState.attemptsCount == 0) 0 else skillState.totalElapsedSeconds / skillState.attemptsCount} sec / answer", "Уровень ${skillState.difficulty} · ${skillState.attemptsCount} проверено · ${if (skillState.attemptsCount == 0) 0 else skillState.totalElapsedSeconds / skillState.attemptsCount} сек / ответ"))
            skillState.nextReviewEpochDay?.let { day -> SmallNote(l.label("Next review: ${LocalDate.ofEpochDay(day)}", "Повторение: ${LocalDate.ofEpochDay(day)}")) }
            if (skill.id.contains("writing") || skill.id.contains("speaking")) SmallNote(l.label("Open responses receive training observations. AI feedback does not change mastery.", "Открытые ответы получают тренировочные замечания. ИИ не меняет уровень навыка."))
        }
    }
    Text(l.label("Recent work", "Последние ответы"), style = MaterialTheme.typography.titleLarge)
    var allHistoryValue by rememberSaveable(s.exam) { mutableStateOf(false) }
    val history = s.examAttempts.sortedByDescending { it.timestampEpochMillis }
    (if (allHistoryValue) history else history.take(10)).forEach { attempt ->
        StudyCard {
            Text("${if (attempt.correct == true) "✓" else if (attempt.correct == false) "↺" else "○"} ${s.pack.skills.find { it.id == attempt.skillId }?.title?.text(l) ?: attempt.skillId}")
            attempt.recordingPaths.ifEmpty { listOfNotNull(attempt.recordingPath) }.forEachIndexed { index, path ->
                val player = remember { com.tomilov.stylishsat.speech.RecordingPlayback() }
                DisposableEffect(player) { onDispose { player.stop() } }
                TextButton(onClick = { player.play(java.io.File(path)) }) { Text(l.label("▶ Original recording ${index + 1}", "▶ Исходная запись ${index + 1}")) }
            }
            SmallNote(attempt.answer.take(180).ifBlank { l.label("Skipped", "Пропущено") })
            s.feedback.filter { it.attemptId == attempt.id }.forEach { feedback ->
                var expandedValue by rememberSaveable(feedback.id) { mutableStateOf(false) }
                TextButton(onClick = { expandedValue = !expandedValue }) { Text(if (feedback.source == "LOCAL_AI") l.label("AI training feedback", "Тренировочный отзыв ИИ") else l.label("External feedback", "Внешний отзыв")) }
                if (expandedValue) Text(feedback.text, style = MaterialTheme.typography.bodyMedium)
            }
            SmallNote(l.label("${attempt.elapsedSeconds}s · hints ${attempt.hintsUsed}${if (attempt.isRepeat) " · familiar item" else ""}", "${attempt.elapsedSeconds} сек · подсказок ${attempt.hintsUsed}${if (attempt.isRepeat) " · знакомое задание" else ""}"))
        }
    }
    if (history.size > 10) TextButton(onClick = { allHistoryValue = !allHistoryValue }) { Text(if (allHistoryValue) l.label("Show recent work", "Только последние ответы") else l.label("Show complete history", "Показать всю историю")) }

}
