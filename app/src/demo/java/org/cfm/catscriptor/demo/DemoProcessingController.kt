package org.cfm.catscriptor.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.cfm.catscriptor.domain.app.ImportResult
import org.cfm.catscriptor.domain.app.ModelsStatus
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.ProcessingJob
import org.cfm.catscriptor.domain.app.ProcessingStage

class DemoProcessingController(private val store: DemoStore) : ProcessingController {
    private val jobs = MutableStateFlow<List<ProcessingJob>>(emptyList())
    override val modelsStatus: StateFlow<ModelsStatus> = MutableStateFlow(ModelsStatus.Ready)
    override fun observeJobs(): Flow<List<ProcessingJob>> = jobs
    override suspend fun importFile(documentUri: String, displayName: String) = ImportResult.UnsupportedFormat
    override suspend fun cancel(recordingId: String) = Unit
    override suspend fun retry(recordingId: String) = Unit
    override suspend fun skipStage(recordingId: String, stage: ProcessingStage) = Unit
    fun applyScenario(scenario: DemoScenario) = Unit
}
