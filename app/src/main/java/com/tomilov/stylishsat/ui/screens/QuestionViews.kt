package com.tomilov.stylishsat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType

/** A matching list shows "iv  Heading"; ordinary options show their own text. */
fun optionLabel(exercise: Exercise, option: String): String =
    exercise.group?.options?.firstOrNull { it.key == option }?.let { "${it.key}  ${it.text}" } ?: option

/** Questions answered by picking a key from a shared list: headings, features, paragraphs, map letters. */
fun Exercise.usesGroupList(): Boolean = type == ExerciseType.MULTIPLE_CHOICE && group?.options?.isNotEmpty() == true

fun formatLabel(format: QuestionFormat, l: Language): String = when (format) {
    QuestionFormat.TRUE_FALSE_NOT_GIVEN -> "True / False / Not given"
    QuestionFormat.YES_NO_NOT_GIVEN -> "Yes / No / Not given"
    QuestionFormat.MATCHING_HEADINGS -> l.label("Matching headings", "Заголовки к абзацам")
    QuestionFormat.MATCHING_INFORMATION -> l.label("Matching information", "Поиск информации по абзацам")
    QuestionFormat.MATCHING_FEATURES -> l.label("Matching features", "Соотнесение с признаками")
    QuestionFormat.SUMMARY_COMPLETION -> l.label("Summary completion", "Заполнение резюме")
    QuestionFormat.SENTENCE_COMPLETION -> l.label("Sentence completion", "Завершение предложений")
    QuestionFormat.NOTE_COMPLETION -> l.label("Note completion", "Заполнение заметок")
    QuestionFormat.TABLE_COMPLETION -> l.label("Table completion", "Заполнение таблицы")
    QuestionFormat.DIAGRAM_LABEL -> l.label("Diagram labelling", "Подписи к схеме")
    QuestionFormat.MAP_LABEL -> l.label("Map labelling", "Подписи к карте")
    QuestionFormat.MULTIPLE_CHOICE -> l.label("Multiple choice", "Выбор ответа")
    QuestionFormat.SHORT_ANSWER -> l.label("Short answer", "Краткий ответ")
}

/** Shared instruction, lettered list, gapped text and figure; [numbers] maps question ids to their display numbers. */
@Composable
fun GroupContext(group: QuestionGroup, numbers: Map<String, Int>) {
    val c = Study.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(group.instruction, style = StudyType.Strong, color = c.ink)
        if (group.options.isNotEmpty()) Block(padding = 14.dp, spacing = 6.dp) {
            group.options.forEach { option ->
                Row {
                    Text(option.key, Modifier.width(44.dp), style = StudyType.Mono, color = c.ink)
                    Text(option.text, Modifier.weight(1f), style = StudyType.Small.copy(fontSize = 15.sp, lineHeight = 21.sp), color = c.ink)
                }
            }
        }
        group.text?.let { text ->
            MarginNote(rule = c.inkSoft) {
                SelectionContainer { Text(ContentPackCodec.fillGaps(text, numbers), style = StudyType.Reading.copy(fontSize = 17.sp, lineHeight = 28.sp), color = c.ink) }
            }
        }
        group.figure?.let { FigureView(it, numbers) }
    }
}

/** Number badge used in sections and results. */
@Composable
fun QuestionNumber(number: Int, state: Mark = Mark.Todo) {
    val c = Study.colors
    val (fill, content) = when (state) {
        Mark.Right -> c.marker to c.onMarker
        Mark.Wrong -> c.bad to c.paper
        Mark.Done, Mark.Open -> c.ink to c.paper
        else -> c.sunken to c.ink
    }
    Box(Modifier.size(30.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Text("$number", style = StudyType.Mono.copy(fontSize = 13.sp), color = content)
    }
}

/** A grid row: pick one key from the group's list. */
@Composable
fun KeyChips(exercise: Exercise, selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    val c = Study.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        exercise.options.forEach { option ->
            val on = option == selected
            Box(Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp)
                .tapSurface(RoundedCornerShape(12.dp), if (on) c.ink else c.raised, enabled, BorderStroke(1.dp, if (on) c.ink else c.line), role = Role.RadioButton) { onPick(option) }
                .semantics { this.selected = on; contentDescription = optionLabel(exercise, option) }
                .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(option, style = StudyType.Mono.copy(fontSize = 15.sp), color = if (on) c.paper else c.ink)
            }
        }
    }
}

/** Compact option rows for ordinary multiple choice inside a section or module; options can be struck out while thinking. */
@Composable
fun ChoiceList(exercise: Exercise, selected: String, enabled: Boolean, eliminated: List<String> = emptyList(), eliminating: Boolean = false,
    eliminate: (String) -> Unit = {}, onPick: (String) -> Unit) {
    val c = Study.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        exercise.options.forEachIndexed { index, option ->
            val on = option == selected
            val struck = option in eliminated
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .tapSurface(RoundedCornerShape(14.dp), if (on) c.sunken else c.raised, enabled, BorderStroke(if (on) 2.dp else 1.dp, if (on) c.ink else c.line), role = Role.RadioButton) {
                    if (struck) eliminate(option)
                    onPick(option)
                }
                .semantics { this.selected = on }
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${'A' + index}", Modifier.width(26.dp), style = StudyType.Mono, color = c.inkSoft)
                Text(option, Modifier.weight(1f), style = if (struck) StudyType.Body.copy(textDecoration = TextDecoration.LineThrough) else StudyType.Body,
                    color = if (struck) c.inkFaint else c.ink)
                if (eliminating && enabled) GlyphButton(Glyph.Strike, "${'A' + index}", { eliminate(option) }, tint = if (struck) c.ink else c.inkSoft, size = 40.dp)
            }
        }
    }
}
