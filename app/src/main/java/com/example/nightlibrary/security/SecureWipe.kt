package com.example.nightlibrary.security

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

object SecureWipe {

    fun wipe(file: File) {

        if (!file.exists()) return

        val length = file.length()

        RandomAccessFile(file, "rw").use { raf ->
            val randomData = ByteArray(8192)

            var written = 0L
            while (written < length) {
                Random.nextBytes(randomData)
                raf.write(randomData)
                written += randomData.size
            }
        }

        file.delete()
    }
    @OptIn(UnstableApi::class)
    fun wipeVaultFolder(folder: File) {

        if (!folder.exists()) return

        folder.listFiles()?.forEach { file ->
            wipe(file)
        }

        folder.delete()

        android.util.Log.d("SecureWipe", "Vault folder wiped: ${folder.name}")
    }
}

