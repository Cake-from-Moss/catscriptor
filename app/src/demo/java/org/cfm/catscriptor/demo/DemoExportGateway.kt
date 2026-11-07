package org.cfm.catscriptor.demo

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.ExportGateway
import org.cfm.catscriptor.domain.app.ExportOptions
import org.cfm.catscriptor.domain.app.SharePayload
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.cfm.catscriptor.domain.app.TranscriptRendering
import org.cfm.catscriptor.domain.app.toPipelineTranscript
import org.cfm.catscriptor.domain.pipeline.DefaultTranscriptExporter
import org.cfm.catscriptor.domain.pipeline.TranscriptFormat
import org.cfm.catscriptor.viewmodel.export.mimeTypeOf

/**
 * Экспорт демо-сборки. Строгий JSON пишет тот же DefaultTranscriptExporter, что и основная
 * сборка; TXT и SRT строит TranscriptRendering. В демо нет аудиофайлов, поэтому опция
 * «Сохранять с аудиофайлом» здесь не меняет результат.
 */
class DemoExportGateway(private val context: Context, private val store: DemoStore) : ExportGateway {
    private val exporter = DefaultTranscriptExporter()

    override suspend fun preview(recordingId: String, options: ExportOptions): String =
        TranscriptRendering.preview(document(recordingId), options, ::defaultSpeakerName)

    override suspend fun saveTo(documentUri: String, recordingId: String, options: ExportOptions): String {
        val document = document(recordingId)
        val uri = documentUri.toUri()
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Провайдер не открыл поток для $uri")
            stream.use { write(document, options, it) }
        }
        return displayName(uri) ?: suggestedFileName(document.title, options.format)
    }

    override suspend fun prepareShare(recordingId: String, options: ExportOptions): SharePayload {
        val document = document(recordingId)
        val file = withContext(Dispatchers.IO) {
            // Каталог чистится перед каждой отправкой: старые файлы в кэше никому не нужны.
            val directory = File(context.cacheDir, SHARE_DIR).apply {
                deleteRecursively()
                mkdirs()
            }
            File(directory, suggestedFileName(document.title, options.format)).also { target ->
                target.outputStream().use { write(document, options, it) }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
        return SharePayload(uri.toString(), mimeTypeOf(options.format))
    }

    /** Символы, запрещённые в именах файлов SAF и FAT, заменяются на «_». */
    override fun suggestedFileName(title: String, format: ExportFormat): String {
        val base = title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "transcript" }
        val extension = when (format) {
            ExportFormat.TXT -> "txt"
            ExportFormat.JSON -> "json"
            ExportFormat.SRT -> "srt"
        }
        return "$base.$extension"
    }

    private suspend fun write(document: TranscriptDocument, options: ExportOptions, output: OutputStream) {
        when (options.format) {
            ExportFormat.JSON -> exporter.export(document.toPipelineTranscript(), TranscriptFormat.JSON, output)
            ExportFormat.TXT, ExportFormat.SRT -> {
                output.write(TranscriptRendering.render(document, options, ::defaultSpeakerName).toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }

    private fun document(recordingId: String): TranscriptDocument =
        store.documents.value[recordingId] ?: throw IllegalStateException("Нет расшифровки для $recordingId")

    private fun defaultSpeakerName(number: Int): String = context.getString(R.string.speaker_default_name, number)

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private companion object {
        const val SHARE_DIR = "exports"
        const val AUTHORITY_SUFFIX = ".recordings"
    }
}
