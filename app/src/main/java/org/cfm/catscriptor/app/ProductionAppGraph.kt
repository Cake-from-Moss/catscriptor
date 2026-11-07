package org.cfm.catscriptor.app

import android.content.Context
import org.cfm.catscriptor.domain.app.ExportGateway
import org.cfm.catscriptor.domain.app.PlaybackController
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.RecorderController
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.SettingsRepository

/**
 * Основной граф: импорт и подготовка аудио (этап PREPARE_AUDIO) работают по-настоящему
 * через AudioProcessingService. Запись с микрофона (#21), воспроизведение (#20) и экспорт
 * без расшифровок остаются недоступными — для них граф использует честные заглушки
 * из UnavailableAppGraph, как и раньше.
 */
class ProductionAppGraph(context: Context) : AppGraph {
    private val store = PipelineStore()

    override val recordings: RecordingRepository = PipelineRecordingRepository(store)
    override val processing: ProcessingController =
        PipelineProcessingController(context.applicationContext, store)
    override val recorder: RecorderController = UnavailableRecorderController()
    override val playback: PlaybackController = UnavailablePlaybackController()
    override val export: ExportGateway = UnavailableExportGateway
    override val settings: SettingsRepository = InMemorySettingsRepository()
}
