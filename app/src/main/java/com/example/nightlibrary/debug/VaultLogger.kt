package com.example.nightlibrary.debug

import android.content.ContentValues.TAG
import android.util.Log

object VaultLogger {

    private const val GLOBAL_TAG = "NightLibrary"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun e(message: String) {
        Log.e(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }
}