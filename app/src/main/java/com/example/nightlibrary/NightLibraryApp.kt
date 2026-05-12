package com.example.nightlibrary

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.example.nightlibrary.di.AppContainer
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import com.example.nightlibrary.security.TempFileGuard
import com.example.nightlibrary.setting.FloatingLauncherService
import com.example.nightlibrary.setting.VaultSessionManager
import com.example.nightlibrary.worker.TrashCleanupWorker
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

@HiltAndroidApp
class NightLibraryApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    companion object {
        private const val TAG = "NightLibraryApp"

        // ✅ Globally accessible init state
        private val ytdlInitialized = AtomicBoolean(false)
        private val ffmpegInitialized = AtomicBoolean(false)
        private val aria2cInitialized = AtomicBoolean(false)
        private val initInProgress = AtomicBoolean(false)
        private val initMutex = Mutex()

        @JvmStatic fun isYtDlpReady(): Boolean = ytdlInitialized.get()
        @JvmStatic fun isFFmpegReady(): Boolean = ffmpegInitialized.get()
        @JvmStatic fun isAria2cReady(): Boolean = aria2cInitialized.get()
        @JvmStatic fun isInitInProgress(): Boolean = initInProgress.get()

        /**
         * 🔥 CRITICAL: Workers/Extractors call this before using YoutubeDL.
         * Returns true if ready, false if init permanently failed.
         */
        @JvmStatic
        suspend fun ensureYtDlpInitialized(app: Application): Boolean {
            if (ytdlInitialized.get()) return true
            return initMutex.withLock { initYtDlpWithRetry(app) }
        }

        /**
         * Wait up to [timeoutMs] for in-progress init to finish.
         * Used by MediaExtractor to give yt-dlp a chance instead of skipping.
         */
        @JvmStatic
        suspend fun waitForInit(timeoutMs: Long = 8_000L): Boolean {
            if (ytdlInitialized.get()) return true
            val start = System.currentTimeMillis()
            while (initInProgress.get() && System.currentTimeMillis() - start < timeoutMs) {
                delay(150)
                if (ytdlInitialized.get()) return true
            }
            return ytdlInitialized.get()
        }

        private suspend fun initYtDlpWithRetry(app: Application): Boolean {
            if (ytdlInitialized.get()) return true
            initInProgress.set(true)
            try {
                repeat(3) { attempt ->
                    try {
                        Log.d(TAG, "🔄 YoutubeDL init attempt ${attempt + 1}/3...")
                        withContext(Dispatchers.IO) {
                            YoutubeDL.getInstance().init(app)
                        }
                        ytdlInitialized.set(true)
                        Log.d(TAG, "✅ YoutubeDL initialized successfully (attempt ${attempt + 1})")

                        try {
                            withContext(Dispatchers.IO) {
                                FFmpeg.getInstance().init(app)
                            }
                            ffmpegInitialized.set(true)
                            Log.d(TAG, "✅ FFmpeg initialized successfully")
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ FFmpeg init failed (downloads still work): ${e.message}", e)
                        }

                        try {
                            withContext(Dispatchers.IO) {
                                Aria2c.getInstance().init(app)
                            }
                            aria2cInitialized.set(true)
                            Log.d(TAG, "✅ Aria2c initialized successfully")
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Aria2c init failed: ${e.message}", e)
                        }

                        return true
                    } catch (e: YoutubeDLException) {
                        Log.e(TAG, "❌ YoutubeDL init attempt ${attempt + 1}/3 failed: ${e.message}", e)
                        if (attempt < 2) delay(2000L * (attempt + 1))
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "❌ Native library missing — check ABI/extractNativeLibs!", e)
                        return false  // No point retrying
                    } catch (e: Throwable) {
                        Log.e(TAG, "❌ Unexpected init error: ${e.message}", e)
                        if (attempt < 2) delay(2000L * (attempt + 1))
                    }
                }
                Log.e(TAG, "❌❌❌ YoutubeDL FAILED after 3 attempts!")
                return false
            } finally {
                initInProgress.set(false)
            }
        }
    }

    lateinit var container: AppContainer
    private lateinit var preferences: SecurityPreferenceManager
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var isIgnoringNextLock = false
    var needsAuth = false

    override fun onCreate() {
        if (!isMainProcess()) {
            super.onCreate()
            Log.d(TAG, "Skipping initialization for non-main process: ${getProcessName()}")
            return
        }

        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    if (!isIgnoringNextLock) {
                        VaultSessionManager.lock()
                        needsAuth = true
                    }
                    isIgnoringNextLock = false
                }
            }
        )

        preferences = SecurityPreferenceManager(this)
        TempFileGuard(this).cleanOrphanedTempFiles()
        restoreFloatingState()
        TrashCleanupWorker.enqueue(this)

        // val isSafe = SecurityManager.performStartupChecks(this)

        // ✅ Background init with retry — NEVER blocks main thread
        appScope.launch {
            Log.d(TAG, "🚀 Starting YoutubeDL initialization in background...")
            val success = initMutex.withLock { initYtDlpWithRetry(this@NightLibraryApp) }

            if (success) {
                // Update yt-dlp binary in background — non-fatal if fails
                try {
                    val result = YoutubeDL.getInstance().updateYoutubeDL(this@NightLibraryApp)
                    Log.d(TAG, "yt-dlp update: ${result?.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "yt-dlp update failed (non-fatal): ${e.message}")
                }
            } else {
                Log.e(TAG, "❌ YoutubeDL UNAVAILABLE — YouTube/IG downloads disabled")
            }
        }

        /*
        if (!isSafe) {
            throw SecurityException("Security violation detected")
        }
        */

        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // ✅ SAFE cleanup — preserves yt-dlp's working files
            safeClearTempCache()
        }
    }

    /**
     * ✅ Only delete OUR temp files. NEVER touches yt-dlp/ffmpeg directories.
     */
    private fun safeClearTempCache() {
        try {
            val preserveDirs = setOf(
                "youtubedl-android",
                "ffmpeg",
                "python",
                "okhttp",
                "image_manager_disk_cache",
                "video_cache"
            )

            cacheDir.listFiles()?.forEach { file ->
                if (file.name !in preserveDirs) {
                    try {
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                    } catch (_: Exception) {}
                }
            }
            Log.d(TAG, "🧹 Safe cache cleanup — yt-dlp preserved")
        } catch (e: Exception) {
            Log.w(TAG, "Cache cleanup failed: ${e.message}")
        }
    }

    private fun restoreFloatingState() {
        if (preferences.isFloatingLauncherEnabled && Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(this, FloatingLauncherService::class.java)
                startForegroundService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isMainProcess(): Boolean {
        return getProcessName() == packageName
    }
}