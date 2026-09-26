package com.tomilov.stylishsat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.ai.*
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.speech.Recording
import com.tomilov.stylishsat.ui.components.*
import com.tomilov.stylishsat.ui.theme.Study
import com.tomilov.stylishsat.ui.theme.StudyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun SettingsScreen(s: StudyUiState, vm: StudyViewModel, back: () -> Unit) {
    val l = s.language
    val c = Study.colors
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.ArrowLeft, l.label("Back", "Назад"), back)
            Spacer(Modifier.width(4.dp))
            Text(l.label("Settings", "Настройки"), style = StudyType.Title, color = c.ink)
        }
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(34.dp)) {
            ProfileSection(s, vm)
            Section(l.label("Language", "Язык")) {
                Segmented(Language.entries.map { it to if (it == Language.RU) "Русский" else "English" }, l, vm::selectLanguage, Modifier.fillMaxWidth(), fill = true)
                Hint(l.label("Menus and explanations follow this choice. Exam tasks stay in English.", "Интерфейс и объяснения меняют язык. Экзаменационные задания остаются на английском."))
            }
            ModelsSection(s, vm)
            ContentSection(s, vm)
            RecordingsSection(s, vm)
            AboutSection(s)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun Hint(text: String) { Text(text, style = StudyType.Small, color = Study.colors.inkSoft) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSection(s: StudyUiState, vm: StudyViewModel) {
    val l = s.language
    val c = Study.colors
    val profile = s.profile
    var targetValue by rememberSaveable(s.exam, profile.target) { mutableStateOf(profile.target) }
    var knownValue by rememberSaveable(s.exam, profile.knownResult) { mutableStateOf(profile.knownResult) }
    var dateValue by rememberSaveable(s.exam, profile.examDateEpochDay) { mutableStateOf(profile.examDateEpochDay) }
    var minutesValue by rememberSaveable(s.exam, profile.dailyMinutes) { mutableIntStateOf(profile.dailyMinutes) }
    var pickerValue by rememberSaveable { mutableStateOf(false) }
    var savedValue by remember(s.exam) { mutableStateOf(false) }
    val dirty = targetValue.trim() != profile.target || knownValue.trim() != profile.knownResult ||
        dateValue != profile.examDateEpochDay || minutesValue != profile.dailyMinutes
    Section(l.label("${if (s.exam == Exam.IELTS) "IELTS" else "SAT"} profile", "Профиль ${if (s.exam == Exam.IELTS) "IELTS" else "SAT"}")) {
        Text(l.label("Minutes a day", "Минут в день"), style = StudyType.Strong, color = c.ink)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(15, 30, 45, 60, 75, 90, 105, 120) + profile.dailyMinutes).distinct().sorted().forEach { value ->
                val on = value == minutesValue
                Text("$value", Modifier.tapSurface(RoundedCornerShape(50), if (on) c.ink else c.sunken) { minutesValue = value; savedValue = false }
                    .padding(horizontal = 16.dp, vertical = 10.dp), style = StudyType.Mono.copy(fontSize = 15.sp), color = if (on) c.paper else c.ink)
            }
        }
        Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(16.dp), c.raised) { pickerValue = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Calendar, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.label("Exam date", "Дата экзамена"), style = StudyType.Strong, color = c.ink)
                Text(dateValue?.let { shortDate(it, l) + " " + LocalDate.ofEpochDay(it).year } ?: l.label("Not set", "Не указана"), style = StudyType.Small, color = c.inkSoft)
            }
            if (dateValue != null) Text(l.label("Clear", "Сбросить"), Modifier.tapSurface(RoundedCornerShape(50), c.raised) { dateValue = null; savedValue = false }
                .padding(horizontal = 10.dp, vertical = 6.dp), style = StudyType.Button.copy(fontSize = 14.sp), color = c.inkSoft)
        }
        OutlinedTextField(targetValue, { targetValue = it; savedValue = false }, Modifier.fillMaxWidth(), label = { Text(l.label("Goal", "Цель")) },
            singleLine = true, shape = RoundedCornerShape(16.dp), colors = studyFieldColors())
        OutlinedTextField(knownValue, { knownValue = it; savedValue = false }, Modifier.fillMaxWidth(), label = { Text(l.label("Known result (optional)", "Известный результат (необязательно)")) },
            singleLine = true, shape = RoundedCornerShape(16.dp), colors = studyFieldColors())
        if (dirty) StudyButton(l.label("Save profile", "Сохранить профиль"), {
            vm.saveProfile(profile.copy(target = targetValue.trim(), knownResult = knownValue.trim(), examDateEpochDay = dateValue, dailyMinutes = minutesValue))
            savedValue = true
        }, Modifier.fillMaxWidth()) else if (savedValue) Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Check, tint = c.good, size = 16.dp); Spacer(Modifier.width(6.dp)); Meta(l.label("Saved on this device", "Сохранено на устройстве"), color = c.good)
        }
    }
    if (pickerValue) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (dateValue ?: LocalDate.now().toEpochDay()).let {
            LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        })
        val colors = DatePickerDefaults.colors(containerColor = c.paper, selectedDayContainerColor = c.ink, selectedDayContentColor = c.paper,
            todayDateBorderColor = c.ink, todayContentColor = c.ink, headlineContentColor = c.ink, titleContentColor = c.inkSoft)
        DatePickerDialog(onDismissRequest = { pickerValue = false }, colors = colors, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { dateValue = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(); savedValue = false }
                pickerValue = false
            }) { Text("OK", style = StudyType.Button, color = c.ink) }
        }, dismissButton = { TextButton(onClick = { pickerValue = false }) { Text(l.label("Cancel", "Отмена"), style = StudyType.Button, color = c.inkSoft) } }) {
            DatePicker(state, colors = colors)
        }
    }
}

@Composable
private fun ModelsSection(s: StudyUiState, vm: StudyViewModel) {
    val l = s.language
    val c = Study.colors
    val downloadValue by vm.runtime.downloads.state.collectAsStateWithLifecycle()
    val accelerationValue by vm.runtime.acceleration.state.collectAsStateWithLifecycle()
    val capability = remember { vm.runtime.capability }
    val eligible = remember { vm.runtime.acceleration.eligible }
    // Receipt and file checks run off the main thread, once per download phase rather than per progress byte.
    val phase = when (val state = downloadValue) {
        is DownloadState.Downloading -> "downloading:${state.modelId}"
        else -> state.toString()
    }
    val installed by produceState<Map<String, Boolean>?>(null, phase) {
        value = withContext(Dispatchers.IO) {
            vm.runtime.acceleration.refresh()
            ModelCatalog.all.associate { it.id to vm.runtime.downloads.isInstalled(it) }
        }
    }
    Section(l.label("On-device AI", "ИИ на устройстве")) {
        Hint(if (capability.supported) l.label("This 8 GB+ ARM64 device can run optional local feedback and transcription. Practice never needs them.", "Это устройство (ARM64, 8+ ГБ) может запускать локальный разбор и распознавание. Для практики они не нужны.")
            else l.label("Local AI needs 8 GB+ RAM and ARM64. Lessons, practice, recording and manual transcripts work on this device.", "Для локального ИИ нужны 8+ ГБ RAM и ARM64. Уроки, практика, запись и ручной транскрипт работают на этом устройстве."))
        ModelCatalog.all.forEach { spec ->
            val downloading = downloadValue as? DownloadState.Downloading
            val verifying = downloadValue as? DownloadState.Verifying
            Block(spacing = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(spec.name, style = StudyType.Strong, color = c.ink)
                        Meta("${sizeLabel(spec.sizeBytes)} · SHA-256")
                    }
                    when {
                        downloading?.modelId == spec.id -> StudyButton(l.label("Pause", "Пауза"), vm.runtime.downloads::cancel, tone = Tone.Quiet, compact = true, glyph = Glyph.Pause)
                        verifying?.modelId == spec.id -> CircularProgressIndicator(Modifier.size(22.dp), color = c.ink, strokeWidth = 2.dp)
                        installed?.get(spec.id) == true -> GlyphIcon(Glyph.Check, tint = c.good, size = 22.dp)
                        installed == null -> Unit
                        else -> StudyButton(l.label("Get", "Скачать"), { vm.runtime.downloads.start(spec) }, tone = Tone.Ink, compact = true, glyph = Glyph.Download,
                            enabled = capability.supported && downloading == null && verifying == null)
                    }
                }
                if (downloading?.modelId == spec.id) {
                    Bar(downloading.downloadedBytes.toFloat() / downloading.totalBytes.coerceAtLeast(1))
                    Text("${downloading.downloadedBytes / 1_000_000} / ${downloading.totalBytes / 1_000_000} MB", style = StudyType.Mono.copy(fontSize = 12.sp), color = c.inkSoft)
                }
                if (verifying?.modelId == spec.id) Hint(l.label("Verifying every byte…", "Проверка контрольной суммы…"))
                if (installed?.get(spec.id) == true && downloading?.modelId != spec.id) Hint(l.label("Installed and verified", "Установлено и проверено"))
            }
        }
        when (val status = downloadValue) {
            is DownloadState.Failed -> Text(l.label(status.message, "Загрузка прервана. Можно продолжить после проверки сети и свободного места. ${status.message}"), style = StudyType.Small, color = c.bad)
            is DownloadState.Paused -> Hint(l.label("Paused. The download resumes from the saved part.", "Пауза. Загрузка продолжится с сохранённого места."))
            else -> Unit
        }
        Hint(l.label("Keep the app open while downloading. Interrupted downloads resume after a restart.", "Не закрывайте приложение во время загрузки. После прерывания загрузку можно продолжить."))
        if (eligible) Block(spacing = 10.dp) {
            Text(l.label("Fast local feedback", "Быстрый локальный разбор"), style = StudyType.Strong, color = c.ink)
            when (val preparation = accelerationValue) {
                is LocalAccelerationState.Ready -> Hint(l.label("Prepared on this device. The preparation is reused after restarts.", "Подготовлено на этом устройстве. Подготовка сохраняется после перезапуска."))
                is LocalAccelerationState.Preparing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.ink, trackColor = c.sunken)
                    Hint(l.label("Preparing locally. Keep the app open; lessons stay available.", "Идёт локальная подготовка. Не закрывайте приложение; уроки доступны."))
                }
                else -> {
                    Hint(l.label("One-time setup after downloading Gemma. It took about 40 seconds on the tested phone; allow up to a minute.", "Однократная подготовка после загрузки Gemma. На проверенном телефоне заняла около 40 секунд; выделите до минуты."))
                    if (preparation is LocalAccelerationState.Failed) Text(runtimeMessage(preparation.message, l), style = StudyType.Small, color = c.bad)
                    StudyButton(l.label("Prepare fast feedback", "Подготовить быстрый разбор"), vm::prepareLocalAcceleration, tone = Tone.Ink, compact = true,
                        enabled = installed?.get(ModelCatalog.gemma.id) == true && downloadValue !is DownloadState.Downloading && downloadValue !is DownloadState.Verifying)
                }
            }
        }
    }
}

private fun sizeLabel(bytes: Long) = if (bytes >= 1_000_000_000) "%.2f GB".format(java.util.Locale.ROOT, bytes / 1e9) else "${(bytes + 500_000) / 1_000_000} MB"

@Composable
private fun ContentSection(s: StudyUiState, vm: StudyViewModel) {
    val l = s.language
    val c = Study.colors
    val pack = s.pack ?: return
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importContent) }
    Section(l.label("Content", "Материалы")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${pack.title.text(l)} · v${pack.version}", style = StudyType.Strong, color = c.ink)
                Hint(l.label("${pack.exercises.size} tasks · ${pack.lessons.size} lessons", "${pack.exercises.size} заданий · ${pack.lessons.size} уроков"))
            }
            StudyButton(l.label("Import", "Импорт"), { importLauncher.launch(arrayOf("application/json", "text/plain")) }, tone = Tone.Quiet, compact = true)
        }
        Hint(l.label("Imports are validated. Your answers and the exact task versions you answered are kept.", "Импорт проверяется. Ваши ответы и точные версии заданий сохраняются."))
    }
}

@Composable
private fun RecordingsSection(s: StudyUiState, vm: StudyViewModel) {
    val l = s.language
    val c = Study.colors
    var recordingsValue by remember { mutableStateOf<List<Recording>>(emptyList()) }
    LaunchedEffect(s.attempts.size, s.session?.recordingPath) {
        recordingsValue = withContext(Dispatchers.IO) { vm.runtime.recorder.recordings() }
    }
    DisposableEffect(Unit) { onDispose { vm.runtime.playback.stop() } }
    if (recordingsValue.isEmpty()) return
    Section(l.label("Recordings", "Записи")) {
        Column {
            recordingsValue.take(20).forEach { recording ->
                Hairline()
                Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(12.dp), c.paper) { vm.runtime.playback.play(recording.file) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Play, size = 16.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(shortDate(Instant.ofEpochMilli(recording.createdAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(), l),
                        Modifier.weight(1f), style = StudyType.Body, color = c.ink)
                    Text("${recording.durationMillis / 1000}s", style = StudyType.Mono, color = c.inkSoft)
                }
            }
            Hairline()
        }
        StudyButton(l.label("Stop playback", "Остановить"), vm.runtime.playback::stop, tone = Tone.Quiet, compact = true, glyph = Glyph.Stop)
    }
}

@Composable
private fun AboutSection(s: StudyUiState) {
    val l = s.language
    val c = Study.colors
    val context = LocalContext.current
    var licensesValue by remember { mutableStateOf<String?>(null) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    Section(l.label("About", "О приложении")) {
        Text(l.label("Private by default. Answers, drafts, recordings and feedback stay on this device and are excluded from system backup. Text leaves only when you copy or share it.",
            "Данные остаются на устройстве. Ответы, черновики, записи и отзывы исключены из системной резервной копии. Текст передаётся только когда вы копируете его или делитесь им."),
            style = StudyType.Small, color = c.ink)
        Text(l.label("Lessons and tasks are original AI-authored drafts, machine-validated. Expert editorial review and student testing are pending. Accuracy here is a training estimate, not an official SAT score or IELTS band. AI and external feedback never change keys, grades or mastery.",
            "Уроки и задания — оригинальные ИИ-черновики с машинной проверкой. Экспертная редактура и испытания с учениками ожидаются. Точность здесь — учебная оценка, не официальный SAT score или IELTS band. ИИ и внешние отзывы не меняют ключи, оценки и уровень навыков."),
            style = StudyType.Small, color = c.inkSoft)
        Column {
            val rows = listOf<Pair<String, () -> Unit>>(
                l.label("Licenses & content provenance", "Лицензии и происхождение материалов") to {
                    licensesValue = listOf("licenses/whisper-LICENSE.txt", "licenses/content-notices.txt").joinToString("\n\n") { path -> runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault(path) }
                },
                "SAT · College Board ↗" to { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://satsuite.collegeboard.org/sat/whats-on-the-test/structure"))) }; Unit },
                "IELTS Academic ↗" to { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ielts.org/take-a-test/test-types/ielts-academic-test"))) }; Unit },
            )
            rows.forEach { (title, action) ->
                Hairline()
                Row(Modifier.fillMaxWidth().tapSurface(RoundedCornerShape(12.dp), c.paper, onClick = action).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = StudyType.Body, color = c.ink)
                    GlyphIcon(Glyph.ChevronRight, tint = c.inkSoft, size = 18.dp)
                }
            }
            Hairline()
        }
        Meta("stylishSAT ${version.orEmpty()}", color = c.inkFaint)
    }
    licensesValue?.let { text ->
        AlertDialog(onDismissRequest = { licensesValue = null }, containerColor = c.paper,
            title = { Text(l.label("Licenses", "Лицензии"), style = StudyType.Title, color = c.ink) },
            text = { Text(text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = StudyType.Mono.copy(fontSize = 12.sp, lineHeight = 17.sp), color = c.ink) },
            confirmButton = { TextButton(onClick = { licensesValue = null }) { Text("OK", style = StudyType.Button, color = c.ink) } })
    }
}
