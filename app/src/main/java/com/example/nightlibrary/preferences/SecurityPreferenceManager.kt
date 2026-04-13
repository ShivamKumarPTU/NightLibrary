package com.example.nightlibrary.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Centralized preference manager for all security settings.
 */
class SecurityPreferenceManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "vault_security_prefs"

        // Keys
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_FLOATING_LAUNCHER = "floating_launcher_enabled"
        private const val KEY_EMERGENCY_LOCK = "emergency_lock_enabled"
        private const val KEY_SILENT_MODE = "silent_mode_enabled"
        private const val KEY_INCOGNITO_MODE = "incognito_mode_enabled"
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout_ms"
        private const val KEY_SECURE_SCREENSHOTS = "secure_screenshots_enabled"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        
        // PIN & Auth Keys
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_HASHED_PIN = "hashed_pin"
        private const val KEY_LOCK_UNTIL = "lock_until"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs.edit { putBoolean(KEY_BIOMETRIC, value) }

    var isFloatingLauncherEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_LAUNCHER, false)
        set(value) = prefs.edit { putBoolean(KEY_FLOATING_LAUNCHER, value) }

    var isEmergencyLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMERGENCY_LOCK, false)
        set(value) = prefs.edit { putBoolean(KEY_EMERGENCY_LOCK, value) }

    var isSilentMode: Boolean
        get() = prefs.getBoolean(KEY_SILENT_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_SILENT_MODE, value) }

    var isIncognitoMode: Boolean
        get() = prefs.getBoolean(KEY_INCOGNITO_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_INCOGNITO_MODE, value) }

    var autoLockTimeoutMs: Long
        get() = prefs.getLong(KEY_AUTO_LOCK_TIMEOUT, 30_000L)
        set(value) = prefs.edit { putLong(KEY_AUTO_LOCK_TIMEOUT, value) }

    var isSecureScreenshotsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SECURE_SCREENSHOTS, true)
        set(value) = prefs.edit { putBoolean(KEY_SECURE_SCREENSHOTS, value) }

    var isFirstLaunchDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_LAUNCH_DONE, value) }

    // PIN & Auth Properties
    var isSetupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_COMPLETE, value) }

    var hashedPin: String?
        get() = prefs.getString(KEY_HASHED_PIN, null)
        set(value) = prefs.edit { putString(KEY_HASHED_PIN, value) }

    var lockUntil: Long
        get() = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        set(value) = prefs.edit { putLong(KEY_LOCK_UNTIL, value) }

    var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit { putInt(KEY_FAILED_ATTEMPTS, value) }

    fun resetAll() {
        prefs.edit { clear() }
    }
}
