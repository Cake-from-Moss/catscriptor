package org.cfm.catscriptor.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.audio.background.AudioProcessingState
import org.cfm.catscriptor.domain.app.*
import org.cfm.catscriptor.domain.pipeline.AsrSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange
import org.junit.Assert.*
import org.junit.Test

class PipelineRecordingRepositoryTest {
    @Test fun diarizedDraftExposesRealSpeakersAndStaysNonExportable() = runBlocking {
        val store = PipelineStore()
        val utterances = listOf(
            org.cfm.catscriptor.domain.pipeline.Utterance("speaker_1", 1000, 2000, "Привет"),
            org.cfm.catscriptor.domain.pipeline.Utterance("speaker_2", 2500, 3500, "Здравствуй"),
        )
        store.upsert(PipelineEntry("id", "Диалог", "content://audio", "wav", 1, 4000, RecordingStatus.AsrReady,
            processingState = AudioProcessingState(AudioProcessingPhase.COMPLETED,
                asrDraft = utterances.map { AsrSegment(TimeRange(it.startMs, it.endMs), it.text) }, speakerDraft = utterances)))
        val repository = PipelineRecordingRepository(store)
        val document = requireNotNull(repository.observeTranscript("id").first())
        assertEquals(listOf("speaker_1", "speaker_2"), document.asrDraftSpeakers)
        assertNotNull(document.asrDraft)
        assertTrue(document.lines.isEmpty())
        val summary = repository.observe().first().single()
        assertEquals(2, summary.speakerCount)
    }

    @Test fun draftHasMetadataNoSpeakersAndCannotBeEdited() = runBlocking {
        val store = PipelineStore()
        val draft = listOf(AsrSegment(TimeRange(1500, 2500), "Кошачий разговор"))
        store.upsert(PipelineEntry("id", "Название", "content://audio", "wav", 123, 3000,
            RecordingStatus.AsrReady, processingState = AudioProcessingState(
                AudioProcessingPhase.COMPLETED, asrDraft = draft)))
        val repository = PipelineRecordingRepository(store)
        val document = requireNotNull(repository.observeTranscript("id").first())
        assertEquals("Название", document.title)
        assertEquals(3000L, document.durationMs)
        assertEquals(draft, document.asrDraft)
        assertTrue(document.lines.isEmpty())
        assertTrue(document.speakers.isEmpty())
        assertEquals(1, repository.observe(RecordingQuery("кошачий", RecordingFilter.READY)).first().size)
        assertTrue(repository.observe(RecordingQuery(filter = RecordingFilter.PROCESSING)).first().isEmpty())
        repository.editLineText("id", "fake", "Подмена")
        repository.renameSpeaker("id", "speaker_1", "Подмена")
        repository.changeLineSpeaker("id", "fake", "speaker_1")
        assertEquals(document, repository.observeTranscript("id").first())
        repository.rename("id", "Другое название")
        assertEquals("Другое название", repository.observeTranscript("id").first()!!.title)
        repository.delete("id")
        assertNull(repository.observeTranscript("id").first())
        repository.restore("id")
        assertEquals(draft, repository.observeTranscript("id").first()!!.asrDraft)
    }

    @Test fun silenceDraftIsNotConfusedWithMissingTranscript() = runBlocking {
        val store = PipelineStore()
        val repository = PipelineRecordingRepository(store)
        store.upsert(PipelineEntry("id", "Тишина", "content://audio", "wav", 0, 1000,
            RecordingStatus.Queued))
        assertNull(repository.observeTranscript("id").first())
        store.update("id") { it.copy(status = RecordingStatus.AsrReady,
            processingState = AudioProcessingState(AudioProcessingPhase.COMPLETED, asrDraft = emptyList())) }
        assertEquals(emptyList<AsrSegment>(), repository.observeTranscript("id").first()!!.asrDraft)
    }
}
