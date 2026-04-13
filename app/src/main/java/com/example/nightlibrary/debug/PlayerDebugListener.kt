package com.example.nightlibrary.debug

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

class PlayerDebugListener : Player.Listener {

    override fun onPlaybackStateChanged(state: Int) {

        val stateName = when(state){
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }

        VaultLogger.d("Player state=$stateName")
    }

    override fun onPlayerError(error: PlaybackException) {
        VaultLogger.e("Player error = ${error.message}")
    }

}