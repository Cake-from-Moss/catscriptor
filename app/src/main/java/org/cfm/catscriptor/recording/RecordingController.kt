package org.cfm.catscriptor.recording

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cfm.catscriptor.domain.app.*
import kotlin.math.log10

/** Платформенная сессия; stop обязан завершить контейнер до передачи в очередь. */
internal interface RecordingDevice {
    fun start(file: File, onError: () -> Unit)
    fun pause()
    fun resume()
    fun amplitude(): Int
    fun stop()
    fun release()
}

internal interface RecordingStorage {
    fun availableBytes(): Long
    fun create(): File
    fun uri(file: File): String
}

/** Сериализация защищает также синхронные команды UI и асинхронные ошибки MediaRecorder. */
internal class RecordingController(
    private val available: () -> Boolean,
    private val factory: () -> RecordingDevice,
    private val storage: RecordingStorage,
    private val clock: () -> Long,
    private val importFile: suspend (String, String) -> ImportResult,
    private val scope: CoroutineScope,
    private val supported: () -> Boolean = available,
) : RecorderController {
    private val lock = Any()
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    override val state = mutableState.asStateFlow()
    override val isAvailable get() = runCatching { supported() }.getOrDefault(false)
    private var session: Session? = null
    private class Session(val device: RecordingDevice, val file: File, var since: Long) {
        var elapsed = 0L
        var paused = false
        var job: Job? = null
    }

    override suspend fun start(liveText: Boolean): StartRecordingResult {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        return synchronized(lock) {
            if (session != null) return@synchronized StartRecordingResult.Unavailable
            var file: File? = null
            var device: RecordingDevice? = null
            var owned: Session? = null
            try {
                if (!available()) return@synchronized StartRecordingResult.Unavailable
                if (storage.availableBytes() < RESERVE_BYTES) return@synchronized StartRecordingResult.LowStorage(0)
                file = storage.create()
                device = factory()
                val active = Session(device, file, 0)
                owned = active
                session = active
                device.start(file) { synchronized(lock) { if (session === active) discard(active) } }
                check(session === active)
                active.since = clock()
                callerContext.ensureActive()
                mutableState.value = RecorderState.Active(0, false, -90f, -90f, true, emptyList(), emptyList())
                active.job = scope.launch {
                    while (isActive) {
                        delay(100)
                        synchronized(lock) { if (session === active) tick(active) }
                    }
                }
                StartRecordingResult.Started
            } catch (error: Exception) {
                // Владение устройством переходит сессии до start; callback мог уже освободить её.
                if (owned != null) {
                    if (session === owned) discard(owned)
                } else runCatching { device?.release() }
                file?.delete()
                if (error is CancellationException) throw error
                StartRecordingResult.Unavailable
            }
        }
    }

    override suspend fun stop(): String? {
        val file = synchronized(lock) {
            val active = session ?: return null
            session = null
            active.job?.cancel()
            mutableState.value = RecorderState.Idle
            try {
                active.device.stop()
                check(active.file.length() > 0)
                active.file
            } catch (_: Exception) {
                active.file.delete()
                null
            } finally { runCatching { active.device.release() } }
        } ?: return null
        // Завершённая запись сохраняется даже при отмене, отказе очереди или ошибке URI.
        return try {
            (importFile(storage.uri(file), file.name) as? ImportResult.Accepted)?.recordingId
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
    }

    override fun pause() = synchronized(lock) {
        val active = session ?: return@synchronized
        if (!active.paused) operate(active) {
            active.device.pause()
            active.elapsed += (clock() - active.since).coerceAtLeast(0)
            active.paused = true
            publish(active, -90f)
        }
    }

    override fun resume() = synchronized(lock) {
        val active = session ?: return@synchronized
        if (active.paused) operate(active) {
            active.device.resume()
            active.since = clock()
            active.paused = false
            publish(active, -90f)
        }
    }

    // Live-ASR здесь отсутствует: флаг не создаёт вымышленных гипотез.
    override fun setLiveText(enabled: Boolean) = Unit
    override fun addMark() = synchronized(lock) {
        val active = session ?: return@synchronized
        val state = mutableState.value as? RecorderState.Active ?: return@synchronized
        mutableState.value = state.copy(marksMs = state.marksMs + elapsed(active))
    }

    private fun elapsed(active: Session) = active.elapsed +
        if (active.paused) 0 else (clock() - active.since).coerceAtLeast(0)

    private fun tick(active: Session) = operate(active) {
        if (storage.availableBytes() < RESERVE_BYTES) {
            // Недостаток места: пытаемся финализировать контейнер, но не запускаем обработку скрытно.
            session = null
            active.job?.cancel()
            try { active.device.stop() } catch (_: Exception) { active.file.delete() }
            finally { runCatching { active.device.release() }; mutableState.value = RecorderState.Idle }
        } else {
            val peak = if (active.paused) 0 else active.device.amplitude().coerceIn(0, 32767)
            publish(active, if (peak == 0) -90f else (20 * log10(peak / 32767.0)).toFloat().coerceAtLeast(-90f))
        }
    }

    private fun publish(active: Session, db: Float) {
        val previous = mutableState.value as? RecorderState.Active ?: return
        // maxAmplitude — пик за интервал опроса, не PCM RMS. VAD нет: «Тишина» не симулируется.
        mutableState.value = previous.copy(elapsedMs = elapsed(active), paused = active.paused,
            levelDbfs = db, peakDbfs = db, speechDetected = true)
    }

    private inline fun operate(active: Session, block: () -> Unit) {
        try { block() } catch (_: Exception) { discard(active) }
    }

    private fun discard(active: Session) {
        session = null
        active.job?.cancel()
        runCatching { active.device.release() }
        active.file.delete()
        mutableState.value = RecorderState.Idle
    }

    private companion object { const val RESERVE_BYTES = 5L * 1024 * 1024 }
}
