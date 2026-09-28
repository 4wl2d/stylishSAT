package com.tomilov.stylishsat.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.LocalReducedMotion
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyMotion
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.delay

@Composable
fun LibraryScreen(s: StudyUiState, begin: (ContentSplit, String?) -> Unit, startSection: (String, String, Boolean) -> Unit, openPaper: (String) -> Unit) {
    val pack = s.pack ?: return
    var skillIdValue by rememberSaveable(s.exam) { mutableStateOf<String?>(null) }
    var lessonIdValue by rememberSaveable(s.exam) { mutableStateOf<String?>(null) }
    val skill = skillIdValue?.let { id -> pack.skills.find { it.id == id && it.exam == s.exam } }
    val lesson = lessonIdValue?.let { id -> pack.lessons.find { it.id == id } }
    val back = rememberBackGesture()
    PredictiveBack(back, 2, lesson != null) { lessonIdValue = null }
    PredictiveBack(back, 1, lesson == null && skill != null) { skillIdValue = null }
    val states = remember(pack, s.attempts) { s.skillStates.associateBy { it.skillId } }
    val depth = when { lesson != null -> 2; skill != null -> 1; else -> 0 }
    val c = Study.colors
    val reduced = LocalReducedMotion.current
    val side = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    Box(Modifier.fillMaxSize().drawBehind { drawRect(lerp(c.paper, c.sunken, back.depth)) }) {
        // Deeper pages stack on top: they slide in over the list and slide off it again.
        AnimatedContent(depth, transitionSpec = {
            val deeper = targetState > initialState
            when {
                reduced -> fadeIn(StudyMotion.fade(160)) togetherWith fadeOut(StudyMotion.fade(90))
                deeper -> (slideInHorizontally(StudyMotion.page) { side * it / 3 } + fadeIn(StudyMotion.fade(160))) togetherWith
                    (slideOutHorizontally(StudyMotion.page) { -side * it / 8 } + fadeOut(StudyMotion.fade(160)))
                else -> ((slideInHorizontally(StudyMotion.page) { -side * it / 8 } + fadeIn(StudyMotion.fade(160, 40))) togetherWith
                    (slideOutHorizontally(StudyMotion.page) { side * it / 3 } + fadeOut(StudyMotion.fade(160)))).apply { targetContentZIndex = -1f }
            }
        }, label = "library") { level ->
            Box(Modifier.fillMaxSize().followBack(back, level).background(c.paper)) {
                when {
                    level == 2 && lesson != null && skill != null -> LessonReader(s, skill, lesson, { lessonIdValue = null }) { begin(ContentSplit.PRACTICE, skill.id) }
                    level >= 1 && skill != null -> SkillPage(s, skill, states[skill.id], { skillIdValue = null }, { lessonIdValue = it }, startSection, openPaper) { begin(ContentSplit.PRACTICE, skill.id) }
                    else -> SkillList(s, states) { skillIdValue = it; lessonIdValue = null }
                }
            }
        }
    }
}

@Composable
private fun SkillList(s: StudyUiState, states: Map<String, SkillState>, open: (String) -> Unit) {
    val l = s.language
    val c = Study.colors
    val pack = s.pack ?: return
    var searchValue by rememberSaveable { mutableStateOf("") }
    val skills = remember(pack, s.exam, searchValue) {
        pack.skills.filter { it.exam == s.exam }.filter { skill ->
            val lessons = pack.lessons.filter { it.skillId == skill.id }
            searchValue.isBlank() || "${skill.title.en} ${skill.title.ru} ${lessons.joinToString { it.title.en + it.title.ru + it.body.en + it.body.ru }}".contains(searchValue.trim(), true)
        }
    }
    // Only the first screenful makes an entrance; rows scrolled to later simply appear.
    var entering by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(600); entering = false }
    val order = remember(skills) { skills.groupBy { it.section }.values.flatten().withIndex().associate { (index, skill) -> skill.id to index } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle(l.label("Library", "Библиотека"), Modifier.rise(0)) }
        item {
            OutlinedTextField(searchValue, { searchValue = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp).rise(1), singleLine = true,
                placeholder = { Text(l.label("Find a skill or rule", "Найти навык или правило"), style = StudyType.Body) },
                leadingIcon = { GlyphIcon(Glyph.Search, tint = c.inkSoft, size = 20.dp) },
                trailingIcon = if (searchValue.isNotEmpty()) ({ GlyphButton(Glyph.Close, l.label("Clear", "Очистить"), { searchValue = "" }, tint = c.inkSoft, size = 40.dp) }) else null,
                shape = RoundedCornerShape(50), colors = studyFieldColors(), textStyle = StudyType.Body)
        }
        if (skills.isEmpty()) item(key = "empty") { Text(l.label("Nothing matches “$searchValue”.", "Ничего не найдено по запросу «$searchValue»."), Modifier.animateItem(), style = StudyType.Body, color = c.inkSoft) }
        skills.groupBy { it.section }.forEach { (section, group) ->
            item(key = "section:$section") {
                SectionLabel(section, modifier = Modifier.animateItem().padding(top = 10.dp, bottom = 2.dp).rise((order[group.first().id] ?: 0) + 2, enabled = entering))
            }
            items(group, key = { it.id }) { skill ->
                val lessons = pack.lessons.count { it.skillId == skill.id }
                val state = states[skill.id]
                Row(Modifier.animateItem().rise((order[skill.id] ?: 0) + 2, enabled = entering).fillMaxWidth()
                    .tapSurface(RoundedCornerShape(20.dp), c.raised) { open(skill.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(skill.title.text(l), style = StudyType.Title.copy(fontSize = 18.sp), color = c.ink)
                        Text(listOf(l.label("$lessons lessons", "уроков: $lessons"), l.label("level ${state?.difficulty ?: 1}", "уровень ${state?.difficulty ?: 1}")).joinToString(" · "),
                            style = StudyType.Small.copy(fontSize = 13.sp), color = c.inkSoft)
                        Bar(state?.let { if (it.independentCount == 0) 0f else it.independentCorrectCount.toFloat() / it.independentCount } ?: 0f, Modifier.padding(top = 2.dp),
                            color = if (state?.mastered == true) c.good else c.ink, height = 4.dp)
                    }
                    Spacer(Modifier.width(16.dp))
                    if (state?.mastered == true) Box(Modifier.popIn(300, 0.4f).size(30.dp).clip(CircleShape).background(c.marker), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Check, tint = c.onMarker, size = 16.dp) }
                    else GlyphIcon(Glyph.ChevronRight, tint = c.inkSoft, size = 20.dp)
                }
            }
        }
    }
}

@Composable
private fun SkillPage(s: StudyUiState, skill: Skill, state: SkillState?, back: () -> Unit, openLesson: (String) -> Unit,
    startSection: (String, String, Boolean) -> Unit, openPaper: (String) -> Unit, practise: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val lessons = s.pack?.lessons.orEmpty().filter { it.skillId == skill.id }
    val sections = remember(s.pack, skill.id) { s.pack?.let { Sections.of(it, skill.exam, ContentSplit.PRACTICE, skill.id) }.orEmpty() }
    Column(Modifier.fillMaxSize()) {
        GlyphButton(Glyph.ArrowLeft, l.label("Back", "Назад"), back, Modifier.padding(start = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Meta(skill.section, Modifier.rise(0))
            MarkedText(skill.title.text(l), StudyType.Headline, Modifier.rise(1), delayMillis = 240)
            skill.description.text(l).takeIf { it.isNotBlank() }?.let { Text(it, Modifier.rise(2), style = StudyType.Body, color = c.inkSoft) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).rise(3)) {
                Stat("${state?.difficulty ?: 1}/3", l.label("level", "уровень"))
                Stat(state?.takeIf { it.independentCount > 0 }?.let { "${it.independentCorrectCount}/${it.independentCount}" } ?: "—", l.label("independent", "самостоятельно"))
            }
            state?.nextReviewEpochDay?.let { Text(l.label("Next review: ", "Следующее повторение: ") + shortDate(it, l), Modifier.rise(3), style = StudyType.Small, color = c.inkSoft) }
            SectionLabel(l.label("Lessons", "Уроки"), Modifier.rise(4), trailing = "${lessons.size}")
            Column {
                lessons.forEachIndexed { index, lesson ->
                    Hairline(Modifier.rise(index + 5))
                    Row(Modifier.rise(index + 5).fillMaxWidth().tapSurface(RoundedCornerShape(12.dp), c.paper) { openLesson(lesson.id) }.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}".padStart(2, '0'), Modifier.width(40.dp), style = StudyType.Mono, color = c.inkSoft)
                        Text(lesson.title.text(l), Modifier.weight(1f), style = StudyType.Strong, color = c.ink)
                        Text(minutes(lesson.estimatedMinutes, l), style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
                    }
                }
                Hairline(Modifier.rise(lessons.size + 5))
            }
            if (sections.isNotEmpty()) SectionList(s, sections, startSection, openPaper)
        }
        StudyButton(if (sections.isEmpty()) l.label("Practise this skill", "Отработать навык") else l.label("Short drills", "Короткие упражнения"), practise, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).rise(2, 24.dp), arrow = true)
    }
}

/** Whole passages and recordings. Drills stay available below as the short practice mode. */
@Composable
private fun SectionList(s: StudyUiState, sections: List<SourceSection>, startSection: (String, String, Boolean) -> Unit, openPaper: (String) -> Unit) {
    val l = s.language
    val c = Study.colors
    val active = s.paper
    SectionLabel(l.label("Full sections", "Целые секции"), Modifier.padding(top = 12.dp), "${sections.size}")
    Text(l.label("Every question for one passage or recording on one page. Exam conditions add the clock and play a recording once.",
        "Все вопросы к одному тексту или записи на одной странице. В экзаменационном режиме идёт таймер, а запись звучит один раз."),
        style = StudyType.Small, color = c.inkSoft)
    sections.forEach { section ->
        val done = remember(s.attempts, section) { Sections.answered(section, s.attempts) }
        val formats = section.exercises.mapNotNull { it.format }.distinct()
        val open = active?.takeIf { it.sourceId == section.sourceId && it.sourceSkillId == section.skillId }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(section.title, style = StudyType.Title.copy(fontSize = 18.sp), color = c.ink)
            Meta(listOfNotNull(
                l.label("${section.exercises.size} questions", "вопросов: ${section.exercises.size}"),
                if (section.listening) l.label("recording", "запись") else l.label("${section.words} words", "${section.words} слов"),
                if (done > 0) l.label("$done answered before", "ранее отвечено: $done") else l.label("new", "новая"),
            ).joinToString(" · "))
            if (formats.isNotEmpty()) Text(formats.joinToString(" · ") { formatLabel(it, l) }, style = StudyType.Small.copy(fontSize = 13.sp), color = c.inkSoft)
            if (open != null) StudyButton(l.label("Continue", "Продолжить"), { openPaper(open.id) }, Modifier.fillMaxWidth(), compact = true, arrow = true)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudyButton(l.label("Practise", "Практика"), { startSection(section.skillId, section.sourceId, false) }, Modifier.weight(1f), compact = true, enabled = active == null)
                StudyButton(l.label("Exam conditions", "Как на экзамене"), { startSection(section.skillId, section.sourceId, true) }, Modifier.weight(1f), tone = Tone.Quiet, compact = true, enabled = active == null)
            }
        }
    }
    if (active != null && sections.none { it.sourceId == active.sourceId }) Text(l.label("Finish the open section or exam before starting another.", "Завершите открытую секцию или экзамен, прежде чем начинать новую."),
        style = StudyType.Small, color = c.inkSoft)
}

@Composable
private fun LessonReader(s: StudyUiState, skill: Skill, lesson: Lesson, back: () -> Unit, practise: () -> Unit) {
    val l = s.language
    val c = Study.colors
    Column(Modifier.fillMaxSize()) {
        GlyphButton(Glyph.ArrowLeft, l.label("Back", "Назад"), back, Modifier.padding(start = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Meta("${skill.title.text(l)} · ${minutes(lesson.estimatedMinutes, l)}", Modifier.rise(0))
            MarkedText(lesson.title.text(l), StudyType.Headline, Modifier.rise(1), delayMillis = 240)
            SelectionContainer(Modifier.rise(2)) { Text(lesson.body.text(l), style = StudyType.Reading, color = c.ink) }
            MarginNote(Modifier.rise(3)) {
                Meta(l.label("Worked example", "Разобранный пример"))
                SelectionContainer { Text(lesson.workedExample.text(l), style = StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 27.sp), color = c.ink) }
            }
        }
        StudyButton(l.label("Practise this skill", "Отработать навык"), practise, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).rise(2, 24.dp), arrow = true)
    }
}
