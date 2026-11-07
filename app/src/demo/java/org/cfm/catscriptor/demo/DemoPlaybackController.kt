package org.cfm.catscriptor.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cfm.catscriptor.domain.app.PlaybackController
import org.cfm.catscriptor.domain.app.PlaybackState

/**
 * Плеер демо-сборки без звука: позиция идёт по часам с учётом скорости, чтобы были видны
 * подсветка реплик, автопрокрутка и дорожка. Аудиофайлов в демо нет.
 */
class DemoPlaybackController(private val store: DemoStore) : PlaybackController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    private var ticker: Job? = null

    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun load(recordingId: String) {
        if (_state.value.recordingId == recordingId && _state.value.isAvailable) return
        stopTicker()
        val document = store.documents.value[recordingId]
        _state.value = PlaybackState(
            recordingId = recordingId,
            durationMs = document?.durationMs ?: 0,
            isAvailable = document != null,
        )
    }

    override fun play() {
        val current = _state.value
        if (!current.isAvailable || current.isPlaying) return
        // С конца записи воспроизведение начинается заново, как в системных плеерах.
        val start = if (current.positionMs >= current.durationMs) 0L else current.positionMs
        _state.value = current.copy(positionMs = start, isPlaying = true)
        ticker = scope.launch {
            var last = System.nanoTime()
            while (isActive) {
                delay(TICK_MS)
                val now = System.nanoTime()
                val elapsedMs = (now - last) / 1_000_000
                last = now
                _state.update { state ->
                    val next = state.positionMs + (elapsedMs * state.speed).toLong()
                    if (next >= state.durationMs) {
                        state.copy(positionMs = state.durationMs, isPlaying = false)
                    } else {
                        state.copy(positionMs = next)
                    }
                }
                if (!_state.value.isPlaying) break
            }
        }
    }

    override fun pause() {
        stopTicker()
        _state.update { it.copy(isPlaying = false) }
    }

    override fun seekTo(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs.coerceIn(0, it.durationMs)) }
    }

    override fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
    }

    override fun release() {
        stopTicker()
        _state.value = PlaybackState()
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        /** 10 обновлений в секунду хватает для плавной дорожки и смены реплики. */
        const val TICK_MS = 100L
    }
}
