package com.tomilov.stylishsat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*

fun Language.label(en: String, ru: String) = if (this == Language.RU) ru else en

@Composable
fun StudyApp(vm: StudyViewModel) {
    val stateValue by vm.state.collectAsStateWithLifecycle()
    var tabValue by rememberSaveable { mutableStateOf("today") }
    var practiceValue by rememberSaveable { mutableStateOf(false) }
    val language = stateValue.language
    BackHandler(practiceValue) { practiceValue = false }
    val begin: (ContentSplit, String?) -> Unit = { mode, skill ->
        vm.startSession(mode, skill)
        if (vm.state.value.session?.let { !it.finished } == true) practiceValue = true
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!practiceValue) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf(
                    Triple("today", "◉", language.label("Today", "Сегодня")),
                    Triple("library", "▤", language.label("Library", "Библиотека")),
                    Triple("progress", "↗", language.label("Progress", "Прогресс")),
                    Triple("settings", "⚙", language.label("Settings", "Настройки")),
                ).forEach { (id, icon, title) ->
                    NavigationBarItem(selected = tabValue == id, onClick = { tabValue = id }, icon = { Text(icon, fontSize = 22.sp) }, label = { Text(title, fontSize = 11.sp) })
                }
            }
        },
    ) { padding ->
        if (stateValue.loading) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (stateValue.pack == null) Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) { Text(stateValue.error ?: language.label("Content unavailable", "Материалы недоступны")) }
        else if (practiceValue && stateValue.session != null) SessionScreen(stateValue, vm, Modifier.padding(padding)) { practiceValue = false; tabValue = "today" }
        else Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("stylish", fontFamily = FontFamily.Serif, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text(" / study", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.selectLanguage(if (language == Language.RU) Language.EN else Language.RU) }) { Text(if (language == Language.RU) "RU / en" else "EN / ru") }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (tabValue) {
                    "today" -> TodayScreen(stateValue, vm, begin, { day -> vm.startPlannedDay(day); if (vm.state.value.session?.let { !it.finished } == true) practiceValue = true }) { practiceValue = true }
                    "library" -> LibraryScreen(stateValue, begin)
                    "progress" -> ProgressScreen(stateValue)
                    "settings" -> SettingsScreen(stateValue, vm)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    stateValue.error?.takeIf { stateValue.pack != null }?.let { message ->
        AlertDialog(onDismissRequest = vm::clearError, title = { Text(language.label("Study update", "Учебная сессия")) }, text = { Text(message) }, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } })
    }
}

@Composable
fun PageTitle(eyebrow: String, title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.8.sp)
        Text(title, fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 38.sp)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun StudyCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = color, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun Eyebrow(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
fun SmallNote(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp) }
