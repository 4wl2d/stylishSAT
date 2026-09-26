package com.tomilov.stylishsat.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.speech.RecordingPlayback
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

private const val HeatmapWeeks = 17

@Composable
fun ProgressScreen(s: StudyUiState) {
    val l = s.language
    val c = Study.colors
    val pack = s.pack ?: return
    val attempts = remember(s.attempts, s.exam) { s.examAttempts }
    val checked = attempts.count { it.correct != null }
    val independent = attempts.filter { it.independent }
    val rhythm = rememberRhythm(s)
    val states = remember(pack, s.attempts) { s.skillStates.associateBy { it.skillId } }
    val history = remember(attempts) { attempts.sortedByDescending { it.timestampEpochMillis } }
    var allHistoryValue by rememberSaveable(s.exam) { mutableStateOf(false) }
    val skills = pack.skills.filter { it.exam == s.exam }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp)) {
        item { ScreenTitle(l.label("Progress", "Прогресс")) }
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (independent.isEmpty()) "—" else "${independent.count { it.correct == true } * 100 / independent.size}",
                        style = StudyType.Display.copy(fontSize = 104.sp, lineHeight = 96.sp), color = c.ink)
                    if (independent.isNotEmpty()) Text("%", Modifier.padding(bottom = 12.dp, start = 4.dp), style = StudyType.Display.copy(fontSize = 40.sp, lineHeight = 40.sp), color = c.inkFaint)
                }
                MarkedText(l.label("independent accuracy", "точность без подсказок"), StudyType.Title)
                Text(if (checked == 0) l.label("Answer a few questions and your accuracy appears here.", "Ответьте на несколько заданий, и здесь появится точность.")
                    else l.label("${independent.size} independent of $checked checked answers. A training estimate, not an official SAT score or IELTS band.",
                        "${independent.size} самостоятельных из $checked проверенных ответов. Учебная оценка, не официальный SAT score или IELTS band."),
                    style = StudyType.Small, color = c.inkSoft)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Stat(if (checked == 0) "—" else "${independent.size * 100 / checked}%", l.label("no hints", "без подсказок"))
                Stat("${rhythm.streak}", l.label("day streak", "дней подряд"))
                Stat("${StudyRhythm.minutes(rhythm.secondsByDay.values.sum())}", l.label("minutes", "минут"))
            }
        }
        item { Heatmap(rhythm, l) }
        item { SectionLabel(l.label("Skills", "Навыки"), modifier = Modifier.padding(top = 28.dp, bottom = 4.dp)) }
        items(skills, key = { it.id }) { skill ->
            val state = states[skill.id] ?: SkillState(skill.id)
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.title.text(l), Modifier.weight(1f), style = StudyType.Strong, color = c.ink)
                    if (state.mastered) Meta(l.label("mastered", "освоено"), color = c.good)
                    else Text("${state.independentCorrectCount}/${state.independentCount}", style = StudyType.Mono, color = c.inkSoft)
                }
                Bar(if (state.independentCount == 0) 0f else state.independentCorrectCount.toFloat() / state.independentCount, color = if (state.mastered) c.good else c.ink, height = 5.dp)
                Meta(listOfNotNull(
                    l.label("level ${state.difficulty}", "уровень ${state.difficulty}"),
                    l.label("${state.attemptsCount} checked", "проверено ${state.attemptsCount}"),
                    if (state.attemptsCount > 0) l.label("${state.totalElapsedSeconds / state.attemptsCount}s avg", "${state.totalElapsedSeconds / state.attemptsCount} с в ср.") else null,
                    state.nextReviewEpochDay?.let { l.label("review ", "повтор ") + shortDate(it, l) },
                ).joinToString(" · "))
            }
            Hairline()
        }
        item { SectionLabel(l.label("Recent answers", "Последние ответы"), Modifier.padding(top = 28.dp, bottom = 4.dp), "${history.size}") }
        if (history.isEmpty()) item { Text(l.label("Nothing yet.", "Пока пусто."), style = StudyType.Body, color = c.inkSoft) }
        items(if (allHistoryValue) history else history.take(12), key = { it.id }) { attempt -> HistoryRow(s, attempt) }
        if (history.size > 12) item {
            StudyButton(if (allHistoryValue) l.label("Show recent only", "Только последние") else l.label("Show all ${history.size}", "Показать все: ${history.size}"),
                { allHistoryValue = !allHistoryValue }, Modifier.fillMaxWidth().padding(top = 12.dp), tone = Tone.Quiet, compact = true)
        }
    }
}

/** Seventeen weeks of saved answer time; darker cells mean more minutes that day. */
@Composable
private fun Heatmap(rhythm: Rhythm, l: Language) {
    val c = Study.colors
    val today = LocalDate.ofEpochDay(rhythm.today)
    val start = today.with(DayOfWeek.MONDAY).minusWeeks((HeatmapWeeks - 1).toLong()).toEpochDay()
    val studied = rhythm.secondsByDay.keys.count { it in start..rhythm.today }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(l.label("Activity", "Активность"), trailing = l.label("$studied days", "$studied дн."))
        Canvas(Modifier.fillMaxWidth().aspectRatio(HeatmapWeeks / 7f).semantics {
            contentDescription = l.label("Studied on $studied days in the last $HeatmapWeeks weeks", "Занятия в $studied дн. за последние $HeatmapWeeks недель")
        }) {
            val gap = 3.dp.toPx()
            val cell = (size.width - gap * (HeatmapWeeks - 1)) / HeatmapWeeks
            val radius = CornerRadius(3.dp.toPx())
            for (week in 0 until HeatmapWeeks) for (weekday in 0 until 7) {
                val day = start + week * 7 + weekday
                if (day > rhythm.today) continue
                val offset = Offset(week * (cell + gap), weekday * (cell + gap))
                val seconds = rhythm.secondsByDay[day]
                val color = when {
                    seconds == null -> c.sunken
                    seconds < 10 * 60 -> c.good.copy(alpha = 0.4f)
                    seconds < 25 * 60 -> c.good.copy(alpha = 0.7f)
                    else -> c.good
                }
                drawRoundRect(color, offset, Size(cell, cell), radius)
                if (day == rhythm.today) drawRoundRect(c.ink, offset, Size(cell, cell), radius, style = Stroke(1.5.dp.toPx()))
            }
        }
        Row {
            Meta(shortDate(start, l))
            Spacer(Modifier.weight(1f))
            Meta(l.label("today", "сегодня"))
        }
    }
}

@Composable
private fun HistoryRow(s: StudyUiState, attempt: Attempt) {
    val l = s.language
    val c = Study.colors
    var openValue by rememberSaveable(attempt.id) { mutableStateOf(false) }
    val recordings = attempt.recordingPaths.ifEmpty { listOfNotNull(attempt.recordingPath) }
    val feedback = remember(s.feedback, attempt.id) { s.feedback.filter { it.attemptId == attempt.id } }
    val expandable = recordings.isNotEmpty() || feedback.isNotEmpty() || attempt.answer.length > 60
    Column {
        Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(12.dp), c.paper, enabled = expandable) { openValue = !openValue }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(when (attempt.correct) { true -> Glyph.Check; false -> Glyph.Cross; null -> Glyph.Dot },
                tint = when (attempt.correct) { true -> c.good; false -> c.bad; null -> c.inkSoft }, size = 20.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.pack?.skills?.find { it.id == attempt.skillId }?.title?.text(l) ?: attempt.skillId, style = StudyType.Strong.copy(fontSize = 15.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(attempt.answer.ifBlank { l.label("Skipped", "Пропущено") }, style = StudyType.Small.copy(fontSize = 13.sp), color = c.inkSoft,
                    maxLines = if (openValue) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${attempt.elapsedSeconds}s", style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
                val flags = listOfNotNull(if (attempt.hintsUsed > 0) l.label("hint", "подск.") else null, if (attempt.isRepeat) l.label("repeat", "повтор") else null)
                if (flags.isNotEmpty()) Meta(flags.joinToString(" · "), color = c.inkSoft)
            }
        }
        if (openValue) Column(Modifier.padding(start = 34.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recordings.isNotEmpty()) {
                val player = remember { RecordingPlayback() }
                DisposableEffect(player) { onDispose { player.stop() } }
                recordings.forEachIndexed { index, path ->
                    StudyButton(l.label("Recording ${index + 1}", "Запись ${index + 1}"), { player.play(File(path)) }, tone = Tone.Quiet, compact = true, glyph = Glyph.Play)
                }
            }
            feedback.forEach { item ->
                MarginNote(rule = c.inkSoft) {
                    Meta(if (item.source == "LOCAL_AI") l.label("Local AI feedback · training only", "Отзыв локального ИИ · только тренировка") else l.label("External feedback", "Внешний отзыв"))
                    SelectionContainer { Text(item.text, style = StudyType.Small, color = c.ink) }
                }
            }
        }
        Hairline()
    }
}
