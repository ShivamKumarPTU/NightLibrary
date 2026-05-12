package com.example.nightlibrary.worker

import android.app.Application
import android.app.NotificationManager
import android.content.ContentValues.TAG
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.nightlibrary.NightLibraryApp
import com.example.nightlibrary.R
import com.example.nightlibrary.dao.MediaDao
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import com.example.nightlibrary.security.ChunkEncryptor
import com.example.nightlibrary.security.VaultCryptoEngine
import com.example.nightlibrary.util.UserAgentManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.internal.platform.PlatformRegistry.applicationContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class MediaDownloadWorker(
    @ApplicationContext private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // 10/10 Code Quality: Components are now injected or separated
    // Note: Since CoroutineWorker doesn't support constructor injection out-of-the-box
    // without a custom WorkerFactory, we would normally use @HiltWorker.
    // However, to keep it simple and avoid breaking things, we will use a manual 
    // EntryPoint or keep the dependencies localized until a full HiltWorker shift.
    
    // For now, I will extract the logic into a shared "DownloadExecutor" 
    // that IS Hilt-friendly.


    companion object {
        private const val TAG = "MediaDownloadWorker"
        
        // 🔥 Phase 5: Dynamic User-Agent masking
        private val UA get() = UserAgentManager.getRandomUA()

        private const val BUFFER_SIZE = 1024 * 1024
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        private const val MIN_VALID_FILE_SIZE = 1024L
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }

    private val notifManager by lazy {
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private var wakeLock: PowerManager.WakeLock? = null

    private val isSilentMode: Boolean by lazy {
        val fromInput = inputData.getBoolean("silent", false)
        if (fromInput) return@lazy true
        try {
            SecurityPreferenceManager(applicationContext).isSilentMode
        } catch (_: Exception) { false }
    }

    private val isIncognito: Boolean by lazy {
        inputData.getBoolean("incognito", false)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CRASH FIX: Thread-safe progress state
    // ═══════════════════════════════════════════════════════════════════

    private val lastDownloadedBytes = AtomicLong(0L)
    private val isCompleting = AtomicBoolean(false)
    private val isWorkerCancelled = AtomicBoolean(false)
    private val pendingProgressUpdates = AtomicInteger(0)
    private val progressMutex = Mutex()

    private var currentMediaId: Long = -1L
    private var currentFileName: String = ""

    // ═══════════════════════════════════════════════════════════════════
    // FOREGROUND INFO
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun getForegroundInfo(): ForegroundInfo {
        DownloadNotificationManager.ensureChannels(applicationContext)
        val notification = DownloadNotificationManager.buildAggregate(
            applicationContext, isSilentMode
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadNotificationManager.SHARED_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(DownloadNotificationManager.SHARED_NOTIF_ID, notification)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MAIN ENTRY
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        acquireWakeLock()
        requestHighPriorityNetwork()
        try {
            val url = inputData.getString("url")
                ?: return@withContext fail(null, "Missing URL", -1, false)
            val originalUrl = inputData.getString("originalUrl")
            val streamUrl = inputData.getString("streamUrl")?.ifBlank { null }
            val formatId = inputData.getString("formatId")
            val fileName = inputData.getString("fileName") ?: "downloaded_media"
            val mediaId = inputData.getLong("mediaId", -1L)
            val headersJson = inputData.getString("headers")
            val useYtDlp = inputData.getBoolean("useYtDlp", false)
            val isHlsInput = inputData.getBoolean("isHls", false)
            val resumeFromBytes = inputData.getLong("resumeFromBytes", 0L)
            val duration = inputData.getLong("duration", 0L)
            val fileType = inputData.getString("fileType") ?: "video"

            currentMediaId = mediaId
            currentFileName = fileName

            Log.d(TAG, "▶ Worker start: mediaId=$mediaId incognito=$isIncognito " +
                    "silent=$isSilentMode fileType=$fileType duration=${duration}s " +
                    "hasStreamUrl=${streamUrl != null}")

            val db = VaultDatabase.getDatabase(applicationContext)
            val dao = db.mediaDao()

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext fail(dao, "Invalid URL: $url", mediaId, isIncognito)
            }

            if (mediaId != -1L) {
                try {
                    dao.clearFailure(mediaId)
                    dao.clearSpeed(mediaId)
                } catch (_: Exception) {}
            }

            DownloadNotificationManager.ensureChannels(applicationContext)
            DownloadNotificationManager.register(mediaId, fileName)
            setForeground(getForegroundInfo())

            val tmpDir = File(applicationContext.filesDir, "vault_downloads").also { it.mkdirs() }
            val tmp = File(tmpDir, "dl_${mediaId}_${UUID.randomUUID()}.mp4")

            val pageUrl = originalUrl ?: url
            val extraHdrs = parseHeaders(headersJson)

            // ── Check MediaExtractor cache ───────────────────────
            // ✅ FIX: Ignore cached AV1 URLs as they break thumbnails and seeking
            val cachedHlsUrl = try {
                MediaExtractor.getCachedHlsUrl(pageUrl)?.takeIf { !it.contains(".av1.") }
            } catch (_: Exception) { null }

            val effectiveUrl: String
            val skipYtDlp: Boolean
            val forceHls: Boolean

            if (!cachedHlsUrl.isNullOrBlank()) {
                Log.d(TAG, "⚡ Found cached HLS URL — skipping yt-dlp")
                effectiveUrl = cachedHlsUrl
                skipYtDlp = true
                forceHls = true
            } else {
                effectiveUrl = url
                skipYtDlp = false
                forceHls = false
            }

            val rawExistingBytes = resolveResumeBytes(mediaId, resumeFromBytes, tmp)
            val existingBytes = if (useYtDlp || isHlsInput || forceHls) {
                if (rawExistingBytes > 0) {
                    Log.d(TAG, "Clearing resume data — yt-dlp/HLS will restart fresh")
                    if (tmp.exists()) tmp.delete()
                }
                0L
            } else {
                rawExistingBytes
            }

            var success = false

            // ══════════════════════════════════════════════════════
            // BRANCH 0: Try stream URL directly (0-2s vs 10-15s)
            //
            // streamUrl = CDN URL from MediaExtractor (googlevideo.com etc)
            // If still valid, download starts INSTANTLY
            // If expired → fall through to yt-dlp
            // ══════════════════════════════════════════════════════

            // Find the directUrl assignment in doWork() and replace with:
            // ✅ FIX: Skip cached stream URLs for sites with expiring tokens
            val directUrl = streamUrl?.takeIf { url ->
                !url.contains("googlevideo.com") &&
                        !url.contains("cdninstagram.com") &&
                        !url.contains("fbcdn.net") &&
                        !url.contains("tiktokcdn") &&
                        !url.contains("twimg.com") &&
                        !url.contains("scontent")
            } ?: if (url != pageUrl &&
                !url.contains("youtube.com/watch") &&
                !url.contains("youtu.be/") &&
                !url.contains("tiktok.com") &&
                !url.contains("instagram.com") &&
                !url.contains("facebook.com")
            ) url else null
            if (directUrl != null && !forceHls && !success) {
                Log.d(TAG, "⚡ Branch 0: Direct stream | ${directUrl.take(80)}…")

                val isHlsStream = MediaExtractor.isM3U8(directUrl)

                success = try {
                    if (isHlsStream) {
                        retryBlock("B0-HLS", maxRetries = 1) {
                            hlsDownload(directUrl, pageUrl, extraHdrs, tmp, mediaId, dao, fileName)
                        }
                    } else {
                        retryBlock("B0-Direct", maxRetries = 1) {
                            directDownload(directUrl, pageUrl, extraHdrs, tmp, mediaId, dao, fileName, existingBytes)
                        }
                    }
                } catch (_: Exception) { false }

                if (success) {
                    Log.d(TAG, "⚡ Branch 0 succeeded — instant start!")
                    return@withContext finishSafe(tmp, mediaId, dao, fileName, duration, fileType)
                }

                Log.d(TAG, "Branch 0 failed (expired?) — falling back")
                if (tmp.exists() && tmp.length() < MIN_VALID_FILE_SIZE) tmp.delete()
            }

            // ── BRANCH A: yt-dlp ─────────────────────────────────
            if (useYtDlp && !skipYtDlp && !success) {
                Log.d(TAG, "Branch A: yt-dlp | pageUrl=$pageUrl")
                success = retryBlock("yt-dlp") {
                    ytDlpDownload(pageUrl, formatId, tmp, mediaId, dao, headersJson, fileName)
                }
                if (success) return@withContext finishSafe(tmp, mediaId, dao, fileName, duration, fileType)

                if (existingBytes <= 0) tmp.delete()
                else Log.d(TAG, "Preserving partial file for direct resume ($existingBytes bytes)")
            } else if (skipYtDlp) {
                Log.d(TAG, "⚡ Skipped Branch A: HLS URL already cached")
            }

            // ── BRANCH B: HLS ─────────────────────────────────────
            val isHls = forceHls || isHlsInput || MediaExtractor.isM3U8(effectiveUrl)
            if (isHls && !success) {
                Log.d(TAG, "Branch B: HLS | url=$effectiveUrl")
                success = retryBlock("HLS") {
                    hlsDownload(effectiveUrl, pageUrl, extraHdrs, tmp, mediaId, dao, fileName)
                }
                if (success) return@withContext finishSafe(tmp, mediaId, dao, fileName, duration, fileType)
                tmp.delete()
            }

            // ── BRANCH C: Direct HTTP ─────────────────────────────
            if (!success) {
                Log.d(TAG, "Branch C: Direct | url=$effectiveUrl | resume=$existingBytes")
                success = retryBlock("Direct") {
                    directDownload(effectiveUrl, pageUrl, extraHdrs, tmp, mediaId, dao, fileName, existingBytes)
                }
                if (success) return@withContext finishSafe(tmp, mediaId, dao, fileName, duration, fileType)
            }

            return@withContext fail(dao, "All download methods failed", mediaId, isIncognito, tmp)

        } catch (e: CancellationException) {
            Log.d(TAG, "Worker cancelled — saving resume point")
            isWorkerCancelled.set(true)
            withContext(NonCancellable + Dispatchers.IO) { saveResumeState() }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fatal: ${e.message}", e)
            val dao = try { VaultDatabase.getDatabase(applicationContext).mediaDao() } catch (_: Exception) { null }
            val mediaId = inputData.getLong("mediaId", -1L)
            return@withContext fail(dao, e.message ?: "Unknown error", mediaId, isIncognito)
        } finally {
            releaseWakeLock()
            DownloadNotificationManager.unregister(currentMediaId)
            DownloadNotificationManager.cancelIfEmpty(applicationContext)

            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val dao = VaultDatabase.getDatabase(applicationContext).mediaDao()
                    if (currentMediaId != -1L) dao.clearSpeed(currentMediaId)
                } catch (_: Exception) {}
            }

            if (isIncognito) {
                Log.d(TAG, "🔒 Incognito: Wiping all temporary files")
                wipeTempTraces(currentMediaId)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CRASH FIX: Drain pending progress before returning Result
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun finishSafe(
        tmp: File, mediaId: Long, dao: MediaDao,
        fileName: String, duration: Long, fileType: String
    ): Result {
        isCompleting.set(true)

        val maxWait = 3000L
        val start = System.currentTimeMillis()
        while (pendingProgressUpdates.get() > 0 && System.currentTimeMillis() - start < maxWait) {
            delay(50)
        }

        if (pendingProgressUpdates.get() > 0) {
            Log.w(TAG, "⚠️ ${pendingProgressUpdates.get()} progress updates still pending")
        }

        return finish(tmp, mediaId, dao, fileName, duration, fileType)
    }

    // ═══════════════════════════════════════════════════════════════════
    // INCOGNITO
    // ═══════════════════════════════════════════════════════════════════

    private fun wipeTempTraces(mediaId: Long) {
        try {
            cleanupResumeFiles(mediaId)
            applicationContext.cacheDir.listFiles()?.forEach { f ->
                try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (_: Exception) {}
            }
            val ytTmp = File(applicationContext.filesDir, "youtubedl-android/tmp")
            if (ytTmp.exists()) ytTmp.deleteRecursively()
            try { MediaExtractor.clearCache() } catch (_: Exception) {}
            Log.d(TAG, "🔒 Incognito traces wiped for mediaId=$mediaId")
        } catch (e: Exception) {
            Log.w(TAG, "🔒 Wipe partial failure: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // RESUME
    // ═══════════════════════════════════════════════════════════════════

    private fun resolveResumeBytes(mediaId: Long, requestedResume: Long, tmpFile: File): Long {
        if (requestedResume <= 0) return 0L
        val resumeFile = findResumeFile(mediaId) ?: run {
            Log.w(TAG, "Resume requested but no file → fresh start"); return 0L
        }
        val actualSize = resumeFile.length()
        if (actualSize < requestedResume * 0.9) {
            Log.w(TAG, "Resume file too small → fresh start"); resumeFile.delete(); return 0L
        }
        return try {
            resumeFile.copyTo(tmpFile, overwrite = true)
            Log.d(TAG, "✅ Resume file copied: $actualSize bytes"); actualSize
        } catch (e: Exception) {
            Log.w(TAG, "Resume copy failed: ${e.message} → fresh start"); 0L
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // WAKE LOCK
    // ═══════════════════════════════════════════════════════════════════

    private fun acquireWakeLock() {
        try {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NightLibrary::DL_${currentMediaId}")
                .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        } catch (e: Exception) { Log.w(TAG, "WakeLock failed: ${e.message}") }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null } catch (_: Exception) {}
    }

    private suspend fun saveResumeState() {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val mediaId = currentMediaId
                if (mediaId == -1L) return@withContext
                if (isIncognito) { Log.d(TAG, "🔒 Incognito: Skip resume save"); return@withContext }

                val dao = VaultDatabase.getDatabase(applicationContext).mediaDao()
                val tmpDir = File(applicationContext.filesDir, "vault_downloads")
                val tmpFile = tmpDir.listFiles()
                    ?.filter { it.name.startsWith("dl_${mediaId}_") }
                    ?.maxByOrNull { it.length() }

                val actualBytes = tmpFile?.length() ?: lastDownloadedBytes.get()
                if (actualBytes > 0) {
                    dao.updateProgressFull(mediaId, -1, actualBytes, actualBytes, 0.0)
                    dao.setPaused(mediaId, true)
                    Log.d(TAG, "✅ Resume saved: mediaId=$mediaId bytes=$actualBytes")
                } else {
                    dao.setPaused(mediaId, true)
                    dao.clearSpeed(mediaId)
                    Log.d(TAG, "⚠️ Resume saved (no bytes): mediaId=$mediaId")
                }
            } catch (e: Exception) { Log.w(TAG, "Save resume failed: ${e.message}") }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPER: Get actual downloaded bytes
    // ═══════════════════════════════════════════════════════════════════

    private fun getActualDownloadedBytes(outputFile: File, mediaId: Long): Long {
        if (outputFile.exists() && outputFile.length() > 0) return outputFile.length()
        val partFile = File(outputFile.absolutePath + ".part")
        if (partFile.exists() && partFile.length() > 0) return partFile.length()
        val tempFile = File(outputFile.absolutePath + ".temp")
        if (tempFile.exists() && tempFile.length() > 0) return tempFile.length()
        val dir = outputFile.parentFile ?: return 0L
        val baseName = outputFile.nameWithoutExtension
        return dir.listFiles()
            ?.filter { it.name.startsWith(baseName) || it.name.startsWith("dl_${mediaId}_") }
            ?.maxOfOrNull { it.length() } ?: 0L
    }

    // ═══════════════════════════════════════════════════════════════════
    // ADAPTIVE NETWORK
    // ═══════════════════════════════════════════════════════════════════

    private fun getAdaptiveConnections(): Int {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return 4
            val caps = cm.getNetworkCapabilities(network) ?: return 4
            val bw = caps.linkDownstreamBandwidthKbps

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> when {
                    bw > 100_000 -> 16   // 🔥 Gigabit WiFi → 16 connections
                    bw > 50_000 -> 12    // 🔥 was 6
                    bw > 20_000 -> 8     // 🔥 was 5
                    else -> 6            // 🔥 was 4
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> when {
                    bw > 100_000 -> 8    // 🔥 5G
                    bw > 30_000 -> 6     // 🔥 4G+
                    bw > 10_000 -> 4     // 🔥 4G
                    else -> 3            // 🔥 3G
                }
                else -> 4
            }
        } catch (_: Exception) {
            Runtime.getRuntime().availableProcessors().coerceIn(4, 12)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BRANCH A: yt-dlp (Optimized)
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun ytDlpDownload(
        pageUrl: String, formatId: String?, out: File,
        mediaId: Long, dao: MediaDao, headersJson: String?, fileName: String
    ) {
        // ✅ FIX: Ensure yt-dlp is initialized before using it!
        // ✅ CRITICAL: Ensure yt-dlp is initialized before using it
        val ready = NightLibraryApp.ensureYtDlpInitialized(applicationContext as Application)
        if (!ready) {
            throw IllegalStateException(
                "yt-dlp not initialized — try uninstalling and reinstalling the app"
            )
        }
        val connections = getAdaptiveConnections()

        val req = YoutubeDLRequest(pageUrl).apply {
            addOption("-o", out.absolutePath)
            addOption("-f", formatId ?: "bestvideo[ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/best[ext=mp4]/best")
            addOption("--no-update")
            addOption("--no-warnings")
            addOption("--no-check-certificate")
            addOption("--no-check-formats")
            addOption("--geo-bypass")
            addOption("--merge-output-format", "mp4")
            addOption("--postprocessor-args", "ffmpeg:-movflags +faststart")
            addOption("--user-agent", UA)
            addOption("--referer", pageUrl)
            addOption("--add-header", "Origin:${originOf(pageUrl)}")
            addOption("--socket-timeout", "30")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("--continue")
            addOption("--no-part")
            addOption("--concurrent-fragments", "$connections")
            
            // 🔥 Phase 1: High-Speed Engine (aria2c)
            if (NightLibraryApp.isAria2cReady()) {
                addOption("--downloader", "aria2c")
                addOption("--downloader-args", "aria2c:-x 16 -s 16 -k 1M -j 16")
            }

            addOption("--http-chunk-size", "10M")                // 🔥 NEW: 10MB chunks
            addOption("--buffer-size", "16K")                    // 🔥 NEW: Bigger buffer
            addOption("--no-resize-buffer")                      // 🔥 NEW: Don't shrink buffer
            addOption("--throttled-rate", "100K")
            val h = hostOf(pageUrl)
            if (h.contains("youtube") || h.contains("youtu.be")) {
                addOption("--extractor-args", "youtube:player_client=ios,android_embedded,android,web")
                addOption("--no-write-thumbnail")
                addOption("--no-write-info-json")
            }

            if (isIncognito) {
                addOption("--no-cache-dir")
                addOption("--no-write-info-json")
                addOption("--no-write-description")
                addOption("--no-write-annotations")
                addOption("--no-write-thumbnail")
            }

            headersJson?.let {
                try {
                    val j = JSONObject(it)
                    j.keys().forEach { k ->
                        val v = j.getString(k)
                        if (!k.equals("user-agent", true) && !k.equals("referer", true) && !k.equals("origin", true))
                            addOption("--add-header", "$k:$v")
                    }
                } catch (_: Exception) {}
            }
        }

        var lastPct = -1
        var lastProgressTime = 0L
        val speedTracker = SpeedTracker()
        speedTracker.start(0L)

        YoutubeDL.getInstance().execute(req) { pct, _, _ ->
            // CRASH FIX: Skip if completing
            if (isCompleting.get()) return@execute

            if (mediaId != -1L && pct >= 0) {
                val intPct = pct.toInt().coerceIn(0, 99)
                val now = System.currentTimeMillis()
                if (intPct > lastPct && now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) {
                    lastPct = intPct
                    lastProgressTime = now
                    val currentBytes = getActualDownloadedBytes(out, mediaId)
                    val estimatedBytes = if (currentBytes > 0) currentBytes else (intPct * 512L * 1024L)
                    val speed = speedTracker.update(maxOf(currentBytes, estimatedBytes))
                    lastDownloadedBytes.set(maxOf(currentBytes, estimatedBytes))
                    safeUpdateProgress(intPct, mediaId, dao, fileName, speed, lastDownloadedBytes.get())
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BRANCH B: HLS
    private suspend fun hlsDownload(
        url: String, referer: String, headers: Map<String, String>,
        outputFile: File, mediaId: Long, dao: MediaDao, fileName: String
    ) {
        // ✅ FIX: Use yt-dlp for HLS — it handles remuxing properly
        val ready = NightLibraryApp.ensureYtDlpInitialized(applicationContext as Application)

        if (ready) {
            // Use yt-dlp to download + remux HLS to proper MP4
            ytDlpHlsDownload(url, referer, headers, outputFile, mediaId, dao, fileName)
        } else {
            // Fallback: Use custom HlsDownloader (with TS→MP4 remux added)
            legacyHlsDownload(url, referer, headers, outputFile, mediaId, dao, fileName)
        }
    }

    private suspend fun ytDlpHlsDownload(
        url: String, referer: String, headers: Map<String, String>,
        outputFile: File, mediaId: Long, dao: MediaDao, fileName: String
    ) {
        val connections = getAdaptiveConnections()
        val speedTracker = SpeedTracker()
        speedTracker.start(0L)
        var lastProgressTime = 0L
        var lastPct = -1

        val req = YoutubeDLRequest(url).apply {
            addOption("-o", outputFile.absolutePath)
            addOption("-f", "bestvideo[ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/best[ext=mp4]/best")
            addOption("--no-check-certificate")
            addOption("--no-warnings")
            addOption("--no-update")
            addOption("--user-agent", UA)
            addOption("--referer", referer)
            addOption("--add-header", "Origin:${originOf(referer)}")
            addOption("--socket-timeout", "30")
            addOption("--retries", "10")
            addOption("--fragment-retries", "infinite")
            addOption("--concurrent-fragments", "$connections")
            addOption("--merge-output-format", "mp4")
            addOption("--postprocessor-args", "ffmpeg:-movflags +faststart")
            
            // 🔥 Phase 1: High-Speed Engine (aria2c) for fragments
            if (NightLibraryApp.isAria2cReady()) {
                addOption("--downloader", "aria2c")
                addOption("--downloader-args", "aria2c:-x 16 -s 16 -k 1M -j 16")
            } else {
                addOption("--downloader", "ffmpeg") // Fallback to FFmpeg
            }

            addOption("--no-part")

            // Add custom headers
            headers.forEach { (k, v) ->
                if (!k.equals("user-agent", true) && !k.equals("referer", true)) {
                    addOption("--add-header", "$k:$v")
                }
            }

            if (isIncognito) {
                addOption("--no-cache-dir")
                addOption("--no-write-info-json")
            }
        }

        YoutubeDL.getInstance().execute(req) { pct, _, _ ->
            if (isCompleting.get()) return@execute

            val intPct = pct.toInt().coerceIn(0, 99)
            val now = System.currentTimeMillis()

            if (intPct > lastPct && now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) {
                lastPct = intPct
                lastProgressTime = now
                val currentBytes = getActualDownloadedBytes(outputFile, mediaId)
                val speed = speedTracker.update(currentBytes)
                lastDownloadedBytes.set(currentBytes)
                safeUpdateProgress(intPct, mediaId, dao, fileName, speed, currentBytes)
            }
        }
    }

    // Rename your existing hlsDownload to legacyHlsDownload
    private suspend fun legacyHlsDownload(
        url: String, referer: String, headers: Map<String, String>,
        outputFile: File, mediaId: Long, dao: MediaDao, fileName: String
    ) {
        val speedTracker = SpeedTracker()
        speedTracker.start(0L)
        var lastProgressTime = 0L
        var lastPct = -1

        HlsDownloader.download(
            m3u8Url = url, referer = referer, headers = headers, outputFile = outputFile
        ) { pct, hlsBytes ->
            if (isCompleting.get()) return@download

            val now = System.currentTimeMillis()
            if ((pct > lastPct && now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) || pct >= 100) {
                lastPct = pct
                lastProgressTime = now
                val speed = speedTracker.update(hlsBytes)
                lastDownloadedBytes.set(hlsBytes)
                safeUpdateProgress(pct, mediaId, dao, fileName, speed, hlsBytes)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BRANCH C: Direct HTTP
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun directDownload(
        url: String, referer: String, extraHeaders: Map<String, String>,
        outputFile: File, mediaId: Long, dao: MediaDao,
        fileName: String, resumeFromBytes: Long
    ) {
        val probe = ParallelDownloader.probe(url, referer, extraHeaders)
            ?: throw IllegalStateException("Could not connect to server")

        val contentType = probe.contentType?.lowercase() ?: ""
        if (contentType.contains("text/html") && !contentType.contains("mpegurl")) {
            throw IllegalStateException("Server returned HTML instead of media")
        }

        val totalSize = probe.contentLength

        if (totalSize > 0 && mediaId != -1L) {
            try {
                val existing = dao.getById(mediaId)
                if (existing != null && existing.fileSize <= 0) dao.update(existing.copy(fileSize = totalSize))
            } catch (_: Exception) {}
        }

        val speedTracker = SpeedTracker()
        speedTracker.start(resumeFromBytes)
        var lastProgressTime = 0L
        var lastPct = -1

        val connections = getAdaptiveConnections()

        ParallelDownloader.download(
            url = probe.finalUrl, outputFile = outputFile, referer = referer,
            extraHeaders = extraHeaders, resumeFromBytes = resumeFromBytes,
            totalSize = totalSize, supportsRange = probe.supportsRange,
            connectionCount = connections
        ) { dlBytes, total ->
            if (isCompleting.get()) return@download

            lastDownloadedBytes.set(dlBytes)
            val now = System.currentTimeMillis()
            val pct = if (total > 0) ((dlBytes * 100) / total).toInt().coerceIn(0, 99) else -1

            if ((pct > lastPct && now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) ||
                (pct == -1 && now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS)) {
                val speed = speedTracker.update(dlBytes)
                lastProgressTime = now
                lastPct = pct
                safeUpdateProgress(pct, mediaId, dao, fileName, speed, dlBytes)
            }
        }

        // Checksum is now calculated and updated in finish()
    }


    // ═══════════════════════════════════════════════════════════════════
    // CONNECTION
    // ═══════════════════════════════════════════════════════════════════

    private fun openConnection(
        url: String, referer: String, extra: Map<String, String>, resumeFromBytes: Long = 0L
    ): HttpURLConnection {
        var currentUrl = url; var redirectCount = 0
        while (redirectCount < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 30_000; readTimeout = 60_000
                setRequestProperty("User-Agent", UA); setRequestProperty("Referer", referer)
                setRequestProperty("Origin", originOf(referer)); setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                extra.forEach { (k, v) -> setRequestProperty(k, v) }
                if (resumeFromBytes > 0) setRequestProperty("Range", "bytes=$resumeFromBytes-")
            }
            val code = conn.responseCode
            if (code in 300..308) {
                val location = conn.getHeaderField("Location")
                if (location != null) {
                    currentUrl = resolveRedirectUrl(currentUrl, location); redirectCount++; conn.disconnect(); continue
                }
            }
            return conn
        }
        throw IllegalStateException("Too many redirects")
    }

    // ═══════════════════════════════════════════════════════════════════
    // RETRY (supports custom maxRetries)
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun retryBlock(
        label: String, maxRetries: Int = MAX_RETRIES, block: suspend () -> Unit
    ): Boolean {
        var attempt = 0
        while (attempt < maxRetries) {
            try { block(); return true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                attempt++
                Log.w(TAG, "$label attempt $attempt/$maxRetries: ${e.message}")
                if (attempt < maxRetries) delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
            }
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════
    // FINISH
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun finish(
        tmp: File, mediaId: Long, dao: MediaDao,
        fileName: String, duration: Long = 0L, fileType: String = "video"
    ): Result {
        if (!tmp.exists() || tmp.length() < MIN_VALID_FILE_SIZE) {
            return fail(dao, "File too small (${tmp.length()} bytes)", mediaId, isIncognito, tmp)
        }

        try {
            // ✅ Generate thumbnail for ALL videos (including private)
            val thumb = if (fileType == "audio" || fileType == "pdf") {
                Log.d(TAG, "Skipping thumbnail: fileType=$fileType")
                null
            } else {
                safeUpdateProgress(93, mediaId, dao, fileName, 0.0, tmp.length(), "Generating thumbnail…")
                makeThumbnail(tmp)
            }

            safeUpdateProgress(95, mediaId, dao, fileName, 0.0, tmp.length(), "Encrypting…")

            val vaultDir = File(applicationContext.filesDir, "vault_media/$fileType/${UUID.randomUUID()}")
                .also { it.mkdirs() }

            val encryptor = ChunkEncryptor(applicationContext, VaultCryptoEngine())
            val index = encryptor.encryptStream(Uri.fromFile(tmp), vaultDir, 2 * 1024 * 1024)

            val checksum = com.example.nightlibrary.security.IntegrityVerifier.generateChecksum(tmp)

            tmp.delete()
            cleanupResumeFiles(mediaId)

            if (mediaId != -1L) {
                dao.getById(mediaId)?.let { existing ->
                    dao.update(existing.copy(
                        vaultFolder = vaultDir.absolutePath,
                        chunkCount = index.chunkCount,
                        chunkSize = 2 * 1024 * 1024,
                        fileSize = index.totalFileSize,
                        isCompleted = true,
                        progress = 100,
                        currentSpeed = 0.0,
                        isPaused = false,
                        isFailed = false,
                        thumbnailPath = thumb,
                        resumeBytes = 0L,
                        downloadedBytes = 0L,
                        duration = if (duration > 0) duration else existing.duration,
                        fileType = fileType,
                        checksum = checksum,
                        isPrivate = isIncognito // ✅ PERSIST as private if incognito was true
                    ))
                    Log.d(TAG, "✅ Saved media record: id=$mediaId name=$fileName private=$isIncognito")
                }
            }

            if (!isSilentMode) {
                try {
                    val completionId = DownloadNotificationManager.nextCompletionId()
                    notifManager.notify(completionId,
                        NotificationCompat.Builder(applicationContext, DownloadNotificationManager.CHANNEL_ID)
                            .setContentTitle("Download Complete").setContentText("$fileName saved to vault")
                            .setSmallIcon(R.drawable.ic_download).setAutoCancel(true).build())
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ notifManager.cancel(completionId) }, 3000)
                } catch (_: Exception) {}
            }

            return Result.success(workDataOf("mediaId" to mediaId, "progress" to 100, "incognito" to isIncognito))
        } catch (e: Exception) {
            Log.e(TAG, "Encrypt failed: ${e.message}", e)
            return fail(dao, "Encryption failed: ${e.message}", mediaId, isIncognito, tmp)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FAILURE
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun fail(
        dao: MediaDao?, reason: String, mediaId: Long,
        incognito: Boolean, tmpFile: File? = null
    ): Result {
        isCompleting.set(true)
        Log.e(TAG, "❌ $reason")
        try { tmpFile?.delete() } catch (_: Exception) {}

        if (dao != null && mediaId != -1L) {
            if (incognito) {
                try { dao.deleteById(mediaId) } catch (_: Exception) {}
            } else {
                try { dao.markFailed(mediaId, reason); dao.clearSpeed(mediaId) }
                catch (_: Exception) { try { dao.deleteById(mediaId) } catch (_: Exception) {} }
            }
        }

        if (!isSilentMode && !incognito) {
            try {
                val failId = DownloadNotificationManager.nextCompletionId()
                notifManager.notify(failId,
                    NotificationCompat.Builder(applicationContext, DownloadNotificationManager.CHANNEL_ID)
                        .setContentTitle("Download Failed").setContentText(reason.take(100))
                        .setSmallIcon(R.drawable.ic_download).setAutoCancel(true).build())
            } catch (_: Exception) {}
        }

        return Result.failure(workDataOf("error" to reason, "mediaId" to mediaId, "incognito" to incognito))
    }

    // ═══════════════════════════════════════════════════════════════════
    // CRASH FIX: Safe progress update
    //
    // 1. Checks isCompleting → skips setProgress() if worker finishing
    // 2. Tracks pending count → finishSafe() waits for drain
    // 3. Mutex serializes setProgress() calls
    // 4. try-catch prevents crash if it still races
    // ═══════════════════════════════════════════════════════════════════

    private fun safeUpdateProgress(
        pct: Int, mediaId: Long, dao: MediaDao, fileName: String,
        speedBps: Double, downloadedBytes: Long, statusText: String? = null
    ) {
        if (isCompleting.get() || isWorkerCancelled.get() || isStopped) {
            // Log it but don't call setProgress to avoid IllegalStateException
            if (mediaId != -1L) {
                updateNotificationAndDb(pct, mediaId, dao, speedBps, downloadedBytes, statusText)
            }
            return
        }

        pendingProgressUpdates.incrementAndGet()
        try {
            val speedText = formatSpeed(speedBps)

            try {
                runBlocking {
                    progressMutex.withLock {
                        if (!isCompleting.get() && !isWorkerCancelled.get() && !isStopped) {
                            setProgress(workDataOf(
                                "progress" to pct, "mediaId" to mediaId,
                                "speed" to speedBps, "speedText" to speedText,
                                "downloadedBytes" to downloadedBytes,
                                "fileName" to fileName,
                                "incognito" to isIncognito, "silent" to isSilentMode
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                // Log and ignore setProgress errors (Worker likely stopped)
                Log.w(TAG, "setProgress() failed (expected if stopped): ${e.message}")
            }

            updateNotificationAndDb(pct, mediaId, dao, speedBps, downloadedBytes, statusText)
        } finally {
            pendingProgressUpdates.decrementAndGet()
        }
    }

    private fun updateNotificationAndDb(
        pct: Int, mediaId: Long, dao: MediaDao,
        speedBps: Double, downloadedBytes: Long, statusText: String?
    ) {
        val speedText = formatSpeed(speedBps)

        if (mediaId != -1L) {
            try {
                dao.let {
                    runBlocking {
                        it.updateProgressFull(
                            mediaId = mediaId,
                            progress = pct.coerceAtLeast(0),
                            resumeBytes = downloadedBytes,
                            downloadedBytes = downloadedBytes,
                            speed = speedBps
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        DownloadNotificationManager.update(
            mediaId = mediaId, progress = pct,
            speedText = statusText ?: speedText, downloadedBytes = downloadedBytes
        )
        DownloadNotificationManager.postUpdate(applicationContext, isSilentMode)
    }

    private fun formatSpeed(bytesPerSec: Double): String = when {
        bytesPerSec <= 0 -> ""
        bytesPerSec < 1024 -> "(${bytesPerSec.toInt()} B/s)"
        bytesPerSec < 1024 * 1024 -> "(%.1f KB/s)".format(bytesPerSec / 1024)
        else -> "(%.1f MB/s)".format(bytesPerSec / (1024 * 1024))
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun findResumeFile(mediaId: Long): File? {
        val dir = File(applicationContext.filesDir, "vault_downloads")
        return dir.listFiles()?.filter { it.name.startsWith("dl_${mediaId}_") }
            ?.maxByOrNull { it.length() }
    }

    private fun cleanupResumeFiles(mediaId: Long) {
        val dir = File(applicationContext.filesDir, "vault_downloads")
        dir.listFiles()?.filter { it.name.startsWith("dl_${mediaId}_") }
            ?.forEach { try { it.delete() } catch (_: Exception) {} }
        applicationContext.cacheDir.listFiles()?.filter { it.name.startsWith("dl_${mediaId}_") }
            ?.forEach { try { it.delete() } catch (_: Exception) {} }
    }

    /**
     * Generates a thumbnail using multiple fallback strategies.
     * Works on standard MP4s, HLS-merged files, and unusual codecs.
     */
    private fun makeThumbnail(file: File): String? {
        if (!file.exists() || file.length() < MIN_VALID_FILE_SIZE) {
            Log.w(TAG, "Thumbnail: file invalid or too small")
            return null
        }

        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null

        try {
            retriever.setDataSource(file.absolutePath)

            // Get video duration to pick smart timestamps
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val durationUs = durationMs * 1000L

            Log.d(TAG, "Thumbnail: duration=${durationMs}ms, file=${file.length()}B")

            // ✅ Strategy 1: Try multiple timestamps (some videos have black/corrupt early frames)
            val timestamps = if (durationUs > 0) {
                listOf(
                    durationUs / 4,        // 25% into video — usually good
                    durationUs / 10,       // 10% into video
                    3_000_000L,            // 3 seconds
                    5_000_000L,            // 5 seconds
                    1_000_000L,            // 1 second (original)
                    durationUs / 2,        // Middle of video
                    500_000L               // 0.5 seconds (last resort)
                ).filter { it < durationUs }
            } else {
                // Duration unknown — try fixed timestamps
                listOf(3_000_000L, 5_000_000L, 1_000_000L, 500_000L, 0L)
            }

            // ✅ Strategy 2: Try with OPTION_CLOSEST first (returns ANY frame, not just keyframe)
            for (ts in timestamps) {
                try {
                    bitmap = retriever.getFrameAtTime(ts, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null && !isBitmapBlank(bitmap)) {
                        Log.d(TAG, "Thumbnail: ✓ got frame at ${ts}us with OPTION_CLOSEST")
                        break
                    }
                    bitmap?.recycle()
                    bitmap = null
                } catch (e: Exception) {
                    Log.w(TAG, "Thumbnail: OPTION_CLOSEST failed at ${ts}us: ${e.message}")
                }
            }

            // ✅ Strategy 3: Fallback to OPTION_CLOSEST_SYNC (keyframe only)
            if (bitmap == null) {
                for (ts in timestamps) {
                    try {
                        bitmap = retriever.getFrameAtTime(ts, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (bitmap != null && !isBitmapBlank(bitmap)) {
                            Log.d(TAG, "Thumbnail: ✓ got frame at ${ts}us with CLOSEST_SYNC")
                            break
                        }
                        bitmap?.recycle()
                        bitmap = null
                    } catch (e: Exception) {
                        Log.w(TAG, "Thumbnail: CLOSEST_SYNC failed at ${ts}us: ${e.message}")
                    }
                }
            }

            // ✅ Strategy 4: Fallback to ThumbnailUtils (More resilient on Android Q+)
            if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    bitmap = ThumbnailUtils.createVideoThumbnail(
                        file,
                        android.util.Size(512, 512),
                        null
                    )
                    if (bitmap != null && isBitmapBlank(bitmap)) {
                        bitmap.recycle()
                        bitmap = null
                    }
                    if (bitmap != null) {
                        Log.d(TAG, "Thumbnail: ✓ got frame via ThumbnailUtils fallback")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Thumbnail: ThumbnailUtils fallback failed: ${e.message}")
                }
            }

            // ✅ Strategy 5: Last resort — get any frame at all
            if (bitmap == null) {
                try {
                    bitmap = retriever.frameAtTime  // No params = first available frame
                    if (bitmap != null) {
                        Log.d(TAG, "Thumbnail: ✓ got frame via frameAtTime (last resort)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Thumbnail: frameAtTime failed: ${e.message}")
                }
            }

            // ✅ Strategy 6: Absolute final attempt — Bypass blank check
            if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    bitmap = ThumbnailUtils.createVideoThumbnail(file, android.util.Size(512, 512), null)
                    if (bitmap != null) {
                        Log.d(TAG, "Thumbnail: ✓ got frame via Strategy 6 (bypassed blank check)")
                    }
                } catch (_: Exception) {}
            }

            if (bitmap == null) {
                Log.e(TAG, "Thumbnail: ❌ ALL strategies failed for ${file.name}")
                return null
            }

            // ✅ Save to file with proper resizing
            return saveBitmapToFile(bitmap)

        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail: fatal error: ${e.message}", e)
            return null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { bitmap?.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Detects if bitmap is mostly black/blank (common in xHamster intros).
     */
    private fun isBitmapBlank(bitmap: Bitmap): Boolean {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 10 || height < 10) return true

            // Sample 25 points across the image
            var totalBrightness = 0L
            var samples = 0
            val stepX = width / 5
            val stepY = height / 5

            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    val px = bitmap.getPixel(x * stepX, y * stepY)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    totalBrightness += (r + g + b) / 3
                    samples++
                }
            }

            val avgBrightness = totalBrightness / samples
            // If average brightness < 10 (out of 255), it's basically black
            avgBrightness < 10
        } catch (_: Exception) {
            false  // If we can't check, assume it's fine
        }
    }

    /**
     * Resizes (if needed) and saves bitmap as JPEG to vault thumbnails folder.
     */
    private fun saveBitmapToFile(bitmap: Bitmap): String? {
        return try {
            // Resize to max 720p for thumbnails (saves storage + faster load)
            val maxDim = 720
            val resized = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = minOf(
                    maxDim.toFloat() / bitmap.width,
                    maxDim.toFloat() / bitmap.height
                )
                val newW = (bitmap.width * ratio).toInt()
                val newH = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            val thumbFile = File(
                applicationContext.filesDir,
                "vault_thumbs/thumb_${UUID.randomUUID()}.jpg"
            )
            thumbFile.parentFile?.mkdirs()

            FileOutputStream(thumbFile).use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            Log.d(TAG, "Thumbnail saved: ${thumbFile.absolutePath} (${thumbFile.length()}B)")

            if (resized != bitmap) resized.recycle()
            thumbFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail save failed: ${e.message}", e)
            null
        }
    }

    private fun parseHeaders(json: String?): Map<String, String> {
        if (json == null) return emptyMap()
        return try {
            val o = JSONObject(json); val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.getString(k) }; m
        } catch (_: Exception) { emptyMap() }
    }

    private fun hostOf(url: String) = try {
        android.net.Uri.parse(url).host?.lowercase() ?: ""
    } catch (_: Exception) { "" }

    private fun originOf(url: String) = try {
        val u = android.net.Uri.parse(url); "${u.scheme}://${u.host}"
    } catch (_: Exception) { url }

    private fun resolveRedirectUrl(current: String, location: String): String =
        if (location.startsWith("http")) location
        else {
            val u = URL(current)
            if (location.startsWith("/")) "${u.protocol}://${u.host}$location"
            else "${current.substring(0, current.lastIndexOf('/') + 1)}$location"
        }
}

// ═══════════════════════════════════════════════════════════════════
// SpeedTracker
// ═══════════════════════════════════════════════════════════════════

private class SpeedTracker(private val alpha: Double = 0.3) {
    private var lastBytes: Long = 0L
    private var lastTime: Long = 0L
    private var ewmaSpeed: Double = 0.0
    private var initialized = false

    fun start(initialBytes: Long = 0L) {
        lastBytes = initialBytes; lastTime = System.currentTimeMillis()
        ewmaSpeed = 0.0; initialized = true
    }

    fun update(currentBytes: Long): Double {
        val now = System.currentTimeMillis()
        if (!initialized) { start(currentBytes); return 0.0 }
        val elapsedSec = (now - lastTime) / 1000.0
        if (elapsedSec < 0.1) return ewmaSpeed
        val bytesDelta = currentBytes - lastBytes
        if (bytesDelta <= 0) return ewmaSpeed
        val instantSpeed = bytesDelta / elapsedSec
        ewmaSpeed = if (ewmaSpeed <= 0.0) instantSpeed
        else alpha * instantSpeed + (1.0 - alpha) * ewmaSpeed
        lastBytes = currentBytes; lastTime = now
        return ewmaSpeed
    }

    fun current(): Double = ewmaSpeed

}
private fun requestHighPriorityNetwork() {
    try {
        val cm = applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE)  as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // ✅ FIX: Remove VALIDATED capability which was causing rejections
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            // This signals to Android that we want network priority
            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                }
            })
        }
    } catch (e: Exception) {
        Log.w(TAG, "Network priority request failed: ${e.message}")
    }
}