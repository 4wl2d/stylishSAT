package com.tomilov.stylishsat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.ContentSplit
import com.tomilov.stylishsat.domain.Exam
import com.tomilov.stylishsat.domain.Exercise
import com.tomilov.stylishsat.domain.PlanBlockType
import com.tomilov.stylishsat.domain.PlanMode
import kotlinx.coroutines.CancellationException

/** Review uses the version actually attempted; checklist completion is saved with the plan. */
@Composable
fun IntensiveSupport(s: StudyUiState, vm: StudyViewModel, begin: (ContentSplit, String?) -> Unit) {
    val plan = s.plan?.takeIf { it.mode == PlanMode.INTENSIVE } ?: return
    val pack = s.pack ?: return
    val l = s.language
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
    var retryValue by remember { mutableStateOf(0) }
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
    val breakBlock = plan.blocks.firstOrNull { it.type == PlanBlockType.BREAK }

    StudyCard {
        Eyebrow(l.label("YOUR THREE PRIORITIES", "ВАШИ ПРИОРИТЕТЫ"))
        Text(l.label("Focus the next round.", "Сосредоточьтесь на главном."), style = MaterialTheme.typography.titleLarge)
        skills.forEachIndexed { index, skill ->
            Text("${index + 1}. ${skill.title.text(l)}", fontWeight = FontWeight.Medium)
        }
        SmallNote(l.label(
            "Explain one rule in your own words, apply it to another question, then check your timing.",
            "Объясните одно правило своими словами, примените его в другом задании и проверьте время решения.",
        ))
        if (errors.isEmpty()) {
            SmallNote(l.label(
                "No checked errors are recorded in these priorities yet. Start practice to find what needs attention.",
                "В этих темах пока нет сохранённых ошибок. Начните практику, чтобы найти то, что требует внимания.",
            ))
            skills.forEach { skill ->
                OutlinedButton(onClick = { begin(ContentSplit.PRACTICE, skill.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (activeSession) l.label("Resume current session", "Продолжить текущую сессию") else skill.title.text(l))
                }
            }
        }
    }

    if (errors.isNotEmpty()) StudyCard {
        Eyebrow(l.label("REVIEW YOUR ACTUAL ERRORS", "РАЗБЕРИТЕ СВОИ ОШИБКИ"))
        SmallNote(l.label(
            "Compare your answer with its original key. Identify the missed step before starting another question.",
            "Сравните ответ с исходным ключом. Найдите пропущенный шаг перед следующим заданием.",
        ))
        errors.forEachIndexed { index, attempt ->
            key(attempt.id) {
                val exercise = versions[attempt.exerciseId to attempt.exerciseVersion]
                val skill = pack.skills.find { it.id == attempt.skillId }
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(skill?.title?.text(l) ?: attempt.skillId, fontWeight = FontWeight.SemiBold)
                    if (exercise == null) {
                        SmallNote(l.label(
                            "The original question version is being retrieved. Its key is not replaced by a newer version.",
                            "Загружается исходная версия задания. Её ключ не заменяется ответом из новой версии.",
                        ))
                    } else {
                        Text(exercise.prompt)
                        Text("${l.label("Your answer", "Ваш ответ")}: ${attempt.answer}")
                        Text("${l.label("Prepared answer", "Ответ из ключа")}: ${exercise.acceptedAnswers.joinToString(" / ")}", fontWeight = FontWeight.Medium)
                        Text(exercise.explanation.text(l))
                        exercise.evidence?.let { SmallNote("${l.label("Evidence", "Подтверждение")}: $it") }
                        exercise.typicalErrors.firstOrNull()?.let { SmallNote(it.text(l)) }
                    }
                    OutlinedButton(onClick = { begin(ContentSplit.PRACTICE, attempt.skillId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (activeSession) l.label("Resume current session", "Продолжить текущую сессию") else l.label("Practise this skill again", "Снова отработать навык"))
                    }
                }
            }
        }
        if (archiveFailedValue) {
            SmallNote(l.label("An older question could not be loaded. Try again.", "Не удалось загрузить прежнее задание. Попробуйте ещё раз."))
            TextButton(onClick = { retryValue++ }) { Text(l.label("Retry original versions", "Повторить загрузку")) }
        }
        reviewBlock?.let { block ->
            Button(onClick = { vm.completeBlock(block.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (block.completed) l.label("✓ Review completed · reopen", "✓ Разбор завершён · открыть снова") else l.label("Mark this review complete", "Отметить разбор завершённым"))
            }
        }
    }

    breakBlock?.let { block -> StudyCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Eyebrow(l.label("YOUR STUDY BREAK", "ПЕРЕРЫВ В ЗАНЯТИЯХ"))
        Text("${block.minutes} ${l.label("minutes planned", "минут по плану")}", style = MaterialTheme.typography.titleMedium)
        SmallNote(l.label(
            "Step away from the screen, stretch and get water. Before returning, choose the one mistake you will watch for next. This is your study schedule; follow the test centre's instructions during the real exam.",
            "Отойдите от экрана, разомнитесь и выпейте воды. Перед возвращением выберите одну ошибку, за которой будете следить. Это учебный перерыв; на экзамене следуйте указаниям организаторов.",
        ))
    } }

    StudyCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow(l.label("FINAL CHECKLIST", "ИТОГОВЫЙ ЧЕК-ЛИСТ"))
        Text(l.label("Finish with a clear next step.", "Завершите день с ясным планом."), style = MaterialTheme.typography.titleLarge)
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
        items.forEachIndexed { index, item -> Text("${index + 1}. $item") }
        val links = if (s.exam == Exam.SAT) listOf(
            "College Board · SAT test-day preparation" to "https://satsuite.collegeboard.org/sat/what-to-bring-do/what-to-bring",
            "College Board · SAT School Day" to "https://bluebook.collegeboard.org/students/sat-school-day",
        ) else listOf(
            "IELTS · test-day guidance" to "https://ielts.org/take-a-test/preparation-resources/on-test-day",
        )
        SmallNote(l.label("Check the current official guidance for your test administration:", "Сверьте действия с актуальной официальной инструкцией для своего экзамена:"))
        links.forEach { (title, url) ->
            TextButton(onClick = {
                linkErrorValue = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
            }, modifier = Modifier.fillMaxWidth()) { Text("$title ↗") }
        }
        if (linkErrorValue) SmallNote(l.label("The link could not be opened. Check your browser and connection.", "Ссылка не открылась. Проверьте браузер и подключение."))
        checklistBlock?.let { block ->
            Button(onClick = { vm.completeBlock(block.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (block.completed) l.label("✓ Checklist completed · reopen", "✓ Чек-лист завершён · открыть снова") else l.label("I've reviewed this checklist", "Я проверил все пункты"))
            }
            SmallNote(l.label("Checklist completion is saved with this intensive.", "Завершение чек-листа сохраняется вместе с интенсивом."))
        }
    }
}
