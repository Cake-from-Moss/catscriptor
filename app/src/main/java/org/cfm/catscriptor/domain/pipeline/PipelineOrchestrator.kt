package org.cfm.catscriptor.domain.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.StageProgress
import org.cfm.catscriptor.domain.app.StageStatus

/** Снимок хода обработки одной записи; UI подписывается на [PipelineOrchestrator.progress]. */
data class PipelineProgress(
    val stages: List<StageProgress>,
    val overallFraction: Float,
    /** Текст, уже распознанный ASR: для блока «Уже распознано». */
    val partialText: String,
) {
    companion object {
        val Initial = PipelineProgress(
            stages = ProcessingStage.entries.map { StageProgress(it, StageStatus.Pending) },
            overallFraction = 0f,
            partialText = "",
        )
    }
}

/** Ошибка конкретного этапа; исходная причина в [cause] (в UI её текст не показываем: он может содержать URI). */
class PipelineStageException(val stage: ProcessingStage, cause: Throwable) :
    Exception("Pipeline stage $stage failed", cause)

/**
 * Последовательно проходит этапы AGENTS.md: декодирование, VAD, ASR, диаризация (+ слияние), коррекция LLM.
 *
 * Память: модель создаётся фабрикой непосредственно перед своим этапом и закрывается сразу после него,
 * в том числе при ошибке и отмене, поэтому одновременно загружена не более чем одна модель.
 * Декодированное аудио закрывается после последнего аудиоэтапа (диаризации).
 * Один экземпляр обрабатывает одну запись за раз.
 */
class PipelineOrchestrator(
    private val decoder: AudioDecoder,
    private val createVad: () -> VoiceActivityDetector,
    private val createAsr: () -> TranscriptionBackend,
    private val createDiarizer: () -> DiarizationBackend,
    private val merger: UtteranceMerger,
    private val createCorrector: () -> TextCorrectionBackend,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableProgress = MutableStateFlow(PipelineProgress.Initial)
    val progress: StateFlow<PipelineProgress> = mutableProgress.asStateFlow()

    /** Выбрасывает [PipelineStageException] при ошибке этапа и [CancellationException] при отмене. */
    suspend fun run(input: AudioInput): Transcript {
        mutableProgress.value = PipelineProgress.Initial
        val inner = HashMap<ProcessingStage, Float>()
        var partial = ""

        val audio = stage(ProcessingStage.PREPARE_AUDIO, inner) { decoder.decode(input) }
        try {
            val speech = stage(ProcessingStage.DETECT_SPEECH, inner) {
                createVad().use { it.detect(audio) }
            }
            if (speech.isEmpty()) {
                // Тишина: дальше нечего распознавать.
                for (skipped in listOf(ProcessingStage.TRANSCRIBE, ProcessingStage.DIARIZE, ProcessingStage.CORRECT_TEXT)) {
                    setStatus(skipped, StageStatus.Skipped, inner)
                }
                return Transcript(emptyList())
            }

            val totalSpeechMs = speech.sumOf { it.time.endMs - it.time.startMs }.coerceAtLeast(1)
            val recognized = stage(ProcessingStage.TRANSCRIBE, inner) {
                createAsr().use { asr ->
                    var doneMs = 0L
                    val result = ArrayList<AsrSegment>(speech.size)
                    for (segment in speech) {
                        val text = asr.transcribe(audio, segment)
                        result += text
                        doneMs += segment.time.endMs - segment.time.startMs
                        val words = text.words.joinToString(" ") { it.text }
                        if (words.isNotEmpty()) partial = if (partial.isEmpty()) words else "$partial $words"
                        report(doneMs.toFloat() / totalSpeechMs, processedMs = segment.time.endMs, partialText = partial)
                    }
                    result
                }
            }

            val merged = stage(ProcessingStage.DIARIZE, inner) {
                val speakers = createDiarizer().use { it.diarize(audio, speech) }
                merger.merge(recognized, speakers)
            }
            // Аудио больше не нужно: освобождаем до тяжёлой LLM-стадии.
            audio.close()

            return stage(ProcessingStage.CORRECT_TEXT, inner) {
                createCorrector().use { corrector ->
                    val source = merged.transcript
                    val result = ArrayList<Utterance>(source.size)
                    source.forEachIndexed { index, utterance ->
                        val corrected = corrector.correct(utterance)
                        // Пустой ответ LLM не должен стирать распознанный текст.
                        result += if (corrected.isBlank()) utterance else utterance.copy(text = corrected)
                        report((index + 1).toFloat() / source.size)
                    }
                    Transcript(result)
                }
            }
        } finally {
            audio.close() // повторное закрытие безопасно
        }
    }

    private inner class StageScope(
        private val stage: ProcessingStage,
        private val inner: MutableMap<ProcessingStage, Float>,
        private val startedAtMs: Long,
    ) {
        /** [fraction] — доля выполнения внутри этапа, 0..1. */
        fun report(fraction: Float, processedMs: Long? = null, partialText: String? = null) {
            inner[stage] = fraction.coerceIn(0f, 1f)
            setStatus(
                stage, StageStatus.Running(clockMs() - startedAtMs, processedMs), inner,
                partialText,
            )
        }
    }

    private suspend fun <T> stage(
        stage: ProcessingStage,
        inner: MutableMap<ProcessingStage, Float>,
        block: suspend StageScope.() -> T,
    ): T {
        val startedAt = clockMs()
        inner[stage] = 0f
        setStatus(stage, StageStatus.Running(0), inner)
        try {
            val result = StageScope(stage, inner, startedAt).block()
            inner[stage] = 1f
            setStatus(stage, StageStatus.Done(clockMs() - startedAt), inner)
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            setStatus(stage, StageStatus.Failed(null), inner)
            throw PipelineStageException(stage, error)
        }
    }

    private fun setStatus(
        stage: ProcessingStage,
        status: StageStatus,
        inner: MutableMap<ProcessingStage, Float>,
        partialText: String? = null,
    ) {
        if (status is StageStatus.Done || status is StageStatus.Skipped) {
            inner[stage] = 1f
        }
        mutableProgress.update { current ->
            current.copy(
                stages = current.stages.map { if (it.stage == stage) StageProgress(stage, status) else it },
                overallFraction = ProcessingStage.entries.sumOf { s ->
                    (WEIGHTS.getValue(s) * (inner[s] ?: 0f)).toDouble()
                }.toFloat(),
                partialText = partialText ?: current.partialText,
            )
        }
    }

    private companion object {
        val WEIGHTS = mapOf(
            ProcessingStage.PREPARE_AUDIO to 0.05f,
            ProcessingStage.DETECT_SPEECH to 0.10f,
            ProcessingStage.TRANSCRIBE to 0.55f,
            ProcessingStage.DIARIZE to 0.15f,
            ProcessingStage.CORRECT_TEXT to 0.15f,
        )
    }
}
