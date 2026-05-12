package com.example.nightlibrary.securefileactivity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.nightlibrary.NightLibraryApp
import com.example.nightlibrary.R
import com.example.nightlibrary.core.security.SecureShareHelper
import com.example.nightlibrary.core.security.VaultFileManager
import com.example.nightlibrary.databinding.ActivitySecurePdfBinding
import com.example.nightlibrary.databinding.DialogDeletePhotoBinding
import com.example.nightlibrary.databinding.DialogRenameMediaBinding
import com.example.nightlibrary.databinding.DialogShareProgressBinding
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.security.SecureScreenManager
import com.example.nightlibrary.security.VaultCryptoEngine
import com.example.nightlibrary.setting.BaseActivity
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.github.barteksc.pdfviewer.PDFView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

class SecurePdfActivity : BaseActivity() {

    companion object {
        private const val TAG = "SecurePdfActivity"

        fun newIntent(context: Context, id: Long) =
            Intent(context, SecurePdfActivity::class.java).putExtra("id", id)
    }
    private lateinit var shareHelper: SecureShareHelper
    private val binding by lazy { ActivitySecurePdfBinding.inflate(layoutInflater) }
    private lateinit var viewModel: VaultViewModel
    private var currentMedia: MediaEntity? = null
    private var isHeaderVisible = true
    private var cacheFile: File? = null

    // ── Cancellable jobs ─────────────────────────────────────────────────
    private var loadJob: Job? = null
    private var shareJob: Job? = null

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureScreenManager.enable(this)
        enableEdgeToEdge()
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val wic = WindowCompat.getInsetsController(window, window.decorView)
        wic.hide(WindowInsetsCompat.Type.systemBars())
        wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        val factory = (application as NightLibraryApp).container.vaultViewModelFactory
        viewModel = ViewModelProvider(this, factory)[VaultViewModel::class.java]
        cleanupStaleShareFiles()
        binding.btnBack.setOnClickListener {
            (application as NightLibraryApp).isIgnoringNextLock = true
            finish()
        }
        binding.btnMenu.setOnClickListener { view ->
            currentMedia?.let { showPdfMenu(view, it) }
        }
        shareHelper = SecureShareHelper(this)
        shareHelper.cleanupStaleFiles(lifecycleScope)
        cleanupPdfCache()
        val mediaId = intent.getLongExtra("id", -1)
        if (mediaId != -1L) loadSecurePdf(mediaId) else finish()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Load PDF — Custom progress dialog + cancel
    // ────────────────────────────────────────────────────────────────────────

    private fun loadSecurePdf(mediaId: Long) {
        loadJob?.cancel()

        // 1. Inflate custom dialog
        val dialogBinding = DialogShareProgressBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.7f)

        dialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )


        dialog.show()

        // 2. Initialize UI
        dialogBinding.tvShareTitle.text = "Loading Document"
        dialogBinding.tvShareStatus.text = "Decrypting PDF…"
        dialogBinding.shareProgressBar.progress = 0
        dialogBinding.tvSharePercentage.text = "0%"

        // 3. Cancel button
        dialogBinding.btnCancelShare.setOnClickListener {
            loadJob?.cancel()
            loadJob = null
            dialog.dismiss()
            Toast.makeText(this, "Loading cancelled", Toast.LENGTH_SHORT).show()
            finish()
        }

        // 4. Launch cancellable decryption
        loadJob = lifecycleScope.launch {
            try {
                val media = withContext(Dispatchers.IO) {
                    (application as NightLibraryApp).container.mediaRepository.getById(mediaId)
                } ?: run {
                    Log.e(TAG, "Media not found id=$mediaId")
                    dialog.dismiss()
                    finish()
                    return@launch
                }

                currentMedia = media
                binding.tvPdfName.text = media.fileName
                binding.tvPdfMeta.text = "${media.fileSize / 1024 / 1024} MB • Secured"

                val vaultFolder = resolveVaultFolder(media)

                // Fast decrypt with progress
                val decryptedBytes = withContext(Dispatchers.IO) {
                    decryptPdfWithProgress(vaultFolder) { pct ->
                        launch(Dispatchers.Main) {
                            dialogBinding.shareProgressBar.progress = pct
                            dialogBinding.tvSharePercentage.text = "$pct%"
                            dialogBinding.tvShareStatus.text = when {
                                pct < 100 -> "Decrypting PDF… $pct%"
                                else -> "Rendering…"
                            }
                        }
                    }
                }

                ensureActive()

                /*
                // Integrity check
                if (media.checksum.isNotBlank()) {
                    val ok = withContext(Dispatchers.IO) {
                        com.example.nightlibrary.security.IntegrityVerifier.verify(decryptedBytes, media.checksum)
                    }
                    if (!ok) {
                        Log.e(TAG, "Security Alert: Decrypted file integrity failed for mediaId=$mediaId")
                        dialog.dismiss()
                        Toast.makeText(
                            this@SecurePdfActivity,
                            "Security Alert: File tampered or corrupted",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                        return@launch
                    }
                }
                */

                // Update dialog before rendering
                dialogBinding.tvShareStatus.text = "Rendering PDF…"
                dialogBinding.shareProgressBar.progress = 100
                dialogBinding.tvSharePercentage.text = "100%"

                Log.d(TAG, "Decoded ${decryptedBytes.size} bytes for PDF, loading…")

                val cacheDir = File(cacheDir, "pdf_cache").apply { mkdirs() }
                val pdfFile = File(cacheDir, "temp_${System.currentTimeMillis()}.pdf")
                pdfFile.writeBytes(decryptedBytes)
                cacheFile = pdfFile

                binding.pdfView.fromFile(pdfFile)
                    .enableSwipe(true)
                    .swipeHorizontal(false)
                    .enableDoubletap(true)
                    .defaultPage(0)
                    .onTap { _ ->
                        toggleHeader()
                        true
                    }
                    .load()

                dialog.dismiss()

            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) { dialog.dismiss() }

            } catch (e: Exception) {
                Log.e(TAG, "loadSecurePdf failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(
                        this@SecurePdfActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } finally {
                loadJob = null
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Share PDF — Custom progress dialog + cancel
    // ────────────────────────────────────────────────────────────────────────



    // ────────────────────────────────────────────────────────────────────────
    // Fast decryption helpers
    // ────────────────────────────────────────────────────────────────────────
// Replace sharePdf() with:
    private fun sharePdf(media: MediaEntity) {
        shareHelper.share(
            media = media,
            lifecycleScope = lifecycleScope,
            dialogInflater = { createShareDialog() }
        )
    }

    private fun createShareDialog(): Pair<AlertDialog, DialogShareProgressBinding> {
        val dialogBinding = DialogShareProgressBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.7f)
        dialog.show()
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        return dialog to dialogBinding
    }

    override fun onResume() {
        super.onResume()
        if (::shareHelper.isInitialized) {
            shareHelper.cleanupLastSharedFile(lifecycleScope)
        }
    }
    /**
     *
     * FAST PDF decryption → ByteArray for PDFView.fromBytes()
     * Uses doFinal() per chunk instead of CipherInputStream for speed.
     */
    private suspend fun decryptPdfWithProgress(
        vaultFolder: File,
        onProgress: (Int) -> Unit
    ): ByteArray {
        val crypto = VaultCryptoEngine()

        // ── Single file format (images via VaultFileManager) ─────────
        val singleFile = File(vaultFolder, "full_image.enc")
        if (singleFile.exists()) {
            coroutineContext.ensureActive()
            val bytes = singleFile.readBytes()
            val iv = bytes.copyOfRange(0, 16)
            val plaintext = crypto.createDecryptCipher(iv)
                .doFinal(bytes, 16, bytes.size - 16)
            onProgress(100)
            return plaintext
        }

        // ── Chunked format (PDFs, videos, documents) ─────────────────
        val chunks = vaultFolder
            .listFiles { f -> f.name.startsWith("chunk_") && f.name.endsWith(".enc") }
            ?.sortedBy {
                it.name.removePrefix("chunk_").removeSuffix(".enc").toIntOrNull() ?: 0
            }
            ?: throw Exception("No chunks found in ${vaultFolder.absolutePath}")

        if (chunks.isEmpty()) throw Exception("Vault folder is empty")

        // ✅ FIX: Read index.json for envelope encryption
        var envelopeDek: javax.crypto.SecretKey? = null
        val indexFile = File(vaultFolder, "index.json")
        if (indexFile.exists()) {
            try {
                val indexJson = indexFile.readText()
                val wrappedKeyB64 = extractJsonString(indexJson, "wrappedKey")
                val keyIvB64 = extractJsonString(indexJson, "keyIv")

                if (wrappedKeyB64 != null && keyIvB64 != null) {
                    val wrappedBytes = android.util.Base64.decode(wrappedKeyB64, android.util.Base64.NO_WRAP)
                    val ivBytes = android.util.Base64.decode(keyIvB64, android.util.Base64.NO_WRAP)
                    envelopeDek = crypto.unwrapDek(wrappedBytes, ivBytes)
                    Log.d(TAG, "✅ PDF decrypt: envelope mode (1 TEE call)")
                } else {
                    Log.d(TAG, "PDF decrypt: legacy mode (TEE per chunk)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Index read failed, using legacy: ${e.message}")
            }
        }

        Log.d(TAG, "Decrypting ${chunks.size} chunks for PDF (envelope=${envelopeDek != null})")

        val totalSize = chunks.sumOf { it.length() }
        var written = 0L

        val baos = ByteArrayOutputStream(totalSize.toInt())

        for ((index, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()

            val bytes = chunk.readBytes()
            val iv = bytes.copyOfRange(0, 16)

            // ✅ FIX: Use envelope DEK (software) or legacy TEE key
            val plaintext = if (envelopeDek != null) {
                crypto.createSoftDecryptCipher(envelopeDek, iv)
                    .doFinal(bytes, 16, bytes.size - 16)
            } else {
                crypto.createDecryptCipher(iv)
                    .doFinal(bytes, 16, bytes.size - 16)
            }

            // Verify first chunk has PDF header
            if (index == 0) {
                val header = String(
                    plaintext.copyOfRange(0, minOf(5, plaintext.size)),
                    Charsets.US_ASCII
                )
                Log.d(TAG, "First chunk header: '$header'")
                if (!header.startsWith("%PDF")) {
                    Log.e(TAG, "⚠️ Decrypted data is NOT a valid PDF!")
                }
            }

            baos.write(plaintext)
            written += plaintext.size

            if (totalSize > 0) {
                onProgress(((written * 100L) / totalSize).toInt().coerceAtMost(99))
            }
        }

        onProgress(100)
        return baos.toByteArray()
    }

    // ── JSON helper ──────────────────────────────────────────────────
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]+)""""
        return Regex(pattern).find(json)?.groupValues?.get(1)
    }

    /**
     * Fast chunked decryption to File (for sharing).
     */
    private suspend fun decryptChunkedCancellable(
        vaultFolder: File,
        outFile: File,
        onProgress: (Int) -> Unit
    ) {
        val crypto = VaultCryptoEngine()

        // Read index.json for envelope key
        val indexFile = File(vaultFolder, "index.json")
        if (!indexFile.exists()) {
            throw IllegalStateException("index.json not found")
        }

        val indexJson = indexFile.readText()
        val wrappedKeyB64 = extractJsonString(indexJson, "wrappedKey")
            ?: throw IllegalStateException("wrappedKey not found in index.json")
        val keyIvB64 = extractJsonString(indexJson, "keyIv")
            ?: throw IllegalStateException("keyIv not found in index.json")

        val wrappedKeyBytes = android.util.Base64.decode(wrappedKeyB64, android.util.Base64.NO_WRAP)
        val keyIvBytes = android.util.Base64.decode(keyIvB64, android.util.Base64.NO_WRAP)
        val dek = crypto.unwrapDek(wrappedKeyBytes, keyIvBytes)

        val chunks = vaultFolder
            .listFiles { f -> f.name.startsWith("chunk_") && f.name.endsWith(".enc") }
            ?.sortedBy {
                it.name.removePrefix("chunk_").removeSuffix(".enc").toIntOrNull() ?: 0
            }
            ?: throw IllegalStateException("No chunks found")

        if (chunks.isEmpty()) throw IllegalStateException("No chunks found")

        val totalSize = chunks.sumOf { it.length() }
        var written = 0L

        BufferedOutputStream(FileOutputStream(outFile), 131_072).use { out ->
            for (chunk in chunks) {
                coroutineContext.ensureActive()

                val bytes = chunk.readBytes()
                val iv = bytes.copyOfRange(0, 16)
                val plaintext = crypto.createSoftDecryptCipher(dek, iv)
                    .doFinal(bytes, 16, bytes.size - 16)

                out.write(plaintext)
                written += plaintext.size

                if (totalSize > 0) {
                    onProgress(((written * 100L) / totalSize).toInt().coerceAtMost(99))
                }
            }
            out.flush()
        }
        onProgress(100)
    }
    /**
     * Fast single-file decryption to File (for sharing).
     */
    private suspend fun decryptSingleFileCancellable(
        encFile: File,
        outFile: File,
        onProgress: (Int) -> Unit
    ) {
        coroutineContext.ensureActive()

        val bytes = encFile.readBytes()
        val iv = bytes.copyOfRange(0, 16)
        val plaintext = VaultCryptoEngine()
            .createDecryptCipher(iv)
            .doFinal(bytes, 16, bytes.size - 16)

        coroutineContext.ensureActive()

        FileOutputStream(outFile).use { it.write(plaintext) }
        onProgress(100)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Vault folder resolver
    // ────────────────────────────────────────────────────────────────────────

    private fun resolveVaultFolder(media: MediaEntity): File {
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

    private fun cleanupPdfCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(cacheDir, "pdf_cache")
                if (cacheDir.exists()) {
                    cacheDir.listFiles()?.forEach { it.delete() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup PDF cache", e)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Header toggle
    // ────────────────────────────────────────────────────────────────────────

    private fun toggleHeader() {
        if (isHeaderVisible) {
            binding.headerLayout.animate()
                .alpha(0f)
                .translationY(-binding.headerLayout.height.toFloat())
                .setDuration(250)
                .withEndAction { binding.headerLayout.visibility = View.GONE }
                .start()
        } else {
            binding.headerLayout.visibility = View.VISIBLE
            binding.headerLayout.alpha = 0f
            binding.headerLayout.translationY = -binding.headerLayout.height.toFloat()
            binding.headerLayout.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }
        isHeaderVisible = !isHeaderVisible
    }

    // ────────────────────────────────────────────────────────────────────────
    // Menu
    // ────────────────────────────────────────────────────────────────────────

    private fun showPdfMenu(anchor: View, media: MediaEntity) {
        val popup = PopupMenu(ContextThemeWrapper(this, R.style.AppTheme_PopupMenu), anchor)
        popup.menuInflater.inflate(R.menu.media_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename_media -> { showRenameDialog(media); true }
                R.id.action_delete_media -> { showDeleteConfirmation(media); true }
                R.id.action_share_media  -> { sharePdf(media); true }
                else -> false
            }
        }
        popup.show()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Rename Dialog
    // ────────────────────────────────────────────────────────────────────────

    private fun showRenameDialog(media: MediaEntity) {
        val db = DialogRenameMediaBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(this).setView(db.root).create()
        db.dialogTitle.text = "Rename Document"
        db.editTextEditName.setText(media.fileName)
        db.saveChangesButton.setOnClickListener {
            val n = db.editTextEditName.text.toString().trim()
            if (n.isNotEmpty()) {
                val updated = media.copy(fileName = n)
                lifecycleScope.launch(Dispatchers.IO) {
                    (application as NightLibraryApp).container.mediaRepository.update(updated)
                    withContext(Dispatchers.Main) {
                        binding.tvPdfName.text = n
                        currentMedia = updated
                        d.dismiss()
                    }
                }
            }
        }
        db.cancelButton.setOnClickListener { d.dismiss() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.75f)
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Delete Dialog
    // ────────────────────────────────────────────────────────────────────────

    private fun showDeleteConfirmation(media: MediaEntity) {
        val db = DialogDeletePhotoBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(this).setView(db.root).create()
        db.deleteConfirmationText.text = "Permanently delete this document from your vault?"
        db.deleteButton.setOnClickListener {
            viewModel.permanentDelete(media)
            Toast.makeText(this, "Document securely wiped", Toast.LENGTH_SHORT).show()
            d.dismiss(); finish()
        }
        db.cancelButton.setOnClickListener { d.dismiss() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.75f)
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ────────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        loadJob?.cancel()
        loadJob = null
        shareJob?.cancel()
        shareJob = null
        cacheFile?.delete()
        super.onDestroy()
    }
    private fun createShareFile(media: MediaEntity): File {
        val shareDir = File(filesDir, "vault_share").also { it.mkdirs() }

        // ⛔ REMOVE THIS LINE:
        // shareDir.listFiles()?.forEach { it.delete() }

        val originalName = media.fileName
        val ext = originalName.substringAfterLast('.', "").lowercase()
        val baseName = originalName.substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .trim()
            .take(100)

        // ✅ REPLACE the safeName logic with this:
        val safeExt = when {
            ext.isNotEmpty() && ext.length <= 5 -> ext
            media.mimeType.contains("pdf") -> "pdf"
            else -> "pdf"
        }

        // ✅ NEW
        val timestamp = System.currentTimeMillis()
        val safeName = if (baseName.isNotEmpty()) {
            "${baseName}_${timestamp}.${safeExt}"
        } else {
            "document_${timestamp}.${safeExt}"
        }

        return File(shareDir, safeName)
    }
    private fun getShareMimeType(media: MediaEntity): String {
// If we already have a concrete MIME, use it
        if (media.mimeType.isNotEmpty() &&
            !media.mimeType.contains("*") &&
            media.mimeType != "application/octet-stream"
        ) {
            return media.mimeType
        }

// Derive from file extension
        val ext = media.fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "mp4"         -> "video/mp4"
            "mkv"         -> "video/x-matroska"
            "webm"        -> "video/webm"
            "mp3"         -> "audio/mpeg"
            "m4a"         -> "audio/mp4"
            "ogg"         -> "audio/ogg"
            "wav"         -> "audio/wav"
            "flac"        -> "audio/flac"
            else->"application/pdf"
        }
    }
    private fun cleanupStaleShareFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ✅ NEW
                val shareDir = File(filesDir, "vault_share")
                if (!shareDir.exists()) return@launch

                val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
                shareDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < fiveMinutesAgo) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {}
        }
    }
}