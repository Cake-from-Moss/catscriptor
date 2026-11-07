package org.cfm.catscriptor.audio.background

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.StageProgress
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.domain.pipeline.*

/** Последовательный запуск моделей: VAD освобождается до создания ASR. */
internal class AsrDraftRunner(
    private val decoder: AudioDecoder,
    private val vadFactory: () -> VoiceActivityDetector,
    private val asrFactory: () -> TranscriptionBackend,
    private val clock: () -> Long,
) {
    suspend fun run(input: AudioInput, publish: (AudioProcessingState) -> Unit): AudioProcessingState {
        var state = AudioProcessingState(AudioProcessingPhase.RUNNING)
        val stages = ProcessingStage.entries.associateWith { StageStatus.Pending as StageStatus }.toMutableMap()
        var started = clock()
        fun emit(stage: ProcessingStage, processedMs: Long? = null) {
            stages[stage] = StageStatus.Running((clock() - started).coerceAtLeast(0), processedMs)
            state = state.copy(stage = stage, stages = stages.map { StageProgress(it.key, it.value) })
            publish(state)
        }
        fun finish(stage: ProcessingStage) {
            stages[stage] = StageStatus.Done((clock() - started).coerceAtLeast(0))
            started = clock()
        }
        try {
            emit(ProcessingStage.PREPARE_AUDIO)
            decoder.decode(input).use { pcm ->
                state = state.copy(sampleCount = pcm.sampleCount)
                finish(ProcessingStage.PREPARE_AUDIO)
                emit(ProcessingStage.DETECT_SPEECH)
                val speech = vadFactory().use { it.detect(pcm) }
                currentCoroutineContext().ensureActive()
                finish(ProcessingStage.DETECT_SPEECH)
                emit(ProcessingStage.TRANSCRIBE, 0)
                val draft = mutableListOf<AsrSegment>()
                if (speech.isNotEmpty()) asrFactory().use { backend ->
                    for (segment in speech) {
                        currentCoroutineContext().ensureActive()
                        draft += backend.transcribe(pcm, segment)
                        state = state.copy(partialText = draft.joinToString("\n") { it.text })
                        emit(ProcessingStage.TRANSCRIBE, segment.time.endMs)
                    }
                }
                currentCoroutineContext().ensureActive()
                finish(ProcessingStage.TRANSCRIBE)
                state = state.copy(phase = AudioProcessingPhase.COMPLETED, asrDraft = draft.toList(),
                    stages = stages.map { StageProgress(it.key, it.value) })
            }
            return state
        } catch (error: Exception) {
            stages[state.stage] = StageStatus.Failed(error.message)
            publish(state.copy(stages = stages.map { StageProgress(it.key, it.value) }))
            throw error
        }
    }
}
