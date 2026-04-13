package com.example.nightlibrary.debug

import androidx.work.WorkerParameters

object WorkerDebug {

    fun logStart(name: String) {
        VaultLogger.d("$name worker started")
    }

    fun logProgress(progress: Int) {
        VaultLogger.d("Download progress $progress%")
    }

    fun logComplete(file: String) {
        VaultLogger.d("Worker finished -> $file")
    }

}