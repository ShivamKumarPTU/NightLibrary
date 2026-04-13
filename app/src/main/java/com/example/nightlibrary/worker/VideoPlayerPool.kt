package com.example.nightlibrary.worker

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.ArrayDeque

object VideoPlayerPool {

    private const val MAX_PLAYERS = 3

    private val players = ArrayDeque<ExoPlayer>()

    @OptIn(UnstableApi::class)
    fun obtain(context: android.content.Context): ExoPlayer {

        if (players.isNotEmpty()) {
            return players.removeFirst()
        }

        return ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
    }

    fun recycle(player: ExoPlayer) {

        if (players.size >= MAX_PLAYERS) {
            player.release()
            return
        }

        player.clearMediaItems()
        player.pause()

        players.addLast(player)
    }

    fun releaseAll() {

        players.forEach {
            it.release()
        }

        players.clear()
    }
}