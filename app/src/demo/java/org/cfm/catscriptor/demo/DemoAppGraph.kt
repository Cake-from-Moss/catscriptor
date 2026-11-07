package org.cfm.catscriptor.demo

import android.content.Context
import org.cfm.catscriptor.app.AppGraph
import org.cfm.catscriptor.app.InMemorySettingsRepository
import org.cfm.catscriptor.domain.app.ExportGateway
import org.cfm.catscriptor.domain.app.PlaybackController
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.RecorderController
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.SettingsRepository

/** Сценарии для проверки состояний экранов и ошибок; выбираются через DemoControlActivity. */
enum class DemoScenario { DEFAULT, EMPTY, PROCESSING_ERROR, MODELS_MISSING, LOW_STORAGE }

class DemoAppGraph(context: Context) : AppGraph {
    val store = DemoStore()
    val demoRecordings = DemoRecordingRepository(store)
    val demoProcessing = DemoProcessingController(store)
    val demoRecorder = DemoRecorderController(store, demoProcessing)

    override val recordings: RecordingRepository = demoRecordings
    override val processing: ProcessingController = demoProcessing
    override val recorder: RecorderController = demoRecorder
    override val playback: PlaybackController = DemoPlaybackController(store)
    override val export: ExportGateway = DemoExportGateway(context.applicationContext, store)
    override val settings: SettingsRepository = InMemorySettingsRepository()
    override val useDynamicColor: Boolean = false

    fun applyScenario(scenario: DemoScenario) {
        store.reset(scenario)
        demoProcessing.applyScenario(scenario)
        demoRecorder.applyScenario(scenario)
    }
}
