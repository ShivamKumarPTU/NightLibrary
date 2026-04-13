package com.example.nightlibrary

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.nightlibrary.core.security.SecurityManager
import com.example.nightlibrary.di.AppContainer
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import com.example.nightlibrary.security.TempFileGuard
import com.example.nightlibrary.setting.FloatingLauncherService
import com.example.nightlibrary.setting.VaultSessionManager
import com.example.nightlibrary.worker.TrashCleanupWorker
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NightLibraryApp : Application() {

    lateinit var container: AppContainer
    private lateinit var preferences: SecurityPreferenceManager
    var isIgnoringNextLock = false

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    if (!isIgnoringNextLock) {
                        VaultSessionManager.lock()
                    }
                    isIgnoringNextLock = false
                }
            }
        )

        preferences = SecurityPreferenceManager(this)
        TempFileGuard(this).cleanOrphanedTempFiles()
        restoreFloatingState()
        TrashCleanupWorker.enqueue(this)

        val isSafe = SecurityManager.performStartupChecks(this)

        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = YoutubeDL.getInstance().updateYoutubeDL(this@NightLibraryApp)
                    Log.d("NightLibraryApp", "yt-dlp update status: ${result?.name}")
                } catch (e: Exception) {
                    Log.e("NightLibraryApp", "Failed to update yt-dlp", e)
                }
            }
        } catch (_: YoutubeDLException) {
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (!isSafe) {
            throw SecurityException("Security violation detected")
        }

        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            clearTempCache()
        }
    }

    private fun clearTempCache() {
        cacheDir.deleteRecursively()
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
}
