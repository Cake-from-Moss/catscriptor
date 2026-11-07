package org.cfm.catscriptor.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptTest {
    private fun utterance(start: Long = 1250, end: Long = 2000) = Utterance(
        "speaker_1", start, end, "Привет!", listOf(TimedWord("привет", TimeRange(start, end))),
    )

    @Test
    fun emptyTranscriptIsValid() {
        assertEquals(emptyList<Utterance>(), Transcript(emptyList()).transcript)
    }

    @Test
    fun correctionPreservesSourceWordsSpeakerAndAbsoluteTimes() {
        val original = utterance()
        val corrected = original.copy(text = "Добрый день!")
        assertEquals(original.words, corrected.words)
        assertEquals(original.speakerId, corrected.speakerId)
        assertEquals(1250L, corrected.startMs)
        assertEquals(2000L, corrected.endMs)
        assertEquals("привет", corrected.words.single().text)
    }

    @Test
    fun invalidIntervalsAreRejected() {
        listOf(-1L to 10L, 10L to 10L, 20L to 10L).forEach { (start, end) ->
            assertThrows(IllegalArgumentException::class.java) { TimeRange(start, end) }
        }
    }

    @Test
    fun largeRecordingOffsetsRemainLongIntegers() {
        val start = Int.MAX_VALUE.toLong() + 1000
        assertEquals(start, utterance(start, start + 500).words.single().time.startMs)
    }

    @Test
    fun invalidSpeakerIdsAreRejected() {
        listOf("", "Alice", "speaker_0", "speaker_-1", "speaker_01").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { utterance().copy(speakerId = id) }
            assertThrows(IllegalArgumentException::class.java) {
                SpeakerSegment(id, TimeRange(0, 100))
            }
        }
    }

    @Test
    fun overlappingUtterancesAreAllowedButUnsortedOnesAreRejected() {
        val first = utterance(0, 1000)
        val second = utterance(500, 1500).copy(speakerId = "speaker_2")
        assertEquals(2, Transcript(listOf(first, second)).transcript.size)
        assertThrows(IllegalArgumentException::class.java) { Transcript(listOf(second, first)) }
    }

    @Test
    fun missingUnsortedAndOutOfBoundsWordsAreRejected() {
        val first = TimedWord("раз", TimeRange(1250, 1500))
        val second = TimedWord("два", TimeRange(1600, 2000))
        val outside = TimedWord("три", TimeRange(1900, 2100))
        assertThrows(IllegalArgumentException::class.java) { utterance().copy(words = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            utterance().copy(words = listOf(second, first))
        }
        assertThrows(IllegalArgumentException::class.java) {
            utterance().copy(words = listOf(outside))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AsrSegment(TimeRange(1250, 2000), listOf(second, first))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AsrSegment(TimeRange(1250, 2000), listOf(outside))
        }
        assertThrows(IllegalArgumentException::class.java) { TimedWord(" ", TimeRange(0, 1)) }
    }

    @Test
    fun silenceHasNoRecognizedWords() {
        assertEquals(emptyList<TimedWord>(), AsrSegment(TimeRange(0, 1000), emptyList()).words)
    }
}
