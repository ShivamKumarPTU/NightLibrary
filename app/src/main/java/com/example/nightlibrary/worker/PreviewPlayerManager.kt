@file:Suppress("DEPRECATION")

package com.example.nightlibrary.worker

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
//import com.example.nightlibrary.core.security.ChunkCache
import com.example.nightlibrary.security.ChunkCache
import java.util.*

object PreviewPlayerManager : ComponentCallbacks2 {

    private const val TAG = "VaultMemoryManager"

    private const val MAX_PLAYERS = 1

    private val activePlayers = ArrayDeque<ExoPlayer>()

    fun obtain(context: Context): ExoPlayer {

        Log.d(TAG, "Creating preview player")

        return ExoPlayer.Builder(context).build()
    }

    fun register(player: ExoPlayer) {

        if (activePlayers.contains(player)) return

        activePlayers.addLast(player)

        Log.d(TAG, "Player registered. Active players=${activePlayers.size}")

        if (activePlayers.size > MAX_PLAYERS) {

            val oldest = activePlayers.removeFirst()

            Log.d(TAG, "Releasing oldest preview player")

            oldest.pause()
            oldest.clearMediaItems()
            oldest.release()
        }
    }

    fun release(player: ExoPlayer) {

        Log.d(TAG, "Releasing preview player")

        player.pause()
        player.clearMediaItems()
        player.release()

        activePlayers.remove(player)
    }

    fun releaseAll() {

        Log.d(TAG, "Releasing all preview players")

        activePlayers.forEach {

            it.pause()
            it.clearMediaItems()
            it.release()
        }

        activePlayers.clear()
    }

    /*
     MEMORY PRESSURE HANDLING
    */

    override fun onTrimMemory(level: Int) {

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {

            Log.d(TAG, "Memory running low. Clearing caches")

            releaseAll()

            ChunkCache.clear()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {

        Log.d(TAG, "System low memory triggered")

        releaseAll()

        ChunkCache.clear()
    }
}