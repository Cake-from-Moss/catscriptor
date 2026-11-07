package org.cfm.catscriptor.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.cfm.catscriptor.domain.app.PlaybackController
import org.cfm.catscriptor.domain.app.PlaybackState

class DemoPlaybackController(private val store: DemoStore) : PlaybackController {
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
    override fun load(recordingId: String) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun release() = Unit
}
