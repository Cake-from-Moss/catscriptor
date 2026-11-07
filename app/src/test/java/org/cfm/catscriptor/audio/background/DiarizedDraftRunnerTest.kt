package org.cfm.catscriptor.audio.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.domain.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class DiarizedDraftRunnerTest {
    private class Fixture(
        val speech: List<SpeechSegment>,
        val speakers: List<SpeakerSegment>,
        val asrFailure: Exception? = null,
        val diarizationFailure: Exception? = null,
    ) {
        val events = mutableListOf<String>()
        val states = mutableListOf<AudioProcessingState>()
        val transcribed = mutableListOf<TimeRange>()
        val pcm = object : PcmAudioSource {
            override val sampleCount = 160_000L
            override suspend fun read(startSample: Long, maxSamples: Int) = FloatArray(maxSamples)
            override fun close() { events += "pcm.close" }
        }
        val runner = DiarizedDraftRunner(
            decoder = object : AudioDecoder {
                override suspend fun decode(input: AudioInput): PcmAudioSource { events += "decode"; return pcm }
            },
            vadFactory = {
                events += "vad.create"
                object : VoiceActivityDetector {
                    override suspend fun detect(audio: PcmAudioSource) = speech
                    override fun close() { events += "vad.close" }
                }
            },
            diarizerFactory = {
                events += "diarizer.create"
                object : DiarizationBackend {
                    override suspend fun diarize(audio: PcmAudioSource, speech: List<SpeechSegment>): List<SpeakerSegment> {
                        events += "diarizer.diarize"
                        diarizationFailure?.let { throw it }
                        return speakers
                    }
                    override fun close() { events += "diarizer.close" }
                }
            },
            asrFactory = {
                events += "asr.create"
                object : TranscriptionBackend {
                    override suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment {
                        events += "asr.transcribe"
                        asrFailure?.let { throw it }
                        transcribed += segment.time
                        return AsrSegment(segment.time, "реплика ${segment.time.startMs}")
                    }
                    override fun close() { events += "asr.close" }
                }
            },
            merger = DefaultUtteranceMerger(),
            clock = { 100L },
        )
        suspend fun run() = runner.run(AudioInput("content://test/audio"), states::add)
    }

    private fun spk(id: Int, start: Long, end: Long) = SpeakerSegment("speaker_$id", TimeRange(start, end))

    @Test fun modelsAreLoadedOneAtATimeAndClosedBeforeTheNextStage() = runBlocking {
        val fixture = Fixture(
            speech = listOf(SpeechSegment(TimeRange(1_000, 6_000))),
            speakers = listOf(spk(1, 1_000, 3_000), spk(2, 3_000, 6_000)),
        )
        val result = fixture.run()
        assertEquals(listOf("decode", "vad.create", "vad.close", "diarizer.create", "diarizer.diarize", "diarizer.close",
            "asr.create", "asr.transcribe", "asr.transcribe", "asr.close", "pcm.close"), fixture.events)
        // Один VAD-сегмент с двумя говорящими даёт два отдельных ASR-вызова по ходам, без деления текста.
        assertEquals(listOf(TimeRange(1_000, 3_000), TimeRange(3_000, 6_000)), fixture.transcribed)
        assertEquals(listOf("speaker_1", "speaker_2"), result.speakerDraft!!.map { it.speakerId })
        assertEquals(listOf(1_000L, 3_000L), result.speakerDraft!!.map { it.startMs })
        assertEquals(AudioProcessingPhase.COMPLETED, result.phase)
        assertTrue(result.stages.filter { it.stage != ProcessingStage.CORRECT_TEXT }.all { it.status is StageStatus.Done })
        assertEquals(StageStatus.Pending, result.stages.single { it.stage == ProcessingStage.CORRECT_TEXT }.status)
    }

    @Test fun silenceNeverCreatesDiarizerOrAsr() = runBlocking {
        val fixture = Fixture(emptyList(), emptyList())
        val result = fixture.run()
        assertEquals(emptyList<Utterance>(), result.speakerDraft)
        assertFalse(fixture.events.contains("diarizer.create"))
        assertFalse(fixture.events.contains("asr.create"))
        assertEquals("pcm.close", fixture.events.last())
    }

    @Test fun speechWithoutDetectedSpeakersDoesNotInventOne() = runBlocking {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), emptyList())
        val result = fixture.run()
        assertEquals(emptyList<Utterance>(), result.speakerDraft)
        assertFalse(fixture.events.contains("asr.create"))
    }

    @Test fun diarizationFailureClosesEverythingAndNeverStartsAsr() {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), emptyList(),
            diarizationFailure = IllegalStateException("native"))
        assertThrows(IllegalStateException::class.java) { runBlocking { fixture.run() } }
        assertEquals(listOf("decode", "vad.create", "vad.close", "diarizer.create", "diarizer.diarize",
            "diarizer.close", "pcm.close"), fixture.events)
        assertTrue(fixture.states.last().stages.any { it.stage == ProcessingStage.DIARIZE && it.status is StageStatus.Failed })
    }

    @Test fun asrCancellationClosesAsrAndPcm() {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), listOf(spk(1, 0, 5_000)),
            asrFailure = CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) { runBlocking { fixture.run() } }
        assertEquals(listOf("asr.create", "asr.transcribe", "asr.close", "pcm.close"), fixture.events.takeLast(4))
    }

    @Test fun draftIsNeverPublishedBeforeMerge() = runBlocking {
        val fixture = Fixture(listOf(SpeechSegment(TimeRange(0, 5_000))), listOf(spk(1, 0, 5_000)))
        fixture.run()
        assertTrue(fixture.states.all { it.speakerDraft == null || it.phase == AudioProcessingPhase.COMPLETED })
    }
}
