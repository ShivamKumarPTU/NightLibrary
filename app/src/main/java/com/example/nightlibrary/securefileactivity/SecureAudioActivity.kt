package com.example.nightlibrary.securefileactivity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.nightlibrary.NightLibraryApp
import com.example.nightlibrary.R
import com.example.nightlibrary.core.security.FastVaultDecryptor
import com.example.nightlibrary.core.security.SecureShareHelper
import com.example.nightlibrary.databinding.ActivitySecureAudioBinding
import com.example.nightlibrary.databinding.DialogDeletePhotoBinding
import com.example.nightlibrary.databinding.DialogRenameMediaBinding
import com.example.nightlibrary.databinding.DialogShareProgressBinding
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.security.SecureScreenManager
import com.example.nightlibrary.setting.BaseActivity
import com.example.nightlibrary.viewmodel.VaultViewModel
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
class SecureAudioActivity : BaseActivity() {

    companion object {
        private const val TAG = "SecureAudioActivity"

        fun newIntent(context: Context, id: Long) =
            Intent(context, SecureAudioActivity::class.java).putExtra("id", id)
    }
private lateinit var shareHelper: SecureShareHelper
    private var shareJob: Job? = null
    private var playbackJob: Job? = null
    private val binding by lazy { ActivitySecureAudioBinding.inflate(layoutInflater) }
    private var player: ExoPlayer? = null
    private lateinit var viewModel: VaultViewModel

    private var currentMedia: MediaEntity? = null
    private var audioPlaylist: List<MediaEntity> = emptyList()

    // Track playback state before menu was opened
    private var wasPlayingBeforeMenu = false
    // Track whether a menu item was selected (sub-dialog will handle resume)
    private var menuActionSelected = false

    private val handler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            player?.let { p ->
                if (p.isPlaying) {
                    binding.audioSeekbar.progress = p.currentPosition.toInt()
                    binding.audioCurrent.text = formatTime(p.currentPosition)
                }
                handler.postDelayed(this, 200)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureScreenManager.enable(this)
        enableEdgeToEdge()
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setupSystemUI()

        val factory = (application as NightLibraryApp).container.vaultViewModelFactory
        viewModel = ViewModelProvider(this, factory)[VaultViewModel::class.java]

        // ✅ FIX: Clean up stale share files from PREVIOUS sessions (not current)
        cleanupStaleShareFiles()

        val mediaId = intent.getLongExtra("id", -1L)
        if (mediaId == -1L) { finish(); return }

        loadPlaylistAndStart(mediaId)

        binding.btnBack.setOnClickListener {
            (application as NightLibraryApp).isIgnoringNextLock = true
            finish()
        }

        binding.btnMenu.setOnClickListener {
            wasPlayingBeforeMenu = player?.isPlaying == true
            player?.pause()
            showAudioMenu(it)
        }
shareHelper = SecureShareHelper(this)
        shareHelper.cleanupLastSharedFile(lifecycleScope)
        setupControlListeners()
    }

    private fun setupSystemUI() {
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topPad = bars.top + (8 * resources.displayMetrics.density).toInt()
            binding.headerLayout.setPadding(
                binding.headerLayout.paddingLeft, topPad,
                binding.headerLayout.paddingRight, binding.headerLayout.paddingBottom
            )
            insets
        }
    }

    // Helper to resume playback only if it was playing before menu opened
    private fun resumePlaybackIfNeeded() {
        if (wasPlayingBeforeMenu) {
            player?.play()
        }
        wasPlayingBeforeMenu = false
    }

    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIX: Clean up share files older than 5 minutes (from previous sessions)
    // NOT from current session — Gmail may still be reading them
    // ─────────────────────────────────────────────────────────────────────

    private fun cleanupStaleShareFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val shareDir = File(filesDir, "vault_share")
                if (!shareDir.exists()) return@launch

                val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)

                shareDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < fiveMinutesAgo) {
                        file.delete()
                        Log.d(TAG, "Cleaned stale share file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Share cleanup error: ${e.message}")
            }
        }
    }

    private fun loadPlaylistAndStart(startId: Long) {
        lifecycleScope.launch {
            audioPlaylist = withContext(Dispatchers.IO) {
                (application as NightLibraryApp).container.mediaRepository.getMediaByTypeOnce("audio")
            }
            val idx = audioPlaylist.indexOfFirst { it.id == startId }
            if (idx != -1) {
                playMediaAtIndex(idx)
            } else {
                val media = withContext(Dispatchers.IO) {
                    (application as NightLibraryApp).container.mediaRepository.getById(startId)
                }
                media?.let { startPlayback(it) } ?: finish()
            }
        }
    }

    private fun playMediaAtIndex(index: Int) {
        playbackJob?.cancel()
        playbackJob = null
        handler.removeCallbacks(progressRunnable)

        binding.audioSeekbar.progress = 0
        binding.audioSeekbar.max = 0
        binding.audioCurrent.text = "00:00"
        binding.audioDuration.text = "--:--"
        binding.btnPlayPause.setImageResource(R.drawable.play)

        startPlayback(audioPlaylist[index])
    }

    private fun startPlayback(media: MediaEntity) {
        playbackJob?.cancel()
        player?.release()
        player = null
        currentMedia = media

        binding.audioTitle.text = media.fileName
        binding.tvFileMeta.text = String.format(
            Locale.getDefault(),
            "%.2f MB • Secured Audio",
            media.fileSize / (1024.0 * 1024.0)
        )

        val vaultFolder = File(media.vaultFolder)
        if (!vaultFolder.exists()) {
            Toast.makeText(this, "Audio not found", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogShareProgressBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.7f)
        dialog.show()
        dialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        dialogBinding.tvShareTitle.text = "Loading Audio"
        dialogBinding.tvShareStatus.text = "Decrypting…"
        dialogBinding.shareProgressBar.progress = 0
        dialogBinding.tvSharePercentage.text = "0%"

        val tempFile = File(cacheDir, "audio_${media.id}_play.tmp")

        dialogBinding.btnCancelShare.setOnClickListener {
            playbackJob?.cancel()
            playbackJob = null
            if (tempFile.exists()) tempFile.delete()
            dialog.dismiss()
            Toast.makeText(this, "Loading cancelled", Toast.LENGTH_SHORT).show()
        }

        playbackJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (tempFile.exists()) tempFile.delete()
                    val resolved = resolveVaultFolder(media)

                    FastVaultDecryptor.decryptToFile(resolved, tempFile) { pct ->
                        withContext(Dispatchers.Main) {
                            dialogBinding.shareProgressBar.progress = pct
                            dialogBinding.tvSharePercentage.text = "$pct%"
                            dialogBinding.tvShareStatus.text = when {
                                pct < 100 -> "Decrypting… $pct%"
                                else -> "Ready!"
                            }
                        }
                    }
                }

                ensureActive()
                dialog.dismiss()

                player = ExoPlayer.Builder(this@SecureAudioActivity).build().apply {
                    setMediaItem(MediaItem.fromUri(tempFile.toURI().toString()))
                    prepare()
                    playWhenReady = false

                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            binding.btnPlayPause.setImageResource(
                                if (isPlaying) R.drawable.ic_pause
                                else R.drawable.play
                            )
                            if (isPlaying) handler.post(progressRunnable)
                            else handler.removeCallbacks(progressRunnable)
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                binding.audioSeekbar.max = duration.toInt()
                                binding.audioDuration.text = formatTime(duration)
                            }
                            if (state == Player.STATE_ENDED) {
                                tempFile.delete()
                                playNext()
                            }
                        }
                    })
                }

            } catch (e: CancellationException) {
                if (tempFile.exists()) tempFile.delete()
                withContext(Dispatchers.Main) { dialog.dismiss() }
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed: ${e.message}", e)
                if (tempFile.exists()) tempFile.delete()
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(
                        this@SecureAudioActivity,
                        "Playback failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                playbackJob = null
            }
        }
    }

    private fun setupControlListeners() {
        binding.btnPlayPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        binding.btnRew.setOnClickListener {
            player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
        }
        binding.btnFwd.setOnClickListener {
            player?.let { it.seekTo((it.currentPosition + 10_000).coerceAtMost(it.duration)) }
        }
        binding.btnPrev.setOnClickListener { playPrevious() }
        binding.btnNext.setOnClickListener { playNext() }

        binding.audioSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun playNext() {
        val idx = audioPlaylist.indexOfFirst { it.id == currentMedia?.id }
        if (idx != -1 && idx < audioPlaylist.size - 1) {
            playMediaAtIndex(idx + 1)
        } else {
            Toast.makeText(this, "End of playlist", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playPrevious() {
        val idx = audioPlaylist.indexOfFirst { it.id == currentMedia?.id }
        if (idx > 0) playMediaAtIndex(idx - 1)
       else if(idx == 0) Toast.makeText(this,"Start of Playlist", Toast.LENGTH_SHORT).show()
        else player?.seekTo(0)
    }

    private fun showAudioMenu(anchor: View) {
        val media = currentMedia ?: return
        menuActionSelected = false

        val popup = PopupMenu(ContextThemeWrapper(this, R.style.AppTheme_PopupMenu), anchor)
        popup.menuInflater.inflate(R.menu.media_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            menuActionSelected = true
            when (item.itemId) {
                R.id.action_rename_media -> { showRenameDialog(media); true }
                R.id.action_delete_media -> { showDeleteConfirmationDialog(media); true }
                R.id.action_share_media  -> { shareAudio(media); true }
                else -> false
            }
        }

        popup.setOnDismissListener {
            if (!menuActionSelected) {
                resumePlaybackIfNeeded()
            }
        }

        popup.show()
    }

    private fun showRenameDialog(media: MediaEntity) {
        val db = DialogRenameMediaBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(db.root).create()
        db.dialogTitle.text = "Rename Audio"
        db.editTextEditName.setText(media.fileName)

        db.saveChangesButton.setOnClickListener {
            val newName = db.editTextEditName.text.toString().trim()
            if (newName.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    (application as NightLibraryApp).container.mediaRepository
                        .update(media.copy(fileName = newName))
                    withContext(Dispatchers.Main) {
                        binding.audioTitle.text = newName
                        currentMedia = media.copy(fileName = newName)
                        dialog.dismiss()
                    }
                }
            }
        }
        db.cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.window?.setDimAmount(0.75f)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { resumePlaybackIfNeeded() }
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        dialog.show()
    }

    private fun showDeleteConfirmationDialog(media: MediaEntity) {
        val db = DialogDeletePhotoBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(db.root).create()
        db.dialogTitle.text = "Delete Audio?"
        db.deleteConfirmationText.text = "Permanently delete this audio?"

        db.deleteButton.setOnClickListener {
            viewModel.permanentDelete(media)
            dialog.dismiss()
            finish()
        }
        db.cancelButton.setOnClickListener { dialog.dismiss() }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.75f)
        dialog.setOnDismissListener { resumePlaybackIfNeeded() }
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        dialog.show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIX: SHARE — Gmail "Unable to Attach" Fix
    // ─────────────────────────────────────────────────────────────────────

    // Replace the entire shareAudio() method with:
    private fun shareAudio(media: MediaEntity) {
        shareHelper.share(
            media = media,
            lifecycleScope = lifecycleScope,
            dialogInflater = { createShareDialog() },
            onComplete = { resumePlaybackIfNeeded() }
        )
    }
    // Add this helper (same in all activities):
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
    override fun onResume(){
        super.onResume()
        shareHelper.cleanupLastSharedFile(lifecycleScope)
    }
    private fun resolveVaultFolder(media: MediaEntity): File {
        val raw = File(media.vaultFolder)
        if (File(raw, "full_image.enc").exists() ||
            File(raw, "chunk_0.enc").exists()
        ) return raw
        return raw
    }

    private fun formatTime(millis: Long): String {
        val s = millis / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60)
    }

    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIX: SAFE FILE NAME + CORRECT EXTENSION
    //
    // Gmail validates both the file extension AND the MIME type.
    // If the file name has no extension or has special characters,
    // Gmail will refuse to attach it.
    // ─────────────────────────────────────────────────────────────────────

    private fun createShareFile(media: MediaEntity): File {
        val shareDir = File(filesDir, "vault_share").also { it.mkdirs() }

        // ✅ FIX: DON'T delete existing files here — a previous share
        // might still be in use by Gmail/WhatsApp in the background.
        // Stale files are cleaned in cleanupStaleShareFiles() on next launch.

        val originalName = media.fileName
        val ext = originalName.substringAfterLast('.', "").lowercase()
        val baseName = originalName.substringBeforeLast('.')
            // ✅ FIX: Remove all unsafe characters
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .trim()
            .take(100)

        // ✅ FIX: Ensure a valid extension exists (Gmail requires it)
        val safeExt = when {
            ext.isNotEmpty() && ext.length <= 5 -> ext
            media.mimeType.contains("mp3") -> "mp3"
            media.mimeType.contains("mp4") -> "m4a"
            media.mimeType.contains("wav") -> "wav"
            media.mimeType.contains("ogg") -> "ogg"
            media.mimeType.contains("flac") -> "flac"
            else -> "mp3" // Default fallback
        }

        // ✅ NEW
        val timestamp = System.currentTimeMillis()
        val safeName = if (baseName.isNotEmpty()) {
            "${baseName}_${timestamp}.${safeExt}"
        } else {
            "audio_${timestamp}.${safeExt}"
        }

        Log.d(TAG, "Share file name: '$safeName' (original: '$originalName')")

        return File(shareDir, safeName)
    }

    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIX: PRECISE MIME TYPES (no wildcards)
    //
    // Gmail rejects "audio/*" — it needs a specific MIME type.
    // ─────────────────────────────────────────────────────────────────────

    private fun getShareMimeType(media: MediaEntity): String {
        // First try the stored MIME type (if it's specific enough)
        if (media.mimeType.isNotEmpty() &&
            !media.mimeType.contains("*") &&
            media.mimeType != "application/octet-stream"
        ) {
            return media.mimeType
        }

        // Infer from file extension
        val ext = media.fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "wma" -> "audio/x-ms-wma"
            "amr" -> "audio/amr"
            "3gp" -> "audio/3gpp"
            else -> "audio/mpeg" // ✅ FIX: Default to mpeg, NOT "audio/*"
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIX: DON'T delete share files in onDestroy()
    //
    // When the user taps "Gmail" in the share chooser, this Activity
    // goes to background and may be destroyed. If we delete the file
    // here, Gmail will see "unable to attach file" because the temp
    // file is gone before Gmail reads it.
    //
    // Share files are cleaned up on next launch in cleanupStaleShareFiles()
    // ─────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        handler.removeCallbacks(progressRunnable)
        playbackJob?.cancel()
        shareJob?.cancel()
        try { player?.release() } catch (_: Exception) {}
        player = null

        // ✅ Clean playback temp files (these are NOT used by other apps)
        cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("audio_") && it.name.endsWith("_play.tmp"))
                it.delete()
        }

        // ⛔ REMOVED: Do NOT delete vault_share files here!
        // Gmail/WhatsApp may still be reading the shared file.
        // File(cacheDir, "vault_share").listFiles()?.forEach { it.delete() }

        super.onDestroy()
    }
}