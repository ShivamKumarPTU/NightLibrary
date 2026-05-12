package com.example.nightlibrary.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceResourceManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "DeviceResourceManager"
    }

    /**
     * 🔥 Performance 10/10: Dynamic connection scaling
     *
     * Low-end device ( < 4GB RAM)  →  Max 4 connections, 256KB buffers
     * Mid-range (4GB - 8GB RAM)   →  Max 8 connections, 512KB buffers
     * High-end ( > 8GB RAM)       →  Max 16-32 connections, 1MB buffers
     */
    fun getOptimalConnections(maxAllowed: Int = 16): Int {
        val totalRamMb = getTotalRamMb()
        val cores = Runtime.getRuntime().availableProcessors()

        val base = when {
            totalRamMb > 8000 -> 16
            totalRamMb > 4000 -> 8
            else -> 4
        }

        return base.coerceAtMost(cores * 2).coerceAtMost(maxAllowed)
    }

    fun getOptimalBufferSize(): Int {
        val totalRamMb = getTotalRamMb()
        return when {
            totalRamMb > 8000 -> 1024 * 1024 // 1MB
            totalRamMb > 4000 -> 512 * 1024  // 512KB
            else -> 256 * 1024               // 256KB
        }
    }

    private fun getTotalRamMb(): Long {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024 * 1024)
        } catch (e: Exception) {
            4000L // Fallback to 4GB
        }
    }
}
