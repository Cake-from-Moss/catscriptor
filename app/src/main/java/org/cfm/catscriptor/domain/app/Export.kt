package org.cfm.catscriptor.domain.app

enum class ExportFormat { TXT, JSON, SRT }

/**
 * [useSpeakerNames] влияет только на TXT и SRT: в JSON всегда speaker_N (AGENTS.md).
 * [includeAudio] — опция из макета, которой нет в спецификации; см. docs/ui-integration.md.
 */
data class ExportOptions(
    val format: ExportFormat,
    val useSpeakerNames: Boolean,
    val includeAudio: Boolean = false,
)

/** Экспорт расшифровки. JSON обязан идти через DefaultTranscriptExporter, схема не меняется. */
interface ExportGateway {
    /** Первые ~10 строк выбранного формата для блока предпросмотра. */
    suspend fun preview(recordingId: String, options: ExportOptions): String

    /** [documentUri] получен из CreateDocument. Возвращает отображаемое имя сохранённого файла. */
    suspend fun saveTo(documentUri: String, recordingId: String, options: ExportOptions): String

    /** Готовит файл для системного share sheet и возвращает его content:// URI и MIME-тип. */
    suspend fun prepareShare(recordingId: String, options: ExportOptions): SharePayload

    fun suggestedFileName(title: String, format: ExportFormat): String
}

data class SharePayload(val contentUri: String, val mimeType: String)
