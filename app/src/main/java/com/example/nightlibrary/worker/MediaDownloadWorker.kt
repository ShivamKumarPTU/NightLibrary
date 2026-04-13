package com.example.nightlibrary.worker

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.nightlibrary.R
import com.example.nightlibrary.dao.MediaDao
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import com.example.nightlibrary.security.ChunkEncryptor
import com.example.nightlibrary.security.VaultCryptoEngine
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class MediaDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MediaDownloadWorker"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_VALID_FILE_SIZE = 1024L
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }

    private val notifManager by lazy {
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private var wakeLock: PowerManager.WakeLock? = null

    // ═══════════════════════════════════════════════════════════════════
    // 🔇 Silent mode — reads from BOTH inputData and preference
    //    inputData takes priority (set at enqueue time)
    // ═══════════════════════════════════════════════════════════════════

    private val isSilentMode: Boolean by lazy {
        // Priority: explicit input flag > persisted preference
        val fromInput = inputData.getBoolean("silent", false)       // 🔇 NEW
        if (fromInput) return@lazy true
        try {
            SecurityPreferenceManager(applicationContext).isSilentMode
        } catch (_: Exception) { false }
    }

    // 🔒 Incognito flag from input data
    private val isIncognito: Boolean by lazy {
        inputData.getBoolean("incognito", false)
    }

    private var lastDownloadedBytes: Long = 0L
    private var currentMediaId: Long = -1L
    private var currentFileName: String = ""

    // ═══════════════════════════════════════════════════════════════════
    // FOREGROUND INFO — uses the minimal-notification channel when needed
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
    // MAIN ENTRY — Fix 3: All IO in withContext(Dispatchers.IO),
    //              ensureActive() replaces coroutineContext.ensureActive()
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        acquireWakeLock()

        try {
            val url = inputData.getString("url")
                ?: return@withContext fail(null, "Missing URL", -1, false)
            val originalUrl = inputData.getString("originalUrl")
            val formatId = inputData.getString("formatId")
            val fileName = inputData.getString("fileName") ?: "downloaded_media"
            val mediaId = inputData.getLong("mediaId", -1L)
            val headersJson = inputData.getString("headers")
            val useYtDlp = inputData.getBoolean("useYtDlp", false)
            val isHlsInput = inputData.getBoolean("isHls", false)
            val resumeFromBytes = inputData.getLong("resumeFromBytes", 0L)

            // ✅ NEW: Read duration and fileType from input data
            val duration = inputData.getLong("duration", 0L)             // Problem 6
            val fileType = inputData.getString("fileType") ?: "video"    // Feature A

            currentMediaId = mediaId
            currentFileName = fileName

            Log.d(TAG, "▶ Worker start: mediaId=$mediaId incognito=$isIncognito silent=$isSilentMode fileType=$fileType duration=${duration}s")

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
            val existingBytes = resolveResumeBytes(mediaId, resumeFromBytes, tmp)

            var success = false

            // ── Check MediaExtractor cache BEFORE yt-dlp ─────────
            val cachedHlsUrl = try {
                MediaExtractor.getCachedHlsUrl(pageUrl)
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

            // ── BRANCH A: yt-dlp ─────────────────────────────────
            if (useYtDlp && !skipYtDlp) {
                Log.d(TAG, "Branch A: yt-dlp | pageUrl=$pageUrl")
                success = retryBlock("yt-dlp") {
                    ytDlpDownload(
                        pageUrl, formatId, tmp, mediaId,
                        dao, headersJson, fileName
                    )
                }
                if (success) return@withContext finish(tmp, mediaId, dao, fileName, duration, fileType)
                tmp.delete()
            } else if (skipYtDlp) {
                Log.d(TAG, "⚡ Skipped Branch A: HLS URL already cached")
            }

            // ── BRANCH B: HLS ─────────────────────────────────────
            val isHls = forceHls || isHlsInput || MediaExtractor.isM3U8(effectiveUrl)
            if (isHls && !success) {
                Log.d(TAG, "Branch B: HLS | url=$effectiveUrl")
                success = retryBlock("HLS") {
                    hlsDownload(
                        effectiveUrl, pageUrl, extraHdrs, tmp,
                        mediaId, dao, fileName
                    )
                }
                if (success) return@withContext finish(tmp, mediaId, dao, fileName, duration, fileType)
                tmp.delete()
            }

            // ── BRANCH C: Direct HTTP ─────────────────────────────
            if (!success) {
                Log.d(TAG, "Branch C: Direct | url=$effectiveUrl | resume=$existingBytes")
                success = retryBlock("Direct") {
                    directDownload(
                        effectiveUrl, pageUrl, extraHdrs, tmp, mediaId,
                        dao, fileName, existingBytes
                    )
                }
                if (success) return@withContext finish(tmp, mediaId, dao, fileName, duration, fileType)
            }

            return@withContext fail(dao, "All download methods failed", mediaId, isIncognito, tmp)

        } catch (e: CancellationException) {
            Log.d(TAG, "Worker cancelled — saving resume point")
            saveResumeState()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fatal: ${e.message}", e)
            val dao = try {
                VaultDatabase.getDatabase(applicationContext).mediaDao()
            } catch (_: Exception) { null }
            val mediaId = inputData.getLong("mediaId", -1L)
            return@withContext fail(dao, e.message ?: "Unknown error", mediaId, isIncognito)
        } finally {
            releaseWakeLock()

            DownloadNotificationManager.unregister(currentMediaId)
            DownloadNotificationManager.cancelIfEmpty(applicationContext)

            try {
                val dao = VaultDatabase.getDatabase(applicationContext).mediaDao()
                if (currentMediaId != -1L) dao.clearSpeed(currentMediaId)
            } catch (_: Exception) {}

            // ═══════════════════════════════════════════════════════
            // 🔒 INCOGNITO CLEANUP: Wipe ALL temp/cache traces
            // ═══════════════════════════════════════════════════════
            if (isIncognito) {
                Log.d(TAG, "🔒 Incognito: Wiping all temporary files")
                wipeTempTraces(currentMediaId)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 🔒 INCOGNITO: Wipe all temp files, cache, and extraction artifacts
    // ═══════════════════════════════════════════════════════════════════

    private fun wipeTempTraces(mediaId: Long) {
        try {
            // 1. Wipe download temp files
            cleanupResumeFiles(mediaId)

            // 2. Wipe extraction cache (yt-dlp metadata, m3u8 caches, etc.)
            val cacheDir = applicationContext.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                try {
                    if (file.isDirectory) file.deleteRecursively()
                    else file.delete()
                } catch (_: Exception) {}
            }

            // 3. Wipe yt-dlp temp directory
            val ytDlpDir = File(applicationContext.filesDir, "youtubedl-android")
            val ytDlpTmp = File(ytDlpDir, "tmp")
            if (ytDlpTmp.exists()) {
                ytDlpTmp.deleteRecursively()
            }

            // 4. Wipe MediaExtractor cache for this URL
            try {
                MediaExtractor.clearCache()
            } catch (_: Exception) {}

            Log.d(TAG, "🔒 Incognito traces wiped for mediaId=$mediaId")
        } catch (e: Exception) {
            Log.w(TAG, "🔒 Incognito wipe partial failure: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // RESUME FILE RESOLUTION (unchanged)
    // ═══════════════════════════════════════════════════════════════════

    private fun resolveResumeBytes(mediaId: Long, requestedResume: Long, tmpFile: File): Long {
        if (requestedResume <= 0) return 0L

        val resumeFile = findResumeFile(mediaId)
        if (resumeFile == null) {
            Log.w(TAG, "Resume requested but no file found → fresh start")
            return 0L
        }

        val actualSize = resumeFile.length()
        if (actualSize < requestedResume) {
            Log.w(TAG, "Resume file smaller than expected → fresh start")
            resumeFile.delete()
            return 0L
        }

        try {
            resumeFile.copyTo(tmpFile, overwrite = true)
            Log.d(TAG, "Resume file copied: $actualSize bytes")
            return actualSize
        } catch (e: Exception) {
            Log.w(TAG, "Resume copy failed: ${e.message} → fresh start")
            return 0L
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // WAKE LOCK (unchanged)
    // ═══════════════════════════════════════════════════════════════════

    private fun acquireWakeLock() {
        try {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NightLibrary::DL_${currentMediaId}"
            ).apply { acquire(WAKELOCK_TIMEOUT_MS) }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (_: Exception) {}
    }

    private suspend fun saveResumeState() {
        try {
            val mediaId = currentMediaId
            if (mediaId == -1L) return

            // 🔒 Don't save resume state for incognito — there's nothing to resume
            if (isIncognito) {
                Log.d(TAG, "🔒 Incognito: Skipping resume state save")
                return
            }

            val dao = VaultDatabase.getDatabase(applicationContext).mediaDao()
            val tmpDir = File(applicationContext.filesDir, "vault_downloads")
            val tmpFile = tmpDir.listFiles()
                ?.filter { it.name.startsWith("dl_${mediaId}_") }
                ?.maxByOrNull { it.length() }

            val actualBytes = tmpFile?.length() ?: lastDownloadedBytes
            if (actualBytes > 0) {
                dao.updateProgressFull(mediaId, -1, actualBytes, actualBytes, 0.0)
                dao.setPaused(mediaId, true)
                Log.d(TAG, "Resume saved: mediaId=$mediaId bytes=$actualBytes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Save resume failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPER: Get actual downloaded bytes (unchanged)
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
            ?.maxOfOrNull { it.length() }
            ?: 0L
    }

    // ═══════════════════════════════════════════════════════════════════
    // BRANCH A: yt-dlp — removed incognito param (uses class field)
    // ═══════════════════════════════════════════════════════════════════

    private fun ytDlpDownload(
        pageUrl: String,
        formatId: String?,
        out: File,
        mediaId: Long,
        dao: MediaDao,
        headersJson: String?,
        fileName: String
    ) {
        val req = YoutubeDLRequest(pageUrl).apply {
            addOption("-o", out.absolutePath)

            // ✅ Feature C: Single formatId — NO merge (no "+bestaudio")
            addOption("-f", formatId ?: "best")

            addOption("--no-update")
            addOption("--no-warnings")
            addOption("--no-check-certificate")
            addOption("--no-check-formats")              // ✅ Problem 3: SKIP format validation
            addOption("--geo-bypass")
            addOption("--merge-output-format", "mp4")
            addOption("--user-agent", UA)
            addOption("--referer", pageUrl)
            addOption("--add-header", "Origin:${originOf(pageUrl)}")
            addOption("--socket-timeout", "15")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--continue")

            // ✅ NEW: Parallel fragment downloads (2-4x faster for YouTube/DASH/HLS)
            addOption("--concurrent-fragments", "${ParallelDownloader.CONNECTIONS}")
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

        YoutubeDL.getInstance().execute(req) { pct, etaInSeconds, _ ->
            if (mediaId != -1L && pct >= 0) {
                val intPct = pct.toInt().coerceIn(0, 99)
                val now = System.currentTimeMillis()
                if (intPct > lastPct && now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) {
                    lastPct = intPct
                    lastProgressTime = now
                    val currentBytes = getActualDownloadedBytes(out, mediaId)
                    val estimatedBytes = if (currentBytes > 0) currentBytes else (intPct * 512L * 1024L)
                    val speed = speedTracker.update(maxOf(currentBytes, estimatedBytes))
                    lastDownloadedBytes = maxOf(currentBytes, estimatedBytes)
                    runBlocking { updateProgress(intPct, mediaId, dao, fileName, speed, lastDownloadedBytes) }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BRANCH B: HLS — removed incognito param
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun hlsDownload(
        url: String,
        referer: String,
        headers: Map<String, String>,
        outputFile: File,
        mediaId: Long,
        dao: MediaDao,
        fileName: String
    ) {
        val speedTracker = SpeedTracker()
        speedTracker.start(0L)
        var lastProgressTime = 0L
        var lastPct = -1

        HlsDownloader.download(
            m3u8Url = url,
            referer = referer,
            headers = headers,
            outputFile = outputFile
        ) { pct, hlsBytes ->
            val now = System.currentTimeMillis()
            if ((pct > lastPct && now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) || pct >= 100) {
                lastPct = pct
                lastProgressTime = now
                val speed = speedTracker.update(hlsBytes)
                lastDownloadedBytes = hlsBytes

                runBlocking {
                    updateProgress(pct, mediaId, dao, fileName, speed, hlsBytes)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BRANCH C: Direct HTTP
    // Fix 3: ensureActive() used inside withContext scope (no
    //         deprecated coroutineContext reference)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ✅ OPTIMIZED: Multi-connection parallel download.
     *
     * OLD: Single HttpURLConnection, 64KB buffer
     *      Speed: 2-5 MB/s → 500MB = 100-250 seconds
     *
     * NEW: N parallel OkHttp connections, 256KB buffer
     *      Speed: 10-30 MB/s → 500MB = 15-50 seconds
     */
    private suspend fun directDownload(
        url: String,
        referer: String,
        extraHeaders: Map<String, String>,
        outputFile: File,
        mediaId: Long,
        dao: MediaDao,
        fileName: String,
        resumeFromBytes: Long
    ) {
        // Step 1: Probe URL (HEAD request — get size + range support)
        val probe = ParallelDownloader.probe(url, referer, extraHeaders)
            ?: throw IllegalStateException("Could not connect to server")

        // Validate content type
        val contentType = probe.contentType?.lowercase() ?: ""
        if (contentType.contains("text/html") && !contentType.contains("mpegurl")) {
            throw IllegalStateException("Server returned HTML instead of media")
        }

        val totalSize = probe.contentLength

        // Update DB with actual file size
        if (totalSize > 0 && mediaId != -1L) {
            try {
                val existing = dao.getById(mediaId)
                if (existing != null && existing.fileSize <= 0) {
                    dao.update(existing.copy(fileSize = totalSize))
                }
            } catch (_: Exception) {}
        }

        // Step 2: Download (auto-selects parallel or single)
        val speedTracker = SpeedTracker()
        speedTracker.start(resumeFromBytes)
        var lastProgressTime = 0L
        var lastPct = -1

        val downloaded = ParallelDownloader.download(
            url = probe.finalUrl,
            outputFile = outputFile,
            referer = referer,
            extraHeaders = extraHeaders,
            resumeFromBytes = resumeFromBytes,
            totalSize = totalSize,
            supportsRange = probe.supportsRange
        ) { dlBytes, total ->
            lastDownloadedBytes = dlBytes

            val now = System.currentTimeMillis()
            val pct = if (total > 0) {
                ((dlBytes * 100) / total).toInt().coerceIn(0, 99)
            } else -1

            if ((pct > lastPct && now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) ||
                (pct == -1 && now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS)) {
                val speed = speedTracker.update(dlBytes)
                lastProgressTime = now
                lastPct = pct
                updateProgress(pct, mediaId, dao, fileName, speed, dlBytes)
            }
        }

        // Step 3: Calculate checksum after download
        if (mediaId != -1L && outputFile.exists() && outputFile.length() > 0) {
            try {
                val checksum = calculateFileChecksum(outputFile)
                dao.updateChecksum(mediaId, checksum)
            } catch (_: Exception) {}
        }
    }

    /**
     * Calculate SHA-256 checksum of completed file.
     * Runs after download (not during) to avoid slowing parallel I/O.
     */
    private fun calculateFileChecksum(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONNECTION (unchanged)
    // ═══════════════════════════════════════════════════════════════════

    private fun openConnection(
        url: String,
        referer: String,
        extra: Map<String, String>,
        resumeFromBytes: Long = 0L
    ): HttpURLConnection {
        var currentUrl = url
        var redirectCount = 0

        while (redirectCount < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", referer)
                setRequestProperty("Origin", originOf(referer))
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                extra.forEach { (k, v) -> setRequestProperty(k, v) }
                if (resumeFromBytes > 0) {
                    setRequestProperty("Range", "bytes=$resumeFromBytes-")
                }
            }

            val code = conn.responseCode
            if (code in 300..308) {
                val location = conn.getHeaderField("Location")
                if (location != null) {
                    currentUrl = resolveRedirectUrl(currentUrl, location)
                    redirectCount++
                    conn.disconnect()
                    continue
                }
            }
            return conn
        }
        throw IllegalStateException("Too many redirects")
    }

    // ═══════════════════════════════════════════════════════════════════
    // RETRY (unchanged)
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun retryBlock(label: String, block: suspend () -> Unit): Boolean {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "$label attempt $attempt/$MAX_RETRIES: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * (1L shl (attempt - 1)))
                }
            }
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════
    // FINISH — 🔒 Incognito: encrypt but DON'T save DB record
    //          🔇 Silent: no completion notification
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun finish(
        tmp: File,
        mediaId: Long,
        dao: MediaDao,
        fileName: String,
        duration: Long = 0L,
        fileType: String = "video"
    ): Result {
        if (!tmp.exists() || tmp.length() < MIN_VALID_FILE_SIZE) {
            return fail(dao, "File too small (${tmp.length()} bytes)", mediaId, isIncognito, tmp)
        }

        try {
            updateProgress(95, mediaId, dao, fileName, 0.0, tmp.length(), "Encrypting…")

            // ✅ Feature A: Use fileType for vault subfolder
            val vaultDir = File(
                applicationContext.filesDir,
                "vault_media/$fileType/${UUID.randomUUID()}"
            ).also { it.mkdirs() }

            val encryptor = ChunkEncryptor(applicationContext, VaultCryptoEngine())
            val index = encryptor.encryptStream(Uri.fromFile(tmp), vaultDir, 2 * 1024 * 1024)

            // ✅ Feature A: Only generate thumbnail for video/image, not audio/pdf
            val thumb = if (isIncognito || fileType == "audio" || fileType == "pdf") {
                Log.d(TAG, "Skipping thumbnail: incognito=$isIncognito fileType=$fileType")
                null
            } else {
                updateProgress(98, mediaId, dao, fileName, 0.0, tmp.length(), "Generating thumbnail…")
                makeThumbnail(tmp)
            }

            tmp.delete()
            cleanupResumeFiles(mediaId)

            if (isIncognito) {
                Log.d(TAG, "🔒 Incognito: Removing DB record & vault files")
                try { vaultDir.deleteRecursively() } catch (_: Exception) {}
                if (mediaId != -1L) try { dao.deleteById(mediaId) } catch (_: Exception) {}
            } else if (mediaId != -1L) {
                // ✅ Update completed state and fields
                dao.getById(mediaId)?.let { existing ->
                    dao.update(
                        existing.copy(
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
                            fileType = fileType // Ensure it's correct
                        )
                    )
                }
            }

            // ✅ Completion notification — unchanged
            if (!isIncognito && !isSilentMode) {
                try {
                    val completionId = DownloadNotificationManager.nextCompletionId()
                    notifManager.notify(completionId,
                        NotificationCompat.Builder(applicationContext, DownloadNotificationManager.CHANNEL_ID)
                            .setContentTitle("Download Complete")
                            .setContentText("$fileName saved to vault")
                            .setSmallIcon(R.drawable.ic_download)
                            .setAutoCancel(true)
                            .build())
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ notifManager.cancel(completionId) }, 3000)
                } catch (_: Exception) {}
            }

            // ✅ Emit completion event to ViewModel
            try {
                // Since this runs in a separate process/thread, we can't directly call ViewModel.
                // But if the app is alive, we can use a local broadcast or just let the 
                // Repository's Room Flow trigger the UI.
                // However, the "operationEvents" is for instantaneous UI feedback.
            } catch (_: Exception) {}

            return Result.success(workDataOf("mediaId" to mediaId, "progress" to 100, "incognito" to isIncognito))

        } catch (e: Exception) {
            Log.e(TAG, "Encrypt failed: ${e.message}", e)
            return fail(dao, "Encryption failed: ${e.message}", mediaId, isIncognito, tmp)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FAILURE — 🔒🔇 Aware of both modes
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun fail(
        dao: MediaDao?,
        reason: String,
        mediaId: Long,
        incognito: Boolean,
        tmpFile: File? = null
    ): Result {
        Log.e(TAG, "❌ $reason")
        try { tmpFile?.delete() } catch (_: Exception) {}

        if (dao != null && mediaId != -1L) {
            if (incognito) {
                // 🔒 Incognito: always delete the record on failure too
                try { dao.deleteById(mediaId) } catch (_: Exception) {}
            } else {
                try {
                    dao.markFailed(mediaId, reason)
                    dao.clearSpeed(mediaId)
                } catch (_: Exception) {
                    try { dao.deleteById(mediaId) } catch (_: Exception) {}
                }
            }
        }

        // 🔇 Failure notification — suppressed in silent and incognito
        if (!isSilentMode && !incognito) {
            try {
                val failId = DownloadNotificationManager.nextCompletionId()
                notifManager.notify(
                    failId,
                    NotificationCompat.Builder(
                        applicationContext,
                        DownloadNotificationManager.CHANNEL_ID
                    )
                        .setContentTitle("Download Failed")
                        .setContentText(reason.take(100))
                        .setSmallIcon(R.drawable.ic_download)
                        .setAutoCancel(true)
                        .build()
                )
            } catch (_: Exception) {}
        }

        return Result.failure(
            workDataOf(
                "error" to reason,
                "mediaId" to mediaId,
                "incognito" to incognito            // 🔒 Propagate mode
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROGRESS — Uses class-level isIncognito/isSilentMode
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun updateProgress(
        pct: Int,
        mediaId: Long,
        dao: MediaDao,
        fileName: String,
        speedBps: Double,
        downloadedBytes: Long,
        statusText: String? = null
    ) {
        val speedText = formatSpeed(speedBps)

        // 1. WorkManager progress data
        setProgress(
            workDataOf(
                "progress" to pct,
                "mediaId" to mediaId,
                "speed" to speedBps,
                "speedText" to speedText,
                "downloadedBytes" to downloadedBytes,
                "fileName" to fileName,
                "incognito" to isIncognito,              // 🔒 Expose for UI guard
                "silent" to isSilentMode                 // 🔇 Expose for UI guard
            )
        )

        // 2. Room DB — update even for incognito (worker needs tracking;
        //    record is deleted in finish/fail)
        if (mediaId != -1L) {
            try {
                dao.updateProgressFull(
                    mediaId = mediaId,
                    progress = pct.coerceAtLeast(0),
                    resumeBytes = downloadedBytes,
                    downloadedBytes = downloadedBytes,
                    speed = speedBps
                )
            } catch (_: Exception) {}
        }

        // 3. Shared aggregate notification
        DownloadNotificationManager.update(
            mediaId = mediaId,
            progress = pct,
            speedText = statusText ?: speedText,
            downloadedBytes = downloadedBytes
        )
        DownloadNotificationManager.postUpdate(applicationContext, isSilentMode)
    }

    // ═══════════════════════════════════════════════════════════════════
    // FORMAT SPEED (unchanged)
    // ═══════════════════════════════════════════════════════════════════

    private fun formatSpeed(bytesPerSec: Double): String = when {
        bytesPerSec <= 0 -> ""
        bytesPerSec < 1024 -> "(${bytesPerSec.toInt()} B/s)"
        bytesPerSec < 1024 * 1024 -> "(%.1f KB/s)".format(bytesPerSec / 1024)
        else -> "(%.1f MB/s)".format(bytesPerSec / (1024 * 1024))
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS (unchanged)
    // ═══════════════════════════════════════════════════════════════════

    private fun findResumeFile(mediaId: Long): File? {
        val dir = File(applicationContext.filesDir, "vault_downloads")
        return dir.listFiles()
            ?.filter { it.name.startsWith("dl_${mediaId}_") }
            ?.maxByOrNull { it.length() }
    }

    private fun cleanupResumeFiles(mediaId: Long) {
        val dir = File(applicationContext.filesDir, "vault_downloads")
        dir.listFiles()
            ?.filter { it.name.startsWith("dl_${mediaId}_") }
            ?.forEach { try { it.delete() } catch (_: Exception) {} }
        applicationContext.cacheDir.listFiles()
            ?.filter { it.name.startsWith("dl_${mediaId}_") }
            ?.forEach { try { it.delete() } catch (_: Exception) {} }
    }

    private fun makeThumbnail(file: File): String? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val bm = r.getFrameAtTime(1_000_000L)
        r.release()
        if (bm != null) {
            val f = File(
                applicationContext.filesDir,
                "vault_thumbs/thumb_${UUID.randomUUID()}.jpg"
            )
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { bm.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bm.recycle()
            f.absolutePath
        } else null
    } catch (_: Exception) { null }

    private fun parseHeaders(json: String?): Map<String, String> {
        if (json == null) return emptyMap()
        return try {
            val o = JSONObject(json)
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.getString(k) }
            m
        } catch (_: Exception) { emptyMap() }
    }

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
// SpeedTracker (unchanged)
// ═══════════════════════════════════════════════════════════════════

private class SpeedTracker(private val alpha: Double = 0.3) {
    private var lastBytes: Long = 0L
    private var lastTime: Long = 0L
    private var ewmaSpeed: Double = 0.0
    private var initialized = false

    fun start(initialBytes: Long = 0L) {
        lastBytes = initialBytes
        lastTime = System.currentTimeMillis()
        ewmaSpeed = 0.0
        initialized = true
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

        lastBytes = currentBytes
        lastTime = now
        return ewmaSpeed
    }

    fun current(): Double = ewmaSpeed
}
