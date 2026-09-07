package com.tomilov.stylishsat.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomilov.stylishsat.StudyUiState
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.ai.*
import com.tomilov.stylishsat.domain.*
import java.time.LocalDate
import java.io.File
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun SettingsScreen(s: StudyUiState, vm: StudyViewModel) {
    val l = s.language
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importContent) }
    val downloadValue by vm.runtime.downloads.state.collectAsStateWithLifecycle()
    val accelerationValue by vm.runtime.acceleration.state.collectAsStateWithLifecycle()
    LaunchedEffect(downloadValue) { vm.runtime.acceleration.refresh() }
    var targetValue by rememberSaveable(s.exam, s.profile.target) { mutableStateOf(s.profile.target) }
    var knownValue by rememberSaveable(s.exam, s.profile.knownResult) { mutableStateOf(s.profile.knownResult) }
    var dateValue by rememberSaveable(s.exam, s.profile.examDateEpochDay) { mutableStateOf(s.profile.examDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "") }
    var minutesValue by rememberSaveable(s.exam, s.profile.dailyMinutes) { mutableFloatStateOf(s.profile.dailyMinutes.toFloat()) }
    var profileNoteValue by rememberSaveable { mutableStateOf("") }
    var licensesValue by remember { mutableStateOf<String?>(null) }
    PageTitle(l.label("MAKE IT YOURS", "ПОД ВАШ РИТМ"), l.label("Ready when\nyou are.", "Всё для\nвашей цели."))
    StudyCard {
        Text(l.label("${s.exam} profile", "Профиль ${s.exam}"), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(targetValue, { targetValue = it }, label = { Text(l.label("Your goal", "Ваша цель")) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(knownValue, { knownValue = it }, label = { Text(l.label("Known result (optional)", "Известный результат (необязательно)")) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dateValue, { dateValue = it }, label = { Text(l.label("Exam date · YYYY-MM-DD", "Дата экзамена · ГГГГ-ММ-ДД")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(l.label("Daily study: ${minutesValue.toInt()} minutes", "Ежедневно: ${minutesValue.toInt()} минут"))
        Slider(minutesValue, { minutesValue = it }, valueRange = 15f..120f, steps = 6)
        Button(onClick = {
            val date = if (dateValue.isBlank()) null else runCatching { LocalDate.parse(dateValue) }.getOrNull()
            if (dateValue.isNotBlank() && date == null) profileNoteValue = l.label("Use a valid date, for example 2026-10-15.", "Введите дату, например 2026-10-15.")
            else {
                vm.saveProfile(s.profile.copy(target = targetValue.trim(), knownResult = knownValue.trim(), examDateEpochDay = date?.toEpochDay(), dailyMinutes = minutesValue.toInt()))
                profileNoteValue = l.label("Saved on this device.", "Сохранено на этом устройстве.")
            }
        }) { Text(l.label("Save profile", "Сохранить профиль")) }
        if (profileNoteValue.isNotBlank()) SmallNote(profileNoteValue)
    }
    StudyCard {
        Text(l.label("Language", "Язык"), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Language.entries.forEach { language -> FilterChip(l == language, { vm.selectLanguage(language) }, label = { Text(if (language == Language.RU) "Русский" else "English") }) }
        }
        SmallNote(l.label("The interface and explanations follow this choice. Exam tasks stay in English.", "Интерфейс и объяснения меняют язык. Экзаменационные задания остаются на английском."))
    }
    StudyCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow(l.label("OFFLINE READINESS", "ГОТОВНОСТЬ БЕЗ ИНТЕРНЕТА"))
        Text(l.label("✓ Lessons & prepared answers", "✓ Уроки и готовые разборы"))
        Text(l.label("✓ Bundled listening audio", "✓ Учебные аудиозаписи"))
        val installed = ModelCatalog.all.all { vm.runtime.downloads.isInstalled(it) }
        Text(if (installed) l.label("✓ Local AI & transcription installed", "✓ ИИ и распознавание установлены") else l.label("○ Optional AI downloads remain", "○ Дополнительные модели ещё не установлены"))
        if (vm.runtime.acceleration.eligible) Text(if (accelerationValue is LocalAccelerationState.Ready)
            l.label("✓ Fast local feedback prepared", "✓ Быстрый локальный разбор подготовлен") else l.label("○ Fast feedback setup remains", "○ Быстрый разбор ещё нужно подготовить"))
        SmallNote(l.label("Open every skill and check model status before going offline. Prepared practice works without the models.", "Перед интенсивом проверьте каждый навык и состояние моделей. Готовая практика работает без моделей."))
    }
    StudyCard {
        Text(l.label("Downloads", "Загрузки"), style = MaterialTheme.typography.titleLarge)
        val capability = vm.runtime.capability
        SmallNote(if (capability.supported) l.label("8 GB+ ARM64 device detected. Performance depends on available memory.", "Устройство ARM64 с 8+ ГБ RAM. Скорость зависит от доступной памяти.") else l.label("This device supports lessons, practice, recording and manual transcripts. Local AI needs 8 GB+ RAM and ARM64.", "Доступны уроки, практика, запись и ручной транскрипт. Для локального ИИ нужны 8+ ГБ RAM и ARM64."))
        ModelCatalog.all.forEach { spec ->
            Text(spec.name, style = MaterialTheme.typography.titleMedium)
            SmallNote("${if (spec == ModelCatalog.gemma) "2.59 GB" else "148 MB"} · SHA-256 · ${l.label("private device storage", "локальное хранилище")}")
            val downloading = downloadValue as? DownloadState.Downloading
            val verifying = downloadValue as? DownloadState.Verifying
            if (downloading?.modelId == spec.id) {
                LinearProgressIndicator(progress = { downloading.downloadedBytes.toFloat() / downloading.totalBytes }, modifier = Modifier.fillMaxWidth())
                Text("${downloading.downloadedBytes / 1_000_000} / ${downloading.totalBytes / 1_000_000} MB")
                OutlinedButton(onClick = vm.runtime.downloads::cancel) { Text(l.label("Pause", "Приостановить")) }
            } else if (verifying?.modelId == spec.id) { CircularProgressIndicator(); SmallNote(l.label("Verifying every byte…", "Проверка контрольной суммы…")) }
            else if (vm.runtime.downloads.isInstalled(spec)) Text(l.label("✓ Installed and verified", "✓ Установлено и проверено"), color = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = { vm.runtime.downloads.start(spec) }, enabled = capability.supported && downloading == null && verifying == null) { Text(l.label("Download / resume", "Скачать / продолжить")) }
        }
        when (val status = downloadValue) {
            is DownloadState.Failed -> SmallNote(l.label(status.message, "Загрузка прервана. Можно продолжить после проверки сети и свободного места. ${status.message}"))
            is DownloadState.Paused -> SmallNote(l.label("Paused. Download resumes from the saved part.", "Пауза. Загрузка продолжится с сохранённого места."))
            else -> Unit
        }
        SmallNote(l.label("Keep the app open during a download. Interrupted downloads can be resumed after restart.", "Оставьте приложение открытым во время загрузки. После прерывания или перезапуска можно продолжить."))
    }
    if (vm.runtime.acceleration.eligible) StudyCard {
        Text(l.label("Fast local feedback", "Быстрый локальный разбор"), style = MaterialTheme.typography.titleLarge)
        when (val preparation = accelerationValue) {
            is LocalAccelerationState.Ready -> {
                Text(l.label("✓ Prepared on this device", "✓ Подготовлен на этом устройстве"), color = MaterialTheme.colorScheme.primary)
                SmallNote(l.label("Your saved preparation is reused after restarting the app. This acceleration profile has been checked on this device configuration.", "Подготовка сохраняется после перезапуска приложения. Ускорение проверено на этой конфигурации устройства."))
            }
            is LocalAccelerationState.Preparing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                SmallNote(l.label("Preparing locally. Keep the app open; you can continue using the lessons.", "Идёт локальная подготовка. Оставьте приложение открытым; уроки доступны."))
            }
            else -> {
                SmallNote(l.label("One-time setup after downloading Gemma. The first setup took about40 seconds on this phone; allow up to a minute before an intensive. Prepared starts are faster.", "Однократная подготовка после загрузки Gemma. На этом телефоне первый запуск занял около40 секунд; выделите до минуты перед интенсивом. Повторные старты быстрее."))
                if (preparation is LocalAccelerationState.Failed) SmallNote(runtimeMessage(preparation.message, l))
                Button(onClick = vm::prepareLocalAcceleration,
                    enabled = vm.runtime.downloads.isInstalled(ModelCatalog.gemma) && downloadValue !is DownloadState.Downloading && downloadValue !is DownloadState.Verifying) {
                    Text(l.label("Prepare fast feedback", "Подготовить быстрый разбор"))
                }
            }
        }
    }
    StudyCard {
        Text(l.label("Content package", "Пакет материалов"), style = MaterialTheme.typography.titleLarge)
        Text("${s.pack!!.title.text(l)} · v${s.pack.version}")
        SmallNote(l.label("${s.pack.exercises.size} tasks · ${s.pack.lessons.size} lessons · AI drafts, pending expert review", "${s.pack.exercises.size} заданий · ${s.pack.lessons.size} уроков · ИИ-черновики, ожидают экспертизы"))
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text(l.label("Import a package update", "Импорт обновления пакета")) }
        SmallNote(l.label("Imports validate the schema and preserve attempts and original task versions. Audio references must use installed bundled assets.", "Импорт проверяет схему и сохраняет попытки и исходные версии. Аудио должно ссылаться на встроенные файлы."))
    }
    var recordingsValue by remember { mutableStateOf<List<com.tomilov.stylishsat.speech.Recording>>(emptyList()) }
    LaunchedEffect(s.attempts.size, s.session?.recordingPath) {
        recordingsValue = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.runtime.recorder.recordings() }
    }
    val recordings = recordingsValue
    if (recordings.isNotEmpty()) StudyCard {
        Text(l.label("Saved recordings", "Сохранённые записи"), style = MaterialTheme.typography.titleLarge)
        recordings.take(20).forEach { recording ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { vm.runtime.playback.play(recording.file) }) { Text("▶ ${java.time.Instant.ofEpochMilli(recording.createdAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()} · ${recording.durationMillis / 1000}s") }
            }
        }
        TextButton(onClick = vm.runtime.playback::stop) { Text(l.label("Stop playback", "Остановить воспроизведение")) }
    }
    DisposableEffect(Unit) { onDispose { vm.runtime.playback.stop() } }
    StudyCard {
        Text(l.label("Private by default", "Данные на устройстве"), style = MaterialTheme.typography.titleLarge)
        SmallNote(l.label("Answers, drafts, recordings and feedback stay here and are excluded from system backup. ChatGPT receives text only when you choose Copy or Share. External feedback never changes keys or your mastery automatically.", "Ответы, черновики, записи и отзывы сохраняются здесь и исключены из системной резервной копии. Передача в ChatGPT — только через выбранное вами копирование или «Поделиться». Внешний отзыв не меняет ключи и уровень."))
        TextButton(onClick = {
            licensesValue = listOf("licenses/whisper-LICENSE.txt", "licenses/content-notices.txt").joinToString("\n\n") { path -> runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault(path) }
        }) { Text(l.label("Licenses & content provenance", "Лицензии и происхождение материалов")) }
        Text(l.label("Official references", "Официальные материалы"), style = MaterialTheme.typography.titleMedium)
        listOf("SAT · College Board" to "https://satsuite.collegeboard.org/sat/whats-on-the-test/structure", "IELTS Academic" to "https://ielts.org/take-a-test/test-types/ielts-academic-test").forEach { (name, url) ->
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("$name ↗") }
        }
    }
    licensesValue?.let { text -> AlertDialog(onDismissRequest = { licensesValue = null }, title = { Text(l.label("Licenses", "Лицензии")) }, text = { Text(text, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { licensesValue = null }) { Text("OK") } }) }
}
