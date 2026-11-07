package org.cfm.catscriptor.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.app.RecordingFilter
import org.cfm.catscriptor.domain.app.RecordingQuery
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.TranscriptDocument

/**
 * Библиотека записей поверх [PipelineStore]. Расшифровки пока не производятся:
 * [observeTranscript] честно отдаёт null, а правки реплик/спикеров — no-op.
 * Хранение в памяти процесса: после его смерти «мягко удалённые» записи
 * исчезают сами, что эквивалентно финальной очистке из контракта репозитория.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineRecordingRepository(
    private val store: PipelineStore,
) : RecordingRepository {

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    override fun observe(query: RecordingQuery): Flow<List<RecordingSummary>> =
        store.entries.map { map ->
            map.values.asSequence()
                .filter { !it.hidden && !it.pendingCommitDelete }
                .map { it.toSummary() }
                .filter { matchesFilter(it.status, query.filter) }
                .filter { summary ->
                    query.text.isBlank() ||
                        summary.title.contains(query.text.trim(), ignoreCase = true)
                }
                .sortedByDescending { it.createdAtEpochMs }
                .toList()
        }

    override fun observeTranscript(recordingId: String): Flow<TranscriptDocument?> = flowOf(null)

    override suspend fun rename(recordingId: String, title: String) = withContext(dispatcher) {
        store.update(recordingId) { it.copy(title = title) }
    }

    override suspend fun delete(recordingId: String) = withContext(dispatcher) {
        store.update(recordingId) { it.copy(hidden = true, pendingCommitDelete = true) }
    }

    override suspend fun restore(recordingId: String) = withContext(dispatcher) {
        store.update(recordingId) { it.copy(hidden = false, pendingCommitDelete = false) }
    }

    override suspend fun commitDelete(recordingId: String) = withContext(dispatcher) {
        store.remove(recordingId)
    }

    override suspend fun renameSpeaker(recordingId: String, speakerId: String, displayName: String) = Unit

    override suspend fun editLineText(recordingId: String, lineId: String, text: String) = Unit

    override suspend fun changeLineSpeaker(recordingId: String, lineId: String, speakerId: String) = Unit

    private fun matchesFilter(status: RecordingStatus, filter: RecordingFilter): Boolean = when (filter) {
        RecordingFilter.ALL -> true
        RecordingFilter.READY -> status == RecordingStatus.Ready
        RecordingFilter.PROCESSING -> status != RecordingStatus.Ready
    }
}
