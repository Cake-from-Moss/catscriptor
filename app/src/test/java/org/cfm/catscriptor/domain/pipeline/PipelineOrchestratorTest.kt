package org.cfm.catscriptor.domain.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.StageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineOrchestratorTest {
    private val range = TimeRange(1_000, 3_000)
    private val events = mutableListOf<String>()
    private var loaded = 0
    private var maxLoaded = 0

    private fun model(name: String): AutoCloseable {
        loaded++
        maxLoaded = maxOf(maxLoaded, loaded)
        events += "open:$name"
        return AutoCloseable { loaded--; events += "close:$name" }
    }

    private val audio = object : PcmAudioSource {
        override val sampleCount = 48_000L
        override suspend fun read(startSample: Long, maxSamples: Int) = FloatArray(0)
        override fun close() { events += "close:audio" }
    }
    private val decoder = object : AudioDecoder {
        override suspend fun decode(input: AudioInput): PcmAudioSource = audio
    }

    private inner class Vad(
        val result: List<SpeechSegment> = listOf(SpeechSegment(range)),
        val failure: Exception? = null,
    ) : VoiceActivityDetector {
        private val handle = model("vad")
        override suspend fun detect(audio: PcmAudioSource): List<SpeechSegment> {
            failure?.let { throw it }
            return result
        }
        override fun close() = handle.close()
    }

    private inner class Asr(val gate: CompletableDeferred<Unit>? = null) : TranscriptionBackend {
        private val handle = model("asr")
        override suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment {
            gate?.await()
            return AsrSegment(segment.time, "привет")
        }
        override fun close() = handle.close()
    }

    private inner class Diarizer : DiarizationBackend {
        private val handle = model("diarizer")
        override suspend fun diarize(audio: PcmAudioSource, speech: List<SpeechSegment>) =
            listOf(SpeakerSegment("speaker_1", range))
        override fun close() = handle.close()
    }

    private inner class Corrector(val answer: String) : TextCorrectionBackend {
        private val handle = model("llm")
        override suspend fun correct(utterance: Utterance) = answer
        override fun close() = handle.close()
    }

    private val merger = object : UtteranceMerger {
        override suspend fun merge(asr: List<AsrSegment>, speakers: List<SpeakerSegment>) =
            Transcript(asr.map { Utterance("speaker_1", it.time.startMs, it.time.endMs, it.text) })
    }

    private fun orchestrator(
        vad: () -> VoiceActivityDetector = { Vad() },
        asr: () -> TranscriptionBackend = { Asr() },
        corrector: () -> TextCorrectionBackend = { Corrector("Привет.") },
    ) = PipelineOrchestrator(decoder, vad, asr, { Diarizer() }, merger, corrector)

    private val input = AudioInput("content://test/doc")

    @Test
    fun runsAllStagesAndKeepsAsrTimestampsWhileCorrectingText() = runTest {
        val sut = orchestrator()
        val result = sut.run(input)

        assertEquals(1, result.transcript.size)
        val utterance = result.transcript.single()
        assertEquals("Привет.", utterance.text)
        assertEquals(1_000, utterance.startMs)
        assertEquals(3_000, utterance.endMs)
        val progress = sut.progress.value
        assertTrue(progress.stages.all { it.status is StageStatus.Done })
        assertEquals(1f, progress.overallFraction, 0.001f)
        assertEquals("привет", progress.partialText)
    }

    @Test
    fun neverHoldsMoreThanOneModelAndClosesEachBeforeTheNext() = runTest {
        orchestrator().run(input)

        assertEquals(1, maxLoaded)
        assertEquals(
            listOf(
                "open:vad", "close:vad", "open:asr", "close:asr", "open:diarizer", "close:diarizer",
                "close:audio", "open:llm", "close:llm", "close:audio",
            ),
            events,
        )
    }

    @Test
    fun stageFailureIsReportedAndReleasesModelAndAudio() = runTest {
        val sut = orchestrator(vad = { Vad(failure = IllegalStateException("boom")) })
        try {
            sut.run(input)
            fail("expected PipelineStageException")
        } catch (error: PipelineStageException) {
            assertEquals(ProcessingStage.DETECT_SPEECH, error.stage)
        }

        val stages = sut.progress.value.stages.associate { it.stage to it.status }
        assertTrue(stages.getValue(ProcessingStage.PREPARE_AUDIO) is StageStatus.Done)
        assertTrue(stages.getValue(ProcessingStage.DETECT_SPEECH) is StageStatus.Failed)
        assertEquals(StageStatus.Pending, stages.getValue(ProcessingStage.TRANSCRIBE))
        assertEquals(0, loaded)
        assertTrue("close:audio" in events)
    }

    @Test
    fun cancellationDuringAsrClosesModelAndAudio() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val sut = orchestrator(asr = { Asr(gate) })
        var cancelled = false
        val job = launch {
            try {
                sut.run(input)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        assertTrue("open:asr" in events)
        job.cancel()
        job.join()

        assertTrue(cancelled)
        assertEquals(0, loaded)
        assertTrue("close:asr" in events)
        assertTrue("close:audio" in events)
        assertTrue("open:diarizer" !in events)
    }

    @Test
    fun silenceSkipsLaterStagesAndReturnsEmptyTranscript() = runTest {
        val sut = orchestrator(vad = { Vad(result = emptyList()) })
        val result = sut.run(input)

        assertTrue(result.transcript.isEmpty())
        val stages = sut.progress.value.stages.associate { it.stage to it.status }
        assertEquals(StageStatus.Skipped, stages.getValue(ProcessingStage.TRANSCRIBE))
        assertEquals(StageStatus.Skipped, stages.getValue(ProcessingStage.CORRECT_TEXT))
        assertTrue("open:asr" !in events)
    }

    @Test
    fun blankCorrectionKeepsRecognizedText() = runTest {
        val result = orchestrator(corrector = { Corrector("  ") }).run(input)
        assertEquals("привет", result.transcript.single().text)
    }
}
