package org.cfm.catscriptor.demo

import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.TranscriptDocument

object DemoSeed {
    /** Запись «Планёрка 06.11» из макета: библиотека показывает её, контроллер обработки ведёт. */
    const val PROCESSING_ID = "planerka"

    fun summaries(): List<RecordingSummary> = emptyList()
    fun documents(): Map<String, TranscriptDocument> = emptyMap()
}
