package org.cfm.catscriptor.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.TranscriptDocument

/**
 * Общее состояние демо-сборки: что показывает библиотека. Данные повторяют кадры из
 * design-screens.pdf (см. DemoSeed). Изменения видны всем демо-контроллерам сразу.
 */
class DemoStore {
    val summaries = MutableStateFlow<List<RecordingSummary>>(emptyList())
    val documents = MutableStateFlow<Map<String, TranscriptDocument>>(emptyMap())

    init {
        reset(DemoScenario.DEFAULT)
    }

    fun reset(scenario: DemoScenario) {
        val seeded = scenario != DemoScenario.EMPTY
        summaries.value = if (seeded) DemoSeed.summaries() else emptyList()
        documents.value = if (seeded) DemoSeed.documents() else emptyMap()
    }

    fun upsert(summary: RecordingSummary) = summaries.update { current ->
        if (current.any { it.id == summary.id }) {
            current.map { if (it.id == summary.id) summary else it }
        } else {
            listOf(summary) + current
        }
    }

    fun updateSummary(id: String, transform: (RecordingSummary) -> RecordingSummary) =
        summaries.update { current -> current.map { if (it.id == id) transform(it) else it } }

    fun updateDocument(id: String, transform: (TranscriptDocument) -> TranscriptDocument) =
        documents.update { current ->
            current[id]?.let { current + (id to transform(it)) } ?: current
        }
}
