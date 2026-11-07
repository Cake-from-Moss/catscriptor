package org.cfm.catscriptor.audio.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.MainActivity
import org.cfm.catscriptor.audio.AndroidAudioDecoder
import org.cfm.catscriptor.domain.pipeline.AudioInput

class AudioProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cancellationMessage = "Подготовка аудио отменена"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            if (job == null) {
                AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.CANCELLED, message = cancellationMessage))
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        if (intent?.action != ACTION_START || intent.data == null) {
            if (AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) {
                AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.FAILED,
                    message = "Не удалось запустить подготовку: отсутствует аудиодокумент"))
            }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Подготовка аудио", NotificationManager.IMPORTANCE_LOW))
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            // Разрешение уведомлений не является условием запуска foreground service.
            startForeground(NOTIFICATION_ID, notification(), type)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:audioPreparation")
                .apply { acquire(6 * 60 * 60 * 1000L) }
            val input = AudioInput(intent.data.toString())
            // Входим в try/finally до возврата из onStartCommand даже при немедленном уничтожении.
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var outcome = AudioProcessingState(AudioProcessingPhase.CANCELLED, message = cancellationMessage)
                try {
                    outcome = withContext(Dispatchers.IO) {
                        DiarizedDraftRunner(AndroidAudioDecoder(this@AudioProcessingService),
                            { org.cfm.catscriptor.native.models.SherpaVoiceActivityDetector(this@AudioProcessingService) },
                            { org.cfm.catscriptor.native.models.SherpaDiarizationBackend(this@AudioProcessingService) },
                            { org.cfm.catscriptor.native.models.SherpaTranscriptionBackend(this@AudioProcessingService) },
                            org.cfm.catscriptor.domain.pipeline.DefaultUtteranceMerger(),
                            { org.cfm.catscriptor.native.models.LlamaTextCorrector.create(this@AudioProcessingService) },
                            android.os.SystemClock::elapsedRealtime,
                            logTiming = { android.util.Log.i("PipelineTiming", it) },
                        ).run(input, AudioProcessingController::publish)
                    }
                    ensureActive()
                } catch (_: CancellationException) {
                    outcome = AudioProcessingController.state.value.copy(phase = AudioProcessingPhase.CANCELLED, message = cancellationMessage)
                } catch (error: Exception) {
                    // TODO(#8): при PipelineStageException публиковать упавший этап, чтобы UI показал, какой именно.
                    logProcessingError("Ошибка декодирования аудио", error)
                    outcome = AudioProcessingController.state.value.copy(phase = AudioProcessingPhase.FAILED, message = "Не удалось обработать аудио")
                } finally {
                    // Декодер уже закрыл PCM и временные файлы; здесь нет приостанавливающих операций.
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    job = null
                    stopSelf()
                    AudioProcessingController.publish(outcome)
                }
            }
        } catch (error: Exception) {
            logProcessingError("Ошибка инициализации foreground service", error)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.FAILED, message = "Не удалось запустить подготовку аудио"))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancellationMessage = "Подготовка отменена: истекло время фоновой обработки"
        job?.cancel()
        if (job == null && AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) {
            AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.CANCELLED, message = cancellationMessage))
        }
        releaseWakeLock()
        // Системный тайм-аут требует немедленного выхода из foreground, очистка продолжается в job.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        // При активном job терминальное состояние публикуется только после очистки декодера.
        if (job == null && AudioProcessingController.state.value.phase == AudioProcessingPhase.RUNNING) {
            AudioProcessingController.publish(AudioProcessingState(AudioProcessingPhase.CANCELLED, message = cancellationMessage))
        }
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, AudioProcessingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Подготовка аудио")
            .setContentText("Выделение речи, определение говорящих и распознавание на устройстве")
            .setContentIntent(open)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(Notification.Action.Builder(null, "Отмена", cancel).build())
            .build()
    }

    companion object {
        internal const val ACTION_START = "org.cfm.catscriptor.audio.START"
        internal const val ACTION_CANCEL = "org.cfm.catscriptor.audio.CANCEL"
        private const val CHANNEL = "audio_preparation"
        private const val NOTIFICATION_ID = 1001
    }
}
