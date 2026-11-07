package org.cfm.catscriptor.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary

/**
 * Запись библиотеки вместе со служебным состоянием очереди подготовки.
 * Хранилище живёт в памяти процесса; постоянная база появится вместе с расшифровками.
 */
internal data class PipelineEntry(
    val id: String,
    val title: String,
    val documentUri: String,
    val fileExtension: String?,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val status: RecordingStatus,
    val prepareFailure: String? = null,
    val prepareElapsedMs: Long = 0,
    val hidden: Boolean = false,
    val pendingCommitDelete: Boolean = false,
    val processingState: org.cfm.catscriptor.audio.background.AudioProcessingState? = null,
) {
    fun toSummary() = RecordingSummary(
        id = id,
        title = title,
        source = RecordingSource.FILE,
        createdAtEpochMs = createdAtEpochMs,
        durationMs = durationMs,
        speakerCount = null,
        previewSpeakerId = null,
        previewSpeakerName = null,
        previewText = processingState?.asrDraft?.firstOrNull()?.text,
        status = status,
    )
}

/**
 * Потоки отсюда подписываются UI; все изменения идут копиями LinkedHashMap,
 * чтобы StateFlow замечал обновления. Порядок вставки — порядок очереди.
 */
internal class PipelineStore {
    private val state = MutableStateFlow(LinkedHashMap<String, PipelineEntry>())

    val entries: StateFlow<LinkedHashMap<String, PipelineEntry>> = state.asStateFlow()

    fun upsert(entry: PipelineEntry) = state.update { LinkedHashMap(it).apply { put(entry.id, entry) } }

    fun update(id: String, transform: (PipelineEntry) -> PipelineEntry) = state.update { map ->
        if (map.containsKey(id)) LinkedHashMap(map).apply { put(id, transform(getValue(id))) } else map
    }

    fun remove(id: String) = state.update { map ->
        if (map.containsKey(id)) LinkedHashMap(map).apply { remove(id) } else map
    }

    fun entry(id: String): PipelineEntry? = state.value[id]
}
