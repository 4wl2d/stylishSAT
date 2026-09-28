package com.tomilov.stylishsat.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.LocalReducedMotion
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyMotion
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun TodayScreen(s: StudyUiState, vm: StudyViewModel, begin: (ContentSplit, String?) -> Unit, plannedDay: (Int) -> Unit, openPaper: (String) -> Unit,
    continueRevision: (String) -> Unit, followUps: () -> Unit, resume: () -> Unit) {
    val rhythm = rememberRhythm(s)
    var intensiveSheet by rememberSaveable { mutableStateOf(false) }
    var dayDetail by rememberSaveable { mutableStateOf<Int?>(null) }
    val plan = s.plan
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        WeekStrip(rhythm, s.language)
        heroModel(s, vm, begin, plannedDay, openPaper, resume)?.let { Hero(it, Modifier.rise(1, 28.dp)) }
        TodayMeter(s, rhythm, Modifier.rise(2))
        QuickRow(s, vm, begin, Modifier.rise(3)) { intensiveSheet = true }
        Drafts(s, vm, resume, Modifier.rise(4))
        RevisionDrafts(s, continueRevision)
        FollowUps(s, followUps)
        if (plan?.mode == PlanMode.INTENSIVE) {
            Column(Modifier.rise(5), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                IntensiveTimeline(s, vm, plan)
                IntensiveSupport(s, vm, begin)
                StudyButton(s.language.label("Back to the daily course", "Вернуться к ежедневному курсу"), { vm.makePlan() }, Modifier.fillMaxWidth(), tone = Tone.Line)
            }
        } else if (plan != null) Route(s, plan, Modifier.rise(5)) { dayDetail = it }
    }
    if (intensiveSheet) IntensiveSheet(s, vm) { intensiveSheet = false }
    val detail = dayDetail
    if (detail != null && plan?.mode == PlanMode.COURSE) DaySheet(s, plan, detail, plannedDay) { dayDetail = null }
}

@Composable
private fun WeekStrip(rhythm: Rhythm, l: Language) {
    val c = Study.colors
    val today = LocalDate.ofEpochDay(rhythm.today)
    val monday = today.with(DayOfWeek.MONDAY)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (0L..6L).forEach { offset ->
            val date = monday.plusDays(offset)
            val delayMillis = 30 + offset * 28
            val day = date.toEpochDay()
            val studied = day in rhythm.secondsByDay
            val isToday = day == rhythm.today
            val future = day > rhythm.today
            Column(Modifier.semantics(mergeDescendants = true) {
                contentDescription = "${shortDate(day, l)}: " + if (studied) l.label("studied", "занимались") else l.label("no practice", "без занятий")
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Meta(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, l.locale()).take(2), color = if (isToday) c.ink else c.inkSoft)
                Box(Modifier.popIn(delayMillis, 0.4f).size(38.dp).clip(CircleShape)
                    .background(if (studied) c.marker else if (future) Color.Transparent else c.sunken)
                    .then(when {
                        isToday && !studied -> Modifier.border(2.dp, c.ink, CircleShape)
                        future -> Modifier.border(1.dp, c.line, CircleShape)
                        else -> Modifier
                    }), contentAlignment = Alignment.Center) {
                    if (studied) GlyphIcon(Glyph.Check, Modifier.popIn(delayMillis + 120, 0.2f), tint = c.onMarker, size = 18.dp)
                    else Text("${date.dayOfMonth}", style = StudyType.Mono.copy(fontSize = 13.sp), color = if (future) c.inkFaint else c.inkSoft)
                }
            }
        }
    }
}

/** What the hero offers; [key] names the offer so the card morphs only when the next step really changes. */
private class HeroModel(
    val key: String,
    val label: String,
    val title: String,
    val action: String,
    val onAction: () -> Unit,
    val detail: String? = null,
    val marks: List<Mark>? = null,
    val secondary: Pair<String, () -> Unit>? = null,
)

/** Exactly one next step, always in the same place. */
private fun heroModel(s: StudyUiState, vm: StudyViewModel, begin: (ContentSplit, String?) -> Unit, plannedDay: (Int) -> Unit, openPaper: (String) -> Unit, resume: () -> Unit): HeroModel? {
    val l = s.language
    val pack = s.pack ?: return null
    val session = s.session?.takeIf { !it.finished }
    val section = s.paper?.takeIf { it.kind == PaperKind.SECTION }
    val plan = s.plan
    val skillName = { id: String -> pack.skills.find { it.id == id }?.title?.text(l) ?: id }
    return when {
        session != null -> HeroModel("session:${session.id}",
            l.label("In progress", "В процессе") + " · " + modeLabel(session.mode, l),
            session.courseDay?.let { l.label("Day $it", "День $it") } ?: session.exercise?.let { skillName(it.skillId) } ?: modeLabel(session.mode, l),
            l.label("Continue", "Продолжить"), resume,
            detail = l.label("Step ${session.index + 1} of ${session.stepCount}", "Шаг ${session.index + 1} из ${session.stepCount}"),
            marks = sessionMarks(s, session))
        section != null -> HeroModel("section:${section.id}",
            l.label("In progress", "В процессе") + " · " + if (section.strict) l.label("Exam conditions", "Экзаменационный режим") else l.label("Section", "Секция"),
            section.part?.title ?: l.label("Section", "Секция"),
            l.label("Continue", "Продолжить"), { openPaper(section.id) },
            detail = section.part?.let { l.label("${section.answered(it)} of ${it.exercises.size} answered", "Отвечено ${section.answered(it)} из ${it.exercises.size}") })
        plan?.mode == PlanMode.INTENSIVE -> {
            val block = plan.blocks.firstOrNull { !it.completed }
            if (block == null) HeroModel("intensive:${plan.id}:done", l.label("Intensive", "Интенсив"), l.label("Every block done.", "Все блоки пройдены."),
                l.label("Back to the daily course", "Вернуться к курсу"), { vm.makePlan() })
            else {
                val label = l.label("Intensive · next block", "Интенсив · следующий блок") + " · " + minutes(block.minutes, l)
                val priorities = plan.prioritySkillIds.take(3).map(skillName)
                val mode = when (block.type) {
                    PlanBlockType.DIAGNOSTIC -> ContentSplit.DIAGNOSTIC
                    PlanBlockType.LESSON_PRACTICE -> ContentSplit.PRACTICE
                    PlanBlockType.TIMED_CHECK -> ContentSplit.ASSESSMENT
                    else -> null
                }
                if (mode != null) HeroModel("intensive:${block.id}", label, blockTitle(block.type, l), l.label("Start", "Начать"), { begin(mode, null) },
                    detail = priorities.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                    secondary = l.label("Mark block done", "Отметить блок") to { vm.completeBlock(block.id) })
                else HeroModel("intensive:${block.id}", label, blockTitle(block.type, l), l.label("Done", "Готово"), { vm.completeBlock(block.id) },
                    detail = when (block.type) {
                        PlanBlockType.BREAK -> l.label("Step away from the screen. Water, stretch, then pick one mistake to watch for.", "Отойдите от экрана. Вода, разминка, затем выберите одну ошибку, за которой будете следить.")
                        PlanBlockType.REVIEW -> l.label("Your recent errors are below.", "Ваши последние ошибки — ниже.")
                        else -> l.label("The checklist is below.", "Чек-лист — ниже.")
                    })
            }
        }
        !s.profile.diagnosticCompleted -> HeroModel("diagnostic", l.label("Start here", "С чего начать"), l.label("Diagnostic", "Диагностика"),
            l.label("Start diagnostic", "Пройти диагностику"), { begin(ContentSplit.DIAGNOSTIC, null) },
            detail = if (s.exam == Exam.SAT) l.label("16 questions · 8 domains", "16 заданий · 8 доменов") else "Reading · Listening · Writing · Speaking")
        plan == null -> {
            val left = s.profile.examDateEpochDay?.let { it - LocalDate.now().toEpochDay() }?.takeIf { it > 0 }
            HeroModel("route", l.label("Next", "Дальше"),
                if (left != null) l.label("Your route to exam day", "Маршрут до дня экзамена") else l.label("Your 28-day route", "Маршрут на 28 дней"),
                l.label("Build route", "Составить маршрут"), { vm.makePlan() },
                detail = if (left != null) l.label("$left days · ${s.profile.dailyMinutes} min a day, shaped by your date, goal and known result.", "$left дн. · ${s.profile.dailyMinutes} мин в день с учётом даты, цели и известного результата.")
                    else l.label("${s.profile.dailyMinutes} min a day. Set an exam date in Settings to fit the route to it.", "${s.profile.dailyMinutes} мин в день. Укажите дату экзамена в настройках, чтобы подстроить маршрут."))
        }
        else -> {
            val done = s.courseProgress[s.exam]?.takeIf { it.planId == plan.id }?.completedDays.orEmpty()
            val day = plan.days.firstOrNull { it.dayNumber !in done }
            if (day != null && !day.contentExhausted) {
                val names = day.skillIds.map(skillName).distinct()
                val kinds = day.activities.groupingBy { it.kind }.eachCount()
                HeroModel("day:${plan.id}:${day.dayNumber}",
                    l.label("Day ${day.dayNumber} of ${plan.days.size}", "День ${day.dayNumber} из ${plan.days.size}") + " · " + minutes(day.minutes, l),
                    (names.take(2).joinToString(" · ") + if (names.size > 2) " +${names.size - 2}" else "").ifBlank { l.label("Day ${day.dayNumber}", "День ${day.dayNumber}") },
                    l.label("Start day ${day.dayNumber}", "Начать день ${day.dayNumber}"), { plannedDay(day.dayNumber) },
                    detail = kinds.takeIf { it.isNotEmpty() }?.let { ActivityKind.entries.filter { kind -> kind in it }.joinToString(" · ") { kind -> "${kindLabel(kind, l)} ×${it.getValue(kind)}" } })
            } else HeroModel("practice", if (day == null) l.label("Route complete", "Маршрут пройден") else l.label("Practice", "Практика"),
                l.label("Adaptive practice", "Адаптивная практика"), l.label("Practise", "Практиковаться"), { begin(ContentSplit.PRACTICE, null) },
                detail = l.label("Picked from your weakest skills and due reviews.", "Подобрано по слабым навыкам и повторениям."))
        }
    }
}

/** The card stays put while its content morphs: the old step lifts away and the next one rises in. */
@Composable
private fun Hero(model: HeroModel, modifier: Modifier = Modifier) {
    val c = Study.colors
    val reduced = LocalReducedMotion.current
    AnimatedContent(model, modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.hero), contentKey = { it.key }, transitionSpec = {
        (if (reduced) fadeIn(StudyMotion.fade(180)) togetherWith fadeOut(StudyMotion.fade(90))
        else (fadeIn(StudyMotion.fade(200, 40)) + slideInVertically(StudyMotion.page) { it / 4 }) togetherWith
            (fadeOut(StudyMotion.fade(80)) + slideOutVertically(StudyMotion.page) { -it / 6 })) using SizeTransform { _, _ -> StudyMotion.size }
    }, label = "hero") { m ->
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Meta(m.label, color = c.onHeroSoft)
            Text(m.title, color = c.onHero, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = if (m.title.length > 28) StudyType.Headline.copy(fontSize = 25.sp, lineHeight = 29.sp, letterSpacing = (-0.6).sp) else StudyType.Headline)
            m.marks?.let { Spacer(Modifier.height(2.dp)); StepTrack(it, onHero = true, reveal = true) }
            m.detail?.let { Text(it, style = StudyType.Small, color = c.onHeroSoft, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(6.dp))
            StudyButton(m.action, m.onAction, Modifier.fillMaxWidth(), tone = Tone.Marker, arrow = true)
            m.secondary?.let { (text, action) ->
                Text(text, Modifier.tapSurface(RoundedCornerShape(50), c.hero, onClick = action).padding(vertical = 8.dp, horizontal = 4.dp),
                    style = StudyType.Button.copy(fontSize = 14.sp), color = c.onHeroSoft)
            }
        }
    }
}

@Composable
private fun TodayMeter(s: StudyUiState, rhythm: Rhythm, modifier: Modifier = Modifier) {
    val l = s.language
    val c = Study.colors
    val goal = s.profile.dailyMinutes.coerceAtLeast(1)
    val reached = rhythm.todayMinutes >= goal
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            val ring by animateColorAsState(if (reached) c.good else c.ink, StudyMotion.fade(300), label = "ring")
            Ring(rhythm.todayMinutes.toFloat() / goal, Modifier.fillMaxSize(), color = ring)
            if (reached) GlyphIcon(Glyph.Check, Modifier.popIn(500, 0.2f), tint = c.good, size = 18.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            CountUp(rhythm.todayMinutes, StudyType.Title, format = { "$it / ${minutes(goal, l)}" })
            Meta(l.label("Answer time today", "Время ответов сегодня"))
        }
        s.profile.examDateEpochDay?.let { date ->
            val days = date - rhythm.today
            Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.End) {
                if (days >= 0) {
                    CountUp(days.toInt(), StudyType.Numeral.copy(fontSize = 26.sp, lineHeight = 28.sp))
                    Meta(l.label("days to exam", "дн. до экзамена"))
                } else Meta(l.label("Exam date passed", "Дата экзамена прошла"))
            }
        }
    }
}

@Composable
private fun QuickRow(s: StudyUiState, vm: StudyViewModel, begin: (ContentSplit, String?) -> Unit, modifier: Modifier = Modifier, openIntensive: () -> Unit) {
    val l = s.language
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickTile(Glyph.Today, l.label("Practice", "Практика"), l.label("Adaptive set", "Подборка"), Modifier.weight(1f)) { begin(ContentSplit.PRACTICE, null) }
        QuickTile(Glyph.Clock, l.label("Timed", "На время"), l.label("Fresh items", "Новые задания"), Modifier.weight(1f)) { begin(ContentSplit.ASSESSMENT, null) }
        if (s.plan?.mode == PlanMode.INTENSIVE) QuickTile(Glyph.Calendar, l.label("Course", "Курс"), l.label("Daily route", "Каждый день"), Modifier.weight(1f)) { vm.makePlan() }
        else QuickTile(Glyph.Bolt, l.label("Intensive", "Интенсив"), l.label("2 · 4 · 6 h", "2 · 4 · 6 ч"), Modifier.weight(1f), onClick = openIntensive)
    }
}

@Composable
private fun QuickTile(glyph: Glyph, title: String, detail: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Study.colors
    Column(modifier.fillMaxHeight().tapSurface(RoundedCornerShape(20.dp), c.raised, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlyphIcon(glyph, size = 22.dp, filled = glyph == Glyph.Bolt)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = StudyType.Strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = StudyType.Small.copy(fontSize = 12.sp, lineHeight = 16.sp), color = c.inkSoft, maxLines = 2)
        }
    }
}

@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Meta(title, color = Study.colors.ink)
        Spacer(Modifier.weight(1f))
        trailing?.let { Meta(it) }
    }
}

@Composable
private fun Drafts(s: StudyUiState, vm: StudyViewModel, resume: () -> Unit, modifier: Modifier = Modifier) {
    val drafts = remember(s.drafts, s.exam) {
        s.drafts.values.filter { draft ->
            draft.exam == s.exam && !draft.submitted && draft.supersededByWorkId == null &&
                (draft.text.isNotBlank() || draft.recordingPath != null || draft.recordingPaths.isNotEmpty() || draft.hintsUsed > 0 || draft.reviewGuidanceViewed || draft.elapsedSeconds > 0)
        }.sortedByDescending { it.updatedAt }
    }
    if (drafts.isEmpty()) return
    val l = s.language
    val c = Study.colors
    val active = s.session?.finished == false
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(l.label("Unfinished", "Незавершённое"), trailing = "${drafts.size}")
        if (active) Text(l.label("Finish the current session to open a draft.", "Завершите текущую сессию, чтобы открыть черновик."), style = StudyType.Small, color = c.inkSoft)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(drafts, key = { it.key }) { draft ->
                val exercise = s.pack?.exercises?.firstOrNull { it.id == draft.exerciseId && it.version == draft.exerciseVersion }
                    ?: s.sessions[s.exam]?.exercises?.firstOrNull { it.id == draft.exerciseId && it.version == draft.exerciseVersion }
                    ?: (s.plans.values + s.dailyPlans.values).asSequence().flatMap { it.days.asSequence() }
                        .flatMap { it.activities.asSequence() }.mapNotNull { it.exerciseSnapshot }
                        .firstOrNull { it.id == draft.exerciseId && it.version == draft.exerciseVersion }
                val withinPriorities = s.plan?.mode != PlanMode.INTENSIVE || exercise == null ||
                    exercise.split == ContentSplit.DIAGNOSTIC || exercise.skillId in s.plan!!.prioritySkillIds.take(3)
                val title = exercise?.let { ex -> s.pack?.skills?.firstOrNull { it.id == ex.skillId }?.title?.text(l) } ?: l.label("Saved response", "Сохранённый ответ")
                Column(Modifier.animateItem().width(250.dp).clip(RoundedCornerShape(20.dp)).background(c.raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meta(title)
                    Text(exercise?.prompt ?: "", style = StudyType.Small, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
                    Text(draft.text.ifBlank {
                        if (draft.recordingPath != null || draft.recordingPaths.isNotEmpty()) l.label("Recording saved", "Запись сохранена") else l.label("Time and hints saved", "Время и подсказки сохранены")
                    }, style = StudyType.Small.copy(fontSize = 13.sp), color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!withinPriorities) Text(l.label("Opens in the daily course.", "Откроется в ежедневном курсе."), style = StudyType.Small.copy(fontSize = 12.sp), color = c.inkSoft)
                    StudyButton(l.label("Continue", "Продолжить"), { if (vm.resumeDraft(draft.key)) resume() }, Modifier.fillMaxWidth(),
                        tone = Tone.Quiet, compact = true, enabled = !active && withinPriorities && !s.loading)
                }
            }
        }
    }
}

/** Fresh questions queued from the mistake notebook. */
@Composable
private fun FollowUps(s: StudyUiState, practise: () -> Unit) {
    val pending = remember(s.notebook, s.attempts, s.exam) { Notebook.pending(s.notebook.values, s.attempts, s.exam) }
    if (pending.isEmpty()) return
    val l = s.language
    val c = Study.colors
    Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(20.dp), c.raised, enabled = s.session?.finished != false, onClick = practise).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.Bulb, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.label("${pending.size} follow-up(s) from your notebook", "Из тетради ошибок: ${pending.size}"), style = StudyType.Strong, color = c.ink)
            Text(l.label("Fresh questions from the families you missed", "Новые задания из семей, где были ошибки"), style = StudyType.Small, color = c.inkSoft)
        }
        GlyphIcon(Glyph.ArrowRight, tint = c.inkSoft, size = 18.dp)
    }
}

/** Unsaved next versions of written answers. */
@Composable
private fun RevisionDrafts(s: StudyUiState, open: (String) -> Unit) {
    val drafts = remember(s.revisions, s.exam) { s.revisions.values.filter { it.exam == s.exam && !it.saved }.sortedByDescending { it.updatedAt } }
    if (drafts.isEmpty()) return
    val l = s.language
    val c = Study.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(l.label("Revisions in progress", "Доработка ответов"), trailing = "${drafts.size}")
        drafts.forEach { draft ->
            val prompt = s.pack?.exercises?.firstOrNull { it.id == draft.exerciseId }?.prompt.orEmpty()
            Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(20.dp), c.raised) { open(draft.workId) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Meta(l.label("Version ${draft.number} · ${AnswerChecker.wordCount(draft.text)} words", "Версия ${draft.number} · ${AnswerChecker.wordCount(draft.text)} слов"))
                    Text(prompt, style = StudyType.Small, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                GlyphIcon(Glyph.ChevronRight, tint = c.inkSoft, size = 18.dp)
            }
        }
    }
}

@Composable
private fun Route(s: StudyUiState, plan: StudyPlan, modifier: Modifier = Modifier, openDay: (Int) -> Unit) {
    val l = s.language
    val c = Study.colors
    val done = s.courseProgress[s.exam]?.takeIf { it.planId == plan.id }?.completedDays.orEmpty()
    val next = plan.days.firstOrNull { it.dayNumber !in done }?.dayNumber
    val route = plan.route
    var allWeeks by rememberSaveable(plan.id) { mutableStateOf(false) }
    val weeks = plan.days.chunked(7)
    val currentWeek = weeks.indexOfFirst { week -> week.any { it.dayNumber == next } }.coerceAtLeast(0)
    // Long routes show the current month; the rest opens on request.
    val shown = if (allWeeks || weeks.size <= 5) weeks else weeks.drop(currentWeek).take(5)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(l.label("Route", "Маршрут"), Modifier.padding(bottom = 4.dp), "${done.size} / ${plan.days.size}")
        if (route != null) RouteSummary(s, plan, route, next)
        shown.forEachIndexed { row, week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEachIndexed { column, day ->
                    val completed = day.dayNumber in done
                    val isNext = day.dayNumber == next
                    val (fill, content) = when {
                        completed -> c.ink to c.paper
                        isNext && !day.contentExhausted -> c.marker to c.onMarker
                        day.contentExhausted -> Color.Transparent to c.inkFaint
                        else -> c.sunken to c.inkSoft
                    }
                    val sitting = route?.sittingDays?.contains(day.dayNumber) == true
                    Box(Modifier.weight(1f).aspectRatio(1f).popIn(160L + (row + column) * 24L, 0.5f)
                        .tapSurface(RoundedCornerShape(10.dp), fill, border = when {
                            day.contentExhausted -> BorderStroke(1.dp, c.line)
                            sitting && !completed -> BorderStroke(2.dp, c.ink)
                            else -> null
                        }) { openDay(day.dayNumber) }
                        .semantics { contentDescription = l.label("Day ${day.dayNumber}", "День ${day.dayNumber}") + (if (completed) l.label(", done", ", пройден") else "") +
                            if (sitting) l.label(", full sitting suggested", ", рекомендуется полный пробный экзамен") else "" },
                        contentAlignment = Alignment.Center) {
                        if (completed) GlyphIcon(Glyph.Check, tint = content, size = 16.dp)
                        else Text("${day.dayNumber}", style = StudyType.Mono.copy(fontSize = 13.sp), color = content)
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (shown.size < weeks.size) Text(l.label("Show all ${plan.days.size} days", "Показать все ${plan.days.size} дн."),
            Modifier.tapSurface(RoundedCornerShape(50), c.paper) { allWeeks = true }.padding(vertical = 8.dp), style = StudyType.Button.copy(fontSize = 14.sp), color = c.ink)
        val planned = route?.days ?: 28
        if (plan.days.size > planned) Text(l.label("+${plan.days.size - planned} catch-up days keep unfinished work within your daily time.", "+${plan.days.size - planned} доп. дн. сохраняют незавершённую работу в пределах дневного лимита."),
            style = StudyType.Small, color = c.inkSoft)
    }
}

/** What the exam date, goal and known result did to the route, in plain words. Nothing here is a predicted score. */
@Composable
private fun RouteSummary(s: StudyUiState, plan: StudyPlan, route: CourseRoute, next: Int?) {
    val l = s.language
    val c = Study.colors
    val pack = s.pack ?: return
    val skillName = { id: String -> pack.skills.find { it.id == id }?.title?.text(l) ?: id }
    val checksFrom = plan.days.firstOrNull { day -> day.activities.any { it.kind == ActivityKind.ASSESSMENT } }?.dayNumber
    val unit = if (s.exam == Exam.SAT) l.label("points", "баллов") else l.label("band", "балла")
    fun number(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    val lines = buildList {
        when (route.pace) {
            RoutePace.OPEN -> add(if (route.datePassed) l.label("Your exam date has passed, so this is the standard 28-day course. Set a new date in Settings to plan to it.",
                "Дата экзамена прошла, поэтому это стандартный курс на 28 дней. Укажите новую дату в настройках.")
                else l.label("No exam date: the standard 28-day course. Set a date in Settings to fit the route to it.", "Дата экзамена не указана: стандартный курс на 28 дней. Укажите дату в настройках."))
            RoutePace.SPRINT -> add(l.label("${route.daysLeft} days left: a short route with no separate rules block.", "Осталось ${route.daysLeft} дн.: короткий маршрут без отдельного блока правил."))
            RoutePace.STEADY -> add(l.label("${route.daysLeft} days left: rules first, then mixed practice, then harder exam practice.", "Осталось ${route.daysLeft} дн.: сначала правила, затем смешанная практика и более трудная экзаменационная."))
            RoutePace.LONG -> add(l.label("${route.daysLeft} days left: a longer rules-first block, mixed practice, then harder exam practice with regular full sittings.",
                "Осталось ${route.daysLeft} дн.: длинный блок правил, смешанная практика, затем более трудная практика и регулярные пробные экзамены."))
        }
        if (route.capped) add(l.label("The next ${CourseRoutes.MAX_DAYS} days are planned; the route extends as you complete days, and harder practice and final checks stay timed to the exam date.",
            "Спланированы ближайшие ${CourseRoutes.MAX_DAYS} дн.; маршрут продлится по мере прохождения, а трудная практика и финальные проверки привязаны к дате экзамена."))
        if (route.focusSkillIds.isNotEmpty()) add(l.label("New work stays on your weakest areas: ", "Новая работа — только по самым слабым темам: ") + route.focusSkillIds.joinToString(", ") { skillName(it) } + ".")
        val gap = route.gap
        if (gap != null && route.target != null && route.known != null) add(if (route.targetMet)
            l.label("Your known result (${number(route.known)}) already meets the goal (${number(route.target)}), so the route keeps you exam-ready: less new material, more timed work.",
                "Известный результат (${number(route.known)}) уже достигает цели (${number(route.target)}): меньше нового материала, больше работы на время.")
            else l.label("Goal ${number(route.target)} · known ${number(route.known)} · gap ${number(gap)} $unit. The gap only sizes the route; it is not a prediction.",
                "Цель ${number(route.target)} · известно ${number(route.known)} · разница ${number(gap)} $unit. Разница лишь задаёт маршрут и не является прогнозом."))
        else if (route.pace != RoutePace.OPEN && (s.profile.target.isNotBlank() || s.profile.knownResult.isNotBlank()) && (route.target == null || route.known == null))
            add(l.label("Add both a goal and a known result as ${if (s.exam == Exam.SAT) "SAT totals such as 1350" else "IELTS bands such as 6.5"} to size the gap.",
                "Укажите цель и известный результат как ${if (s.exam == Exam.SAT) "итог SAT, например 1350" else "балл IELTS, например 6.5"}, чтобы учесть разницу."))
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        next?.let { day ->
            val phase = when {
                checksFrom != null && day >= checksFrom -> l.label("Final skill checks", "Финальные проверки навыков")
                else -> when (route.phaseOn(day)) {
                    RoutePhaseKind.FOUNDATION -> l.label("Rules first: a short lesson before each new topic", "Сначала правила: короткий урок перед каждой новой темой")
                    RoutePhaseKind.EXAM_PRACTICE -> l.label("Exam practice: items one level above your current level", "Экзаменационная практика: задания на уровень выше текущего")
                    RoutePhaseKind.BUILD -> l.label("Mixed practice and reviews", "Смешанная практика и повторение")
                    null -> null
                }
            }
            phase?.let { Text(l.label("Day $day · ", "День $day · ") + it, style = StudyType.Strong, color = c.ink) }
        }
        lines.forEach { Text(it, style = StudyType.Small, color = c.inkSoft) }
        route.sittingDays.firstOrNull { next == null || it >= next }?.let { day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Sheet, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (day == next) l.label("Suggested today: a full sitting in the Exam tab, outside your daily minutes. Raw counts only.", "Сегодня рекомендуется полный пробный экзамен во вкладке «Экзамен», сверх дневного лимита. Только число верных ответов.")
                    else l.label("Next full sitting: day $day (Exam tab, outside your daily minutes).", "Следующий полный пробный экзамен: день $day (вкладка «Экзамен», сверх дневного лимита)."),
                    style = StudyType.Small, color = c.ink)
            }
        }
    }
}

@Composable
private fun DaySheet(s: StudyUiState, plan: StudyPlan, dayNumber: Int, plannedDay: (Int) -> Unit, dismiss: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val pack = s.pack ?: return
    val day = plan.days.firstOrNull { it.dayNumber == dayNumber } ?: return
    val done = s.courseProgress[s.exam]?.takeIf { it.planId == plan.id }?.completedDays.orEmpty()
    val next = plan.days.firstOrNull { it.dayNumber !in done }?.dayNumber
    val skillName = { id: String -> pack.skills.find { it.id == id }?.title?.text(l) ?: id }
    StudySheet(dismiss) {
        Meta(shortDate(day.epochDay, l) + " · " + minutes(day.minutes, l))
        Text(l.label("Day ${day.dayNumber}", "День ${day.dayNumber}"), style = StudyType.Headline, color = c.ink)
        if (day.skillIds.isNotEmpty()) Text(day.skillIds.map(skillName).distinct().joinToString(" · "), style = StudyType.Body, color = c.inkSoft)
        if (day.activities.isNotEmpty()) Column {
            day.activities.forEach { activity ->
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(kindLabel(activity.kind, l), style = StudyType.Strong, color = c.ink)
                        Text(skillName(activity.skillId) + if (activity.continuation) l.label(" · continued", " · продолжение") else "", style = StudyType.Small, color = c.inkSoft)
                    }
                    Text(minutes(activity.minutes, l), style = StudyType.Mono, color = c.inkSoft)
                }
            }
            Hairline()
        }
        if (day.contentExhausted) Text(l.label("No suitable new material is left for this day. Use the library or import an update.", "Для этого дня не осталось подходящих новых материалов. Используйте библиотеку или обновите пакет."), style = StudyType.Small, color = c.inkSoft)
        when {
            day.dayNumber in done -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlyphIcon(Glyph.Check, tint = c.good, size = 18.dp); Text(l.label("Done", "Пройден"), style = StudyType.Strong, color = c.good)
            }
            day.dayNumber == next && !day.contentExhausted -> StudyButton(l.label("Start day ${day.dayNumber}", "Начать день ${day.dayNumber}"),
                { dismiss(); plannedDay(day.dayNumber) }, Modifier.fillMaxWidth(), arrow = true)
            next != null && day.dayNumber > next -> Text(l.label("Opens after day ${day.dayNumber - 1}.", "Откроется после дня ${day.dayNumber - 1}."), style = StudyType.Small, color = c.inkSoft)
        }
    }
}

@Composable
private fun IntensiveSheet(s: StudyUiState, vm: StudyViewModel, dismiss: () -> Unit) {
    val l = s.language
    val c = Study.colors
    StudySheet(dismiss) {
        Text(l.label("Intensive", "Интенсив"), style = StudyType.Headline, color = c.ink)
        Text(l.label("Up to three priorities, timed checks, a review of your own errors and an exam-day checklist.",
            "До трёх приоритетов, проверки на время, разбор ваших ошибок и чек-лист к экзамену."), style = StudyType.Body, color = c.inkSoft)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(2, 4, 6).forEachIndexed { index, hours ->
                Column(Modifier.weight(1f).popIn(120L + index * 50L, 0.8f).tapSurface(RoundedCornerShape(20.dp), c.raised, border = BorderStroke(1.dp, c.line)) { vm.makePlan(hours); dismiss() }
                    .padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$hours", style = StudyType.Numeral, color = c.ink)
                    Meta(l.label("hours", if (hours == 6) "часов" else "часа"))
                }
            }
        }
        if (s.session?.finished == false) Text(l.label("Your current session will be saved and closed.", "Текущая сессия будет сохранена и закрыта."), style = StudyType.Small, color = c.inkSoft)
        Hairline()
        IntensiveOfflineReadiness(l, s.pack?.version ?: 0, vm.runtime)
    }
}

@Composable
private fun IntensiveTimeline(s: StudyUiState, vm: StudyViewModel, plan: StudyPlan) {
    val l = s.language
    val c = Study.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(l.label("Intensive plan", "План интенсива"), Modifier.padding(bottom = 6.dp), minutes(plan.totalMinutes, l))
        plan.blocks.forEach { block ->
            Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(14.dp), c.paper) { vm.completeBlock(block.id) }.padding(vertical = 10.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.pop(block.completed.takeIf { it }).size(24.dp).clip(CircleShape).background(if (block.completed) c.ink else Color.Transparent)
                    .border(1.5.dp, if (block.completed) c.ink else c.inkFaint, CircleShape), contentAlignment = Alignment.Center) {
                    if (block.completed) GlyphIcon(Glyph.Check, tint = c.paper, size = 14.dp)
                }
                Spacer(Modifier.width(14.dp))
                Text(blockTitle(block.type, l), Modifier.weight(1f), style = StudyType.Strong, color = if (block.completed) c.inkSoft else c.ink)
                Text(minutes(block.minutes, l), style = StudyType.Mono, color = c.inkSoft)
            }
        }
        var remainingValue by rememberSaveable(plan.id) { mutableFloatStateOf(120f) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.label("Time left today", "Осталось сегодня"), Modifier.weight(1f), style = StudyType.Small, color = c.inkSoft)
            Text(minutes(remainingValue.toInt(), l), style = StudyType.Mono, color = c.ink)
        }
        Slider(remainingValue, { remainingValue = it }, valueRange = 30f..360f, steps = 10,
            colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink, inactiveTrackColor = c.sunken, activeTickColor = c.paper, inactiveTickColor = c.inkFaint))
        StudyButton(l.label("Fit the rest into this time", "Уложить остаток в это время"), { vm.recalculate(remainingValue.toInt()) }, Modifier.fillMaxWidth(),
            tone = Tone.Quiet, compact = true, enabled = plan.blocks.any { !it.completed })
    }
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
    val c = Study.colors
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
    val failed = installationValue?.isFailure == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Meta(l.label("Works offline", "Работает без интернета"), color = c.ink)
        StatusLine(l.label("Lessons & audio", "Уроки и аудио"), "v$packVersion", true)
        StatusLine("Gemma", intensiveModelStatus(l, ModelCatalog.gemma.id, files?.gemmaInstalled, files?.modelsSupported, failed, downloadValue), files?.gemmaInstalled == true && files.modelsSupported)
        StatusLine("Whisper", intensiveModelStatus(l, ModelCatalog.whisper.id, files?.whisperInstalled, files?.modelsSupported, failed, downloadValue), files?.whisperInstalled == true && files.modelsSupported)
        if (files?.accelerationEligible == true) StatusLine(l.label("Fast feedback", "Быстрый разбор"), when (accelerationValue) {
            is LocalAccelerationState.Ready -> l.label("prepared", "подготовлен")
            is LocalAccelerationState.Preparing -> l.label("preparing", "готовится")
            is LocalAccelerationState.Failed -> l.label("check Settings", "см. настройки")
            is LocalAccelerationState.Idle -> l.label("not prepared", "не подготовлен")
        }, accelerationValue is LocalAccelerationState.Ready)
        Text(if (files?.modelsSupported == false) l.label("Local AI needs 8 GB+ RAM and ARM64. Lessons, audio and self-check work on this device.",
            "Для локального ИИ нужны 8+ ГБ RAM и ARM64. Уроки, аудио и самопроверка работают на этом устройстве.")
            else l.label("Model downloads live in Settings.", "Загрузка моделей — в настройках."), style = StudyType.Small, color = c.inkSoft)
    }
}

@Composable
fun StatusLine(name: String, status: String, ok: Boolean) {
    val c = Study.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) c.good else c.inkFaint))
        Spacer(Modifier.width(10.dp))
        Text(name, Modifier.weight(1f), style = StudyType.Strong.copy(fontSize = 15.sp), color = c.ink)
        Text(status, style = StudyType.Small, color = c.inkSoft)
    }
}

private fun intensiveModelStatus(l: Language, modelId: String, installed: Boolean?, supported: Boolean?, failed: Boolean, state: DownloadState): String = when {
    failed -> l.label("status unavailable", "статус недоступен")
    supported == false -> if (installed == true) l.label("installed · can't run here", "установлено · не запустится") else l.label("not supported here", "не поддерживается")
    state is DownloadState.Downloading && state.modelId == modelId -> l.label("downloading", "загружается")
    state is DownloadState.Verifying && state.modelId == modelId -> l.label("verifying", "проверяется")
    installed == null -> l.label("checking…", "проверка…")
    installed == true -> l.label("installed", "установлено")
    state is DownloadState.Paused && state.modelId == modelId -> l.label("paused", "пауза")
    state is DownloadState.Failed && state.modelId == modelId -> l.label("retry in Settings", "повторите в настройках")
    else -> l.label("not installed", "не установлено")
}

fun blockTitle(type: PlanBlockType, l: Language) = when (type) {
    PlanBlockType.DIAGNOSTIC -> l.label("Diagnostic", "Диагностика")
    PlanBlockType.LESSON_PRACTICE -> l.label("Lessons & practice", "Уроки и практика")
    PlanBlockType.BREAK -> l.label("Break", "Перерыв")
    PlanBlockType.TIMED_CHECK -> l.label("Timed check", "Проверка на время")
    PlanBlockType.REVIEW -> l.label("Error review", "Разбор ошибок")
    PlanBlockType.CHECKLIST -> l.label("Exam-day checklist", "Чек-лист к экзамену")
}

fun kindLabel(kind: ActivityKind, l: Language) = when (kind) {
    ActivityKind.LESSON -> l.label("Lesson", "Урок")
    ActivityKind.REVIEW -> l.label("Review", "Разбор")
    ActivityKind.PRACTICE -> l.label("Practice", "Практика")
    ActivityKind.ASSESSMENT -> l.label("Fresh check", "Проверка")
}

fun modeLabel(mode: ContentSplit, l: Language) = when (mode) {
    ContentSplit.DIAGNOSTIC -> l.label("Diagnostic", "Диагностика")
    ContentSplit.PRACTICE -> l.label("Practice", "Практика")
    ContentSplit.ASSESSMENT -> l.label("Timed check", "Проверка на время")
}
