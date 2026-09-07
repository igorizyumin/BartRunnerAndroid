package com.dougkeen.bart.data

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coordinates alarm state and owns the looping alarm player. */
class AlarmController : AutoCloseable {
    private companion object {
        const val TAG = "AlarmController"
    }

    private val _state = MutableStateFlow(AlarmState())
    val state: StateFlow<AlarmState> = _state.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    fun isRingtoneRequested(): Boolean = _state.value.ringtoneRequested

    fun isSounding(): Boolean = _state.value.sounding

    fun requestRingtone() {
        _state.value = _state.value.copy(ringtoneRequested = true)
    }

    fun consumeRingtoneRequest() {
        _state.value = _state.value.copy(ringtoneRequested = false)
    }

    fun setSounding(sounding: Boolean) {
        _state.value = _state.value.copy(sounding = sounding)
    }

    fun getMediaPlayer(): MediaPlayer? = mediaPlayer

    fun setMediaPlayer(player: MediaPlayer?) {
        if (mediaPlayer === player) {
            return
        }
        mediaPlayer?.let { release(it) }
        mediaPlayer = player
    }

    fun silence() {
        val player = mediaPlayer
        mediaPlayer = null
        _state.value = AlarmState()
        if (player != null) {
            release(player)
        }
    }

    private fun release(player: MediaPlayer) {
        try {
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
        } catch (exception: IllegalStateException) {
            Log.e(TAG, "Could not release alarm media player", exception)
        }
    }

    override fun close() {
        silence()
    }
}
