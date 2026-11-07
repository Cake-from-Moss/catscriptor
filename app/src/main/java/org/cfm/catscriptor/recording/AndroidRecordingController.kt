package org.cfm.catscriptor.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.RecorderController

/** Контроллер живёт столько же, сколько граф приложения; запись предназначена для открытого UI. */
fun androidRecordingController(context: Context, processing: ProcessingController): RecorderController {
    val app = context.applicationContext
    return RecordingController(
        available = {
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            // Публичный API не даёт универсально проверить системный mute микрофона:
            // ошибки старта/рекордера обрабатываются, нулевая амплитуда не считается VAD.
        },
        factory = { AndroidRecordingDevice(app) },
        storage = AndroidRecordingStorage(app),
        clock = SystemClock::elapsedRealtime,
        importFile = processing::importFile,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        supported = { app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) },
    )
}

internal class AndroidRecordingStorage(private val context: Context) : RecordingStorage {
    private val directory get() = File(context.filesDir, "recordings")
    override fun availableBytes() = context.filesDir.usableSpace
    override fun create(): File {
        check(directory.isDirectory || directory.mkdirs())
        return File.createTempFile("recording-", ".m4a", directory)
    }
    override fun uri(file: File): String = FileProvider.getUriForFile(
        context, "${context.packageName}.recordings", file,
    ).toString()
}

internal class AndroidRecordingDevice(private val context: Context) : RecordingDevice {
    private var recorder: MediaRecorder? = null
    override fun start(file: File, onError: () -> Unit) {
        @Suppress("DEPRECATION")
        val device = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = device
        device.setOnErrorListener { _, _, _ -> onError() }
        device.setAudioSource(MediaRecorder.AudioSource.MIC)
        device.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        device.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        device.setAudioChannels(1)
        device.setAudioSamplingRate(44100)
        device.setAudioEncodingBitRate(128000)
        device.setOutputFile(file.absolutePath)
        device.prepare()
        device.start()
    }
    override fun pause() = checkNotNull(recorder).pause()
    override fun resume() = checkNotNull(recorder).resume()
    override fun amplitude() = checkNotNull(recorder).maxAmplitude
    override fun stop() = checkNotNull(recorder).stop()
    override fun release() { val device = recorder; recorder = null; device?.release() }
}
