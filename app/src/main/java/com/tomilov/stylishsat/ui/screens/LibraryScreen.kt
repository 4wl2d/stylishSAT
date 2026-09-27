package com.tomilov.stylishsat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType

@Composable
fun LibraryScreen(s: StudyUiState, begin: (ContentSplit, String?) -> Unit) {
    val pack = s.pack ?: return
    var skillIdValue by rememberSaveable(s.exam) { mutableStateOf<String?>(null) }
    var lessonIdValue by rememberSaveable(s.exam) { mutableStateOf<String?>(null) }
    val skill = skillIdValue?.let { id -> pack.skills.find { it.id == id && it.exam == s.exam } }
    val lesson = lessonIdValue?.let { id -> pack.lessons.find { it.id == id } }
    BackHandler(lesson != null) { lessonIdValue = null }
    BackHandler(lesson == null && skill != null) { skillIdValue = null }
    val states = remember(pack, s.attempts) { s.skillStates.associateBy { it.skillId } }
    val depth = when { lesson != null -> 2; skill != null -> 1; else -> 0 }
    AnimatedContent(depth, transitionSpec = {
        (fadeIn(tween(160)) + slideInHorizontally(tween(220)) { if (targetState > initialState) it / 10 else -it / 10 }) togetherWith fadeOut(tween(90))
    }, label = "library") { level ->
        when {
            level == 2 && lesson != null && skill != null -> LessonReader(s, skill, lesson, { lessonIdValue = null }) { begin(ContentSplit.PRACTICE, skill.id) }
            level >= 1 && skill != null -> SkillPage(s, skill, states[skill.id], { skillIdValue = null }, { lessonIdValue = it }) { begin(ContentSplit.PRACTICE, skill.id) }
            else -> SkillList(s, states) { skillIdValue = it; lessonIdValue = null }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle(l.label("Library", "Библиотека")) }
        item {
            OutlinedTextField(searchValue, { searchValue = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true,
                placeholder = { Text(l.label("Find a skill or rule", "Найти навык или правило"), style = StudyType.Body) },
                leadingIcon = { GlyphIcon(Glyph.Search, tint = c.inkSoft, size = 20.dp) },
                trailingIcon = if (searchValue.isNotEmpty()) ({ GlyphButton(Glyph.Close, l.label("Clear", "Очистить"), { searchValue = "" }, tint = c.inkSoft, size = 40.dp) }) else null,
                shape = RoundedCornerShape(50), colors = studyFieldColors(), textStyle = StudyType.Body)
        }
        if (skills.isEmpty()) item { Text(l.label("Nothing matches “$searchValue”.", "Ничего не найдено по запросу «$searchValue»."), style = StudyType.Body, color = c.inkSoft) }
        skills.groupBy { it.section }.forEach { (section, group) ->
            item(key = "section:$section") { SectionLabel(section, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
            items(group, key = { it.id }) { skill ->
                val lessons = pack.lessons.count { it.skillId == skill.id }
                val state = states[skill.id]
                Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(20.dp), c.raised) { open(skill.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(skill.title.text(l), style = StudyType.Title.copy(fontSize = 18.sp), color = c.ink)
                        Text(listOf(l.label("$lessons lessons", "уроков: $lessons"), l.label("level ${state?.difficulty ?: 1}", "уровень ${state?.difficulty ?: 1}")).joinToString(" · "),
                            style = StudyType.Small.copy(fontSize = 13.sp), color = c.inkSoft)
                        Bar(state?.let { if (it.independentCount == 0) 0f else it.independentCorrectCount.toFloat() / it.independentCount } ?: 0f, Modifier.padding(top = 2.dp),
                            color = if (state?.mastered == true) c.good else c.ink, height = 4.dp)
                    }
                    Spacer(Modifier.width(16.dp))
                    if (state?.mastered == true) Box(Modifier.size(30.dp).clip(CircleShape).background(c.marker), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Check, tint = c.onMarker, size = 16.dp) }
                    else GlyphIcon(Glyph.ChevronRight, tint = c.inkSoft, size = 20.dp)
                }
            }
        }
    }
}

@Composable
private fun SkillPage(s: StudyUiState, skill: Skill, state: SkillState?, back: () -> Unit, openLesson: (String) -> Unit, practise: () -> Unit) {
    val l = s.language
    val c = Study.colors
    val lessons = s.pack?.lessons.orEmpty().filter { it.skillId == skill.id }
    Column(Modifier.fillMaxSize()) {
        GlyphButton(Glyph.ArrowLeft, l.label("Back", "Назад"), back, Modifier.padding(start = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Meta(skill.section)
            MarkedText(skill.title.text(l), StudyType.Headline)
            skill.description.text(l).takeIf { it.isNotBlank() }?.let { Text(it, style = StudyType.Body, color = c.inkSoft) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Stat("${state?.difficulty ?: 1}/3", l.label("level", "уровень"))
                Stat(state?.takeIf { it.independentCount > 0 }?.let { "${it.independentCorrectCount}/${it.independentCount}" } ?: "—", l.label("independent", "самостоятельно"))
            }
            state?.nextReviewEpochDay?.let { Text(l.label("Next review: ", "Следующее повторение: ") + shortDate(it, l), style = StudyType.Small, color = c.inkSoft) }
            SectionLabel(l.label("Lessons", "Уроки"), trailing = "${lessons.size}")
            Column {
                lessons.forEachIndexed { index, lesson ->
                    Hairline()
                    Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(12.dp), c.paper) { openLesson(lesson.id) }.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}".padStart(2, '0'), Modifier.width(40.dp), style = StudyType.Mono, color = c.inkSoft)
                        Text(lesson.title.text(l), Modifier.weight(1f), style = StudyType.Strong, color = c.ink)
                        Text(minutes(lesson.estimatedMinutes, l), style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
                    }
                }
                Hairline()
            }
        }
        StudyButton(l.label("Practise this skill", "Отработать навык"), practise, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), arrow = true)
    }
}

@Composable
private fun LessonReader(s: StudyUiState, skill: Skill, lesson: Lesson, back: () -> Unit, practise: () -> Unit) {
    val l = s.language
    val c = Study.colors
    Column(Modifier.fillMaxSize()) {
        GlyphButton(Glyph.ArrowLeft, l.label("Back", "Назад"), back, Modifier.padding(start = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Meta("${skill.title.text(l)} · ${minutes(lesson.estimatedMinutes, l)}")
            MarkedText(lesson.title.text(l), StudyType.Headline)
            SelectionContainer { Text(lesson.body.text(l), style = StudyType.Reading, color = c.ink) }
            MarginNote {
                Meta(l.label("Worked example", "Разобранный пример"))
                SelectionContainer { Text(lesson.workedExample.text(l), style = StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 27.sp), color = c.ink) }
            }
        }
        StudyButton(l.label("Practise this skill", "Отработать навык"), practise, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), arrow = true)
    }
}
