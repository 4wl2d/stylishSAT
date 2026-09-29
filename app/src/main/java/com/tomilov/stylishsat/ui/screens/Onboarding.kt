package com.tomilov.stylishsat.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.Exam
import com.tomilov.stylishsat.domain.Language
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.LocalReducedMotion
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyMotion
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Language and exam apply as they are tapped, so every later step is already in the learner's language. A tapped
 * choice confirms itself for a moment before the next step slides in.
 */
@Composable
fun OnboardingScreen(s: StudyUiState, vm: StudyViewModel, startDiagnostic: () -> Unit) {
    val l = s.language
    val c = Study.colors
    var step by rememberSaveable { mutableIntStateOf(0) }
    var minutesValue by rememberSaveable { mutableIntStateOf(s.profile.dailyMinutes) }
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val advance = { from: Int ->
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch {
            if (!reduced) delay(170)
            if (step == from) step = from + 1
        }
        Unit
    }
    val back = rememberBackGesture()
    PredictiveBack(back, step, step > 0) { step-- }
    Column(Modifier.fillMaxSize().drawBehind { drawRect(lerp(c.paper, c.sunken, back.depth)) }.safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp)) {
                androidx.compose.animation.AnimatedVisibility(step > 0, enter = fadeIn(StudyMotion.fade()) + scaleIn(StudyMotion.bounce(), 0.6f),
                    exit = fadeOut(StudyMotion.fade(100)) + scaleOut(StudyMotion.settle(), 0.6f)) {
                    GlyphButton(Glyph.ArrowLeft, l.label("Back", "Назад"), { step-- })
                }
            }
            StepTrack(List(4) { if (it < step) Mark.Done else if (it == step) Mark.Now else Mark.Todo }, Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(l.label("Skip", "Пропустить"), Modifier.tapSurface(RoundedCornerShape(50), c.paper) { vm.finishOnboarding() }.padding(horizontal = 12.dp, vertical = 10.dp),
                style = StudyType.Button, color = c.inkSoft)
        }
        AnimatedContent(step, Modifier.weight(1f), transitionSpec = {
            if (reduced) fadeIn(StudyMotion.fade(160)) togetherWith fadeOut(StudyMotion.fade(90))
            else {
                val forward = targetState > initialState
                ((slideInHorizontally(StudyMotion.page) { if (forward) it / 4 else -it / 4 } + fadeIn(StudyMotion.fade(180, 40))) togetherWith
                    (slideOutHorizontally(StudyMotion.page) { if (forward) -it / 6 else it / 6 } + fadeOut(StudyMotion.fade(100))))
                    .apply { if (!forward) targetContentZIndex = -1f }
            }
        }, label = "onboarding") { current ->
            Column(Modifier.fillMaxSize().followBack(back, current).background(c.paper).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(top = 28.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current) {
                    0 -> {
                        Wordmark(Modifier.padding(bottom = 28.dp).rise(0))
                        Question("Language · Язык", 1)
                        Choice("English", null, l == Language.EN, 2) { vm.selectLanguage(Language.EN); advance(0) }
                        Choice("Русский", null, l == Language.RU, 3) { vm.selectLanguage(Language.RU); advance(0) }
                    }
                    1 -> {
                        Question(l.label("Which exam?", "Какой экзамен?"), 0)
                        Choice("SAT", "Reading & Writing · Math", s.exam == Exam.SAT, 1) { vm.selectExam(Exam.SAT); advance(1) }
                        Choice("IELTS Academic", "Reading · Listening · Writing · Speaking", s.exam == Exam.IELTS, 2) { vm.selectExam(Exam.IELTS); advance(1) }
                    }
                    2 -> {
                        Question(l.label("Minutes a day?", "Сколько минут в день?"), 0)
                        listOf(15, 30, 45, 60).forEachIndexed { index, value ->
                            Choice(minutes(value, l), null, minutesValue == value, index + 1) { minutesValue = value; advance(2) }
                        }
                    }
                    else -> {
                        MarkedText(l.label("Start with a diagnostic.", "Начните с диагностики."), StudyType.Headline, Modifier.rise(0), delayMillis = 220)
                        Text(if (s.exam == Exam.SAT) l.label("16 questions · 8 domains", "16 заданий · 8 доменов") else "Reading · Listening · Writing · Speaking",
                            Modifier.rise(1), style = StudyType.Body, color = c.inkSoft)
                        Spacer(Modifier.height(20.dp))
                        StudyButton(l.label("Start diagnostic", "Пройти диагностику"), { vm.finishOnboarding(minutesValue); startDiagnostic() },
                            Modifier.fillMaxWidth().rise(2), tone = Tone.Ink, arrow = true)
                        StudyButton(l.label("Look around first", "Сначала осмотреться"), { vm.finishOnboarding(minutesValue) },
                            Modifier.fillMaxWidth().rise(3), tone = Tone.Quiet)
                    }
                }
            }
        }
    }
}

@Composable
private fun Question(text: String, order: Int) {
    Text(text, Modifier.padding(bottom = 12.dp).rise(order), style = StudyType.Headline, color = Study.colors.ink)
}

@Composable
private fun Choice(title: String, detail: String?, selected: Boolean, order: Int, onClick: () -> Unit) {
    val c = Study.colors
    val border by animateDpAsState(if (selected) 2.dp else 1.dp, StudyMotion.settle(), label = "choiceBorder")
    val edge by animateColorAsState(if (selected) c.ink else c.line, StudyMotion.fade(), label = "choiceEdge")
    Row(Modifier.fillMaxWidth().rise(order).heightIn(min = 68.dp)
        .tapSurface(RoundedCornerShape(20.dp), c.raised, border = BorderStroke(border, edge), role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected }
        .padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = StudyType.Title, color = c.ink)
            detail?.let { Text(it, style = StudyType.Small, color = c.inkSoft) }
        }
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.popIn(from = 0.3f).fillMaxSize().clip(CircleShape).background(c.marker), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.Check, tint = c.onMarker, size = 16.dp)
            } else GlyphIcon(Glyph.ArrowRight, tint = c.inkFaint, size = 20.dp)
        }
    }
}
