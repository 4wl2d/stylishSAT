package com.tomilov.stylishsat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Language.label(en: String, ru: String) = if (this == Language.RU) ru else en

enum class Tab { Today, Library, Progress }

private enum class Screen { Splash, Failed, Onboarding, Session, Settings, Tabs }

@Composable
fun StudyApp(vm: StudyViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var practice by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val l = s.language
    val c = Study.colors
    val activeSession = { vm.state.value.session?.let { !it.finished } == true }
    val begin: (ContentSplit, String?) -> Unit = { mode, skill ->
        vm.startSession(mode, skill)
        if (activeSession()) { practice = true; settingsOpen = false }
    }
    val plannedDay: (Int) -> Unit = { day -> vm.startPlannedDay(day); if (activeSession()) practice = true }
    val onboarding = !s.loading && s.pack != null && !s.settings.onboarded && s.attempts.isEmpty() &&
        s.sessions.isEmpty() && s.plans.isEmpty() && s.dailyPlans.isEmpty()
    val screen = when {
        s.pack == null -> if (s.loading) Screen.Splash else Screen.Failed
        // A draft being restored keeps loading visible; the old finished session must not flash first.
        practice && s.session != null && !s.loading -> Screen.Session
        onboarding -> Screen.Onboarding
        settingsOpen -> Screen.Settings
        else -> Screen.Tabs
    }
    BackHandler(screen == Screen.Session) { practice = false }
    BackHandler(screen == Screen.Settings) { settingsOpen = false }
    BackHandler(screen == Screen.Tabs && tab != Tab.Today) { tab = Tab.Today }
    val tabs = rememberSaveableStateHolder()

    Box(Modifier.fillMaxSize().background(c.paper)) {
        AnimatedContent(screen, transitionSpec = {
            (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 16 }) togetherWith fadeOut(tween(110))
        }, label = "screen") { target ->
            when (target) {
                Screen.Splash -> Splash()
                Screen.Failed -> Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(s.error ?: l.label("Content unavailable", "Материалы недоступны"), style = StudyType.Body, color = c.ink)
                }
                Screen.Onboarding -> OnboardingScreen(s, vm) { begin(ContentSplit.DIAGNOSTIC, null) }
                Screen.Session -> SessionScreen(s, vm, Modifier.fillMaxSize(),
                    close = { practice = false; tab = Tab.Today },
                    again = { practice = false; begin(ContentSplit.PRACTICE, null) })
                Screen.Settings -> SettingsScreen(s, vm) { settingsOpen = false }
                Screen.Tabs -> Column(Modifier.fillMaxSize()) {
                    TopBar(s, vm, onStreak = { tab = Tab.Progress }, onSettings = { settingsOpen = true })
                    Box(Modifier.weight(1f)) {
                        tabs.SaveableStateProvider(tab) {
                            when (tab) {
                                Tab.Today -> TodayScreen(s, vm, begin, plannedDay) { practice = true }
                                Tab.Library -> LibraryScreen(s, begin)
                                Tab.Progress -> ProgressScreen(s)
                            }
                        }
                    }
                    BottomBar(tab, l) { tab = it }
                }
            }
        }
        if (s.loading && s.pack != null) LoadingVeil()
    }
    s.error?.takeIf { s.pack != null }?.let { message ->
        AlertDialog(onDismissRequest = vm::clearError, containerColor = c.paper, textContentColor = c.ink,
            text = { Text(message, style = StudyType.Body) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK", style = StudyType.Button, color = c.ink) } })
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Wordmark() }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    MarkedText("stylishSAT", StudyType.Headline.copy(fontSize = 36.sp), modifier)
}

@Composable
private fun LoadingVeil() {
    val c = Study.colors
    Box(Modifier.fillMaxSize().background(c.paper.copy(alpha = 0.7f))
        .clickable(remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = c.ink, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun TopBar(s: StudyUiState, vm: StudyViewModel, onStreak: () -> Unit, onSettings: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val rhythm = rememberRhythm(s)
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Segmented(Exam.entries.map { it to if (it == Exam.IELTS) "IELTS" else "SAT" }, s.exam, vm::selectExam)
        Spacer(Modifier.weight(1f))
        Row(Modifier.tapSurface(RoundedCornerShape(50), if (rhythm.studiedToday) c.marker else c.sunken, onClick = onStreak)
            .semantics(mergeDescendants = true) { contentDescription = l.label("${rhythm.streak}-day streak", "Серия: ${rhythm.streak} дн.") }
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            GlyphIcon(Glyph.Flame, size = 18.dp, filled = rhythm.streak > 0, tint = if (rhythm.studiedToday) c.onMarker else c.ink)
            Text("${rhythm.streak}", style = StudyType.Mono.copy(fontSize = 15.sp), color = if (rhythm.studiedToday) c.onMarker else c.ink)
        }
        GlyphButton(Glyph.Settings, l.label("Settings", "Настройки"), onSettings)
    }
}

@Composable
private fun BottomBar(selected: Tab, l: Language, onSelect: (Tab) -> Unit) {
    val c = Study.colors
    Column(Modifier.fillMaxWidth().background(c.paper)) {
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            listOf(
                Triple(Tab.Today, Glyph.Today, l.label("Today", "Сегодня")),
                Triple(Tab.Library, Glyph.Library, l.label("Library", "Библиотека")),
                Triple(Tab.Progress, Glyph.Progress, l.label("Progress", "Прогресс")),
            ).forEach { (tab, glyph, title) ->
                val on = tab == selected
                Column(Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    GlyphIcon(glyph, tint = if (on) c.ink else c.inkFaint, filled = on)
                    Spacer(Modifier.height(4.dp))
                    Text(title, style = StudyType.Meta.copy(fontSize = 11.sp), color = if (on) c.ink else c.inkSoft, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(width = 16.dp, height = 3.dp).background(if (on) c.marker else c.paper, RoundedCornerShape(50)))
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
