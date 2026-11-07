package org.cfm.catscriptor.audio.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.app.*
import org.cfm.catscriptor.domain.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class AsrDraftRunnerTest {
    private class Fixture(val speech: List<SpeechSegment>, val failure: Exception? = null) {
        val events = mutableListOf<String>()
        val states = mutableListOf<AudioProcessingState>()
        val pcm = object : PcmAudioSource {
            override val sampleCount = 160_000L
            override suspend fun read(startSample: Long, maxSamples: Int) = FloatArray(maxSamples)
            override fun close() { events += "pcm.close" }
        }
        val runner = AsrDraftRunner(object : AudioDecoder {
            override suspend fun decode(input: AudioInput): PcmAudioSource { events += "decode"; return pcm }
        }, {
            events += "vad.create"
            object : VoiceActivityDetector {
                override suspend fun detect(audio: PcmAudioSource) = speech
                override fun close() { events += "vad.close" }
            }
        }, {
            events += "asr.create"
            object : TranscriptionBackend {
                override suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment {
                    events += "asr.transcribe"
                    failure?.let { throw it }
                    return AsrSegment(segment.time, "Настоящий контракт")
                }
                override fun close() { events += "asr.close" }
            }
        }, { 100L })
        suspend fun run() = runner.run(AudioInput("content://test/audio"), states::add)
    }

    @Test fun sequentialModelsPreserveAbsoluteIntervalsAndPendingFutureStages() = runBlocking {
        val speech = listOf(SpeechSegment(TimeRange(1200, 2400)), SpeechSegment(TimeRange(5100, 6000)))
        val fixture = Fixture(speech)
        val result = fixture.run()
        assertEquals(listOf("decode", "vad.create", "vad.close", "asr.create", "asr.transcribe",
            "asr.transcribe", "asr.close", "pcm.close"), fixture.events)
        assertEquals(speech.map { it.time }, result.asrDraft!!.map { it.time })
        assertEquals(AudioProcessingPhase.COMPLETED, result.phase)
        assertEquals("Настоящий контракт\nНастоящий контракт", result.partialText)
        assertEquals(StageStatus.Running(0, 6000), fixture.states.last().stages.single { it.stage == ProcessingStage.TRANSCRIBE }.status)
        // Устаревший черновик #5 не выполняет диаризацию и коррекцию: порядок enum в #6 изменился.
        val done = setOf(ProcessingStage.PREPARE_AUDIO, ProcessingStage.DETECT_SPEECH, ProcessingStage.TRANSCRIBE)
        assertTrue(result.stages.filter { it.stage in done }.all { it.status is StageStatus.Done })
        assertTrue(result.stages.filter { it.stage !in done }.all { it.status == StageStatus.Pending })
    }

    @Test fun silenceDoesNotCreateAsrAndStillProducesDraft() = runBlocking {
        val fixture = Fixture(emptyList())
        assertEquals(emptyList<AsrSegment>(), fixture.run().asrDraft)
        assertFalse(fixture.events.contains("asr.create"))
        assertEquals("pcm.close", fixture.events.last())
    }

    @Test fun failureAndCancellationCloseAllResources() = runBlocking {
        for (failure in listOf(IllegalStateException("ошибка"), CancellationException("отмена"))) {
            val fixture = Fixture(listOf(SpeechSegment(TimeRange(1000, 2000))), failure)
            try { fixture.run(); fail("Ожидалась ошибка") } catch (caught: Exception) { assertSame(failure, caught) }
            assertEquals(listOf("asr.close", "pcm.close"), fixture.events.takeLast(2))
            assertEquals(ProcessingStage.TRANSCRIBE, fixture.states.last().stage)
            assertTrue(fixture.states.last().stages.single { it.stage == ProcessingStage.TRANSCRIBE }.status is StageStatus.Failed)
        }
    }

    @Test fun everyDraftIncludingSilenceRejectsExport() {
        for (draft in listOf(emptyList(), listOf(AsrSegment(TimeRange(1000, 2000), "Привет")))) {
            val document = TranscriptDocument("id", "title", 0, 3000, emptyList(), emptyList(), asrDraft = draft)
            for (format in ExportFormat.entries) {
                try {
                    TranscriptRendering.render(document, ExportOptions(format, false)) { "Спикер $it" }
                    fail("Черновик не экспортируется")
                } catch (_: IllegalArgumentException) { }
            }
            try { document.toPipelineTranscript(); fail("Черновик не финальный") } catch (_: IllegalArgumentException) { }
        }
    }
}
