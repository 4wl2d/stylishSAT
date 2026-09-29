package com.tomilov.stylishsat.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType

private val satMathSkills = setOf("sat_algebra", "sat_advanced", "sat_data", "sat_geometry")

/** Digital SAT Math allows a calculator and the reference sheet on every question. */
fun Exercise.satMath(): Boolean = exam == Exam.SAT && skillId in satMathSkills

enum class WorkTool { Calculator, Reference, Note }

/** Which working tools a question offers, and their current state. */
data class ToolState(
    val eliminating: Boolean = false,
    val highlighting: Boolean = false,
    val hasNote: Boolean = false,
    val marked: Boolean? = null,
)

/** Calculator, reference sheet, option elimination, highlighter, scratch note and mark for review. */
@Composable
fun ToolBar(exercise: Exercise, state: ToolState, l: Language, open: (WorkTool) -> Unit, eliminate: () -> Unit, highlight: () -> Unit, mark: (() -> Unit)?) {
    val c = Study.colors
    @Composable fun Tool(glyph: Glyph, label: String, on: Boolean, role: Role, action: () -> Unit) {
        Box(Modifier.size(44.dp).tapSurface(RoundedCornerShape(12.dp), if (on) c.ink else c.paper, border = BorderStroke(1.dp, if (on) c.ink else c.line), role = role, onClick = action)
            .semantics { contentDescription = label; if (role != Role.Button) selected = on }, contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, tint = if (on) c.paper else c.ink, size = 20.dp, filled = on && glyph == Glyph.Bookmark)
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (exercise.satMath()) {
            Tool(Glyph.Calculator, l.label("Calculator", "Калькулятор"), false, Role.Button) { open(WorkTool.Calculator) }
            Tool(Glyph.Sigma, l.label("Reference sheet", "Справочные формулы"), false, Role.Button) { open(WorkTool.Reference) }
        }
        if (exercise.type == ExerciseType.MULTIPLE_CHOICE && !exercise.usesGroupList()) Tool(Glyph.Strike, l.label("Eliminate answer choices", "Вычёркивать варианты"), state.eliminating, Role.Switch, eliminate)
        if (exercise.passage != null) Tool(Glyph.Marker, l.label("Highlight sentences", "Выделять предложения"), state.highlighting, Role.Switch, highlight)
        Tool(Glyph.Note, l.label("Scratch note", "Заметка"), state.hasNote, Role.Button) { open(WorkTool.Note) }
        if (mark != null && state.marked != null) Tool(Glyph.Bookmark, l.label("Mark for review", "Отметить для повторения"), state.marked, Role.Checkbox, mark)
    }
}

/** Tap sentences to highlight them while highlight mode is on; otherwise the text stays selectable. */
@Composable
fun HighlightablePassage(text: String, highlights: List<Int>, highlighting: Boolean, style: TextStyle, toggle: (Int) -> Unit) {
    val c = Study.colors
    if (!highlighting && highlights.isEmpty()) {
        SelectionContainer { Text(text, style = style, color = c.ink) }
        return
    }
    val sentences = remember(text) { Passages.sentences(text) }
    val annotated = remember(text, highlights, highlighting, c) {
        buildAnnotatedString {
            sentences.forEachIndexed { index, range ->
                val piece = text.substring(range)
                val marked = index in highlights
                val body = { if (marked) withStyle(SpanStyle(background = c.marker, color = c.onMarker)) { append(piece) } else append(piece) }
                if (highlighting) withLink(LinkAnnotation.Clickable("sentence-$index", TextLinkStyles(SpanStyle(textDecoration = TextDecoration.None))) { toggle(index) }) { body() }
                else body()
            }
        }
    }
    if (highlighting) Text(annotated, style = style, color = c.ink) else SelectionContainer { Text(annotated, style = style, color = c.ink) }
}

private val keypad = listOf(
    listOf("2nd", "DEG", "(", ")", "⌫"),
    listOf("sin", "cos", "tan", "ln", "log"),
    listOf("√", "^", "!", "%", "÷"),
    listOf("7", "8", "9", "π", "×"),
    listOf("4", "5", "6", "e", "−"),
    listOf("1", "2", "3", "ans", "+"),
    listOf("0", ".", "E", "AC", "="),
)

/** A bundled scientific calculator. It only computes; nothing typed here is saved or submitted. */
@Composable
fun CalculatorSheet(vm: StudyViewModel, l: Language, dismiss: () -> Unit) {
    val c = Study.colors
    val calc by vm.calculator.collectAsStateWithLifecycle()
    val preview = remember(calc.expression, calc.angle, calc.ans) {
        if (calc.expression.isBlank()) null else Calculator.evaluate(calc.expression, calc.angle, calc.ans)
    }
    StudySheet(dismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.label("Calculator", "Калькулятор"), Modifier.weight(1f), style = StudyType.Title, color = c.ink)
            Meta(if (calc.angle == Calculator.Angle.DEG) l.label("degrees", "градусы") else l.label("radians", "радианы"), color = c.ink)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raised).padding(14.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(calc.expression.ifBlank { " " }, style = StudyType.Mono.copy(fontSize = 20.sp, lineHeight = 26.sp), color = c.ink, textAlign = TextAlign.End, maxLines = 3)
            val shown = when {
                calc.evaluated -> Calculator.Result.Value(calc.ans)
                else -> preview
            }
            when (shown) {
                is Calculator.Result.Value -> {
                    val fraction = Calculator.fraction(shown.value)
                    Text("= " + Calculator.format(shown.value) + (fraction?.let { " (${it.first}/${it.second})" } ?: ""),
                        style = StudyType.Mono.copy(fontSize = if (calc.evaluated) 26.sp else 16.sp, lineHeight = 30.sp), color = if (calc.evaluated) c.ink else c.inkSoft)
                }
                is Calculator.Result.Error -> if (calc.evaluated || calc.expression.isNotBlank()) Text(when (shown.reason) {
                    Calculator.Reason.SYNTAX -> l.label("…", "…")
                    Calculator.Reason.DIVIDE_BY_ZERO -> l.label("Cannot divide by zero", "На ноль делить нельзя")
                    Calculator.Reason.DOMAIN -> l.label("Outside the function's domain", "Вне области определения")
                    Calculator.Reason.OVERFLOW -> l.label("Too large", "Слишком большое число")
                }, style = StudyType.Small, color = c.inkSoft)
                null -> Unit
            }
        }
        keypad.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val label = when {
                        key == "DEG" -> if (calc.angle == Calculator.Angle.DEG) "DEG" else "RAD"
                        calc.second && key in setOf("sin", "cos", "tan") -> "a$key"
                        calc.second && key == "√" -> "x²"
                        else -> key
                    }
                    val input = when {
                        key in setOf("sin", "cos", "tan", "ln", "log") -> (if (calc.second && key in setOf("sin", "cos", "tan")) "a$key" else key) + "("
                        calc.second && key == "√" -> "^2"
                        else -> key
                    }
                    val tone = when (key) { "=" -> c.ink; "AC", "⌫", "2nd", "DEG" -> c.sunken; else -> c.raised }
                    Box(Modifier.weight(1f).height(48.dp).tapSurface(RoundedCornerShape(12.dp), if (key == "2nd" && calc.second) c.marker else tone,
                        border = BorderStroke(1.dp, c.line)) { vm.calculatorKey(input) }
                        .semantics { contentDescription = keyDescription(label, l) }, contentAlignment = Alignment.Center) {
                        Text(label, style = StudyType.Mono.copy(fontSize = 16.sp), color = if (key == "=") c.paper else c.ink)
                    }
                }
            }
        }
        if (calc.history.isNotEmpty()) {
            Meta(l.label("Recent · tap to reuse the result", "Недавние · нажмите, чтобы вставить результат"))
            calc.history.forEach { line ->
                Text(line, Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(10.dp), c.paper) { vm.calculatorInsert(line.substringAfterLast("= ")) }
                    .padding(vertical = 6.dp), style = StudyType.Mono.copy(fontSize = 13.sp), color = c.inkSoft, maxLines = 1)
            }
        }
        Text(l.label("Digital SAT Math allows a calculator on every question. Results here are not checked or saved as answers.",
            "В Digital SAT Math калькулятор разрешён на всех вопросах. Результаты здесь не проверяются и не сохраняются как ответы."), style = StudyType.Small, color = c.inkSoft)
    }
}

private fun keyDescription(key: String, l: Language) = when (key) {
    "⌫" -> l.label("Delete", "Удалить")
    "AC" -> l.label("Clear", "Очистить")
    "√" -> l.label("Square root", "Квадратный корень")
    "x²" -> l.label("Square", "Квадрат")
    "^" -> l.label("Power", "Степень")
    "π" -> "pi"
    "E" -> l.label("Times ten to the power", "Умножить на десять в степени")
    "ans" -> l.label("Last answer", "Последний результат")
    "2nd" -> l.label("Inverse functions", "Обратные функции")
    "DEG", "RAD" -> l.label("Angle unit $key", "Единица угла $key")
    else -> key
}

/** The formulas and facts printed on the Digital SAT Math reference sheet. */
@Composable
fun ReferenceSheet(l: Language, dismiss: () -> Unit) {
    val c = Study.colors
    StudySheet(dismiss) {
        Text(l.label("Reference sheet", "Справочные формулы"), style = StudyType.Title, color = c.ink)
        listOf(
            l.label("Circle", "Окружность") to "A = πr²   C = 2πr",
            l.label("Rectangle", "Прямоугольник") to "A = ℓw",
            l.label("Triangle", "Треугольник") to "A = ½bh",
            l.label("Right triangle", "Прямоугольный треугольник") to "c² = a² + b²",
            l.label("Special right triangles", "Особые прямоугольные треугольники") to "30°–60°–90°: x, x√3, 2x\n45°–45°–90°: s, s, s√2",
            l.label("Rectangular prism", "Прямоугольный параллелепипед") to "V = ℓwh",
            l.label("Cylinder", "Цилиндр") to "V = πr²h",
            l.label("Sphere", "Шар") to "V = ⁴⁄₃πr³",
            l.label("Cone", "Конус") to "V = ⅓πr²h",
            l.label("Pyramid", "Пирамида") to "V = ⅓ℓwh",
        ).forEach { (name, formula) ->
            Row(verticalAlignment = Alignment.Top) {
                Text(name, Modifier.weight(1f), style = StudyType.Small, color = c.inkSoft)
                Text(formula, Modifier.weight(1.2f), style = StudyType.Mono.copy(fontSize = 15.sp, lineHeight = 21.sp), color = c.ink)
            }
            Hairline()
        }
        Text(l.label("The number of degrees of arc in a circle is 360. The number of radians of arc in a circle is 2π. The sum of the measures in degrees of the angles of a triangle is 180.",
            "Дуга полной окружности — 360 градусов, или 2π радиан. Сумма углов треугольника — 180 градусов."), style = StudyType.Small, color = c.ink)
    }
}

/** A scratch note for one question. The editor owns its text; saving follows it. */
@Composable
fun NoteSheet(noteKey: String, initial: String, l: Language, save: (String) -> Unit, dismiss: () -> Unit) {
    val c = Study.colors
    StudySheet(dismiss) {
        Text(l.label("Scratch note", "Заметка"), style = StudyType.Title, color = c.ink)
        key(noteKey) {
            val state = rememberTextFieldState(initial)
            LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect(save) }
            OutlinedTextField(state = state, modifier = Modifier.fillMaxWidth(), label = { Text(l.label("Working, not an answer", "Черновик, не ответ")) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5, maxHeightInLines = 12), shape = RoundedCornerShape(16.dp), colors = studyFieldColors())
        }
        Text(l.label("Saved with this question on this device. It is never marked.", "Сохраняется с этим вопросом на устройстве и не проверяется."), style = StudyType.Small, color = c.inkSoft)
        StudyButton(l.label("Done", "Готово"), dismiss, Modifier.fillMaxWidth(), tone = Tone.Quiet, compact = true)
    }
}
