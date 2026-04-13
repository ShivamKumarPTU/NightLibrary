package com.example.nightlibrary.core.security

import android.os.Debug
import java.io.BufferedReader
import java.io.File

object RuntimeProtection {

    fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected()
    }

    fun isTracerDetected(): Boolean {
        return try {
            val reader = BufferedReader(File("/proc/self/status").reader())
            reader.useLines { lines ->
                lines.any { it.contains("TracerPid:") && !it.endsWith("0") }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun detectFrida(): Boolean {
        return try {
            val maps = File("/proc/self/maps").readText()
            maps.contains("frida")
        } catch (e: Exception) {
            false
        }
    }
}