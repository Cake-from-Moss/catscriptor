package org.cfm.catscriptor.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.cfm.catscriptor.domain.app.RecordingQuery
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.TranscriptDocument

class DemoRecordingRepository(private val store: DemoStore) : RecordingRepository {
    override fun observe(query: RecordingQuery): Flow<List<RecordingSummary>> = store.summaries
    override fun observeTranscript(recordingId: String): Flow<TranscriptDocument?> = flowOf(null)
    override suspend fun rename(recordingId: String, title: String) = Unit
    override suspend fun delete(recordingId: String) = Unit
    override suspend fun restore(recordingId: String) = Unit
    override suspend fun renameSpeaker(recordingId: String, speakerId: String, displayName: String) = Unit
    override suspend fun editLineText(recordingId: String, lineId: String, text: String) = Unit
    override suspend fun changeLineSpeaker(recordingId: String, lineId: String, speakerId: String) = Unit
}
