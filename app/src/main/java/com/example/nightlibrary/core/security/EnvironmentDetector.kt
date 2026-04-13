package com.example.nightlibrary.core.security

import android.os.Build
import com.example.nightlibrary.debug.VaultLogger

object EnvironmentDetector {

    fun isDebugBuild(): Boolean {
        val debug = com.example.nightlibrary.BuildConfig.DEBUG
       // VaultLogger.d("EnvDetector", "Debug build: $debug")
        return debug
    }

    fun isEmulator(): Boolean {
        val emulator = (
                Build.FINGERPRINT.contains("generic") ||
                        Build.MODEL.contains("Emulator") ||
                        Build.MODEL.contains("Android SDK built for x86")
                )

      //  VaultLogger.d("EnvDetector", "Emulator detected: $emulator")
        return emulator
    }
}