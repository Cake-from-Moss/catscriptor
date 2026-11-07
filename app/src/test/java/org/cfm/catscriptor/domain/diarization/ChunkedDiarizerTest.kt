package org.cfm.catscriptor.domain.diarization

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeakerSegment
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedDiarizerTest {
    /** PCM, где значение отсчёта кодирует «голос»: 0.1 — голос A, 0.2 — голос B, 0 — тишина. */
    private class VoiceAudio(val voices: (Long) -> Float, ms: Long) : PcmAudioSource {
        override val sampleCount = ms * 16
        var closed = false
        var maxRead = 0
        override suspend fun read(startSample: Long, maxSamples: Int): FloatArray {
            val count = minOf(maxSamples.toLong(), sampleCount - startSample).toInt()
            maxRead = maxOf(maxRead, count)
            return FloatArray(count) { voices((startSample + it) / 16) }
        }
        override fun close() { closed = true }
    }

    /** Нативная модель-заглушка окна: для каждого окна нумерует голоса в произвольном порядке. */
    private class FakeWindow(val labelOf: (Float) -> Int, val flip: Set<Int> = emptySet()) : WindowDiarizer {
        var calls = 0
        val sizes = mutableListOf<Int>()
        override suspend fun diarize(samples: FloatArray): List<LocalSegment> {
            val index = calls++
            sizes += samples.size
            val result = mutableListOf<LocalSegment>()
            var start = -1; var voice = 0f
            fun flush(end: Int) {
                if (start >= 0) {
                    var label = labelOf(voice)
                    if (index in flip) label = 1 - label
                    result += LocalSegment(label, start / 16000f, end / 16000f)
                }
                start = -1
            }
            for (i in samples.indices) {
                val v = samples[i]
                if (v != voice) { flush(i); voice = v; if (v != 0f) start = i }
            }
            flush(samples.size)
            return result
        }
    }

    private class FakeEmbedder : SpeakerEmbedder {
        var calls = 0
        override suspend fun embed(samples: FloatArray): FloatArray? {
            calls++
            val mean = samples.average().toFloat()
            return if (mean < 0.15f) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }
    }

    private fun speech(vararg r: LongRange) = r.map { SpeechSegment(TimeRange(it.first, it.last + 1)) }
    private val labelOf: (Float) -> Int = { if (it < 0.15f) 0 else 1 }

    @Test fun singleWindowNumbersSpeakersByFirstAppearance() = runBlocking {
        // B говорит первым, но нативная метка у него 1: итог всё равно speaker_1 = первый говорящий.
        val audio = VoiceAudio({ ms -> if (ms < 3_000) 0.2f else if (ms in 4_000..6_999) 0.1f else 0f }, 8_000)
        val embedder = FakeEmbedder()
        val result = ChunkedDiarizer(FakeWindow(labelOf), { embedder }).diarize(audio, speech(0L..2_999, 4_000L..6_999))
        assertEquals(listOf("speaker_1", "speaker_2"), result.map { it.speakerId })
        assertEquals(listOf(0L, 4_000L), result.map { it.time.startMs })
        assertEquals(0, embedder.calls)
        assertFalse(audio.closed)
    }

    @Test fun noSpeechMeansNoSpeakers() = runBlocking {
        val window = FakeWindow(labelOf)
        val result = ChunkedDiarizer(window, { FakeEmbedder() }).diarize(VoiceAudio({ 0f }, 5_000), emptyList())
        assertTrue(result.isEmpty())
        assertEquals(0, window.calls)
    }

    @Test fun multiWindowKeepsSpeakerIdsStableEvenWhenNativeLabelsAreFlipped() = runBlocking {
        // A (0.1) и B (0.2) чередуются каждые 6 секунд; окна ≤ 10 с, во втором окне метки модели инвертированы.
        val voice: (Long) -> Float = { ms ->
            val slot = (ms / 6_000).toInt()
            if (ms % 6_000 > 4_500) 0f else if (slot % 2 == 0) 0.1f else 0.2f
        }
        val audio = VoiceAudio(voice, 36_000)
        val speechRanges = (0 until 6).map { (it * 6_000L)..(it * 6_000L + 4_499) }
        val diarizer = ChunkedDiarizer(
            FakeWindow(labelOf, flip = setOf(1, 3)), { FakeEmbedder() },
            DiarizationWindowPlanner(maxWindowMs = 10_000, paddingMs = 500),
        )
        val result = diarizer.diarize(audio, speech(*speechRanges.toTypedArray()))
        assertEquals(6, result.size)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1", "speaker_2", "speaker_1", "speaker_2"), result.map { it.speakerId })
        assertTrue(audio.maxRead <= 8192)
    }

    @Test fun segmentCrossingWindowBoundaryIsJoinedIntoOneTurn() = runBlocking {
        val audio = VoiceAudio({ ms -> if (ms in 500..24_499) 0.1f else 0f }, 25_000)
        val diarizer = ChunkedDiarizer(
            FakeWindow(labelOf), { FakeEmbedder() },
            DiarizationWindowPlanner(maxWindowMs = 10_000, paddingMs = 500),
        )
        val result = diarizer.diarize(audio, speech(500L..24_499))
        assertEquals(1, result.size)
        assertEquals(SpeakerSegment("speaker_1", TimeRange(500, 24_500)), result[0])
    }

    @Test fun overlapOfDifferentSpeakersIsPreserved() = runBlocking {
        val window = object : WindowDiarizer {
            override suspend fun diarize(samples: FloatArray) =
                listOf(LocalSegment(0, 0f, 4f), LocalSegment(1, 2f, 3f))
        }
        val result = ChunkedDiarizer(window, { FakeEmbedder() }).diarize(VoiceAudio({ 0.1f }, 5_000), speech(0L..4_999))
        assertEquals(2, result.size)
        assertTrue(result[0].time.endMs > result[1].time.startMs)
    }

    @Test fun resultsStayInsideRecording() = runBlocking {
        val window = object : WindowDiarizer {
            override suspend fun diarize(samples: FloatArray) = listOf(LocalSegment(0, -1f, 99f))
        }
        val result = ChunkedDiarizer(window, { FakeEmbedder() }).diarize(VoiceAudio({ 0.1f }, 3_000), speech(0L..2_999))
        assertEquals(TimeRange(0, 3_000), result.single().time)
    }

    @Test fun cancellationBetweenWindowsStops() {
        val window = object : WindowDiarizer {
            var calls = 0
            override suspend fun diarize(samples: FloatArray): List<LocalSegment> {
                if (++calls == 1) throw CancellationException("cancelled")
                return emptyList()
            }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { ChunkedDiarizer(window, { FakeEmbedder() }).diarize(VoiceAudio({ 0.1f }, 3_000), speech(0L..2_999)) }
        }
        assertEquals(1, window.calls)
    }
}
