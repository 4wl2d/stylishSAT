package com.tomilov.stylishsat.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tomilov.stylishsat.domain.Exam
import com.tomilov.stylishsat.domain.Language
import kotlinx.coroutines.flow.map

private val Context.studySettings by preferencesDataStore("study_settings")
data class Settings(val exam: Exam = Exam.SAT, val language: Language = Language.RU, val onboarded: Boolean = false)
interface SettingsStore {
    val flow: kotlinx.coroutines.flow.Flow<Settings>
    suspend fun exam(value: Exam)
    suspend fun language(value: Language)
    /** First-run setup is a presentation preference; stores without it keep existing behavior. */
    suspend fun onboarded(value: Boolean) {}
}
class UserSettings(private val context: Context) : SettingsStore {
    private val examKey = stringPreferencesKey("selected_exam")
    private val languageKey = stringPreferencesKey("language")
    private val onboardedKey = booleanPreferencesKey("onboarded")
    override val flow = context.studySettings.data.map { Settings(
        Exam.entries.firstOrNull { exam -> exam.name == it[examKey] } ?: Exam.SAT,
        Language.entries.firstOrNull { lang -> lang.name == it[languageKey] } ?: Language.RU,
        it[onboardedKey] ?: false,
    ) }
    override suspend fun exam(value: Exam) { context.studySettings.edit { it[examKey] = value.name } }
    override suspend fun language(value: Language) { context.studySettings.edit { it[languageKey] = value.name } }
    override suspend fun onboarded(value: Boolean) { context.studySettings.edit { it[onboardedKey] = value } }
}
