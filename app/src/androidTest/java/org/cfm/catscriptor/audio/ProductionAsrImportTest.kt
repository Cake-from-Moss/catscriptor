package org.cfm.catscriptor.audio

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.app.ProductionAppGraph
import org.cfm.catscriptor.audio.background.AudioProcessingController
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.domain.app.*
import org.cfm.catscriptor.domain.pipeline.AudioInput
import org.cfm.catscriptor.native.models.SherpaVoiceActivityDetector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionAsrImportTest {
    @Test fun importRealRussianSpeechProducesNonExportableAsrDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = "content://org.cfm.catscriptor.audio.service.test/speech"
        // Независимый реальный VAD задаёт ожидаемые абсолютные интервалы без выдуманных слов.
        val expected = AndroidAudioDecoder(context).decode(AudioInput(uri)).use { pcm ->
            SherpaVoiceActivityDetector(context).use { it.detect(pcm).map { segment -> segment.time } }
        }
        assertTrue(expected.isNotEmpty())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.setShowWhenLocked(true) }
            val graph = ProductionAppGraph(context)
            try {
                val result = graph.processing.importFile(uri, "Русская речь.wav") as ImportResult.Accepted
                val document = withTimeout(180_000) {
                    graph.recordings.observeTranscript(result.recordingId).first { it != null }!!
                }
                val draft = requireNotNull(document.asrDraft)
                assertEquals("Русская речь", document.title)
                assertTrue(document.durationMs > 0)
                assertEquals(expected, draft.map { it.time })
                assertTrue(draft.any { segment -> segment.text.any { it in 'а'..'я' || it in 'А'..'Я' } })
                assertTrue(document.lines.isEmpty())
                assertTrue(document.speakers.isEmpty())
                assertEquals(RecordingStatus.AsrReady,
                    graph.recordings.observe().first().single { it.id == result.recordingId }.status)
                assertFalse(graph.processing.observeJobs().first().any { it.recordingId == result.recordingId })
                val state = AudioProcessingController.state.value
                assertEquals(AudioProcessingPhase.COMPLETED, state.phase)
                for (stage in listOf(ProcessingStage.DIARIZE, ProcessingStage.CORRECT_TEXT)) {
                    assertEquals(StageStatus.Pending, state.stages.single { it.stage == stage }.status)
                }
                for (format in ExportFormat.entries) {
                    try {
                        TranscriptRendering.render(document, ExportOptions(format, false)) { "Спикер $it" }
                        fail("Черновик не должен экспортироваться")
                    } catch (_: IllegalArgumentException) { }
                }
                try {
                    document.toPipelineTranscript()
                    fail("Черновик не должен превращаться в финальную расшифровку")
                } catch (_: IllegalArgumentException) { }
            } finally {
                AudioProcessingController.cancel(context)
                withTimeout(180_000) { AudioProcessingController.state.first { it.phase != AudioProcessingPhase.RUNNING } }
            }
        }
    }
}
