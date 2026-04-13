package com.example.nightlibrary.core.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * FIX 4 + FIX 5 — Tuned LoadControl
 *
 * Old values:
 *   minBuffer  = 4000ms   ← TOO HIGH for small audio chunks (256KB each)
 *   maxBuffer  = 15000ms
 *   startPlayback after = 500ms
 *   after rebuffer      = 1500ms
 *
 * Problem with audio (Fix 5):
 *   Audio chunks are only 256KB. At ~128kbps that's about 16 seconds of audio per
 *   chunk. But with minBuffer=4000ms ExoPlayer tries to buffer 4 seconds across
 *   multiple chunks before it starts. The ChunkedEncryptedDataSource decrypts
 *   entire chunks into memory — if a chunk boundary is hit while buffering, the
 *   stream resets and ExoPlayer re-evaluates. With mismatched chunk sizes and
 *   minBuffer, the player could get stuck in a buffer-wait loop.
 *
 * Problem with video (Fix 4):
 *   The "buffer again" behavior happened because the player was re-created on
 *   every lifecycle event (see SecureVideoActivity fix). But even with correct
 *   lifecycle, after a re-prepare the 4000ms minBuffer meant users saw a 4s
 *   delay before video resumed. Lowering startPlayback to 200ms means the first
 *   frame appears almost immediately.
 *
 * New values chosen for encrypted chunk streaming:
 *   minBuffer  = 2000ms   — enough for a smooth start without over-buffering
 *   maxBuffer  = 20000ms  — allow up to 20s ahead for long videos
 *   startAfter = 200ms    — show first frame fast
 *   rebuffer   = 500ms    — quick recovery after seek
 */
object VaultPlayerFactory {

    private const val TAG = "VaultPlayerFactory"

    @OptIn(UnstableApi::class)
    fun create(context: Context): ExoPlayer {

        Log.d(TAG, "Creating tuned vault ExoPlayer")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_000,   // minBufferMs  — start playing after 2 s of data
                20_000,  // maxBufferMs  — buffer up to 20 s ahead
                200,     // bufferForPlaybackMs  — show first frame after 200ms of data
                500      // bufferForPlaybackAfterRebufferMs — resume after seek with 500ms
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()

        Log.d(TAG, "Vault player created with tuned load control")
        return player
    }
}