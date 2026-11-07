package org.cfm.catscriptor.audio.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cfm.catscriptor.domain.pipeline.AudioInput

enum class AudioProcessingPhase { IDLE, RUNNING, COMPLETED, CANCELLED, FAILED }

data class AudioProcessingState(
    val phase: AudioProcessingPhase = AudioProcessingPhase.IDLE,
    val sampleCount: Long = 0,
    val message: String? = null,
)

object AudioProcessingController {
    private val mutableState = MutableStateFlow(AudioProcessingState())
    val state: StateFlow<AudioProcessingState> = mutableState.asStateFlow()

    /** Повторный запуск до завершения очистки текущей задачи игнорируется. */
    @Synchronized
    fun start(context: Context, input: AudioInput) {
        if (mutableState.value.phase == AudioProcessingPhase.RUNNING) return
        publish(AudioProcessingState(AudioProcessingPhase.RUNNING, message = "Подготовка аудио…"))
        try {
            // Временный SAF-доступ действует в рамках задачи приложения; сервис не восстанавливается.
            context.startForegroundService(Intent(context, AudioProcessingService::class.java).apply {
                action = AudioProcessingService.ACTION_START
                data = Uri.parse(input.documentId)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (error: Exception) {
            logProcessingError("Не удалось запустить foreground service", error)
            publish(AudioProcessingState(AudioProcessingPhase.FAILED, message = "Не удалось запустить подготовку аудио"))
        }
    }

    fun cancel(context: Context) {
        if (state.value.phase != AudioProcessingPhase.RUNNING) return
        try {
            context.startService(Intent(context, AudioProcessingService::class.java).apply {
                action = AudioProcessingService.ACTION_CANCEL
            })
        } catch (error: Exception) {
            logProcessingError("Не удалось доставить отмену сервису", error)
            // Не объявляем отмену завершённой, пока сервис ещё может владеть PCM.
        }
    }

    internal fun publish(value: AudioProcessingState) { mutableState.value = value }
}
