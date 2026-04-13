package com.example.nightlibrary.setting

object VaultSessionManager {
    @Volatile
    private var unlocked = false
    var resumeDestinationId: Int? = null
    fun unlock() {
        unlocked = true
    }

    fun lock() {
        unlocked = false
    }

    fun isUnlocked(): Boolean {
        return unlocked
    }
}