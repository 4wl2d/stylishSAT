package com.tomilov.stylishsat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.Exam
import com.tomilov.stylishsat.domain.Language
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType

/** Language and exam apply as they are tapped, so every later step is already in the learner's language. */
@Composable
fun OnboardingScreen(s: StudyUiState, vm: StudyViewModel, startDiagnostic: () -> Unit) {
    val l = s.language
    val c = Study.colors
    var step by rememberSaveable { mutableIntStateOf(0) }
    var minutesValue by rememberSaveable { mutableIntStateOf(s.profile.dailyMinutes) }
    BackHandler(step > 0) { step-- }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) GlyphButton(Glyph.ArrowLeft, l.label("Back", "Назад"), { step-- }) else Spacer(Modifier.size(44.dp))
            StepTrack(List(4) { if (it < step) Mark.Done else if (it == step) Mark.Now else Mark.Todo }, Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(l.label("Skip", "Пропустить"), Modifier.tapSurface(RoundedCornerShape(50), c.paper) { vm.finishOnboarding() }.padding(horizontal = 12.dp, vertical = 10.dp),
                style = StudyType.Button, color = c.inkSoft)
        }
        AnimatedContent(step, Modifier.weight(1f), transitionSpec = {
            (fadeIn(tween(160)) + slideInHorizontally(tween(220)) { if (targetState > initialState) it / 8 else -it / 8 }) togetherWith fadeOut(tween(90))
        }, label = "onboarding") { current ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 28.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current) {
                    0 -> {
                        Wordmark(Modifier.padding(bottom = 28.dp))
                        Question("Language · Язык")
                        Choice("English", null, l == Language.EN) { vm.selectLanguage(Language.EN); step = 1 }
                        Choice("Русский", null, l == Language.RU) { vm.selectLanguage(Language.RU); step = 1 }
                    }
                    1 -> {
                        Question(l.label("Which exam?", "Какой экзамен?"))
                        Choice("SAT", "Reading & Writing · Math", s.exam == Exam.SAT) { vm.selectExam(Exam.SAT); step = 2 }
                        Choice("IELTS Academic", "Reading · Listening · Writing · Speaking", s.exam == Exam.IELTS) { vm.selectExam(Exam.IELTS); step = 2 }
                    }
                    2 -> {
                        Question(l.label("Minutes a day?", "Сколько минут в день?"))
                        listOf(15, 30, 45, 60).forEach { value ->
                            Choice(minutes(value, l), null, minutesValue == value) { minutesValue = value; step = 3 }
                        }
                    }
                    else -> {
                        MarkedText(l.label("Start with a diagnostic.", "Начните с диагностики."), StudyType.Headline)
                        Text(if (s.exam == Exam.SAT) l.label("16 questions · 8 domains", "16 заданий · 8 доменов") else "Reading · Listening · Writing · Speaking",
                            style = StudyType.Body, color = c.inkSoft)
                        Spacer(Modifier.height(20.dp))
                        StudyButton(l.label("Start diagnostic", "Пройти диагностику"), { vm.finishOnboarding(minutesValue); startDiagnostic() },
                            Modifier.fillMaxWidth(), tone = Tone.Ink, arrow = true)
                        StudyButton(l.label("Look around first", "Сначала осмотреться"), { vm.finishOnboarding(minutesValue) },
                            Modifier.fillMaxWidth(), tone = Tone.Quiet)
                    }
                }
            }
        }
    }
}

@Composable
private fun Question(text: String) {
    Text(text, Modifier.padding(bottom = 12.dp), style = StudyType.Headline, color = Study.colors.ink)
}

@Composable
private fun Choice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val c = Study.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)
        .tapSurface(RoundedCornerShape(20.dp), c.raised, border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.line), onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = StudyType.Title, color = c.ink)
            detail?.let { Text(it, style = StudyType.Small, color = c.inkSoft) }
        }
        GlyphIcon(Glyph.ArrowRight, tint = if (selected) c.ink else c.inkFaint, size = 20.dp)
    }
}
