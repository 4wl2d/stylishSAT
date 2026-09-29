package com.tomilov.stylishsat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import java.time.Instant
import java.time.ZoneId

private data class ExamOption(val kind: PaperKind, val sections: List<ExamPapers.SatSection>, val title: String, val detail: String, val availability: String, val ready: Boolean)

/** Timed papers under exam conditions, kept apart from Today's route. Raw counts and time only. */
@Composable
fun ExamScreen(s: StudyUiState, vm: StudyViewModel, openPaper: (String) -> Unit) {
    val l = s.language
    val c = Study.colors
    val pack = s.pack ?: return
    val active = s.paper
    val options = remember(pack, s.attempts, s.exam, l) { examOptions(pack, s.attempts, s.exam, l) }
    val past = remember(s.papers, s.exam) { s.papers.values.filter { it.exam == s.exam && it.kind != PaperKind.SECTION && (it.finished || it.abandoned) }
        .sortedByDescending { it.finishedAt ?: it.startedAt } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle(l.label("Exam mode", "Экзамен")) }
        item {
            Text(l.label("Full papers under exam timing, apart from your daily route. Answers count as ordinary evidence, overtime included. You get raw counts and time — no scaled SAT score and no IELTS band, because nothing here is calibrated.",
                "Полные части с экзаменационным временем, отдельно от ежедневного маршрута. Ответы учитываются как обычные, в том числе сверх времени. Вы получаете число верных ответов и время — без шкального балла SAT и IELTS band: здесь ничего не откалибровано."),
                style = StudyType.Small, color = c.inkSoft)
        }
        if (active != null) item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.hero).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Meta(l.label("In progress", "В процессе"), color = c.onHeroSoft)
                Text(paperTitle(active, l), style = StudyType.Title, color = c.onHero)
                active.part?.let { Text(it.title + " · " + l.label("${active.answered(it)}/${it.exercises.size} answered", "отвечено ${active.answered(it)}/${it.exercises.size}"), style = StudyType.Small, color = c.onHeroSoft) }
                StudyButton(l.label("Continue", "Продолжить"), { openPaper(active.id) }, Modifier.fillMaxWidth(), tone = Tone.Marker, arrow = true)
            }
        }
        items(options, key = { it.title }) { option ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(option.title, style = StudyType.Title.copy(fontSize = 18.sp), color = c.ink)
                Text(option.detail, style = StudyType.Small, color = c.ink)
                Meta(option.availability, color = if (option.ready) c.inkSoft else c.warn)
                StudyButton(l.label("Start", "Начать"), { vm.startExam(option.kind, option.sections)?.let(openPaper) }, Modifier.fillMaxWidth(),
                    tone = Tone.Ink, compact = true, enabled = active == null && option.ready)
            }
        }
        if (active != null) item {
            Text(l.label("Finish or leave the paper in progress before starting another.", "Завершите или покиньте текущую часть, прежде чем начинать другую."), style = StudyType.Small, color = c.inkSoft)
        }
        item { SectionLabel(l.label("Past papers", "Прошлые попытки"), Modifier.padding(top = 16.dp), past.size.takeIf { it > 0 }?.toString()) }
        if (past.isEmpty()) item { Text(l.label("None yet.", "Пока нет."), style = StudyType.Body, color = c.inkSoft) }
        items(past, key = { it.id }) { run ->
            val day = Instant.ofEpochMilli(run.finishedAt ?: run.startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            val summary = run.parts.filter { it.id in run.submittedParts }.joinToString(" · ") { part ->
                val raw = PaperScoring.raw(PaperScoring.score(part, run.answers))
                if (raw.closed > 0) "${part.stage ?: part.title} ${raw.correct}/${raw.closed}" else "${part.stage ?: part.title} ${AnswerChecker.wordCount(run.answers[part.exercises.first().versionKey].orEmpty())}w"
            }
            Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(16.dp), c.paper) { openPaper(run.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(paperTitle(run, l), style = StudyType.Strong, color = c.ink)
                    Meta(shortDate(day, l) + if (run.abandoned) l.label(" · left unmarked", " · без проверки") else "")
                    if (summary.isNotBlank()) Text(summary, style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
                }
                GlyphIcon(Glyph.ChevronRight, tint = c.inkSoft, size = 18.dp)
            }
            Hairline()
        }
    }
}

private fun examOptions(pack: ContentPack, attempts: List<Attempt>, exam: Exam, l: Language): List<ExamOption> {
    fun unseen(skills: List<String>) = pack.exercises.count { item -> item.skillId in skills && item.split != ContentSplit.DIAGNOSTIC &&
        !StudyPlanner.openResponse(item) && !StudyPlanner.isFamiliar(item, pack, attempts) }
    return if (exam == Exam.SAT) {
        val rw = unseen(ExamPapers.SatSection.RW.blueprint.map { it.first })
        val math = unseen(ExamPapers.SatSection.MATH.blueprint.map { it.first })
        val rwNeed = ExamPapers.SatSection.RW.questions * 2; val mathNeed = ExamPapers.SatSection.MATH.questions * 2
        listOf(
            ExamOption(PaperKind.SAT, ExamPapers.SatSection.entries, l.label("Digital SAT · full paper", "Digital SAT · полный вариант"),
                l.label("Reading and Writing: 2 modules × 27 questions × 32 min. 10-minute break. Math: 2 modules × 22 questions × 35 min, calculator allowed throughout. The second module of each section is harder or easier depending on your first module.",
                    "Reading and Writing: 2 модуля × 27 вопросов × 32 мин. Перерыв 10 мин. Math: 2 модуля × 22 вопроса × 35 мин, калькулятор разрешён всё время. Второй модуль каждого раздела сложнее или легче в зависимости от первого."),
                l.label("Unseen questions: R&W $rw of $rwNeed needed · Math $math of $mathNeed", "Новых вопросов: R&W $rw из $rwNeed · Math $math из $mathNeed"), rw > 0 && math > 0),
            ExamOption(PaperKind.SAT, listOf(ExamPapers.SatSection.RW), l.label("SAT · Reading and Writing", "SAT · Reading and Writing"),
                l.label("2 modules × 27 questions × 32 min, second module chosen from the first.", "2 модуля × 27 вопросов × 32 мин, второй модуль выбирается по первому."),
                l.label("Unseen questions: $rw of $rwNeed", "Новых вопросов: $rw из $rwNeed"), rw > 0),
            ExamOption(PaperKind.SAT, listOf(ExamPapers.SatSection.MATH), l.label("SAT · Math", "SAT · Math"),
                l.label("2 modules × 22 questions × 35 min, calculator allowed, second module chosen from the first.", "2 модуля × 22 вопроса × 35 мин, калькулятор разрешён, второй модуль выбирается по первому."),
                l.label("Unseen questions: $math of $mathNeed", "Новых вопросов: $math из $mathNeed"), math > 0),
        )
    } else {
        val reading = ExamPapers.ieltsReading(pack, attempts)
        val listening = ExamPapers.ieltsListening(pack, attempts)
        val writing = ExamPapers.ieltsWriting(pack, attempts)
        listOf(
            ExamOption(PaperKind.IELTS_READING, emptyList(), "IELTS Academic Reading",
                l.label("60 minutes for 40 questions across the passages. Move freely between passages; no extra transfer time.", "60 минут на 40 вопросов по текстам. Между текстами можно переходить; дополнительного времени на перенос нет."),
                reading?.let { part -> l.label("${part.sourceIds.size} unseen passage(s), ${part.exercises.size} questions", "Новых текстов: ${part.sourceIds.size}, вопросов: ${part.exercises.size}") }
                    ?: l.label("No unseen passages remain", "Новых текстов не осталось"), reading != null),
            ExamOption(PaperKind.IELTS_LISTENING, emptyList(), "IELTS Listening",
                l.label("Four recordings, each played once without pausing, then 10 minutes to transfer and check answers.", "Четыре записи, каждая звучит один раз без паузы, затем 10 минут на перенос и проверку ответов."),
                listening?.let { part -> l.label("${part.audioPaths.size} unseen recording(s), ${part.exercises.size} questions", "Новых записей: ${part.audioPaths.size}, вопросов: ${part.exercises.size}") }
                    ?: l.label("No unseen recordings remain", "Новых записей не осталось"), listening != null),
            ExamOption(PaperKind.IELTS_WRITING, emptyList(), "IELTS Academic Writing",
                l.label("Task 1 in 20 minutes, then Task 2 in 40 minutes. Saved for your own checklist review; no band is given.", "Task 1 за 20 минут, затем Task 2 за 40 минут. Сохраняется для проверки по вашему списку; band не выставляется."),
                writing.joinToString(" · ") { part -> part.stage.orEmpty() }.ifBlank { l.label("No tasks available", "Нет заданий") }, writing.size == 2),
        )
    }
}
