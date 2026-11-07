package org.cfm.catscriptor.domain.diarization

import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarizationWindowPlannerTest {
    private val planner = DiarizationWindowPlanner(maxWindowMs = 10_000, paddingMs = 500)
    private fun vad(start: Long, end: Long) = SpeechSegment(TimeRange(start, end))

    @Test fun noSpeechNoWindows() = assertTrue(planner.plan(emptyList(), 60_000).isEmpty())

    @Test fun shortRecordingIsOneWindowTrimmedAroundSpeech() {
        assertEquals(listOf(TimeRange(1_500, 6_500)), planner.plan(listOf(vad(2_000, 3_000), vad(5_000, 6_000)), 60_000))
    }

    @Test fun windowsStayInsideRecordingAndNeverExceedLimit() {
        val windows = planner.plan(listOf(vad(0, 4_000), vad(5_000, 9_000), vad(12_000, 16_000), vad(20_000, 24_000)), 24_200)
        assertTrue(windows.size >= 3)
        windows.forEach { assertTrue(it.startMs >= 0 && it.endMs <= 24_200 && it.endMs - it.startMs <= 10_000) }
        windows.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
    }

    @Test fun windowBoundaryFallsInSilenceNotInsideSpeech() {
        val speech = listOf(vad(0, 5_000), vad(6_000, 11_000), vad(13_000, 17_000))
        val windows = planner.plan(speech, 17_500)
        for (boundary in windows.zipWithNext().map { (a, _) -> a.endMs }) {
            assertTrue(speech.none { boundary > it.time.startMs && boundary < it.time.endMs })
        }
    }

    @Test fun everySpeechSampleIsCoveredByAWindow() {
        val speech = listOf(vad(0, 4_000), vad(5_000, 9_000), vad(9_500, 14_000), vad(40_000, 55_000))
        val windows = planner.plan(speech, 56_000)
        for (s in speech) {
            var covered = 0L
            for (w in windows) covered += maxOf(0L, minOf(w.endMs, s.time.endMs) - maxOf(w.startMs, s.time.startMs))
            assertEquals(s.time.endMs - s.time.startMs, covered)
        }
    }

    @Test fun singleLongSegmentIsSplitHard() {
        val windows = planner.plan(listOf(vad(0, 25_000)), 25_000)
        assertTrue(windows.size >= 3)
        windows.forEach { assertTrue(it.endMs - it.startMs <= 10_000) }
    }
}
