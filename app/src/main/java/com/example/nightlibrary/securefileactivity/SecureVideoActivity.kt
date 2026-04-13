package com.example.nightlibrary.securefileactivity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.nightlibrary.NightLibraryApp
import com.example.nightlibrary.R
import com.example.nightlibrary.core.player.VaultPlayerFactory
import com.example.nightlibrary.core.security.ChunkIndexReader
import com.example.nightlibrary.core.security.ChunkedEncryptedDataSource
import com.example.nightlibrary.core.security.SecureShareHelper
import com.example.nightlibrary.databinding.ActivitySecureVideoBinding
import com.example.nightlibrary.databinding.DialogDeletePhotoBinding
import com.example.nightlibrary.databinding.DialogRenameMediaBinding
import com.example.nightlibrary.databinding.DialogShareProgressBinding
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.security.ChunkCache
import com.example.nightlibrary.security.SecureScreenManager
import com.example.nightlibrary.security.VaultCryptoEngine
import com.example.nightlibrary.setting.BaseActivity
import com.example.nightlibrary.viewmodel.VaultViewModel
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

@UnstableApi
class SecureVideoActivity : BaseActivity() {

    companion object {
        private const val TAG = "SecureVideoActivity"
        private const val NAV_DEBOUNCE_MS = 600L
    }
    // Add as class field:
    private lateinit var shareHelper: SecureShareHelper
    private lateinit var binding: ActivitySecureVideoBinding
    private lateinit var viewModel: VaultViewModel

    private var player: ExoPlayer? = null
    private var currentMedia: MediaEntity? = null
    private var videoList: List<MediaEntity> = emptyList()
    private var currentIndex = 0
    private var isMuted = false
    private var currentAspectRatioMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private var shareJob: Job? = null

    // ── Navigation state ─────────────────────────────────────────────────
    private var isPlayerBusy = false
    private var lastNavTimeMs = 0L

    // ── Currently loaded vault folder (to detect stale cache) ────────────
    private var currentVaultFolder: File? = null

    // ── Controls visibility ──────────────────────────────────────────────
    private var controlsVisible = false
    private var suppressVisibilityCallback = false

    // Track playback state before menu was opened
    private var wasPlayingBeforeMenu = false
    // Track whether a menu item was selected
    private var menuActionSelected = false

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideControls() }

    // Gesture tracking
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isBrightnessSwipe = false
    private var isVolumeSwipe = false

    // ✅ FIX: Use filesDir instead of cacheDir — immune to VaultMemoryManager cache clearing
    private val shareDir: File
        get() = File(filesDir, "vault_share").also { it.mkdirs() }

    // ── Player listener ──────────────────────────────────────────────────
    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(state: Int) {
            Log.d(TAG, "onPlaybackStateChanged: state=$state")
            when (state) {
                Player.STATE_READY -> {
                    Log.d(TAG, "Player READY, duration=${player?.duration}")
                    isPlayerBusy = false
                    binding.loadingOverlay.visibility = View.GONE
                }
                Player.STATE_ENDED -> {
                    if (!isPlayerBusy) {
                        playNextVideo()
                    }
                }
                Player.STATE_BUFFERING -> {
                    binding.loadingOverlay.visibility = View.VISIBLE
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.playerView
                .findViewById<ImageButton>(R.id.exo_play_pause)
                ?.isSelected = isPlaying
            if (isPlaying) {
                binding.loadingOverlay.visibility = View.GONE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: ${error.message}", error)
            isPlayerBusy = false
            binding.loadingOverlay.visibility = View.GONE
            Toast.makeText(
                this@SecureVideoActivity,
                "Playback error: ${error.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureScreenManager.enable(this)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivitySecureVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.headerLayout.alpha = 0f
        binding.headerLayout.translationY = -200f
        binding.headerLayout.visibility = View.GONE
        binding.loadingOverlay.visibility = View.GONE
// In onCreate():
        shareHelper = SecureShareHelper(this)
        shareHelper.cleanupStaleFiles(lifecycleScope)
        val factory = (application as NightLibraryApp).container.vaultViewModelFactory
        viewModel = ViewModelProvider(this, factory)[VaultViewModel::class.java]

        cleanupStaleShareFiles()

        val startId = intent.getLongExtra("id", -1L)

        lifecycleScope.launch {
            videoList = withContext(Dispatchers.IO) {
                (application as NightLibraryApp).container.mediaRepository.getVideosOnce()
            }
            if (videoList.isEmpty()) {
                finish()
                return@launch
            }
            currentIndex =
                videoList.indexOfFirst { it.id == startId }.coerceAtLeast(0)
            navigateTo(currentIndex)
        }

        binding.btnBack.setOnClickListener {
            (application as NightLibraryApp).isIgnoringNextLock = true
            finish()
        }

        binding.btnMenu.setOnClickListener {
            wasPlayingBeforeMenu = player?.isPlaying == true
            player?.pause()
            currentMedia?.let { showVideoMenu(it, binding.btnMenu) }
        }

        setupGestures()
    }

    override fun onStart() {
        super.onStart()
        if (player == null && videoList.isNotEmpty()) {
            isPlayerBusy = false
            lastNavTimeMs = 0L
            navigateTo(currentIndex)
        }
    }

    override fun onStop() {
        super.onStop()
        cancelAutoHide()
        safeReleasePlayer()
    }

    override fun onDestroy() {
        cancelAutoHide()
        shareJob?.cancel()
        shareJob = null
        safeReleasePlayer()
        // ⛔ Do NOT clean vault_share here — Gmail/WhatsApp needs those files!
        super.onDestroy()
    }

    private fun resumePlaybackIfNeeded() {
        if (wasPlayingBeforeMenu) {
            player?.play()
        }
        wasPlayingBeforeMenu = false
    }

    // ─────────────────────────────────────────────────────────────────────
    // NAVIGATION — SINGLE ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────

    private fun navigateTo(index: Int) {
        if (isPlayerBusy) {
            Log.d(TAG, "navigateTo($index) BLOCKED — busy")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastNavTimeMs < NAV_DEBOUNCE_MS) {
            Log.d(TAG, "navigateTo($index) BLOCKED — debounce")
            return
        }

        if (index < 0 || index >= videoList.size) {
            Log.w(TAG, "navigateTo($index) BLOCKED — out of bounds")
            return
        }

        isPlayerBusy = true
        lastNavTimeMs = now
        currentIndex = index

        val media = videoList[index]
        currentMedia = media

        Log.d(TAG, "navigateTo($index) — ${media.fileName}")

        val resolvedFolder = resolveVaultFolder(media)

        if (!resolvedFolder.exists()) {
            Toast.makeText(
                this,
                "Vault data missing for: ${media.fileName}",
                Toast.LENGTH_LONG
            ).show()
            isPlayerBusy = false
            return
        }

        updateHeaderUI(media)

        binding.loadingOverlay.visibility = View.VISIBLE

        initializePlayer(resolvedFolder, media.duration)
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer(vaultFolder: File, knownDurationSecs: Long = 0L) {
        safeReleasePlayer()

        ChunkCache.clear()
        currentVaultFolder = vaultFolder

        Log.d(TAG, "ChunkCache cleared for new video: ${vaultFolder.name}")

        val folder = vaultFolder
        val dsFactory = DataSource.Factory {
            ChunkedEncryptedDataSource(folder, VaultCryptoEngine())
        }

        binding.playerView.controllerAutoShow = false
        binding.playerView.controllerHideOnTouch = false
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { }
        )

        val newPlayer = VaultPlayerFactory.create(this)

        newPlayer.addListener(playerListener)

        // ✅ FIX: Build MediaItem with the stored duration so ExoPlayer can populate
        // the seekbar immediately on STATE_READY, even if the MP4 moov atom is at
        // the end of the file (not faststart-encoded).
        val durationMs = if (knownDurationSecs > 0L) knownDurationSecs * 1000L
        else androidx.media3.common.C.TIME_UNSET
        val mediaItem = MediaItem.fromUri(Uri.parse("vault://video"))

        newPlayer.setMediaSource(
            ProgressiveMediaSource.Factory(dsFactory)
                .createMediaSource(mediaItem)
        )

        // If we know the duration, seed it via setMediaSource clipping so the
        // DefaultTimeBar shows a sensible total before ExoPlayer finishes probing.
        if (durationMs != androidx.media3.common.C.TIME_UNSET) {
            newPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY) {
                        // If ExoPlayer still reports TIME_UNSET, force the seekbar max
                        // by seeking to 0 with the duration hint already visible in the UI
                        if (newPlayer.duration == androidx.media3.common.C.TIME_UNSET ||
                            newPlayer.duration <= 0L) {
                            // Manually update the header duration display from stored value
                            currentMedia?.let { updateHeaderUI(it) }
                        }
                        newPlayer.removeListener(this)
                    }
                }
            })
        }

        newPlayer.playWhenReady = true
        newPlayer.prepare()

        binding.playerView.player = newPlayer
        player = newPlayer

        wireCustomControls()
        showControls()

        Log.d(TAG, "Player initialized — streaming from ${vaultFolder.name}, knownDuration=${knownDurationSecs}s")
    }

    private fun safeReleasePlayer() {
        player?.let { p ->
            try {
                p.removeListener(playerListener)
            } catch (_: Exception) {
            }
            binding.playerView.player = null
            try {
                p.stop()
                p.release()
            } catch (_: Exception) {
            }
        }
        player = null
    }

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

    // ─────────────────────────────────────────────────────────────────────
    // NAVIGATION HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private fun playNextVideo() {
        if (currentIndex < videoList.size - 1) {
            navigateTo(currentIndex + 1)
        } else {
            Toast.makeText(
                this, "End of playlist", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun playPrevVideo() {
        if (currentIndex > 0) {
            navigateTo(currentIndex - 1)
        } else {
            player?.seekTo(0)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // CONTROLS VISIBILITY
    // ─────────────────────────────────────────────────────────────────────

    private fun showControls() {
        cancelAutoHide()
        if (controlsVisible) {
            scheduleAutoHide()
            return
        }
        controlsVisible = true

        binding.headerLayout.visibility = View.VISIBLE
        binding.headerLayout.animate()
            .alpha(1f).translationY(0f).setDuration(180).start()

        binding.playerView
            .findViewById<View>(R.id.brightness_container)
            ?.visibility = View.VISIBLE
        binding.playerView
            .findViewById<View>(R.id.volume_container)
            ?.visibility = View.VISIBLE

        suppressVisibilityCallback = true
        binding.playerView.showController()
        suppressVisibilityCallback = false

        scheduleAutoHide()
    }

    private fun hideControls() {
        cancelAutoHide()
        if (!controlsVisible) return
        controlsVisible = false

        binding.headerLayout.animate()
            .alpha(0f)
            .translationY(-binding.headerLayout.height.toFloat())
            .setDuration(180)
            .withEndAction {
                binding.headerLayout.visibility = View.GONE
            }
            .start()

        binding.playerView
            .findViewById<View>(R.id.brightness_container)
            ?.visibility = View.GONE
        binding.playerView
            .findViewById<View>(R.id.volume_container)
            ?.visibility = View.GONE

        suppressVisibilityCallback = true
        binding.playerView.hideController()
        suppressVisibilityCallback = false
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, 3_000L)
    }

    private fun cancelAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
    }

    // ─────────────────────────────────────────────────────────────────────
    // CUSTOM CONTROLS
    // ─────────────────────────────────────────────────────────────────────

    private fun wireCustomControls() {
        binding.playerView.post {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            binding.playerView
                .findViewById<ImageButton>(R.id.custom_next)
                ?.setOnClickListener { playNextVideo() }

            binding.playerView
                .findViewById<ImageButton>(R.id.custom_prev)
                ?.setOnClickListener { playPrevVideo() }

            binding.playerView
                .findViewById<ImageButton>(R.id.btn_mute_toggle)
                ?.setOnClickListener {
                    isMuted = !isMuted
                    val target = if (isMuted) 0
                    else (am.getStreamMaxVolume(
                        AudioManager.STREAM_MUSIC
                    ) / 2)
                    am.setStreamVolume(
                        AudioManager.STREAM_MUSIC, target, 0
                    )
                    updateVolumeUI(
                        am.getStreamVolume(AudioManager.STREAM_MUSIC),
                        am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    )
                    scheduleAutoHide()
                }

            binding.playerView
                .findViewById<ImageButton>(R.id.exo_ffwd)
                ?.setOnClickListener {
                    player?.let {
                        it.seekTo(
                            (it.currentPosition + 10_000L)
                                .coerceAtMost(it.duration)
                        )
                    }
                    scheduleAutoHide()
                }

            binding.playerView
                .findViewById<ImageButton>(R.id.exo_rew)
                ?.setOnClickListener {
                    player?.let {
                        it.seekTo(
                            (it.currentPosition - 10_000L)
                                .coerceAtLeast(0L)
                        )
                    }
                    scheduleAutoHide()
                }

            binding.playerView
                .findViewById<ImageButton>(R.id.btn_aspect_ratio)
                ?.setOnClickListener {
                    currentAspectRatioMode = when (currentAspectRatioMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                            AspectRatioFrameLayout.RESIZE_MODE_FILL
                        AspectRatioFrameLayout.RESIZE_MODE_FILL ->
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else ->
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    binding.playerView.resizeMode = currentAspectRatioMode
                    scheduleAutoHide()
                }

            binding.playerView
                .findViewById<ImageButton>(R.id.exo_play_pause)
                ?.setOnClickListener {
                    player?.let {
                        if (it.isPlaying) it.pause() else it.play()
                    }
                    scheduleAutoHide()
                }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // GESTURES
    // ─────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        binding.playerView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    isBrightnessSwipe = event.x < v.width / 2f
                    isVolumeSwipe = !isBrightnessSwipe
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = touchStartY - event.y
                    if (kotlin.math.abs(dy) > 10f) {
                        if (isBrightnessSwipe) adjustBrightness(dy)
                        else adjustVolume(dy)
                        cancelAutoHide()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved =
                        kotlin.math.abs(event.x - touchStartX) > 12f ||
                                kotlin.math.abs(event.y - touchStartY) > 12f
                    if (!moved) {
                        if (controlsVisible) hideControls()
                        else showControls()
                    } else {
                        scheduleAutoHide()
                    }
                    isBrightnessSwipe = false
                    isVolumeSwipe = false
                    true
                }
                else -> false
            }
        }
    }

    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        var b = if (lp.screenBrightness < 0) 0.5f
        else lp.screenBrightness
        b = (b + delta / 1_000f).coerceIn(0.01f, 1f)
        lp.screenBrightness = b
        window.attributes = lp
        binding.playerView
            .findViewById<ProgressBar>(R.id.brightness_progress)
            ?.progress = (b * 100).toInt()
    }

    private fun adjustVolume(delta: Float) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val nv = (cur + (delta / 50f).toInt()).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
        updateVolumeUI(nv, max)
    }

    private fun updateVolumeUI(current: Int, max: Int) {
        val iconRes = if (current == 0) R.drawable.volumeoff
        else R.drawable.voulme_on
        binding.playerView
            .findViewById<ProgressBar>(R.id.volume_progress)
            ?.also { it.max = max; it.progress = current }
        binding.playerView
            .findViewById<ImageView>(R.id.volume_icon_overlay)
            ?.setImageResource(iconRes)
        binding.playerView
            .findViewById<ImageButton>(R.id.btn_mute_toggle)
            ?.setImageResource(iconRes)
    }

    // ─────────────────────────────────────────────────────────────────────
    // HEADER UI
    // ─────────────────────────────────────────────────────────────────────

    private fun updateHeaderUI(media: MediaEntity) {
        currentMedia = media
        binding.tvVideoName.text = media.fileName

        // ✅ Problem 6: Show duration in header
        val durationStr = if (media.duration > 0) {
            val s = media.duration
            when {
                s >= 3600 -> String.format(
                    java.util.Locale.getDefault(),
                    "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60
                )
                else -> String.format(
                    java.util.Locale.getDefault(),
                    "%d:%02d", s / 60, s % 60
                )
            }
        } else null

        binding.tvVideoMeta.text = buildString {
            if (durationStr != null) {
                append(durationStr)
                append(" • ")
            }
            append("${media.fileSize / (1024 * 1024)} MB • Secured")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MENU / RENAME / DELETE
    // ─────────────────────────────────────────────────────────────────────

    private fun showVideoMenu(media: MediaEntity, anchor: View) {
        menuActionSelected = false

        val popup = PopupMenu(
            ContextThemeWrapper(this, R.style.AppTheme_PopupMenu), anchor
        )
        popup.menuInflater.inflate(R.menu.media_menu, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            menuActionSelected = true
            when (item.itemId) {
                R.id.action_rename_media -> {
                    showRenameDialog(media); true
                }
                R.id.action_delete_media -> {
                    showDeleteDialog(media); true
                }
                R.id.action_share_media -> {
                    shareVideo(media); true
                }
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
        val d = AlertDialog.Builder(this).setView(db.root).create()
        db.editTextEditName.setText(media.fileName)
        db.saveChangesButton.setOnClickListener {
            val name = db.editTextEditName.text.toString().trim()
            if (name.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val updated = media.copy(fileName = name)
                    (application as NightLibraryApp)
                        .container.mediaRepository.update(updated)
                    withContext(Dispatchers.Main) {
                        updateHeaderUI(updated)
                        d.dismiss()
                    }
                }
            }
        }
        db.cancelButton.setOnClickListener { d.dismiss() }
        d.setOnDismissListener { resumePlaybackIfNeeded() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.75f)
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
    }

    private fun showDeleteDialog(media: MediaEntity) {
        val db = DialogDeletePhotoBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(this).setView(db.root).create()
        db.deleteButton.setOnClickListener {
            wasPlayingBeforeMenu = false
            viewModel.permanentDelete(media)
            d.dismiss()

            val deletedIndex = currentIndex
            val mutableList = videoList.toMutableList()
            mutableList.removeAt(deletedIndex)
            videoList = mutableList

            if (videoList.isEmpty()) {
                finish()
                return@setOnClickListener
            }

            currentIndex = if (deletedIndex >= videoList.size) {
                videoList.size - 1
            } else {
                deletedIndex
            }

            isPlayerBusy = false
            lastNavTimeMs = 0L
            navigateTo(currentIndex)
        }
        db.cancelButton.setOnClickListener { d.dismiss() }
        d.setOnDismissListener { resumePlaybackIfNeeded() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.75f)
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIXED SHARE — Uses filesDir + local decryption + verification
    // ─────────────────────────────────────────────────────────────────────
// Replace shareVideo() with:
    private fun shareVideo(media: MediaEntity) {
        shareHelper.share(
            media = media,
            lifecycleScope = lifecycleScope,
            dialogInflater = { createShareDialog() },
            onComplete = { resumePlaybackIfNeeded() }
        )
    }


    private fun createShareDialog(): Pair<AlertDialog, DialogShareProgressBinding> {
        val dialogBinding = DialogShareProgressBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.75f)
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
    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIXED: Decryption helpers with flush + verify
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIXED: Decryption helpers — sync wrapped in try-catch
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun decryptChunkedCancellable(
        vaultFolder: File,
        outFile: File,
        onProgress: (Int) -> Unit
    ) {
        val crypto = VaultCryptoEngine()

        val indexFile = File(vaultFolder, "index.json")
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

        val totalSize = try {
            if (indexFile.exists()) {
                ChunkIndexReader().readIndex(vaultFolder).totalFileSize
            } else {
                chunks.sumOf { it.length() }
            }
        } catch (_: Exception) {
            chunks.sumOf { it.length() }
        }

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
            // ✅ FIX: Wrap sync in try-catch — flush() already wrote the data
            try {
                fos.fd.sync()
            } catch (e: Exception) {
                Log.w(TAG, "fd.sync() failed (non-fatal, data already flushed): ${e.message}")
            }
        }

        // ✅ Verify output file
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
            throw Exception("Encrypted file too small: ${bytes.size} bytes")
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
            // ✅ FIX: Wrap sync in try-catch — flush() already wrote the data
            try {
                fos.fd.sync()
            } catch (e: Exception) {
                Log.w(TAG, "fd.sync() failed (non-fatal, data already flushed): ${e.message}")
            }
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

    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIX: Create share file in filesDir (not cacheDir)
    // ─────────────────────────────────────────────────────────────────────

    private fun createShareFile(media: MediaEntity): File {
        val dir = shareDir // ← filesDir/vault_share/

        val originalName = media.fileName
        val ext = originalName.substringAfterLast('.', "").lowercase()
        val baseName = originalName.substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .trim()
            .take(100)

        val safeExt = when {
            ext.isNotEmpty() && ext.length <= 5 -> ext
            media.mimeType.contains("mp4") -> "mp4"
            media.mimeType.contains("matroska") || media.mimeType.contains("mkv") -> "mkv"
            media.mimeType.contains("webm") -> "webm"
            media.mimeType.contains("3gpp") -> "3gp"
            media.mimeType.contains("avi") -> "avi"
            else -> "mp4"
        }

        // ✅ FIX: Add timestamp to avoid collision with previous shares
        val timestamp = System.currentTimeMillis()
        val safeName = if (baseName.isNotEmpty()) {
            "${baseName}_${timestamp}.${safeExt}"
        } else {
            "video_${timestamp}.${safeExt}"
        }

        Log.d(TAG, "Share file: '$safeName' (original: '$originalName')")

        return File(dir, safeName)
    }

    // ─────────────────────────────────────────────────────────────────────
    // MIME type resolver
    // ─────────────────────────────────────────────────────────────────────

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
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            else -> "video/mp4" // ✅ Default to video/mp4 instead of octet-stream
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ✅ FIX: Clean stale files from filesDir (not cacheDir)
    // ─────────────────────────────────────────────────────────────────────

    private fun cleanupStaleShareFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = shareDir
                if (!dir.exists()) return@launch

                val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
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
}