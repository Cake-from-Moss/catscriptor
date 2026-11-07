package org.cfm.catscriptor.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cfm.catscriptor.domain.app.AppSettings
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.SettingsRepository

/** Настройки сохраняются до публикации нового состояния. */
internal class PersistentSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(AppSettings(
        language = preferences.getString("language", "ru") ?: "ru",
        speakerCount = preferences.getInt("speakerCount", 0).takeIf { it > 0 },
        defaultExportFormat = runCatching {
            ExportFormat.valueOf(preferences.getString("exportFormat", "JSON") ?: "JSON")
        }.getOrDefault(ExportFormat.JSON),
        liveTextEnabled = preferences.getBoolean("liveTextEnabled", true),
        textCorrectionEnabled = preferences.getBoolean("textCorrectionEnabled", true),
    ))
    override val settings = state.asStateFlow()

    @Synchronized
    override fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        check(preferences.edit()
            .putString("language", next.language)
            .putInt("speakerCount", next.speakerCount ?: 0)
            .putString("exportFormat", next.defaultExportFormat.name)
            .putBoolean("liveTextEnabled", next.liveTextEnabled)
            .putBoolean("textCorrectionEnabled", next.textCorrectionEnabled)
            .commit()) { "Не удалось сохранить настройки" }
        state.value = next
    }
}
