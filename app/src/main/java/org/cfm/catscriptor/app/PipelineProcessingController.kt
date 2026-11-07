package org.cfm.catscriptor.app

import android.content.Context
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.audio.background.AudioProcessingController
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.ImportResult
import org.cfm.catscriptor.domain.app.ModelsStatus
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.ProcessingJob
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.StageProgress
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.native.models.BundledModelSpec

/**
 * Очередь обработки поверх AudioProcessingService. Этап PREPARE_AUDIO реален; когда
 * появятся VAD/ASR/диаризация/LLM, их состояния добавляются в [PipelineEntry] и в [toJob].
 * Все изменения записей идут в одном потоке [dispatcher], чтобы не гнать гонки с сервисом.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineProcessingController(
    private val appContext: Context,
    private val store: PipelineStore,
) : ProcessingController {

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val models = MutableStateFlow<ModelsStatus>(ModelsStatus.Ready)

    override val modelsStatus: StateFlow<ModelsStatus> = models.asStateFlow()

    /** Запись, которой принадлежит текущий RUNNING сервиса; null — сервис свободен. */
    private var currentId: String? = null
    private var stageStartedAtMs = 0L

    init {
        scope.launch(dispatcher) { models.value = checkModels() }
        scope.launch(dispatcher) {
            AudioProcessingController.state.collect { onServiceState(it) }
        }
    }

    override fun observeJobs(): Flow<List<ProcessingJob>> = store.entries.map { map ->
        map.values.asSequence()
            .filter { !it.hidden && it.status != RecordingStatus.Ready }
            .sortedByDescending { it.status is RecordingStatus.Processing }
            .map { it.toJob() }
            .toList()
    }

    override suspend fun importFile(documentUri: String, displayName: String): ImportResult =
        withContext(dispatcher) {
            val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val title = if (extension.isEmpty()) displayName else displayName.substringBeforeLast('.')
            val entry = PipelineEntry(
                id = UUID.randomUUID().toString(),
                title = title,
                documentUri = documentUri,
                fileExtension = extension.ifEmpty { null },
                createdAtEpochMs = System.currentTimeMillis(),
                durationMs = 0L,
                status = RecordingStatus.Queued,
            )
            store.upsert(entry)
            pump()
            ImportResult.Accepted(entry.id)
        }

    override suspend fun cancel(recordingId: String) = withContext(dispatcher) {
        if (currentId == recordingId) {
            // Запись уберёт коллектор по финальному CANCELLED сервиса.
            AudioProcessingController.cancel(appContext)
        } else {
            store.remove(recordingId)
        }
    }

    override suspend fun retry(recordingId: String) = withContext(dispatcher) {
        store.update(recordingId) { it.copy(status = RecordingStatus.Queued, prepareFailure = null) }
        pump()
    }

    override suspend fun skipStage(recordingId: String, stage: ProcessingStage) = withContext(dispatcher) {
        // Необязательных этапов пока нет; пропуск появится вместе с пунктуацией/LLM.
        // TODO(#8): дать оркестратору возможность пропустить необязательный этап (например, LLM-коррекцию) по запросу.
    }

    /** Терминальные фазы сервиса привязываются к текущей записи и запускают следующую в очереди. */
    private suspend fun onServiceState(state: AudioProcessingState) {
        when (state.phase) {
            AudioProcessingPhase.RUNNING, AudioProcessingPhase.IDLE -> Unit
            AudioProcessingPhase.COMPLETED -> {
                val id = currentId ?: return
                currentId = null
                store.update(id) {
                    it.copy(
                        status = RecordingStatus.Ready,
                        durationMs = state.sampleCount * 1000L / PcmAudioSource.SAMPLE_RATE_HZ,
                        prepareElapsedMs = SystemClock.elapsedRealtime() - stageStartedAtMs,
                    )
                }
                pump()
            }
            AudioProcessingPhase.FAILED -> {
                val id = currentId ?: return
                currentId = null
                store.update(id) {
                    it.copy(
                        status = RecordingStatus.Failed(ProcessingStage.PREPARE_AUDIO),
                        prepareFailure = state.message,
                        prepareElapsedMs = SystemClock.elapsedRealtime() - stageStartedAtMs,
                    )
                }
                pump()
            }
            AudioProcessingPhase.CANCELLED -> {
                val id = currentId
                currentId = null
                if (id != null) store.remove(id)
                pump()
            }
        }
    }

    /** Стартует первую ожидающую запись, если сервис свободен. Вызывать только из [dispatcher]. */
    private fun pump() {
        if (AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) return
        val next = store.entries.value.values.firstOrNull {
            !it.hidden && it.status == RecordingStatus.Queued
        } ?: return
        currentId = next.id
        stageStartedAtMs = SystemClock.elapsedRealtime()
        store.update(next.id) {
            it.copy(status = RecordingStatus.Processing(RUN_FRACTION, ProcessingStage.PREPARE_AUDIO))
        }
        AudioProcessingController.start(appContext, AudioInput(next.documentUri))
    }

    // TODO(#8): собирать stages, overallFraction, remainingMs и partialText из PipelineProgress сервиса,
    //  а не отдавать все этапы после PREPARE_AUDIO как Pending.
    private fun PipelineEntry.toJob(): ProcessingJob {
        val prepare = when (val current = status) {
            is RecordingStatus.Processing ->
                StageProgress(ProcessingStage.PREPARE_AUDIO, StageStatus.Running(runningElapsedMs()))
            is RecordingStatus.Failed ->
                StageProgress(ProcessingStage.PREPARE_AUDIO, StageStatus.Failed(prepareFailure))
            RecordingStatus.Queued ->
                StageProgress(ProcessingStage.PREPARE_AUDIO, StageStatus.Pending)
            RecordingStatus.Ready ->
                StageProgress(ProcessingStage.PREPARE_AUDIO, StageStatus.Done(prepareElapsedMs))
        }
        val rest = ProcessingStage.entries
            .filter { it != ProcessingStage.PREPARE_AUDIO }
            .map { StageProgress(it, StageStatus.Pending) }
        return ProcessingJob(
            recordingId = id,
            title = title,
            source = RecordingSource.FILE,
            fileExtension = fileExtension,
            durationMs = durationMs,
            overallFraction = when (status) {
                is RecordingStatus.Processing -> RUN_FRACTION
                is RecordingStatus.Failed -> RUN_FRACTION
                RecordingStatus.Queued -> 0f
                RecordingStatus.Ready -> DONE_FRACTION
            },
            stages = listOf(prepare) + rest,
            remainingMs = null,
            partialText = "",
        )
    }

    private fun runningElapsedMs(): Long =
        (SystemClock.elapsedRealtime() - stageStartedAtMs).coerceAtLeast(0)

    /** Модели нужны этапам после PREPARE_AUDIO; проверяем комплект без распаковки в файловую систему. */
    private fun checkModels(): ModelsStatus {
        return try {
            val spec = appContext.assets.open(MODEL_MANIFEST).use { BundledModelSpec.read(it) }
            val broken = spec.files.firstOrNull { file ->
                appContext.assets.open("${spec.assetDirectory}/${file.name}").use {
                    it.available().toLong() != file.size
                }
            }
            if (broken == null) ModelsStatus.Ready
            else ModelsStatus.Unavailable("Повреждён файл модели: ${broken.name}")
        } catch (error: Exception) {
            ModelsStatus.Unavailable(error.message)
        }
    }

    private companion object {
        const val MODEL_MANIFEST = "model-manifest.properties"
        const val RUN_FRACTION = 0.05f
        const val DONE_FRACTION = 0.2f
    }
}
