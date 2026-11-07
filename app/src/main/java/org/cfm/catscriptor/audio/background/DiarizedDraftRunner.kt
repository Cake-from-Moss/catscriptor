package org.cfm.catscriptor.audio.background

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.StageProgress
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.domain.diarization.SpeakerTurnPlanner
import org.cfm.catscriptor.domain.pipeline.*

/**
 * Рабочий путь #6 + #7: PCM → VAD → диаризация → ASR по ходам спикеров → слияние в реплики
 * → коррекция локальной LLM.
 *
 * Модели загружаются строго по одной и закрываются сразу после своего этапа: VAD освобождается до
 * создания диаризации, диаризация — до создания GigaAM, GigaAM — до загрузки LLM. PCM закрывается
 * после ASR (последнего аудиоэтапа). Результат — финальный транскрипт: спикеры и таймкоды
 * исходные, текст исправлен LLM; пустой ответ LLM не стирает распознанный текст.
 */
internal class DiarizedDraftRunner(
    private val decoder: AudioDecoder,
    private val vadFactory: () -> VoiceActivityDetector,
    private val diarizerFactory: () -> DiarizationBackend,
    private val asrFactory: () -> TranscriptionBackend,
    private val merger: UtteranceMerger,
    private val correctorFactory: () -> TextCorrectionBackend,
    private val clock: () -> Long,
    private val planner: SpeakerTurnPlanner = SpeakerTurnPlanner(),
    /** Тайминги этапов на устройстве; в JVM-тестах Android-лог недоступен — no-op. */
    private val logTiming: (String) -> Unit = {},
) {
    suspend fun run(input: AudioInput, publish: (AudioProcessingState) -> Unit): AudioProcessingState {
        var state = AudioProcessingState(AudioProcessingPhase.RUNNING)
        val stages = ProcessingStage.entries.associateWith { StageStatus.Pending as StageStatus }.toMutableMap()
        var started = clock()
        fun snapshot() = stages.map { StageProgress(it.key, it.value) }
        fun emit(stage: ProcessingStage, processedMs: Long? = null) {
            stages[stage] = StageStatus.Running((clock() - started).coerceAtLeast(0), processedMs)
            state = state.copy(stage = stage, stages = snapshot())
            publish(state)
        }
        fun finish(stage: ProcessingStage) {
            stages[stage] = StageStatus.Done((clock() - started).coerceAtLeast(0))
            // Тайминги этапов на устройстве: без них невозможно отличить медленную модель
            // от зависшего этапа при разборе logcat.
            logTiming("$stage done in ${clock() - started} ms")
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

                var turns = emptyList<SpeakerSegment>()
                emit(ProcessingStage.DIARIZE)
                if (speech.isNotEmpty()) {
                    val speakers = diarizerFactory().use { it.diarize(pcm, speech) }
                    currentCoroutineContext().ensureActive()
                    turns = planner.plan(speakers, speech)
                }
                finish(ProcessingStage.DIARIZE)

                emit(ProcessingStage.TRANSCRIBE, 0)
                val recognized = mutableListOf<AsrSegment>()
                if (turns.isNotEmpty()) asrFactory().use { backend ->
                    for (turn in turns) {
                        currentCoroutineContext().ensureActive()
                        recognized += backend.transcribe(pcm, SpeechSegment(turn.time))
                        state = state.copy(partialText = recognized.map { it.text }.filter { it.isNotBlank() }.joinToString("\n"))
                        emit(ProcessingStage.TRANSCRIBE, turn.time.endMs)
                    }
                }
                currentCoroutineContext().ensureActive()
                // Сопоставление идёт по ходам: время ASR-фрагмента совпадает с интервалом хода его владельца.
                val transcript = merger.merge(recognized, turns)
                finish(ProcessingStage.TRANSCRIBE)

                // Финал: локальная LLM правит только текст, спикеров и таймкоды не трогает (#7).
                emit(ProcessingStage.CORRECT_TEXT)
                val corrected = ArrayList<Utterance>(transcript.transcript.size)
                if (transcript.transcript.isNotEmpty()) {
                    correctorFactory().use { corrector ->
                        for (utterance in transcript.transcript) {
                            currentCoroutineContext().ensureActive()
                            val text = corrector.correct(utterance)
                            // Пустой ответ LLM не должен стирать распознанный текст.
                            corrected += if (text.isBlank()) utterance else utterance.copy(text = text)
                            emit(ProcessingStage.CORRECT_TEXT, utterance.endMs)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                finish(ProcessingStage.CORRECT_TEXT)

                state = state.copy(
                    phase = AudioProcessingPhase.COMPLETED,
                    asrDraft = transcript.transcript.map { AsrSegment(TimeRange(it.startMs, it.endMs), it.text) },
                    speakerDraft = transcript.transcript,
                    transcript = corrected,
                    stages = snapshot(),
                )
            }
            return state
        } catch (error: Exception) {
            stages[state.stage] = StageStatus.Failed(error.message)
            publish(state.copy(stages = snapshot()))
            throw error
        }
    }
}
