package com.example.nightlibrary.security

import com.example.nightlibrary.preferences.SecurityPreferenceManager


class AuthManager(
    private val prefs: SecurityPreferenceManager
) {

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val LOCK_DURATION_MS = 30_000L
    }

    fun savePin(pin: String) {
        prefs.hashedPin = PinHasher.hash(pin)
    }

    fun isLocked(): Boolean {
       val locked = System.currentTimeMillis() < prefs.lockUntil
        if(!locked && prefs.lockUntil!=0L){
            resetAttempts()
        }
        return locked
    }

    fun getRemainingLockTime(): Long {
        return prefs.lockUntil - System.currentTimeMillis()
    }

    fun verifyPin(inputPin: String): Boolean {
        if (isLocked()) return false

        val hashedInput = PinHasher.hash(inputPin)
        val storedHash = prefs.hashedPin ?: return false

        return if (hashedInput == storedHash) {
            resetAttempts()
            true
        } else {
            handleFailedAttempt()
            false
        }
    }
    fun resetSecurityData(){
        prefs.hashedPin = null
        prefs.failedAttempts = 0
        prefs.lockUntil = 0L
    }
    private fun handleFailedAttempt() {
        prefs.failedAttempts += 1

        if (prefs.failedAttempts >= MAX_ATTEMPTS) {
            prefs.lockUntil = System.currentTimeMillis() + LOCK_DURATION_MS
        }
    }

    private fun resetAttempts() {
        prefs.failedAttempts = 0
        prefs.lockUntil = 0L
    }
    fun getRemainingAttempts(): Int{
        return MAX_ATTEMPTS - prefs.failedAttempts

    }
}