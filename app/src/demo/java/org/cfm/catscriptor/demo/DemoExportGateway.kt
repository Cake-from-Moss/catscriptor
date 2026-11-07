package org.cfm.catscriptor.demo

import android.content.Context
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.ExportGateway
import org.cfm.catscriptor.domain.app.ExportOptions
import org.cfm.catscriptor.domain.app.SharePayload

class DemoExportGateway(private val context: Context, private val store: DemoStore) : ExportGateway {
    override suspend fun preview(recordingId: String, options: ExportOptions): String = ""
    override suspend fun saveTo(documentUri: String, recordingId: String, options: ExportOptions): String = ""
    override suspend fun prepareShare(recordingId: String, options: ExportOptions): SharePayload =
        error("Будет реализовано")
    override fun suggestedFileName(title: String, format: ExportFormat): String = title
}
