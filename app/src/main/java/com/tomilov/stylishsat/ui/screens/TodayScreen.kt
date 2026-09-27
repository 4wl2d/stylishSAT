package com.tomilov.stylishsat.ui.screens

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
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun TodayScreen(s: StudyUiState, vm: StudyViewModel, begin: (ContentSplit, String?) -> Unit, plannedDay: (Int) -> Unit, resume: () -> Unit) {
    val rhythm = rememberRhythm(s)
    var intensiveSheet by rememberSaveable { mutableStateOf(false) }
    var dayDetail by rememberSaveable { mutableStateOf<Int?>(null) }
    val plan = s.plan
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        WeekStrip(rhythm, s.language)
        Hero(s, vm, begin, plannedDay, resume)
        TodayMeter(s, rhythm)
        QuickRow(s, vm, begin) { intensiveSheet = true }
        Drafts(s, vm, resume)
        if (plan?.mode == PlanMode.INTENSIVE) {
            IntensiveTimeline(s, vm, plan)
            IntensiveSupport(s, vm, begin)
            StudyButton(s.language.label("Back to the daily course", "Вернуться к ежедневному курсу"), { vm.makePlan() }, Modifier.fillMaxWidth(), tone = Tone.Line)
        } else if (plan != null) Route(s, plan) { dayDetail = it }
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
            val day = date.toEpochDay()
            val studied = day in rhythm.secondsByDay
            val isToday = day == rhythm.today
            val future = day > rhythm.today
            Column(Modifier.semantics(mergeDescendants = true) {
                contentDescription = "${shortDate(day, l)}: " + if (studied) l.label("studied", "занимались") else l.label("no practice", "без занятий")
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Meta(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, l.locale()).take(2), color = if (isToday) c.ink else c.inkSoft)
                Box(Modifier.size(38.dp).clip(CircleShape)
                    .background(if (studied) c.marker else if (future) Color.Transparent else c.sunken)
                    .then(when {
                        isToday && !studied -> Modifier.border(2.dp, c.ink, CircleShape)
                        future -> Modifier.border(1.dp, c.line, CircleShape)
                        else -> Modifier
                    }), contentAlignment = Alignment.Center) {
                    if (studied) GlyphIcon(Glyph.Check, tint = c.onMarker, size = 18.dp)
                    else Text("${date.dayOfMonth}", style = StudyType.Mono.copy(fontSize = 13.sp), color = if (future) c.inkFaint else c.inkSoft)
                }
            }
        }
    }
}

/** Exactly one next step, always in the same place. */
@Composable
private fun Hero(s: StudyUiState, vm: StudyViewModel, begin: (ContentSplit, String?) -> Unit, plannedDay: (Int) -> Unit, resume: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val pack = s.pack ?: return
    val session = s.session?.takeIf { !it.finished }
    val plan = s.plan
    val skillName = { id: String -> pack.skills.find { it.id == id }?.title?.text(l) ?: id }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.hero).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        @Composable fun Label(text: String) = Meta(text, color = c.onHeroSoft)
        @Composable fun Title(text: String) = Text(text, color = c.onHero, maxLines = 3, overflow = TextOverflow.Ellipsis,
            style = if (text.length > 28) StudyType.Headline.copy(fontSize = 25.sp, lineHeight = 29.sp, letterSpacing = (-0.6).sp) else StudyType.Headline)
        @Composable fun Detail(text: String) = Text(text, style = StudyType.Small, color = c.onHeroSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        @Composable fun Go(text: String, action: () -> Unit) {
            Spacer(Modifier.height(6.dp))
            StudyButton(text, action, Modifier.fillMaxWidth(), tone = Tone.Marker, arrow = true)
        }
        when {
            session != null -> {
                Label(l.label("In progress", "В процессе") + " · " + modeLabel(session.mode, l))
                Title(session.courseDay?.let { l.label("Day $it", "День $it") } ?: session.exercise?.let { skillName(it.skillId) } ?: modeLabel(session.mode, l))
                Spacer(Modifier.height(2.dp))
                StepTrack(sessionMarks(s, session), onHero = true)
                Detail(l.label("Step ${session.index + 1} of ${session.stepCount}", "Шаг ${session.index + 1} из ${session.stepCount}"))
                Go(l.label("Continue", "Продолжить"), resume)
            }
            plan?.mode == PlanMode.INTENSIVE -> {
                val block = plan.blocks.firstOrNull { !it.completed }
                if (block == null) {
                    Label(l.label("Intensive", "Интенсив"))
                    Title(l.label("Every block done.", "Все блоки пройдены."))
                    Go(l.label("Back to the daily course", "Вернуться к курсу")) { vm.makePlan() }
                } else {
                    Label(l.label("Intensive · next block", "Интенсив · следующий блок") + " · " + minutes(block.minutes, l))
                    Title(blockTitle(block.type, l))
                    val priorities = plan.prioritySkillIds.take(3).map(skillName)
                    if (priorities.isNotEmpty() && block.type != PlanBlockType.BREAK) Detail(priorities.joinToString(" · "))
                    val mode = when (block.type) {
                        PlanBlockType.DIAGNOSTIC -> ContentSplit.DIAGNOSTIC
                        PlanBlockType.LESSON_PRACTICE -> ContentSplit.PRACTICE
                        PlanBlockType.TIMED_CHECK -> ContentSplit.ASSESSMENT
                        else -> null
                    }
                    if (mode != null) {
                        Go(l.label("Start", "Начать")) { begin(mode, null) }
                        Text(l.label("Mark block done", "Отметить блок"), Modifier.tapSurface(RoundedCornerShape(50), c.hero) { vm.completeBlock(block.id) }
                            .padding(vertical = 8.dp, horizontal = 4.dp), style = StudyType.Button.copy(fontSize = 14.sp), color = c.onHeroSoft)
                    } else {
                        Detail(when (block.type) {
                            PlanBlockType.BREAK -> l.label("Step away from the screen. Water, stretch, then pick one mistake to watch for.", "Отойдите от экрана. Вода, разминка, затем выберите одну ошибку, за которой будете следить.")
                            PlanBlockType.REVIEW -> l.label("Your recent errors are below.", "Ваши последние ошибки — ниже.")
                            else -> l.label("The checklist is below.", "Чек-лист — ниже.")
                        })
                        Go(l.label("Done", "Готово")) { vm.completeBlock(block.id) }
                    }
                }
            }
            !s.profile.diagnosticCompleted -> {
                Label(l.label("Start here", "С чего начать"))
                Title(l.label("Diagnostic", "Диагностика"))
                Detail(if (s.exam == Exam.SAT) l.label("16 questions · 8 domains", "16 заданий · 8 доменов") else "Reading · Listening · Writing · Speaking")
                Go(l.label("Start diagnostic", "Пройти диагностику")) { begin(ContentSplit.DIAGNOSTIC, null) }
            }
            plan == null -> {
                Label(l.label("Next", "Дальше"))
                Title(l.label("Your 28-day route", "Маршрут на 28 дней"))
                Detail(l.label("${s.profile.dailyMinutes} min a day. Lessons, practice, reviews and fresh checks.", "${s.profile.dailyMinutes} мин в день. Уроки, практика, повторение и проверки."))
                Go(l.label("Build route", "Составить маршрут")) { vm.makePlan() }
            }
            else -> {
                val done = s.courseProgress[s.exam]?.takeIf { it.planId == plan.id }?.completedDays.orEmpty()
                val day = plan.days.firstOrNull { it.dayNumber !in done }
                if (day != null && !day.contentExhausted) {
                    Label(l.label("Day ${day.dayNumber} of ${plan.days.size}", "День ${day.dayNumber} из ${plan.days.size}") + " · " + minutes(day.minutes, l))
                    val names = day.skillIds.map(skillName).distinct()
                    Title((names.take(2).joinToString(" · ") + if (names.size > 2) " +${names.size - 2}" else "").ifBlank { l.label("Day ${day.dayNumber}", "День ${day.dayNumber}") })
                    val kinds = day.activities.groupingBy { it.kind }.eachCount()
                    if (kinds.isNotEmpty()) Detail(ActivityKind.entries.filter { it in kinds }.joinToString(" · ") { "${kindLabel(it, l)} ×${kinds.getValue(it)}" })
                    Go(l.label("Start day ${day.dayNumber}", "Начать день ${day.dayNumber}")) { plannedDay(day.dayNumber) }
                } else {
                    Label(if (day == null) l.label("Route complete", "Маршрут пройден") else l.label("Practice", "Практика"))
                    Title(l.label("Adaptive practice", "Адаптивная практика"))
                    Detail(l.label("Picked from your weakest skills and due reviews.", "Подобрано по слабым навыкам и повторениям."))
                    Go(l.label("Practise", "Практиковаться")) { begin(ContentSplit.PRACTICE, null) }
                }
            }
        }
    }
}

@Composable
private fun TodayMeter(s: StudyUiState, rhythm: Rhythm) {
    val l = s.language
    val c = Study.colors
    val goal = s.profile.dailyMinutes.coerceAtLeast(1)
    val reached = rhythm.todayMinutes >= goal
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Ring(rhythm.todayMinutes.toFloat() / goal, Modifier.fillMaxSize(), color = if (reached) c.good else c.ink)
            if (reached) GlyphIcon(Glyph.Check, tint = c.good, size = 18.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("${rhythm.todayMinutes} / ${minutes(goal, l)}", style = StudyType.Title, color = c.ink)
            Meta(l.label("Answer time today", "Время ответов сегодня"))
        }
        s.profile.examDateEpochDay?.let { date ->
            val days = date - rhythm.today
            Column(horizontalAlignment = Alignment.End) {
                if (days >= 0) {
                    Text("$days", style = StudyType.Numeral.copy(fontSize = 26.sp, lineHeight = 28.sp), color = c.ink)
                    Meta(l.label("days to exam", "дн. до экзамена"))
                } else Meta(l.label("Exam date passed", "Дата экзамена прошла"))
            }
        }
    }
}

@Composable
private fun QuickRow(s: StudyUiState, vm: StudyViewModel, begin: (ContentSplit, String?) -> Unit, openIntensive: () -> Unit) {
    val l = s.language
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun Drafts(s: StudyUiState, vm: StudyViewModel, resume: () -> Unit) {
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Column(Modifier.width(250.dp).clip(RoundedCornerShape(20.dp)).background(c.raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun Route(s: StudyUiState, plan: StudyPlan, openDay: (Int) -> Unit) {
    val l = s.language
    val c = Study.colors
    val done = s.courseProgress[s.exam]?.takeIf { it.planId == plan.id }?.completedDays.orEmpty()
    val next = plan.days.firstOrNull { it.dayNumber !in done }?.dayNumber
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(l.label("Route", "Маршрут"), Modifier.padding(bottom = 4.dp), "${done.size} / ${plan.days.size}")
        plan.days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    val completed = day.dayNumber in done
                    val isNext = day.dayNumber == next
                    val (fill, content) = when {
                        completed -> c.ink to c.paper
                        isNext && !day.contentExhausted -> c.marker to c.onMarker
                        day.contentExhausted -> Color.Transparent to c.inkFaint
                        else -> c.sunken to c.inkSoft
                    }
                    Box(Modifier.weight(1f).aspectRatio(1f)
                        .tapSurface(RoundedCornerShape(10.dp), fill, border = if (day.contentExhausted) BorderStroke(1.dp, c.line) else null) { openDay(day.dayNumber) }
                        .semantics { contentDescription = l.label("Day ${day.dayNumber}", "День ${day.dayNumber}") + if (completed) l.label(", done", ", пройден") else "" },
                        contentAlignment = Alignment.Center) {
                        if (completed) GlyphIcon(Glyph.Check, tint = content, size = 16.dp)
                        else Text("${day.dayNumber}", style = StudyType.Mono.copy(fontSize = 13.sp), color = content)
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (plan.days.size > 28) Text(l.label("+${plan.days.size - 28} catch-up days keep unfinished work within your daily time.", "+${plan.days.size - 28} доп. дн. сохраняют незавершённую работу в пределах дневного лимита."),
            style = StudyType.Small, color = c.inkSoft)
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
            listOf(2, 4, 6).forEach { hours ->
                Column(Modifier.weight(1f).tapSurface(RoundedCornerShape(20.dp), c.raised, border = BorderStroke(1.dp, c.line)) { vm.makePlan(hours); dismiss() }
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
                Box(Modifier.size(24.dp).clip(CircleShape).background(if (block.completed) c.ink else Color.Transparent)
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
