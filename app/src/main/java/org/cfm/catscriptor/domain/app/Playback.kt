package org.cfm.catscriptor.domain.app

import kotlinx.coroutines.flow.StateFlow

data class PlaybackState(
    val recordingId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val isAvailable: Boolean = false,
)

/** Плеер (#20). UI вызывает [load] при входе на экран и [release] при выходе. */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    fun load(recordingId: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun release()
}
