package org.cfm.catscriptor.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.cfm.catscriptor.domain.app.RecorderController
import org.cfm.catscriptor.domain.app.RecorderState
import org.cfm.catscriptor.domain.app.StartRecordingResult

class DemoRecorderController(
    private val store: DemoStore,
    private val processing: DemoProcessingController,
) : RecorderController {
    override val isAvailable = true
    override val state: StateFlow<RecorderState> = MutableStateFlow(RecorderState.Idle)
    override suspend fun start(liveText: Boolean) = StartRecordingResult.Unavailable
    override suspend fun stop(): String? = null
    override fun pause() = Unit
    override fun resume() = Unit
    override fun setLiveText(enabled: Boolean) = Unit
    override fun addMark() = Unit
    fun applyScenario(scenario: DemoScenario) = Unit
}
