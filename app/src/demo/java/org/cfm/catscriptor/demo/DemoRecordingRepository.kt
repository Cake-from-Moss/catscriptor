package org.cfm.catscriptor.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import org.cfm.catscriptor.domain.app.RecordingFilter
import org.cfm.catscriptor.domain.app.RecordingQuery
import org.cfm.catscriptor.domain.app.RecordingRepository
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.cfm.catscriptor.domain.app.withRecomputedSpeakers

/**
 * Библиотека демо-сборки поверх общего DemoStore. Удалённая запись убирается из DemoStore
 * и хранится здесь, пока её можно вернуть через «Отменить»; окончательным удалением
 * считается то, что её больше не восстановили.
 */
class DemoRecordingRepository(private val store: DemoStore) : RecordingRepository {
    private val trash = mutableMapOf<String, Pair<RecordingSummary, TranscriptDocument?>>()

    override fun observe(query: RecordingQuery): Flow<List<RecordingSummary>> =
        combine(store.summaries, store.documents) { summaries, documents ->
            val needle = normalized(query.text.trim())
            summaries
                .filter { it.matches(query.filter) }
                .filter { needle.isEmpty() || it.containsText(needle, documents[it.id]) }
                .sortedByDescending { it.createdAtEpochMs }
        }.distinctUntilChanged()

    override fun observeTranscript(recordingId: String): Flow<TranscriptDocument?> =
        combine(store.summaries, store.documents) { summaries, documents ->
            // Расшифровка доступна только у готовой записи, как требует контракт репозитория.
            documents[recordingId]?.takeIf {
                summaries.any { it.id == recordingId && it.status == RecordingStatus.Ready }
            }
        }.distinctUntilChanged()

    override suspend fun rename(recordingId: String, title: String) {
        store.updateSummary(recordingId) { it.copy(title = title) }
        store.updateDocument(recordingId) { it.copy(title = title) }
    }

    override suspend fun delete(recordingId: String) {
        val summary = store.summaries.value.firstOrNull { it.id == recordingId } ?: return
        trash[recordingId] = summary to store.documents.value[recordingId]
        store.summaries.update { list -> list.filterNot { it.id == recordingId } }
        store.documents.update { it - recordingId }
    }

    override suspend fun commitDelete(recordingId: String) {
        trash.remove(recordingId)
    }

    override suspend fun restore(recordingId: String) {
        val (summary, document) = trash.remove(recordingId) ?: return
        if (document != null) store.documents.update { it + (recordingId to document) }
        store.upsert(summary)
    }

    /** Пустое имя возвращает подпись по умолчанию «Спикер N». */
    override suspend fun renameSpeaker(recordingId: String, speakerId: String, displayName: String) {
        val name = displayName.trim().ifEmpty { null }
        store.updateDocument(recordingId) { doc ->
            doc.copy(speakers = doc.speakers.map { if (it.id == speakerId) it.copy(displayName = name) else it })
        }
        store.updateSummary(recordingId) { summary ->
            if (summary.previewSpeakerId == speakerId) summary.copy(previewSpeakerName = name) else summary
        }
    }

    override suspend fun editLineText(recordingId: String, lineId: String, text: String) {
        store.updateDocument(recordingId) { doc ->
            doc.copy(lines = doc.lines.map { if (it.id == lineId) it.copy(text = text) else it })
        }
    }

    override suspend fun changeLineSpeaker(recordingId: String, lineId: String, speakerId: String) {
        store.updateDocument(recordingId) { doc ->
            doc.copy(lines = doc.lines.map { if (it.id == lineId) it.copy(speakerId = speakerId) else it })
                .withRecomputedSpeakers()
        }
    }

    private fun RecordingSummary.matches(filter: RecordingFilter): Boolean = when (filter) {
        RecordingFilter.ALL -> true
        RecordingFilter.READY -> status == RecordingStatus.Ready
        // Ошибка обработки тоже «не готово»: такие записи ищут в той же вкладке, что и очередь.
        RecordingFilter.PROCESSING -> status != RecordingStatus.Ready
    }

    private fun RecordingSummary.containsText(needle: String, document: TranscriptDocument?): Boolean =
        normalized(title).contains(needle) ||
            document?.lines?.any { normalized(it.text).contains(needle) } == true

    /** Поиск без учёта регистра и разницы «ё»/«е». */
    private fun normalized(text: String): String = text.lowercase().replace('ё', 'е')
}
