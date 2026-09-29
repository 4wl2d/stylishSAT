package com.tomilov.stylishsat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.LocalReducedMotion
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyMotion
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Language.label(en: String, ru: String) = if (this == Language.RU) ru else en

enum class Tab { Today, Library, Exam, Progress }

private enum class Screen { Splash, Failed, Onboarding, Session, Paper, Revision, Review, Notebook, Settings, Tabs }

@Composable
fun StudyApp(vm: StudyViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalReducedMotion provides (LocalReducedMotion.current || s.settings.reduceMotion)) { StudyShell(s, vm) }
}

@Composable
private fun StudyShell(s: StudyUiState, vm: StudyViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var practice by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var paperIdValue by rememberSaveable { mutableStateOf<String?>(null) }
    var revisionWorkValue by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewAttemptValue by rememberSaveable { mutableStateOf<String?>(null) }
    var notebookOpen by rememberSaveable { mutableStateOf(false) }
    val l = s.language
    val c = Study.colors
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptic = LocalHapticFeedback.current
    val activeSession = { vm.state.value.session?.let { !it.finished } == true }
    val begin: (ContentSplit, String?) -> Unit = { mode, skill ->
        vm.startSession(mode, skill)
        if (activeSession()) { practice = true; settingsOpen = false }
    }
    val plannedDay: (Int) -> Unit = { day -> vm.startPlannedDay(day); if (activeSession()) practice = true }
    val openPaper: (String) -> Unit = { id -> paperIdValue = id; settingsOpen = false }
    val openRevision: (String) -> Unit = { attemptId -> vm.beginRevision(attemptId)?.let { revisionWorkValue = it; settingsOpen = false } }
    val continueRevision: (String) -> Unit = { workId -> revisionWorkValue = workId }
    val openAttempt: (String) -> Unit = { id -> reviewAttemptValue = id }
    val followUps: () -> Unit = { if (vm.startFollowUps() && activeSession()) { practice = true; notebookOpen = false; reviewAttemptValue = null } }
    val startSection: (String, String, Boolean) -> Unit = { skill, source, strict -> vm.startSection(skill, source, strict)?.let(openPaper) }
    val onboarding = !s.loading && s.pack != null && !s.settings.onboarded && s.attempts.isEmpty() &&
        s.sessions.isEmpty() && s.plans.isEmpty() && s.dailyPlans.isEmpty()
    val screen = when {
        s.pack == null -> if (s.loading) Screen.Splash else Screen.Failed
        // A draft being restored keeps loading visible; the old finished session must not flash first.
        revisionWorkValue?.let { work -> s.revisions.values.any { it.workId == work } } == true && !s.loading -> Screen.Revision
        paperIdValue?.let { it in s.papers } == true && !s.loading -> Screen.Paper
        reviewAttemptValue?.let { id -> s.attempts.any { it.id == id } } == true && !practice -> Screen.Review
        notebookOpen && !practice -> Screen.Notebook
        practice && s.session != null && !s.loading -> Screen.Session
        onboarding -> Screen.Onboarding
        settingsOpen -> Screen.Settings
        else -> Screen.Tabs
    }
    val back = rememberBackGesture()
    PredictiveBack(back, Screen.Session, screen == Screen.Session) { practice = false }
    BackHandler(screen == Screen.Paper) { paperIdValue = null }
    BackHandler(screen == Screen.Revision) { revisionWorkValue = null }
    BackHandler(screen == Screen.Review) { reviewAttemptValue = null }
    BackHandler(screen == Screen.Notebook) { notebookOpen = false }
    PredictiveBack(back, Screen.Settings, screen == Screen.Settings) { settingsOpen = false }
    BackHandler(screen == Screen.Tabs && tab != Tab.Today) { tab = Tab.Today }
    val tabs = rememberSaveableStateHolder()

    // Behind a page that follows the back gesture, the backdrop darkens a little so the page edge stays visible.
    Box(Modifier.fillMaxSize().drawBehind { drawRect(lerp(c.paper, c.sunken, back.depth)) }) {
        AnimatedContent(screen, transitionSpec = { screenTransition(reduced, rtl) }, label = "screen") { target ->
            Box(Modifier.fillMaxSize().followBack(back, target).background(c.paper)) {
                when (target) {
                    Screen.Splash -> Splash()
                    Screen.Failed -> Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(s.error ?: l.label("Content unavailable", "Материалы недоступны"), style = StudyType.Body, color = c.ink)
                    }
                    Screen.Onboarding -> OnboardingScreen(s, vm) { begin(ContentSplit.DIAGNOSTIC, null) }
                    Screen.Session -> SessionScreen(s, vm, Modifier.fillMaxSize(), openRevision,
                        close = { practice = false; tab = Tab.Today },
                        again = { practice = false; begin(ContentSplit.PRACTICE, null) })
                    Screen.Revision -> RevisionScreen(s, vm, revisionWorkValue.orEmpty()) { revisionWorkValue = null }
                    Screen.Review -> AttemptReviewScreen(s, vm, reviewAttemptValue.orEmpty(), openRevision) { reviewAttemptValue = null }
                    Screen.Notebook -> NotebookScreen(s, vm, openAttempt, followUps) { notebookOpen = false }
                    Screen.Paper -> PaperScreen(s, vm, paperIdValue.orEmpty(), openRevision) {
                        // An exam paper returns to the Exam tab; a section returns to where it was opened.
                        if (s.papers[paperIdValue.orEmpty()]?.kind?.let { it != PaperKind.SECTION } == true) tab = Tab.Exam
                        paperIdValue = null
                    }
                    Screen.Settings -> SettingsScreen(s, vm) { settingsOpen = false }
                    Screen.Tabs -> Column(Modifier.fillMaxSize()) {
                        TopBar(s, vm, onStreak = { tab = Tab.Progress }, onSettings = { settingsOpen = true })
                        AnimatedContent(tab, Modifier.weight(1f), transitionSpec = { tabTransition(reduced, rtl) }, label = "tab") { shown ->
                            tabs.SaveableStateProvider(shown) {
                                // Switching exam rebuilds the tab, so its content arrives fresh instead of swapping in place.
                                key(s.exam) {
                                    when (shown) {
                                        Tab.Today -> TodayScreen(s, vm, begin, plannedDay, openPaper, continueRevision, followUps) { practice = true }
                                        Tab.Library -> LibraryScreen(s, begin, startSection, openPaper)
                                        Tab.Exam -> ExamScreen(s, vm, openPaper)
                                        Tab.Progress -> ProgressScreen(s, openRevision, openAttempt) { notebookOpen = true }
                                    }
                                }
                            }
                        }
                        BottomBar(tab, l) {
                            if (it != tab) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            tab = it
                        }
                    }
                }
            }
        }
        LoadingVeil(s.loading && s.pack != null)
    }
    s.error?.takeIf { s.pack != null }?.let { message ->
        AlertDialog(onDismissRequest = vm::clearError, containerColor = c.paper, textContentColor = c.ink,
            text = { Text(message, style = StudyType.Body) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK", style = StudyType.Button, color = c.ink) } })
    }
}

/**
 * Pages move along the path the learner takes: a session rises over Today and sinks back, Settings pushes in
 * from the side, everything else fades through. Reduced motion keeps only short fades.
 */
private fun AnimatedContentTransitionScope<Screen>.screenTransition(reduced: Boolean, rtl: Boolean): ContentTransform {
    val from = initialState
    val to = targetState
    val side = if (rtl) -1 else 1
    if (reduced || from == Screen.Splash || from == Screen.Failed || to == Screen.Failed)
        return fadeIn(StudyMotion.fade(200)) togetherWith fadeOut(StudyMotion.fade(120))
    return when {
        to == Screen.Session ->
            (slideInVertically(StudyMotion.page) { it / 6 } + fadeIn(StudyMotion.fade(140)) + scaleIn(StudyMotion.settle(), 0.97f)) togetherWith
                (fadeOut(StudyMotion.fade(180)) + scaleOut(StudyMotion.settle(), 0.94f))
        from == Screen.Session ->
            ((fadeIn(StudyMotion.fade(200)) + scaleIn(StudyMotion.settle(), 0.94f)) togetherWith
                (slideOutVertically(StudyMotion.page) { it / 5 } + fadeOut(StudyMotion.fade(160)))).apply { targetContentZIndex = -1f }
        to == Screen.Settings ->
            slideInHorizontally(StudyMotion.page) { side * it } togetherWith
                (slideOutHorizontally(StudyMotion.page) { -side * it / 5 } + fadeOut(StudyMotion.fade(220)))
        from == Screen.Settings ->
            ((slideInHorizontally(StudyMotion.page) { -side * it / 5 } + fadeIn(StudyMotion.fade(160))) togetherWith
                slideOutHorizontally(StudyMotion.page) { side * it }).apply { targetContentZIndex = -1f }
        else -> (fadeIn(StudyMotion.fade(220, 60)) + scaleIn(StudyMotion.settle(), 0.97f)) togetherWith fadeOut(StudyMotion.fade(90))
    }
}

/** Tabs fade through with a short slide toward the tab that was tapped. */
private fun AnimatedContentTransitionScope<Tab>.tabTransition(reduced: Boolean, rtl: Boolean): ContentTransform {
    if (reduced) return fadeIn(StudyMotion.fade(160, 40)) togetherWith fadeOut(StudyMotion.fade(80))
    val direction = (if (targetState.ordinal > initialState.ordinal) 1 else -1) * (if (rtl) -1 else 1)
    return (slideInHorizontally(StudyMotion.page) { direction * it / 8 } + fadeIn(StudyMotion.fade(200, 50))) togetherWith
        (slideOutHorizontally(StudyMotion.page) { -direction * it / 14 } + fadeOut(StudyMotion.fade(80)))
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Wordmark(Modifier.popIn(from = 0.9f)) }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    MarkedText("stylishSAT", StudyType.Headline.copy(fontSize = 36.sp), modifier)
}

/** Blocks input at once, but only becomes visible if loading lasts long enough to notice. */
@Composable
private fun LoadingVeil(visible: Boolean) {
    val c = Study.colors
    AnimatedVisibility(visible, enter = fadeIn(tween(200, delayMillis = 280)), exit = fadeOut(StudyMotion.fade(120))) {
        Box(Modifier.fillMaxSize().background(c.paper.copy(alpha = 0.7f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = c.ink, strokeWidth = 2.5.dp)
        }
    }
}

@Composable
private fun TopBar(s: StudyUiState, vm: StudyViewModel, onStreak: () -> Unit, onSettings: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val rhythm = rememberRhythm(s)
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Segmented(Exam.entries.map { it to if (it == Exam.IELTS) "IELTS" else "SAT" }, s.exam, {
            if (it != s.exam) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            vm.selectExam(it)
        })
        Spacer(Modifier.weight(1f))
        Row(Modifier.pop(rhythm.streak to rhythm.studiedToday).tapSurface(RoundedCornerShape(50), if (rhythm.studiedToday) c.marker else c.sunken, onClick = onStreak)
            .semantics(mergeDescendants = true) { contentDescription = l.label("${rhythm.streak}-day streak", "Серия: ${rhythm.streak} дн.") }
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val ink by animateColorAsState(if (rhythm.studiedToday) c.onMarker else c.ink, StudyMotion.fade(180), label = "streakInk")
            GlyphIcon(Glyph.Flame, size = 18.dp, filled = rhythm.streak > 0, tint = ink)
            Text("${rhythm.streak}", style = StudyType.Mono.copy(fontSize = 15.sp), color = ink)
        }
        GlyphButton(Glyph.Settings, l.label("Settings", "Настройки"), onSettings)
    }
}

/**
 * The highlighter pill runs to the tapped tab: its leading edge springs ahead and the trailing edge catches up,
 * so it stretches in flight and settles as a pill.
 */
@Composable
private fun BottomBar(selected: Tab, l: Language, onSelect: (Tab) -> Unit) {
    val c = Study.colors
    val reduced = LocalReducedMotion.current
    val target = selected.ordinal.toFloat()
    val lead = remember { Animatable(target) }
    val trail = remember { Animatable(target) }
    LaunchedEffect(target, reduced) {
        if (reduced) { lead.snapTo(target); trail.snapTo(target); return@LaunchedEffect }
        coroutineScope {
            launch { lead.animateTo(target, spring(dampingRatio = 0.72f, stiffness = 900f)) }
            launch { trail.animateTo(target, spring(dampingRatio = 0.9f, stiffness = 320f)) }
        }
    }
    val pill = c.marker
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth().background(c.paper)) {
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp).drawBehind {
            val slot = size.width / Tab.entries.size
            val half = 32.dp.toPx()
            val from = minOf(lead.value, trail.value)
            val to = maxOf(lead.value, trail.value)
            val left = (from + 0.5f) * slot - half
            val right = (to + 0.5f) * slot + half
            val top = 8.dp.toPx()
            val x = if (rtl) size.width - right else left
            drawRoundRect(pill, Offset(x, top), Size(right - left, 32.dp.toPx()), CornerRadius(16.dp.toPx()))
        }) {
            listOf(
                Triple(Tab.Today, Glyph.Today, l.label("Today", "Сегодня")),
                Triple(Tab.Library, Glyph.Library, l.label("Library", "Библиотека")),
                Triple(Tab.Exam, Glyph.Sheet, l.label("Exam", "Экзамен")),
                Triple(Tab.Progress, Glyph.Progress, l.label("Progress", "Прогресс")),
            ).forEach { (tab, glyph, title) ->
                val on = tab == selected
                val scale by animateFloatAsState(if (on) 1.08f else 1f, StudyMotion.bounce(), label = "tabIcon")
                val tint by animateColorAsState(if (on) c.onMarker else c.inkSoft, StudyMotion.fade(160), label = "tabTint")
                val label by animateColorAsState(if (on) c.ink else c.inkSoft, StudyMotion.fade(160), label = "tabLabel")
                Column(Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab, interactionSource = null, indication = null) { onSelect(tab) }
                    .padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp, 32.dp), contentAlignment = Alignment.Center) {
                        GlyphIcon(glyph, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, tint = tint, filled = on)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(title, style = StudyType.Meta.copy(fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), color = label, maxLines = 1)
                }
            }
        }
    }
}

/** Streak and minute totals, recomputed only when saved attempts or the exam change. */
data class Rhythm(
    val today: Long,
    val secondsByDay: Map<Long, Int>,
    val streak: Int,
    val todayMinutes: Int,
) { val studiedToday get() = today in secondsByDay }

@Composable
fun rememberRhythm(s: StudyUiState): Rhythm {
    val today = LocalDate.now().toEpochDay()
    return remember(s.attempts, s.exam, today) {
        val zone = ZoneId.systemDefault()
        val byDay = StudyRhythm.secondsByDay(s.attempts.filter { it.exam == s.exam }, zone)
        Rhythm(today, byDay, StudyRhythm.streak(byDay.keys, today), StudyRhythm.minutes(byDay[today] ?: 0))
    }
}

fun Language.locale(): Locale = if (this == Language.RU) Locale.forLanguageTag("ru") else Locale.ENGLISH

fun shortDate(epochDay: Long, l: Language): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE d MMM", l.locale()))

fun minutes(value: Int, l: Language) = l.label("$value min", "$value мин")

fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp, bottom = 4.dp), style = StudyType.Headline, color = Study.colors.ink)
}
