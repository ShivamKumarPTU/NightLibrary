package com.example.nightlibrary.securefileactivity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.nightlibrary.NightLibraryApp
import com.example.nightlibrary.R
import com.example.nightlibrary.core.security.SecureShareHelper
import com.example.nightlibrary.databinding.ActivitySecureImageBinding
import com.example.nightlibrary.databinding.DialogDeletePhotoBinding
import com.example.nightlibrary.databinding.DialogRenameMediaBinding
import com.example.nightlibrary.databinding.DialogShareProgressBinding
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.security.SecureScreenManager
import com.example.nightlibrary.security.VaultCryptoEngine
import com.example.nightlibrary.setting.BaseActivity
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

class SecureImageActivity : BaseActivity() {

    companion object {
        private const val TAG = "SecureImageActivity"

        fun newIntent(context: Context, id: Long) =
            Intent(context, SecureImageActivity::class.java).putExtra("id", id)
    }
   private lateinit var shareHelper: SecureShareHelper
    private val binding by lazy { ActivitySecureImageBinding.inflate(layoutInflater) }
    private lateinit var viewModel: VaultViewModel
    private var allImages: List<MediaEntity> = emptyList()
    private var isHeaderVisible = true

    private var shareJob: Job? = null
    private var initialLoadJob: Job? = null
    private val activeJobs = mutableMapOf<Long, Job>()

    private var loadDialog: AlertDialog? = null
    private var loadDialogBinding: DialogShareProgressBinding? = null

    private val screenWidth by lazy { resources.displayMetrics.widthPixels }
    private val screenHeight by lazy { resources.displayMetrics.heightPixels }

    private val bitmapCache: LruCache<Long, Bitmap> = run {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 5
        Log.d(TAG, "Bitmap cache: ${cacheSize / 1024}MB")
        object : LruCache<Long, Bitmap>(cacheSize) {
            override fun sizeOf(key: Long, value: Bitmap): Int =
                value.byteCount / 1024
        }
    }

    // ✅ FIX: Use filesDir instead of cacheDir — survives VaultMemoryManager cache clearing
    private val shareDir: File
        get() = File(filesDir, "vault_share").also { it.mkdirs() }

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureScreenManager.enable(this)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val wic = WindowCompat.getInsetsController(window, window.decorView)
        wic.hide(WindowInsetsCompat.Type.systemBars())
        wic.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContentView(binding.root)

        val factory =
            (application as NightLibraryApp).container.vaultViewModelFactory
        viewModel =
            ViewModelProvider(this, factory)[VaultViewModel::class.java]
        shareHelper = SecureShareHelper(this)
        shareHelper.cleanupStaleFiles(lifecycleScope)
        cleanupStaleShareFiles()

        binding.btnBack.setOnClickListener {
            (application as NightLibraryApp).isIgnoringNextLock = true
            finish()
        }
        binding.btnMenu.setOnClickListener { view ->
            val pos = binding.viewPagerImages.currentItem
            if (allImages.isNotEmpty() && pos < allImages.size) {
                showImageMenu(view, allImages[pos])
            }
        }

        binding.viewPagerImages.offscreenPageLimit = 3
        setupGallery(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newMediaId = intent.getLongExtra("id", -1L)
        if (newMediaId != -1L && allImages.isNotEmpty()) {
            val index = allImages.indexOfFirst { it.id == newMediaId }
            if (index != -1) {
                binding.viewPagerImages.setCurrentItem(index, false)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // ✅ FIX: Clean share files older than 10 minutes from filesDir
    // ────────────────────────────────────────────────────────────────────────

    private fun cleanupStaleShareFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = shareDir
                if (!dir.exists()) return@launch

                val tenMinutesAgo =
                    System.currentTimeMillis() - (10 * 60 * 1000)

                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < tenMinutesAgo) {
                        file.delete()
                        Log.d(TAG, "Cleaned stale share file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Share cleanup error: ${e.message}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Loading Dialog
    // ────────────────────────────────────────────────────────────────────────

    private fun showLoadingDialog() {
        loadDialogBinding =
            DialogShareProgressBinding.inflate(layoutInflater)
        val dlgBinding = loadDialogBinding!!

        loadDialog = AlertDialog.Builder(this)
            .setView(dlgBinding.root)
            .setCancelable(false)
            .create()

        loadDialog?.window?.setBackgroundDrawable(
            ColorDrawable(Color.TRANSPARENT)
        )
        loadDialog?.window?.setDimAmount(0.7f)
        loadDialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        loadDialog?.show()

        dlgBinding.tvShareTitle.text = "Loading Image"
        dlgBinding.tvShareStatus.text = "Decrypting…"
        dlgBinding.shareProgressBar.progress = 0
        dlgBinding.tvSharePercentage.text = "0%"

        dlgBinding.btnCancelShare.setOnClickListener {
            initialLoadJob?.cancel()
            initialLoadJob = null
            dismissLoadingDialog()
            Toast.makeText(this, "Loading cancelled", Toast.LENGTH_SHORT)
                .show()
            finish()
        }
    }

    private fun updateLoadingProgress(status: String, percent: Int) {
        loadDialogBinding?.let { dlg ->
            dlg.tvShareStatus.text = status
            dlg.shareProgressBar.progress = percent
            dlg.tvSharePercentage.text = "$percent%"
        }
    }

    private fun dismissLoadingDialog() {
        loadDialog?.dismiss()
        loadDialog = null
        loadDialogBinding = null
    }

    // ────────────────────────────────────────────────────────────────────────
    // Gallery setup
    // ────────────────────────────────────────────────────────────────────────

    private fun setupGallery(sourceIntent: Intent) {
        val startMediaId = sourceIntent.getLongExtra("id", -1)

        showLoadingDialog()

        initialLoadJob = lifecycleScope.launch {
            allImages = withContext(Dispatchers.IO) {
                (application as NightLibraryApp)
                    .container.mediaRepository.getImagesOrdered()
            }

            if (allImages.isEmpty()) {
                dismissLoadingDialog()
                finish()
                return@launch
            }

            val startIndex = if (startMediaId != -1L) {
                allImages.indexOfFirst { it.id == startMediaId }
                    .coerceAtLeast(0)
            } else 0

            withContext(Dispatchers.IO) {
                val imagesToPreload = mutableListOf(startIndex)
                for (offset in 1..3) {
                    if (startIndex + offset < allImages.size)
                        imagesToPreload.add(startIndex + offset)
                    if (startIndex - offset >= 0)
                        imagesToPreload.add(startIndex - offset)
                }

                val totalImages = imagesToPreload.size
                var decryptedCount = 0

                for (idx in imagesToPreload) {
                    coroutineContext.ensureActive()

                    val item = allImages[idx]
                    val pctBefore =
                        ((decryptedCount * 100) / totalImages)
                            .coerceAtMost(99)

                    withContext(Dispatchers.Main) {
                        val statusText = if (idx == startIndex)
                            "Decrypting current image…"
                        else
                            "Pre-loading nearby images…"
                        updateLoadingProgress(statusText, pctBefore)
                    }

                    fastDecryptToCache(item)
                    decryptedCount++

                    val pctAfter =
                        ((decryptedCount * 100) / totalImages)
                            .coerceAtMost(99)
                    withContext(Dispatchers.Main) {
                        updateLoadingProgress("Decrypting…", pctAfter)
                    }
                }

                withContext(Dispatchers.Main) {
                    updateLoadingProgress("Ready!", 100)
                }
            }

            ensureActive()

            dismissLoadingDialog()

            val adapter = SecureSwipeAdapter(allImages)
            binding.viewPagerImages.adapter = adapter
            binding.viewPagerImages.setCurrentItem(startIndex, false)

            lifecycleScope.launch(Dispatchers.IO) {
                val start = (startIndex - 7).coerceAtLeast(0)
                val end =
                    (startIndex + 7).coerceAtMost(allImages.size - 1)
                for (i in start..end) {
                    if (bitmapCache.get(allImages[i].id) == null) {
                        fastDecryptToCache(allImages[i])
                    }
                }
            }

            binding.viewPagerImages.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        maintainSlidingWindow(position)
                    }
                }
            )

            initialLoadJob = null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // FAST decryption (for display cache)
    // ────────────────────────────────────────────────────────────────────────

    private fun fastDecryptToCache(item: MediaEntity): Bitmap? {
        bitmapCache.get(item.id)?.let { return it }

        return try {
            val crypto = VaultCryptoEngine()
            val vaultFolder = File(item.vaultFolder)

            val encFile =
                File(vaultFolder, "full_image.enc").let {
                    if (it.exists()) it
                    else File(vaultFolder, "chunk_0.enc")
                }
            if (!encFile.exists()) return null

            val fileBytes = encFile.readBytes()
            val iv = fileBytes.copyOfRange(0, 16)
            val decrypted = crypto.createDecryptCipher(iv)
                .doFinal(fileBytes, 16, fileBytes.size - 16)

            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(
                decrypted, 0, decrypted.size, boundsOpts
            )

            val sampleSize = calculateInSampleSize(
                boundsOpts.outWidth, boundsOpts.outHeight,
                screenWidth, screenHeight
            )

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val bitmap = BitmapFactory.decodeByteArray(
                decrypted, 0, decrypted.size, decodeOpts
            )

            if (bitmap != null) {
                bitmapCache.put(item.id, bitmap)
                Log.d(
                    TAG,
                    "Cached ${item.id}: ${bitmap.width}x${bitmap.height} " +
                            "sample=$sampleSize (${bitmap.byteCount / 1024}KB)"
                )
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt failed for ${item.id}: ${e.message}")
            null
        }
    }

    private fun calculateInSampleSize(
        srcWidth: Int, srcHeight: Int,
        reqWidth: Int, reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfH = srcHeight / 2
            val halfW = srcWidth / 2
            while ((halfH / inSampleSize) >= reqHeight &&
                (halfW / inSampleSize) >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ────────────────────────────────────────────────────────────────────────
    // Sliding window
    // ────────────────────────────────────────────────────────────────────────

    private fun maintainSlidingWindow(centerPos: Int) {
        val windowSize = 5
        for (i in (centerPos - windowSize)..(centerPos + windowSize)) {
            if (i in allImages.indices) {
                val item = allImages[i]
                if (bitmapCache.get(item.id) == null &&
                    !activeJobs.containsKey(item.id)
                ) {
                    activeJobs[item.id] =
                        lifecycleScope.launch(Dispatchers.IO) {
                            fastDecryptToCache(item)
                            activeJobs.remove(item.id)
                        }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Adapter
    // ────────────────────────────────────────────────────────────────────────

    private inner class SecureSwipeAdapter(
        private val items: List<MediaEntity>
    ) : RecyclerView.Adapter<SecureSwipeAdapter.VH>() {

        inner class VH(val iv: PhotoView) : RecyclerView.ViewHolder(iv)

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): VH {
            val iv = PhotoView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                setOnClickListener { toggleHeader() }
            }
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.iv.scale = 1f

            val cached = bitmapCache.get(item.id)
            if (cached != null) {
                holder.iv.setImageBitmap(cached)
                return
            }

            holder.iv.setImageDrawable(null)

            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    fastDecryptToCache(item)
                }
                if (bitmap != null &&
                    holder.bindingAdapterPosition == position
                ) {
                    holder.iv.alpha = 0f
                    holder.iv.setImageBitmap(bitmap)
                    holder.iv.animate().alpha(1f).setDuration(150).start()
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    // ────────────────────────────────────────────────────────────────────────
    // Header toggle
    // ────────────────────────────────────────────────────────────────────────

    private fun toggleHeader() {
        if (isHeaderVisible) {
            binding.headerOverlay.animate()
                .alpha(0f)
                .translationY(-binding.headerOverlay.height.toFloat())
                .setDuration(250)
                .withEndAction {
                    binding.headerOverlay.visibility = View.GONE
                }
                .start()
        } else {
            binding.headerOverlay.visibility = View.VISIBLE
            binding.headerOverlay.alpha = 0f
            binding.headerOverlay.translationY =
                -binding.headerOverlay.height.toFloat()
            binding.headerOverlay.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .start()
        }
        isHeaderVisible = !isHeaderVisible
    }

    // ────────────────────────────────────────────────────────────────────────
    // Menu
    // ────────────────────────────────────────────────────────────────────────

    private fun showImageMenu(anchor: View, media: MediaEntity) {
        val popup = PopupMenu(
            ContextThemeWrapper(this, R.style.AppTheme_PopupMenu), anchor
        )
        popup.menuInflater.inflate(R.menu.media_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename_media -> {
                    showRenameDialog(media); true
                }
                R.id.action_delete_media -> {
                    showDeleteDialog(media); true
                }
                R.id.action_share_media -> {
                    shareImage(media); true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Rename
    // ────────────────────────────────────────────────────────────────────────

    private fun showRenameDialog(media: MediaEntity) {
        val db = DialogRenameMediaBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(this).setView(db.root).create()
        db.dialogTitle.text = "Rename Image"
        db.editTextEditName.setText(media.fileName)
        db.saveChangesButton.setOnClickListener {
            val n = db.editTextEditName.text.toString().trim()
            if (n.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    (application as NightLibraryApp)
                        .container.mediaRepository
                        .update(media.copy(fileName = n))
                    withContext(Dispatchers.Main) {
                        setupGallery(intent)
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
    // Delete
    // ────────────────────────────────────────────────────────────────────────

    private fun showDeleteDialog(media: MediaEntity) {
        val db = DialogDeletePhotoBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(this).setView(db.root).create()
        db.deleteConfirmationText.text =
            "Permanently delete this image from your vault?"
        db.deleteButton.setOnClickListener {
            viewModel.permanentDelete(media)
            Toast.makeText(this, "Image securely wiped", Toast.LENGTH_SHORT)
                .show()
            d.dismiss()
            finish()
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
    // ✅ FIXED SHARE — Uses filesDir (immune to cache clearing)
    // ────────────────────────────────────────────────────────────────────────
    private fun shareImage(media: MediaEntity) {
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
                            it.listFiles { f ->
                                f.name.startsWith("chunk_")
                            }?.isNotEmpty() == true
                    )
        } ?: raw
    }

    // ────────────────────────────────────────────────────────────────────────
    // Decryption helpers with flush + verify
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun decryptChunkedCancellable(
        vaultFolder: File,
        outFile: File,
        onProgress: (Int) -> Unit
    ) {
        val crypto = VaultCryptoEngine()
        val chunks = vaultFolder
            .listFiles { f ->
                f.name.startsWith("chunk_") &&
                        f.name.endsWith(".enc")
            }
            ?.sortedBy {
                it.name.removePrefix("chunk_")
                    .removeSuffix(".enc")
                    .toIntOrNull() ?: 0
            }
            ?: throw IllegalStateException(
                "No chunks found in ${vaultFolder.absolutePath}"
            )

        if (chunks.isEmpty())
            throw IllegalStateException(
                "No chunks found in ${vaultFolder.absolutePath}"
            )

        Log.d(TAG, "Decrypting ${chunks.size} chunks from ${vaultFolder.name}")

        val totalSize = chunks.sumOf { it.length() }
        var written = 0L

        outFile.parentFile?.mkdirs()

        FileOutputStream(outFile).use { fos ->
            BufferedOutputStream(fos, 131_072).use { out ->
                for (chunk in chunks) {
                    coroutineContext.ensureActive()

                    val bytes = chunk.readBytes()
                    if (bytes.size < 17) {
                        Log.w(TAG, "Skipping tiny chunk: ${chunk.name} (${bytes.size} bytes)")
                        continue
                    }

                    val iv = bytes.copyOfRange(0, 16)
                    val plaintext = crypto.createDecryptCipher(iv)
                        .doFinal(bytes, 16, bytes.size - 16)

                    out.write(plaintext)
                    written += plaintext.size

                    if (totalSize > 0) {
                        onProgress(
                            ((written * 100L) / totalSize)
                                .toInt().coerceAtMost(99)
                        )
                    }
                }
                out.flush()
            }
            fos.fd.sync() // ✅ Force to disk
        }

        if (!outFile.exists() || outFile.length() == 0L) {
            throw Exception(
                "Chunked decryption produced empty file " +
                        "(written=$written bytes, exists=${outFile.exists()})"
            )
        }

        Log.d(TAG, "Chunked decrypt done: ${outFile.length()} bytes written")
        onProgress(100)
    }

    private suspend fun decryptSingleFileCancellable(
        encFile: File,
        outFile: File,
        onProgress: (Int) -> Unit
    ) {
        coroutineContext.ensureActive()
        onProgress(10)

        val bytes = encFile.readBytes()

        if (bytes.size < 17) {
            throw Exception(
                "Encrypted file too small: ${bytes.size} bytes"
            )
        }

        onProgress(30)
        coroutineContext.ensureActive()

        val iv = bytes.copyOfRange(0, 16)
        val plaintext = VaultCryptoEngine()
            .createDecryptCipher(iv)
            .doFinal(bytes, 16, bytes.size - 16)

        onProgress(70)
        coroutineContext.ensureActive()

        outFile.parentFile?.mkdirs()

        FileOutputStream(outFile).use { fos ->
            fos.write(plaintext)
            fos.flush()
            fos.fd.sync() // ✅ Force flush to disk
        }

        // ✅ Verify
        if (!outFile.exists()) {
            throw Exception("Output file does not exist after write")
        }
        if (outFile.length() == 0L) {
            throw Exception(
                "Output file is 0 bytes after write " +
                        "(plaintext was ${plaintext.size} bytes)"
            )
        }
        if (outFile.length() != plaintext.size.toLong()) {
            throw Exception(
                "Size mismatch: wrote ${plaintext.size} bytes " +
                        "but file is ${outFile.length()} bytes"
            )
        }

        Log.d(
            TAG,
            "Single file decrypt done: ${outFile.length()} bytes " +
                    "(from ${encFile.length()} encrypted)"
        )

        onProgress(100)
    }

    // ────────────────────────────────────────────────────────────────────────
    // ✅ FIX: Create share file in filesDir (not cacheDir)
    // ────────────────────────────────────────────────────────────────────────

    private fun createShareFile(media: MediaEntity): File {
        val dir = shareDir // ← filesDir/vault_share/

        val originalName = media.fileName
        val ext = originalName
            .substringAfterLast('.', "")
            .lowercase()
        val baseName = originalName
            .substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .trim()
            .take(80)

        val safeExt = when {
            ext.isNotEmpty() && ext.length <= 5 -> ext
            media.mimeType.contains("jpeg") || media.mimeType.contains("jpg") -> "jpg"
            media.mimeType.contains("png") -> "png"
            media.mimeType.contains("gif") -> "gif"
            media.mimeType.contains("webp") -> "webp"
            else -> "jpg"
        }

        val timestamp = System.currentTimeMillis()
        val safeName = if (baseName.isNotEmpty()) {
            "${baseName}_${timestamp}.${safeExt}"
        } else {
            "image_${timestamp}.${safeExt}"
        }

        Log.d(TAG, "Share file: '$safeName' (original: '$originalName')")

        return File(dir, safeName)
    }

    // ────────────────────────────────────────────────────────────────────────
    // MIME type resolver
    // ────────────────────────────────────────────────────────────────────────

    private fun getShareMimeType(media: MediaEntity): String {
        if (media.mimeType.isNotEmpty() &&
            !media.mimeType.contains("*") &&
            media.mimeType != "application/octet-stream"
        ) {
            return media.mimeType
        }

        val ext = media.fileName
            .substringAfterLast('.', "")
            .lowercase()
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
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            else -> "image/jpeg"
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // onDestroy — do NOT delete share files
    // ────────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        shareJob?.cancel()
        shareJob = null
        initialLoadJob?.cancel()
        initialLoadJob = null
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        dismissLoadingDialog()
        super.onDestroy()
    }
}