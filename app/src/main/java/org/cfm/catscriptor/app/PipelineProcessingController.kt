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
import org.cfm.catscriptor.domain.app.ProcessingProgress
import org.cfm.catscriptor.domain.app.isOptional
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
 * Очередь реальных PREPARE_AUDIO → VAD → диаризация → ASR → LLM поверх AudioProcessingService.
 * Все изменения записей идут в одном потоке [dispatcher], чтобы не гнать гонки с сервисом.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineProcessingController(
    private val appContext: Context,
    private val store: PipelineStore,
    /** Число спикеров из настроек, читается при старте каждой записи; null — автоопределение. */
    private val speakerCount: () -> Int? = { null },
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
            .filter { !it.hidden && it.status != RecordingStatus.Ready && it.status != RecordingStatus.AsrReady }
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
        if (currentId == recordingId) return@withContext
        store.update(recordingId) { it.copy(status = RecordingStatus.Queued, prepareFailure = null, processingState = null) }
        pump()
    }

    /**
     * «Сохранить без этого этапа» после ошибки необязательного этапа. Сейчас это только
     * LLM-коррекция: реплики со спикерами и таймкодами уже готовы (runner публикует их до LLM),
     * поэтому запись становится готовой с исходным текстом ASR без повторного прогона.
     * Обязательные этапы и записи без готовых реплик не пропускаются.
     */
    override suspend fun skipStage(recordingId: String, stage: ProcessingStage) = withContext(dispatcher) {
        if (!stage.isOptional || currentId == recordingId) return@withContext
        val entry = store.entry(recordingId) ?: return@withContext
        val failed = entry.status as? RecordingStatus.Failed ?: return@withContext
        val draft = entry.processingState?.speakerDraft ?: return@withContext
        if (failed.stage != stage) return@withContext
        store.update(recordingId) {
            it.copy(
                status = RecordingStatus.Ready,
                prepareFailure = null,
                processingState = it.processingState?.copy(
                    phase = AudioProcessingPhase.COMPLETED,
                    transcript = draft,
                    stages = it.processingState.stages.map { progress ->
                        if (progress.stage == stage) progress.copy(status = StageStatus.Skipped) else progress
                    },
                ),
            )
        }
    }

    /** Терминальные фазы сервиса привязываются к текущей записи и запускают следующую в очереди. */
    private suspend fun onServiceState(state: AudioProcessingState) {
        when (state.phase) {
            AudioProcessingPhase.IDLE -> Unit
            AudioProcessingPhase.RUNNING -> currentId?.let { id ->
                val durationMs = state.sampleCount * 1000L / PcmAudioSource.SAMPLE_RATE_HZ
                store.update(id) { it.copy(
                    status = RecordingStatus.Processing(
                        ProcessingProgress.fraction(state.stages, durationMs).coerceAtLeast(RUN_FRACTION),
                        state.stage,
                    ),
                    durationMs = durationMs,
                    processingState = state,
                ) }
            }
            AudioProcessingPhase.COMPLETED -> {
                val id = currentId ?: return
                currentId = null
                store.update(id) {
                    it.copy(
                        // Финальный транскрипт после LLM (#7) означает готовность записи;
                        // null — защитный фолбэк для старых состояний: остаётся ASR-черновик.
                        status = if (state.transcript != null) RecordingStatus.Ready else RecordingStatus.AsrReady,
                        processingState = state,
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
                        status = RecordingStatus.Failed(state.stage),
                        processingState = state,
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
        if (currentId != null || AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) return
        val next = store.entries.value.values.firstOrNull {
            !it.hidden && it.status == RecordingStatus.Queued
        } ?: return
        currentId = next.id
        stageStartedAtMs = SystemClock.elapsedRealtime()
        store.update(next.id) {
            it.copy(status = RecordingStatus.Processing(RUN_FRACTION, ProcessingStage.PREPARE_AUDIO))
        }
        AudioProcessingController.start(appContext, AudioInput(next.documentUri), speakerCount())
    }

    /**
     * Доля и оставшееся время считает [ProcessingProgress] по этапам сервиса: веса этапов взяты из
     * замера на телефоне, внутри ASR и LLM учитывается обработанное аудио. Оставшееся время —
     * экстраполяция по прошедшему времени текущей записи.
     */
    private fun PipelineEntry.toJob(): ProcessingJob {
        val prepare = when (val current = status) {
            is RecordingStatus.Processing ->
                StageProgress(ProcessingStage.PREPARE_AUDIO, StageStatus.Running(runningElapsedMs()))
            is RecordingStatus.Failed ->
                StageProgress(ProcessingStage.PREPARE_AUDIO, StageStatus.Failed(prepareFailure))
            RecordingStatus.Queued ->
                StageProgress(ProcessingStage.PREPARE_AUDIO, StageStatus.Pending)
            RecordingStatus.Ready, RecordingStatus.AsrReady ->
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
                is RecordingStatus.Processing -> status.fraction
                is RecordingStatus.Failed -> ProcessingProgress.fraction(processingState?.stages.orEmpty(), durationMs)
                RecordingStatus.Queued -> 0f
                RecordingStatus.Ready, RecordingStatus.AsrReady -> 1f
            },
            stages = processingState?.stages?.takeIf { it.isNotEmpty() } ?: (listOf(prepare) + rest),
            remainingMs = (status as? RecordingStatus.Processing)
                ?.let { ProcessingProgress.remainingMs(it.fraction, runningElapsedMs()) },
            partialText = processingState?.partialText.orEmpty(),
        )
    }

    private fun runningElapsedMs(): Long =
        (SystemClock.elapsedRealtime() - stageStartedAtMs).coerceAtLeast(0)

    /** Модели нужны этапам после PREPARE_AUDIO; проверяем комплект без распаковки в файловую систему. */
    private fun checkModels(): ModelsStatus {
        return try {
            val manifests = listOf(
                MODEL_MANIFEST to { stream: java.io.InputStream -> BundledModelSpec.read(stream) },
                "vad-manifest.properties" to { stream -> BundledModelSpec.readVad(stream) },
                "diarization-manifest.properties" to { stream -> BundledModelSpec.readDiarization(stream) },
                LLM_MANIFEST to { stream -> BundledModelSpec.readLlm(stream) },
            )
            for ((manifest, readSpec) in manifests) {
            val spec = appContext.assets.open(manifest).use(readSpec)
            val broken = spec.files.firstOrNull { file ->
                appContext.assets.open("${spec.assetDirectory}/${file.name}").use {
                    it.available().toLong() != file.size
                }
            }
            if (broken != null) return ModelsStatus.Unavailable("Повреждён файл модели: ${broken.name}")
            }
            ModelsStatus.Ready
        } catch (error: Exception) {
            ModelsStatus.Unavailable(error.message)
        }
    }

    private companion object {
        const val MODEL_MANIFEST = "model-manifest.properties"
        const val LLM_MANIFEST = "llm-manifest.properties"
        /** Минимальная доля запущенной записи: полоса прогресса сразу показывает, что работа идёт. */
        const val RUN_FRACTION = 0.01f
    }
}
