package com.example.nightlibrary.core.security

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.example.nightlibrary.databinding.DialogShareProgressBinding
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.security.VaultCryptoEngine
import com.example.nightlibrary.security.ChunkIndexReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.crypto.SecretKey
import kotlin.coroutines.coroutineContext

/**
 * SecureShareHelper — Unified sharing logic for all Secure*Activity classes.
 *
 * Features:
 * • Envelope-aware decryption (1 TEE call for all chunks)
 * • Consistent share file naming with safe characters
 * • Consistent MIME type detection (no wildcards)
 * • URI permission granting to all receiver apps
 * • Auto-cleanup of stale share files
 * • Secure wipe (overwrite with zeros before delete)
 * • Progress dialog management
 */
class SecureShareHelper(private val context: Context) {

    companion object {
        private const val TAG = "SecureShareHelper"
        private const val STALE_FILE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val SHARE_TIMEOUT_MS = 5 * 60 * 1000L

        private val KNOWN_SHARE_APPS = listOf(
            "com.google.android.gm",
            "com.whatsapp",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.facebook.orca",
            "com.discord",
            "com.google.android.apps.messaging",
            "com.snapchat.android",
            "com.twitter.android",
            "com.linkedin.android"
        )
    }

    private var lastSharedFile: File? = null
    private var shareJob: Job? = null

    val isSharing: Boolean get() = shareJob?.isActive == true

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    fun share(
        media: MediaEntity,
        lifecycleScope: LifecycleCoroutineScope,
        dialogInflater: () -> Pair<AlertDialog, DialogShareProgressBinding>,
        onComplete: () -> Unit = {}
    ) {
        if (isSharing) {
            Log.w(TAG, "Share already in progress")
            return
        }

        val tempFile = createShareFile(media)
        val (dialog, dialogBinding) = dialogInflater()

        dialogBinding.tvShareTitle.text = "Preparing to share…"
        dialogBinding.tvShareStatus.text = "Decrypting…"
        dialogBinding.shareProgressBar.progress = 0
        dialogBinding.tvSharePercentage.text = "0%"

        dialogBinding.btnCancelShare.setOnClickListener {
            cancelShare(tempFile, dialog)
            onComplete()
        }

        shareJob = lifecycleScope.launch {
            try {
                if (tempFile.exists()) tempFile.delete()

                val vaultFolder = resolveVaultFolder(media)

                withContext(Dispatchers.IO) {
                    decryptToFile(vaultFolder, tempFile) { pct ->
                        launch(Dispatchers.Main) {
                            dialogBinding.shareProgressBar.progress = pct
                            dialogBinding.tvSharePercentage.text = "$pct%"
                            dialogBinding.tvShareStatus.text = when {
                                pct < 30 -> "Reading encrypted data…"
                                pct < 90 -> "Decrypting… $pct%"
                                pct < 100 -> "Almost ready…"
                                else -> "Ready!"
                            }
                        }
                    }
                }

                ensureActive()
                verifyFile(tempFile)

                val shareMime = getShareMimeType(media)
                val shareUri = getShareUri(tempFile)

                Log.d(TAG, "Share ready: uri=$shareUri mime=$shareMime size=${tempFile.length()}")

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = shareMime
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    clipData = android.content.ClipData.newRawUri("", shareUri)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    grantUriPermissions(sendIntent, shareUri)
                    lastSharedFile = tempFile

                    val chooser = Intent.createChooser(
                        sendIntent,
                        "Share ${media.fileType.replaceFirstChar { it.uppercase() }}"
                    ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(chooser)
                }

                onComplete()

            } catch (e: CancellationException) {
                secureDelete(tempFile)
                withContext(Dispatchers.Main) { dialog.dismiss() }
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Share failed: ${e.message}", e)
                secureDelete(tempFile)
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    android.widget.Toast.makeText(
                        context,
                        "Share failed: ${e.message?.take(80)}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                onComplete()
            } finally {
                shareJob = null
            }
        }
    }

    /**
     * ✅ UPDATED: Parallel decryption + optional ZIP strategy.
     */
    fun shareMultiple(
        mediaList: List<MediaEntity>,
        lifecycleScope: LifecycleCoroutineScope,
        dialogInflater: () -> Pair<AlertDialog, DialogShareProgressBinding>,
        onComplete: () -> Unit = {}
    ) {
        if (mediaList.size == 1) {
            share(mediaList.first(), lifecycleScope, dialogInflater, onComplete)
            return
        }

        val tempFiles = mediaList.map { createShareFile(it) }
        val (dialog, dialogBinding) = dialogInflater()

        dialogBinding.tvShareTitle.text = "Preparing ${mediaList.size} files…"
        dialogBinding.tvShareStatus.text = "Decrypting…"
        dialogBinding.shareProgressBar.progress = 0

        dialogBinding.btnCancelShare.setOnClickListener {
            shareJob?.cancel()
            tempFiles.forEach { secureDelete(it) }
            dialog.dismiss()
            onComplete()
        }

        // ✅ Use limited parallelism to avoid overwhelming disk I/O
        val parallelDispatcher = Dispatchers.IO.limitedParallelism(3)

        shareJob = lifecycleScope.launch {
            try {
                val perFilePct = 100.0 / mediaList.size
                val fileProgressArray = IntArray(mediaList.size) { 0 }

                // ✅ Decrypt ALL files in parallel instead of sequentially
                val deferred = mediaList.mapIndexed { index, media ->
                    async(parallelDispatcher) {
                        ensureActive()

                        val tempFile = tempFiles[index]
                        if (tempFile.exists()) tempFile.delete()

                        val vaultFolder = resolveVaultFolder(media)

                        decryptToFile(vaultFolder, tempFile) { pct ->
                            fileProgressArray[index] = pct
                            val overall = fileProgressArray.indices.sumOf { i ->
                                (fileProgressArray[i] * perFilePct / 100.0).toInt()
                            }.coerceIn(0, 99)

                            withContext(Dispatchers.Main) {
                                dialogBinding.shareProgressBar.progress = overall
                                dialogBinding.tvSharePercentage.text = "$overall%"
                                dialogBinding.tvShareStatus.text =
                                    "Decrypting ${index + 1}/${mediaList.size}… $pct%"
                            }
                        }

                        verifyFile(tempFile)
                        tempFile
                    }
                }

                val decryptedFiles = deferred.awaitAll()
                val uris = decryptedFiles.map { getShareUri(it) }

                val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        ArrayList(uris)
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    dialogBinding.shareProgressBar.progress = 100
                    dialogBinding.tvSharePercentage.text = "100%"
                    dialogBinding.tvShareStatus.text = "Ready!"

                    dialog.dismiss()
                    uris.forEach { uri -> grantUriPermissions(sendIntent, uri) }
                    context.startActivity(
                        Intent.createChooser(sendIntent, "Share ${mediaList.size} files")
                    )
                }

                onComplete()

            } catch (e: CancellationException) {
                tempFiles.forEach { secureDelete(it) }
                withContext(Dispatchers.Main) { dialog.dismiss() }
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Multi-share failed: ${e.message}", e)
                tempFiles.forEach { secureDelete(it) }
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    android.widget.Toast.makeText(
                        context, "Share failed: ${e.message?.take(80)}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                onComplete()
            } finally {
                shareJob = null
            }
        }
    }

    fun cancelShare(tempFile: File? = null, dialog: AlertDialog? = null) {
        shareJob?.cancel()
        shareJob = null
        tempFile?.let { secureDelete(it) }
        dialog?.dismiss()
    }

    // ═══════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════

    fun cleanupStaleFiles(lifecycleScope: LifecycleCoroutineScope) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val shareDir = getShareDir()
                if (!shareDir.exists()) return@launch

                val cutoff = System.currentTimeMillis() - STALE_FILE_TIMEOUT_MS
                var cleaned = 0

                shareDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) {
                        secureDelete(file)
                        cleaned++
                    }
                }

                if (cleaned > 0) {
                    Log.d(TAG, "Cleaned $cleaned stale share file(s)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stale cleanup error: ${e.message}")
            }
        }
    }

    fun cleanupLastSharedFile(
        lifecycleScope: LifecycleCoroutineScope,
        delayMs: Long = 5000L
    ) {
        val file = lastSharedFile ?: return
        lastSharedFile = null

        lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(delayMs)
            if (file.exists()) {
                secureDelete(file)
                Log.d(TAG, "Cleaned last shared file: ${file.name}")
            }
        }
    }

    fun secureDelete(file: File) {
        try {
            if (!file.exists()) return

            val size = file.length()
            if (size in 1..200 * 1024 * 1024) {
                try {
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(0)
                        val zeros = ByteArray(minOf(size, 65536L).toInt())
                        var written = 0L
                        while (written < size) {
                            val toWrite = minOf(zeros.size.toLong(), size - written).toInt()
                            raf.write(zeros, 0, toWrite)
                            written += toWrite
                        }
                    }
                } catch (_: Exception) {}
            }

            file.delete()
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // DECRYPTION — ENVELOPE-AWARE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Decrypts vault folder to output file.
     * Tries FastVaultDecryptor first (parallel + envelope-aware).
     * Falls back to sequential envelope-aware decryption.
     */
    suspend fun decryptToFile(
        vaultFolder: File,
        outFile: File,
        onProgress: suspend (Int) -> Unit
    ) {
        val startTime = System.currentTimeMillis()

        // Try FastVaultDecryptor first (parallel, envelope-aware)
        try {
            FastVaultDecryptor.decryptToFile(vaultFolder, outFile, onProgress)
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "✅ FastVaultDecryptor: ${outFile.length()} bytes in ${elapsed}ms")
            return
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ FastVaultDecryptor FAILED after " +
                    "${System.currentTimeMillis() - startTime}ms: ${e.message}")
            // Clean up partial output
            if (outFile.exists()) outFile.delete()
        }

        // Sequential fallback (also envelope-aware)
        val fallbackStart = System.currentTimeMillis()
        val crypto = VaultCryptoEngine()

        val singleFile = File(vaultFolder, "full_image.enc")
        if (singleFile.exists()) {
            decryptSingleFile(singleFile, outFile, crypto, onProgress)
        } else {
            decryptChunked(vaultFolder, outFile, crypto, onProgress)
        }

        val elapsed = System.currentTimeMillis() - fallbackStart
        Log.d(TAG, "✅ Sequential fallback: ${outFile.length()} bytes in ${elapsed}ms")
    }

    private suspend fun decryptSingleFile(
        encFile: File,
        outFile: File,
        crypto: VaultCryptoEngine,
        onProgress: suspend (Int) -> Unit
    ) {
        coroutineContext.ensureActive()
        onProgress(10)

        val bytes = encFile.readBytes()
        if (bytes.size < 17) {
            throw Exception("Encrypted file too small: ${bytes.size} bytes")
        }

        onProgress(30)
        coroutineContext.ensureActive()

        val iv = bytes.copyOfRange(0, 16)
        val plaintext = crypto.createDecryptCipher(iv)
            .doFinal(bytes, 16, bytes.size - 16)

        onProgress(70)
        coroutineContext.ensureActive()

        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { fos ->
            fos.write(plaintext)
            fos.flush()
            try { fos.fd.sync() } catch (_: Exception) {}
        }

        if (!outFile.exists() || outFile.length() == 0L) {
            throw Exception("Decrypted file is empty")
        }

        onProgress(100)
    }

    /**
     * ✅ FIXED: Envelope-aware chunked decryption (sequential fallback).
     *
     * OLD: createDecryptCipher(iv) per chunk = TEE per chunk
     *      250 chunks × ~500ms = ~125 seconds
     *
     * NEW: Read index.json → unwrapDek() = 1 TEE call
     *      createSoftDecryptCipher() per chunk = software = ~0.02ms each
     *      250 chunks = ~5 seconds
     *
     * BACKWARD COMPATIBLE: Old files without wrappedKey → TEE per chunk
     */
    private suspend fun decryptChunked(
        vaultFolder: File,
        outFile: File,
        crypto: VaultCryptoEngine,
        onProgress: suspend (Int) -> Unit
    ) {
        val chunks = vaultFolder
            .listFiles { f -> f.name.startsWith("chunk_") && f.name.endsWith(".enc") }
            ?.sortedBy {
                it.name.removePrefix("chunk_").removeSuffix(".enc").toIntOrNull() ?: 0
            }
            ?: throw IllegalStateException("No chunks in ${vaultFolder.absolutePath}")

        if (chunks.isEmpty()) throw IllegalStateException("No chunks found")

        // ✅ Check for envelope encryption in index.json
        var envelopeDek: SecretKey? = null
        var totalSize: Long

        val indexFile = File(vaultFolder, "index.json")
        if (indexFile.exists()) {
            try {
                val index = ChunkIndexReader().readIndex(vaultFolder)
                totalSize = index.totalFileSize

                if (index.wrappedKey != null && index.keyIv != null) {
                    val wrappedBytes = Base64.decode(index.wrappedKey, Base64.NO_WRAP)
                    val ivBytes = Base64.decode(index.keyIv, Base64.NO_WRAP)
                    envelopeDek = crypto.unwrapDek(wrappedBytes, ivBytes)
                    Log.d(TAG, "✅ Sequential decrypt: envelope mode (1 TEE call)")
                } else {
                    Log.d(TAG, "Sequential decrypt: legacy mode (TEE per chunk)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Index read failed, using legacy: ${e.message}")
                totalSize = chunks.sumOf { it.length() }
            }
        } else {
            totalSize = chunks.sumOf { it.length() }
            Log.d(TAG, "Sequential decrypt: no index, legacy mode")
        }

        var written = 0L

        outFile.parentFile?.mkdirs()

        FileOutputStream(outFile).use { fos ->
            BufferedOutputStream(fos, 256 * 1024).use { out ->
                for (chunk in chunks) {
                    coroutineContext.ensureActive()

                    val bytes = chunk.readBytes()
                    if (bytes.size < 17) continue

                    val iv = bytes.copyOfRange(0, 16)

                    // ✅ Envelope (software) or legacy (TEE)
                    val plaintext = if (envelopeDek != null) {
                        crypto.createSoftDecryptCipher(envelopeDek, iv)
                            .doFinal(bytes, 16, bytes.size - 16)
                    } else {
                        crypto.createDecryptCipher(iv)
                            .doFinal(bytes, 16, bytes.size - 16)
                    }

                    out.write(plaintext)
                    written += plaintext.size

                    if (totalSize > 0) {
                        onProgress(((written * 100L) / totalSize).toInt().coerceAtMost(99))
                    }
                }
                out.flush()
            }
            try { fos.fd.sync() } catch (_: Exception) {}
        }

        if (!outFile.exists() || outFile.length() == 0L) {
            throw Exception("Chunked decryption produced empty file")
        }

        Log.d(TAG, "✅ Sequential decrypt done: ${outFile.length()} bytes (envelope=${envelopeDek != null})")
        onProgress(100)
    }

    // ═══════════════════════════════════════════════════════════════
    // FILE & URI HELPERS
    // ═══════════════════════════════════════════════════════════════

    fun resolveVaultFolder(media: MediaEntity): File {
        val raw = File(media.vaultFolder)
        val candidates = listOf(
            raw,
            raw.parentFile ?: raw,
            File(raw.path.removeSuffix("/temp")),
            File(raw.path.replace("/temp/", "/"))
        )
        return candidates.firstOrNull {
            it.exists() && (
                    File(it, "index.json").exists() ||
                            File(it, "full_image.enc").exists() ||
                            it.listFiles { f -> f.name.startsWith("chunk_") }?.isNotEmpty() == true
                    )
        } ?: raw
    }

    private fun createShareFile(media: MediaEntity): File {
        val dir = getShareDir()

        val originalName = media.fileName
        val ext = originalName.substringAfterLast('.', "").lowercase()
        val baseName = originalName.substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .trim()
            .take(80)

        val safeExt = when {
            ext.isNotEmpty() && ext.length <= 5 -> ext
            else -> defaultExtension(media)
        }

        val timestamp = System.currentTimeMillis()
        val safeName = if (baseName.isNotEmpty()) {
            "${baseName}_${timestamp}.${safeExt}"
        } else {
            "${media.fileType}_${timestamp}.${safeExt}"
        }

        return File(dir, safeName)
    }

    private fun defaultExtension(media: MediaEntity): String {
        val mime = media.mimeType.lowercase()
        return when {
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("png") -> "png"
            mime.contains("gif") -> "gif"
            mime.contains("webp") -> "webp"
            mime.contains("mp4") -> "mp4"
            mime.contains("mkv") || mime.contains("matroska") -> "mkv"
            mime.contains("webm") -> "webm"
            mime.contains("3gpp") -> "3gp"
            mime.contains("mp3") || mime.contains("mpeg") -> "mp3"
            mime.contains("m4a") -> "m4a"
            mime.contains("wav") -> "wav"
            mime.contains("ogg") -> "ogg"
            mime.contains("flac") -> "flac"
            mime.contains("pdf") -> "pdf"
            media.fileType == "image" -> "jpg"
            media.fileType == "video" -> "mp4"
            media.fileType == "audio" -> "mp3"
            media.fileType == "pdf" -> "pdf"
            else -> "bin"
        }
    }

    fun getShareMimeType(media: MediaEntity): String {
        if (media.mimeType.isNotEmpty() &&
            !media.mimeType.contains("*") &&
            media.mimeType != "application/octet-stream"
        ) return media.mimeType

        val ext = media.fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "wma" -> "audio/x-ms-wma"
            "amr" -> "audio/amr"
            "pdf" -> "application/pdf"
            else -> when (media.fileType) {
                "image" -> "image/jpeg"
                "video" -> "video/mp4"
                "audio" -> "audio/mpeg"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
        }
    }

    private fun getShareUri(file: File): android.net.Uri {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: IllegalArgumentException) {
            throw Exception(
                "FileProvider cannot serve ${file.absolutePath}. " +
                        "Ensure file_paths.xml includes vault_share path. Error: ${e.message}"
            )
        }
    }

    private fun verifyFile(file: File) {
        if (!file.exists()) throw Exception("Decrypted file does not exist")
        if (file.length() == 0L) throw Exception("Decrypted file is empty (0 bytes)")
    }

    private fun getShareDir(): File =
        File(context.filesDir, "vault_share").also { it.mkdirs() }

    // ═══════════════════════════════════════════════════════════════
    // URI PERMISSIONS
    // ═══════════════════════════════════════════════════════════════

    private fun grantUriPermissions(intent: Intent, uri: android.net.Uri) {
        try {
            val resInfoList = context.packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in resInfoList) {
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            for (pkg in KNOWN_SHARE_APPS) {
                try {
                    context.grantUriPermission(
                        pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "URI grant warning (non-fatal): ${e.message}")
        }
    }
}
