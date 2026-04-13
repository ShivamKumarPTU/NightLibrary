package com.example.nightlibrary.core.security

import com.example.nightlibrary.debug.VaultLogger
import java.io.File

object RootDetector {

    private val knownPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk"
    )

    fun isDeviceRooted(): Boolean {
        return try {
            val rooted = knownPaths.any { File(it).exists() }
           // VaultLogger.d("RootDetector", "Root check result: $rooted")
            rooted
        } catch (e: Exception) {
         //   VaultLogger.e("RootDetector", "Root detection error", e)
            false
        }
    }
}