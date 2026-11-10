package com.iamahsana.ossstudio.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.ossAppSettingsDataStore by preferencesDataStore(name = "oss_app_settings")

class AppSettingsRepository(context: Context) {
    private val dataStore = context.ossAppSettingsDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            AppSettings(
                themeMode = OssThemeMode.fromStorage(preferences[THEME_MODE]),
                accent = AppAccent.fromStorage(preferences[ACCENT])
            )
        }

    suspend fun setThemeMode(mode: OssThemeMode) {
        dataStore.edit { preferences -> preferences[THEME_MODE] = mode.name }
    }

    suspend fun setAccent(accent: AppAccent) {
        dataStore.edit { preferences -> preferences[ACCENT] = accent.name }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
    }
}
