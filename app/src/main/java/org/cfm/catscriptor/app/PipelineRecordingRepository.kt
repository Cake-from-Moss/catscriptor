package org.cfm.catscriptor.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.app.RecordingFilter
import org.cfm.catscriptor.domain.app.RecordingQuery
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.SpeakerInfo
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.cfm.catscriptor.domain.app.TranscriptLine

/**
 * Библиотека записей поверх [PipelineStore]. Готовые записи отдают финальный транскрипт
 * (спикеры + исправленный LLM текст, #6/#7) с возможностью правки реплик и имён спикеров;
 * ASR-черновики доступны только для чтения.
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
                        summary.title.contains(query.text.trim(), ignoreCase = true) ||
                        map[summary.id]?.processingState?.asrDraft.orEmpty().any {
                            it.text.contains(query.text.trim(), ignoreCase = true)
                        }
                }
                .sortedByDescending { it.createdAtEpochMs }
                .toList()
        }

    override fun observeTranscript(recordingId: String): Flow<TranscriptDocument?> = store.entries.map { entries ->
        entries[recordingId]?.takeIf { !it.hidden && it.status == RecordingStatus.Ready }
            ?.let { entry -> finalDocument(entry) }
            ?: entries[recordingId]?.takeIf { !it.hidden && it.status == RecordingStatus.AsrReady }?.let { entry ->
                TranscriptDocument(entry.id, entry.title, entry.createdAtEpochMs, entry.durationMs,
                    emptyList(), emptyList(), asrDraft = entry.processingState?.asrDraft ?: emptyList(),
                    asrDraftSpeakers = entry.processingState?.speakerDraft?.map { it.speakerId })
            }
    }

    /** Финальный транскрипт: реплики со спикерами и исправленным текстом, таймкоды исходные. */
    private fun finalDocument(entry: PipelineEntry): TranscriptDocument {
        val utterances = entry.displayedTranscript()
        val totalMs = utterances.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }.coerceAtLeast(1)
        val speakers = utterances.map { it.speakerId }.distinct().map { id ->
            val shareMs = utterances.filter { it.speakerId == id }.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }
            SpeakerInfo(id = id, displayName = entry.speakerDisplayNames[id], shareFraction = shareMs.toFloat() / totalMs)
        }
        val lines = utterances.mapIndexed { index, utterance ->
            TranscriptLine(
                id = "line_${utterance.startMs}_$index",
                speakerId = utterance.speakerId,
                startMs = utterance.startMs,
                endMs = utterance.endMs,
                text = utterance.text,
            )
        }
        return TranscriptDocument(
            recordingId = entry.id,
            title = entry.title,
            createdAtEpochMs = entry.createdAtEpochMs,
            durationMs = entry.durationMs,
            speakers = speakers,
            lines = lines,
        )
    }

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

    override suspend fun renameSpeaker(recordingId: String, speakerId: String, displayName: String) =
        withContext(dispatcher) {
            store.update(recordingId) { entry ->
                entry.copy(speakerDisplayNames = entry.speakerDisplayNames + (speakerId to displayName))
            }
        }

    override suspend fun editLineText(recordingId: String, lineId: String, text: String) =
        withContext(dispatcher) {
            store.update(recordingId) { entry ->
                entry.copy(editedTranscript = entry.displayedTranscript().replaceLine(lineId) { it.copy(text = text) })
            }
        }

    override suspend fun changeLineSpeaker(recordingId: String, lineId: String, speakerId: String) =
        withContext(dispatcher) {
            store.update(recordingId) { entry ->
                entry.copy(editedTranscript = entry.displayedTranscript().replaceLine(lineId) { it.copy(speakerId = speakerId) })
            }
        }

    /** Правка одной реплики по её идентификатору; неизвестный id — запись без изменений. */
    private fun List<org.cfm.catscriptor.domain.pipeline.Utterance>.replaceLine(
        lineId: String,
        transform: (org.cfm.catscriptor.domain.pipeline.Utterance) -> org.cfm.catscriptor.domain.pipeline.Utterance,
    ): List<org.cfm.catscriptor.domain.pipeline.Utterance> =
        mapIndexed { index, utterance ->
            if ("line_${utterance.startMs}_$index" == lineId) transform(utterance) else utterance
        }

    private fun matchesFilter(status: RecordingStatus, filter: RecordingFilter): Boolean = when (filter) {
        RecordingFilter.ALL -> true
        RecordingFilter.READY -> status == RecordingStatus.Ready || status == RecordingStatus.AsrReady
        RecordingFilter.PROCESSING -> status != RecordingStatus.Ready && status != RecordingStatus.AsrReady
    }
}
