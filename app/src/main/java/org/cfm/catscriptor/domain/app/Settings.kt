package org.cfm.catscriptor.domain.app

import kotlinx.coroutines.flow.StateFlow

/** [speakerCount] равен null в режиме «авто», иначе это ожидаемое число спикеров. */
data class AppSettings(
    val language: String = "ru",
    val speakerCount: Int? = null,
    val defaultExportFormat: ExportFormat = ExportFormat.JSON,
    val liveTextEnabled: Boolean = true,
    val textCorrectionEnabled: Boolean = true,
)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun update(transform: (AppSettings) -> AppSettings)
}
