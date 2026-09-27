package com.tomilov.stylishsat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.ContentSplit
import com.tomilov.stylishsat.domain.Exam
import com.tomilov.stylishsat.domain.Exercise
import com.tomilov.stylishsat.domain.PlanBlockType
import com.tomilov.stylishsat.domain.PlanMode
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.CancellationException

/** Review uses the version actually attempted; checklist completion is saved with the plan. */
@Composable
fun IntensiveSupport(s: StudyUiState, vm: StudyViewModel, begin: (ContentSplit, String?) -> Unit) {
    val plan = s.plan?.takeIf { it.mode == PlanMode.INTENSIVE } ?: return
    val pack = s.pack ?: return
    val l = s.language
    val c = Study.colors
    val context = LocalContext.current
    val priorities = plan.prioritySkillIds.distinct().take(3)
    val skills = priorities.mapNotNull { id -> pack.skills.find { it.id == id } }
    val errors = s.examAttempts.asSequence()
        .filter { it.correct == false && it.skillId in priorities }
        .sortedByDescending { it.timestampEpochMillis }
        .distinctBy { it.exerciseId to it.exerciseVersion }
        .take(3).toList()
    val localVersions = (pack.exercises + s.session?.exercises.orEmpty()).associateBy { it.id to it.version }
    val missingVersions = errors.map { it.exerciseId to it.exerciseVersion }.filter { it !in localVersions }
    var archivedValue by remember(pack.id, pack.version) { mutableStateOf<List<Exercise>>(emptyList()) }
    var archiveFailedValue by remember(pack.id, pack.version) { mutableStateOf(false) }
    var retryValue by remember { mutableIntStateOf(0) }
    var linkErrorValue by remember { mutableStateOf(false) }
    LaunchedEffect(pack.id, pack.version, missingVersions, retryValue) {
        archiveFailedValue = false
        if (missingVersions.isNotEmpty()) {
            try {
                archivedValue = vm.contentRepository.allExercises()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                archiveFailedValue = true
            }
        }
    }
    val versions = archivedValue.associateBy { it.id to it.version } + localVersions
    val activeSession = s.session?.let { !it.finished } == true
    val reviewBlock = plan.blocks.firstOrNull { it.type == PlanBlockType.REVIEW }
    val checklistBlock = plan.blocks.firstOrNull { it.type == PlanBlockType.CHECKLIST }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(l.label("Priorities", "Приоритеты"))
        skills.forEachIndexed { index, skill ->
            Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(16.dp), c.raised) { begin(ContentSplit.PRACTICE, skill.id) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", style = StudyType.Numeral, color = c.ink)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(skill.title.text(l), style = StudyType.Strong, color = c.ink)
                    Text(if (activeSession) l.label("Resume current session", "Продолжить текущую сессию") else l.label("Practise", "Практиковать"), style = StudyType.Small, color = c.inkSoft)
                }
                GlyphIcon(Glyph.ArrowRight, tint = c.inkSoft, size = 18.dp)
            }
        }
        Text(l.label("Explain one rule in your own words, apply it to another question, then check your timing.",
            "Объясните одно правило своими словами, примените его в другом задании и проверьте время решения."), style = StudyType.Small, color = c.inkSoft)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(l.label("Your errors", "Ваши ошибки"), trailing = errors.size.takeIf { it > 0 }?.toString())
        if (errors.isEmpty()) Text(l.label("No checked errors in these priorities yet. Practise to find what needs attention.",
            "В этих темах пока нет ошибок. Попрактикуйтесь, чтобы найти, что требует внимания."), style = StudyType.Small, color = c.inkSoft)
        errors.forEach { attempt ->
            key(attempt.id) {
                val exercise = versions[attempt.exerciseId to attempt.exerciseVersion]
                val skill = pack.skills.find { it.id == attempt.skillId }
                Block {
                    Meta(skill?.title?.text(l) ?: attempt.skillId)
                    if (exercise == null) {
                        Text(l.label("Loading the original version of this question. Its key is never replaced by a newer version.",
                            "Загружается исходная версия задания. Её ключ не заменяется ответом из новой версии."), style = StudyType.Small, color = c.inkSoft)
                    } else {
                        Text(exercise.prompt, style = StudyType.Reading.copy(fontSize = StudyType.Body.fontSize), color = c.ink)
                        AnswerPair(l.label("You", "Вы"), attempt.answer, wrong = true)
                        AnswerPair(l.label("Key", "Ключ"), exercise.acceptedAnswers.joinToString(" / "), wrong = false)
                        Text(exercise.explanation.text(l), style = StudyType.Small, color = c.ink)
                        exercise.evidence?.let { Text("${l.label("Evidence", "Подтверждение")}: $it", style = StudyType.Small, color = c.inkSoft) }
                        exercise.typicalErrors.firstOrNull()?.let { Text(it.text(l), style = StudyType.Small, color = c.inkSoft) }
                    }
                    StudyButton(if (activeSession) l.label("Resume current session", "Продолжить текущую сессию") else l.label("Practise this skill again", "Снова отработать навык"),
                        { begin(ContentSplit.PRACTICE, attempt.skillId) }, Modifier.fillMaxWidth(), tone = Tone.Quiet, compact = true)
                }
            }
        }
        if (archiveFailedValue) {
            Text(l.label("An older question could not be loaded.", "Не удалось загрузить прежнее задание."), style = StudyType.Small, color = c.bad)
            StudyButton(l.label("Retry", "Повторить"), { retryValue++ }, tone = Tone.Line, compact = true)
        }
        reviewBlock?.takeIf { errors.isNotEmpty() }?.let { block ->
            StudyButton(if (block.completed) l.label("Review done · reopen", "Разбор завершён · открыть снова") else l.label("Mark review done", "Отметить разбор"),
                { vm.completeBlock(block.id) }, Modifier.fillMaxWidth(), tone = if (block.completed) Tone.Quiet else Tone.Ink, glyph = if (block.completed) Glyph.Check else null)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(l.label("Exam-day checklist", "Чек-лист к экзамену"))
        val items = if (s.exam == Exam.SAT) listOf(
            l.label("Check your booking or school instructions for the location, arrival time and admission details.", "Сверьте с регистрацией или инструкцией школы место, время прибытия и порядок допуска."),
            l.label("Prepare the approved testing device, charge it and complete the Bluebook setup required for your test.", "Подготовьте разрешённое устройство для экзамена, зарядите его и выполните требуемую настройку Bluebook."),
            l.label("Check the current ID and permitted-item requirements. Prepare your identification, admission information and any allowed supplies.", "Проверьте действующие требования к документам и вещам. Подготовьте удостоверение личности, данные допуска и разрешённые принадлежности."),
            l.label("Revisit one Reading & Writing error and one Math error. Say which clue or calculation you will check next time.", "Вернитесь к одной ошибке Reading & Writing и одной ошибке Math. Назовите подсказку в тексте или вычисление, которые проверите в следующий раз."),
            l.label("Choose a pacing action: notice time spent on a difficult item and use the review tools you practised in Bluebook.", "Выберите действие для контроля темпа: замечайте время на сложном задании и пользуйтесь знакомыми инструментами проверки в Bluebook."),
        ) else listOf(
            l.label("Confirm your test format, location and appointment times, including Speaking, in your booking.", "Проверьте в регистрации формат, место и время всех частей экзамена, включая Speaking."),
            l.label("Prepare the identification document used for registration and recheck the test centre's instructions about belongings.", "Подготовьте документ, использованный при регистрации, и перечитайте указания центра о личных вещах."),
            l.label("For Reading and Listening, read each answer limit and check the spelling and form of your answer.", "В Reading и Listening читайте ограничения каждого ответа, проверяйте написание и требуемую форму."),
            l.label("For Writing, identify every part of the task, plan your paragraphs and reserve time to check the response.", "В Writing определите все части задания, наметьте абзацы и оставьте время на проверку."),
            l.label("For Speaking, practise a direct answer followed by a reason or example. Replay your recording to check how clearly you communicate.", "В Speaking потренируйте прямой ответ с причиной или примером. Прослушайте запись и оцените понятность своей речи."),
        )
        items.forEachIndexed { index, item ->
            Row {
                Text("${index + 1}".padStart(2, '0'), Modifier.width(34.dp), style = StudyType.Mono, color = c.inkSoft)
                Text(item, Modifier.weight(1f), style = StudyType.Small, color = c.ink)
            }
        }
        val links = if (s.exam == Exam.SAT) listOf(
            "College Board · SAT test-day preparation" to "https://satsuite.collegeboard.org/sat/what-to-bring-do/what-to-bring",
            "College Board · SAT School Day" to "https://bluebook.collegeboard.org/students/sat-school-day",
        ) else listOf(
            "IELTS · test-day guidance" to "https://ielts.org/take-a-test/preparation-resources/on-test-day",
        )
        Text(l.label("Check the current official guidance for your test:", "Сверьтесь с актуальной официальной инструкцией:"), style = StudyType.Small, color = c.inkSoft)
        links.forEach { (title, url) ->
            Text("$title ↗", Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(12.dp), c.paper) {
                linkErrorValue = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
            }.padding(vertical = 8.dp), style = StudyType.Strong.copy(fontSize = StudyType.Small.fontSize), color = c.ink)
        }
        if (linkErrorValue) Text(l.label("The link could not be opened. Check your browser and connection.", "Ссылка не открылась. Проверьте браузер и подключение."), style = StudyType.Small, color = c.bad)
        checklistBlock?.let { block ->
            StudyButton(if (block.completed) l.label("Checklist done · reopen", "Чек-лист пройден · открыть снова") else l.label("I've checked every item", "Я проверил все пункты"),
                { vm.completeBlock(block.id) }, Modifier.fillMaxWidth(), tone = if (block.completed) Tone.Quiet else Tone.Ink, glyph = if (block.completed) Glyph.Check else null)
        }
    }
}

@Composable
fun AnswerPair(label: String, value: String, wrong: Boolean?) {
    val c = Study.colors
    Row(verticalAlignment = Alignment.Top) {
        Text(label.uppercase(), Modifier.width(56.dp).padding(top = 3.dp), style = StudyType.Meta, color = when (wrong) { true -> c.bad; false -> c.good; null -> c.inkSoft })
        Text(value.ifBlank { "—" }, Modifier.weight(1f), style = StudyType.Strong, color = c.ink)
    }
}
