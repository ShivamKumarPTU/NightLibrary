package com.example.nightlibrary.worker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.nightlibrary.model.FormatInfo
import com.example.nightlibrary.worker.ParallelDownloader.CONNECTIONS
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class VideoInfo(
    val streamUrl: String,
    val pageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val useYtDlp: Boolean = false,
    val formatId: String? = null,
    val isHls: Boolean = false,
    val fileSize: Long = -1L,
    val contentType: String? = null,
    val duration: Long = 0L,          // ← Problem 6: seconds
    val fileType: String = "video",   // ← Feature A: "audio" or "video"
    val hasAudio: Boolean = true      // ← Problem 2: accurate audio flag
)

sealed class ExtractResult {
    data class Success(val json: String) : ExtractResult()
    data class Error(val message: String) : ExtractResult()
}

/**
 * MediaExtractor — Optimized hybrid extractor.
 *
 * Problem 3+8: Raw JSON caching so getFormats() is INSTANT after getVideoInfo()
 * Problem 6: Duration extracted from yt-dlp JSON
 * Problem 7: HLS audio detection via FormatInfo
 */
object MediaExtractor {

    private const val TAG = "MediaExtractor"

    private val semaphore = Semaphore(3)

    // Full result cache (5 min TTL)
    private val cache = ConcurrentHashMap<String, Pair<Long, VideoInfo>>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    // ═══════════════════════════════════════════════════════════════
    // Problem 3+8: RAW JSON CACHE
    // getVideoInfo() stores raw JSON → getFormats() reuses it INSTANTLY
    // ═══════════════════════════════════════════════════════════════
    private val rawJsonCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private const val JSON_CACHE_TTL_MS = 10 * 60 * 1000L

    // HLS URL cache (Bug 1 from original)
    private val hlsUrlCache = ConcurrentHashMap<String, String>()
    private const val HLS_CACHE_TTL_MS = 15 * 60 * 1000L
    private val hlsUrlTimestamps = ConcurrentHashMap<String, Long>()

    // ─── Faster OkHttpClient: shorter timeouts + connection reuse ───
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))  // ✅ HTTP/2 multiplexing
        .connectionPool(ConnectionPool(CONNECTIONS + 4, 5, TimeUnit.MINUTES))
        .build()

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ── Site classification ─────────────────

    private val CDN_TOKEN_SITES = setOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "instagram.com", "instagr.am",
        "tiktok.com", "vm.tiktok.com",
        "twitter.com", "x.com", "t.co",
        "facebook.com", "fb.watch",
        "vimeo.com", "dailymotion.com", "dai.ly", "twitch.tv",
        "reddit.com", "v.redd.it", "bilibili.com", "b23.tv",
        "nicovideo.jp", "streamable.com", "gfycat.com", "redgifs.com", "imgur.com"
    )

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Problem 3+8: Get formats — checks rawJsonCache FIRST for INSTANT response.
     * If getVideoInfo() was called before, this returns in <5ms.
     */
    suspend fun getFormats(url: String, progressListener: ((Double) -> Unit)? = null): ExtractResult {
        val key = cacheKeyOf(url)

        // Check raw JSON cache first — INSTANT if getVideoInfo() was called
        rawJsonCache[key]?.let { (timestamp, json) ->
            if (System.currentTimeMillis() - timestamp < JSON_CACHE_TTL_MS) {
                Log.d(TAG, "⚡ RAW JSON cache hit — INSTANT formats for $key")
                progressListener?.invoke(100.0)
                return ExtractResult.Success(json)
            } else {
                rawJsonCache.remove(key)
            }
        }

        // Cache miss — do full extraction
        semaphore.acquire()
        return try {
            withContext(Dispatchers.IO) {
                // Check if it's a direct media URL — skip yt-dlp entirely
                if (isDirectMediaUrl(url)) {
                    Log.d(TAG, "⚡ Direct media URL detected — skipping yt-dlp")
                    progressListener?.invoke(50.0)
                    val info = buildDirectInfo(url)
                    progressListener?.invoke(100.0)
                    if (info != null) {
                        val json = infoToJson(info)
                        rawJsonCache[key] = System.currentTimeMillis() to json
                        ExtractResult.Success(json)
                    } else {
                        ExtractResult.Error("Could not probe direct URL")
                    }
                } else {
                    val info = extractInternal(url, progressListener)
                    if (info != null) {
                        ExtractResult.Success(infoToJson(info))
                    } else {
                        ExtractResult.Error("Could not extract video from this link.")
                    }
                }
            }
        } finally {
            semaphore.release()
        }
    }

    /**
     * Enhanced getVideoInfo() — caches raw JSON for getFormats() reuse.
     * Problem 3+8: Single yt-dlp call, --no-check-formats
     * Problem 6: Extracts duration field
     */
    // ─── Fix: acquire BEFORE try-block so finally never releases an un-acquired permit ───
    suspend fun getVideoInfo(url: String, progressListener: ((Double) -> Unit)? = null): VideoInfo? {
        semaphore.acquire()                          // throws on cancel — finally won't run
        return try {
            withContext(Dispatchers.IO) { extractInternal(url, progressListener) }
        } finally {
            semaphore.release()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Problem 3: Direct media URL detection — skips yt-dlp entirely
    // ═══════════════════════════════════════════════════════════════

    fun isDirectMediaUrl(url: String): Boolean {
        val l = url.lowercase()
        val path = l.substringBefore("?").substringBefore("#")
        return path.endsWith(".mp4") || path.endsWith(".mp3") ||
                path.endsWith(".m3u8") || path.endsWith(".webm") ||
                path.endsWith(".mkv") || path.endsWith(".m4a") ||
                path.endsWith(".ogg") || path.endsWith(".opus") ||
                path.endsWith(".flac") || path.endsWith(".wav") ||
                path.endsWith(".ts")
    }

    private fun buildDirectInfo(url: String): VideoInfo? {
        val l = url.lowercase()
        val isHls = l.contains(".m3u8")
        val isAudio = l.endsWith(".mp3") || l.endsWith(".m4a") ||
                l.endsWith(".ogg") || l.endsWith(".opus") ||
                l.endsWith(".flac") || l.endsWith(".wav")

        // Try HEAD request for file size
        val probed = tryDirectProbe(url)

        return VideoInfo(
            streamUrl = url,
            pageUrl = url,
            headers = mapOf("User-Agent" to USER_AGENT),
            useYtDlp = false,
            isHls = isHls,
            fileSize = probed?.first ?: -1L,
            contentType = probed?.second,
            fileType = if (isAudio) "audio" else "video",
            hasAudio = isAudio || isHls || !l.endsWith(".webm") // Most direct URLs have audio
        )
    }

    private fun tryDirectProbe(url: String): Pair<Long, String?>? {
        return try {
            val resp = http.newCall(
                Request.Builder().url(url).head()
                    .header("User-Agent", USER_AGENT)
                    .build()
            ).execute()
            val size = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val type = resp.header("Content-Type")
            resp.close()
            Pair(size, type)
        } catch (_: Exception) { null }
    }

    // ═══════════════════════════════════════════════════════════════
    // HLS CACHE
    // ═══════════════════════════════════════════════════════════════

    fun cacheHlsUrl(pageUrl: String, hlsUrl: String) {
        if (pageUrl.isBlank() || hlsUrl.isBlank()) return
        val key = cacheKeyOf(pageUrl)
        hlsUrlCache[key] = hlsUrl
        hlsUrlTimestamps[key] = System.currentTimeMillis()
        Log.d(TAG, "⚡ Cached HLS: $key → ${hlsUrl.take(80)}…")
    }

    fun getCachedHlsUrl(pageUrl: String): String? {
        val key = cacheKeyOf(pageUrl)
        val url = hlsUrlCache[key] ?: return null
        val timestamp = hlsUrlTimestamps[key] ?: return null
        if (System.currentTimeMillis() - timestamp > HLS_CACHE_TTL_MS) {
            hlsUrlCache.remove(key)
            hlsUrlTimestamps.remove(key)
            return null
        }
        return url
    }

    fun clearHlsCache() {
        hlsUrlCache.clear()
        hlsUrlTimestamps.clear()
    }

    fun clearCache() {
        cache.clear()
        rawJsonCache.clear()
        clearHlsCache()
    }

    // ═══════════════════════════════════════════════════════════════
    // CORE EXTRACTION
    // ═══════════════════════════════════════════════════════════════

    private suspend fun extractInternal(
        raw: String,
        progressListener: ((Double) -> Unit)? = null
    ): VideoInfo? {
        val url = normalizeUrl(raw)
        val host = hostOf(url)
        Log.d(TAG, "▶ extract host=$host url=$url")

        // ── 1. Full result cache ─────────────────────────────────────────
        cache[url]?.let { (ts, info) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                Log.d(TAG, "✅ Cache hit: ${info.streamUrl}")
                progressListener?.invoke(100.0); return info
            }
            cache.remove(url)
        }

        // ── 2. Direct media URL ──────────────────────────────────────────
        if (isDirectMediaUrl(url)) {
            progressListener?.invoke(55.0)
            return buildDirectInfo(url)?.also { progressListener?.invoke(100.0) }
        }

        // ── 3. HLS cache ────────────────────────────────────────────────
        getCachedHlsUrl(url)?.let { hlsUrl ->
            Log.d(TAG, "⚡ HLS cache hit in extractInternal: $hlsUrl")
            progressListener?.invoke(100.0)
            return VideoInfo(
                streamUrl = hlsUrl, pageUrl = url, isHls = true, useYtDlp = false,
                headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
            )
        }

        // ✅ REMOVED: progressListener?.invoke(12.0)  — was killing fake animation
        // ✅ REMOVED: progressListener?.invoke(15.0)  — was killing fake animation

        val preferYtDlp = CDN_TOKEN_SITES.any { host.contains(it) }

        // Map sub-extractor progress into the 15-88 window
        val mapped: ((Double) -> Unit)? = progressListener?.let { pl ->
            { pct: Double -> pl((15.0 + pct * 0.73).coerceAtMost(88.0)) }
        }

        // ✅ REMOVED: progressListener?.invoke(15.0)  — duplicate, was killing animation

        // ── 4. Race ALL three strategies simultaneously ──────────────────
        val result = raceExtractors(url, host, preferYtDlp, mapped)

        // ✅ REMOVED: progressListener?.invoke(90.0)  — let completion animation handle
        // ✅ REMOVED: progressListener?.invoke(96.0)  — let completion animation handle

        return if (result != null) {
            val validated = if (result.useYtDlp) result else validateStreamUrl(result)
            validated?.also {
                cache[url] = System.currentTimeMillis() to it
                autoCacheHls(it)
            }
        } else null
    }
    /**
     * Fires all three extractors simultaneously. First non-null result wins;
     * the remaining two are cancelled immediately.
     *
     * Worst-case latency = max(slowest_extractor_timeout) = 10s
     * vs old worst-case = scrape_timeout + yt-dlp_timeout = 5 + 15 = 20s
     *
     * Delivery protocol:
     *  • non-null result  → complete(result)  — first call wins, extras are no-ops
     *  • null / failure   → increment counter — when all 3 report, complete(null)
     *  • cancellation     → count as failure  — prevents await() from hanging
     */
    private suspend fun raceExtractors(
        url: String,
        host: String,
        preferYtDlp: Boolean,
        progressListener: ((Double) -> Unit)?
    ): VideoInfo? = coroutineScope {

        val first    = CompletableDeferred<VideoInfo?>()
        val failures = AtomicInteger(0)
        val TOTAL    = 3

        fun deliver(v: VideoInfo?) {
            if (v != null) { first.complete(v); return }     // winner — extras are no-ops
            if (failures.incrementAndGet() >= TOTAL) first.complete(null)   // all failed
        }

        // ── Strategy A: HTML scrape — pure HTTP+regex, cheapest (0.3–4s) ──────
        val scrapeJob = launch(Dispatchers.IO) {
            try {
                deliver(withTimeoutOrNull(4_500L) {
                    fastScrape(url, host)?.also { autoCacheHls(it) }
                })
            } catch (e: CancellationException) { deliver(null); throw e }
            catch (_: Exception)              { deliver(null) }
        }

        // ── Strategy B: WebView network interception — catches HLS/AJAX (1–10s) ─
        val wvJob = launch(Dispatchers.IO) {
            try {
                deliver(withTimeoutOrNull(10_000L) {
                    webViewIntercept(url)?.copy(useYtDlp = false)?.also { autoCacheHls(it) }
                })
            } catch (e: CancellationException) { deliver(null); throw e }
            catch (_: Exception)              { deliver(null) }
        }

        // ── Strategy C: yt-dlp — most reliable for CDN/auth-gated sites (2–10s) ─
        val ytJob = launch(Dispatchers.IO) {
            try {
                val maxHeight = try { com.example.nightlibrary.util.DeviceCapabilityUtil.getSafeDownloadHeight() } catch (_: Exception) { 1080 }
                deliver(withTimeoutOrNull(10_000L) {
                    tryYtDlp(url, progressListener, maxHeight)?.copy(useYtDlp = preferYtDlp)?.also { autoCacheHls(it) }
                })
            } catch (e: CancellationException) { deliver(null); throw e }
            catch (_: Exception)              { deliver(null) }
        }

        val winner = first.await()

        // Cancel the two losers — their CancellationException handlers call deliver(null),
        // but first is already completed so those calls are no-ops.
        scrapeJob.cancel(); wvJob.cancel(); ytJob.cancel()
        winner
    }

    // ═══════════════════════════════════════════════════════════════
    // LAYER: yt-dlp
    // ═══════════════════════════════════════════════════════════════

    private fun tryYtDlp(
        url: String,
        progressListener: ((Double) -> Unit)? = null,
        maxHeight: Int = try { com.example.nightlibrary.util.DeviceCapabilityUtil.getSafeDownloadHeight() } catch (_: Exception) { 1080 }
    ): VideoInfo? {
        return try {
            if (!com.example.nightlibrary.NightLibraryApp.isYtDlpReady()) {
                if (com.example.nightlibrary.NightLibraryApp.isInitInProgress()) {
                    Log.d(TAG, "⏳ Waiting for yt-dlp init...")
                    val ready = kotlinx.coroutines.runBlocking {
                        com.example.nightlibrary.NightLibraryApp.waitForInit(8_000L)
                    }
                    if (!ready) {
                        Log.w(TAG, "⚠️ yt-dlp init timeout — skipping this strategy")
                        return null
                    }
                    Log.d(TAG, "✅ yt-dlp ready — proceeding")
                } else {
                    Log.w(TAG, "⚠️ yt-dlp not initialized — skipping this strategy")
                    return null
                }
            }
            val req = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--no-check-formats")          // skip format probing entirely
                addOption("--no-check-certificate")
                addOption("--geo-bypass")
                addOption("--no-warnings")
                addOption("--no-cache-dir")
                addOption("--socket-timeout", "6")       // was 10 — fail fast, let other strategies win
                addOption("--retries", "0")              // was 1 — one shot; WebView/scrape are running in parallel anyway
                addOption("--user-agent", USER_AGENT)
                addOption("--add-header", "Accept:text/html,application/xhtml+xml,*/*;q=0.8")
                addOption("--add-header", "Referer:$url")

                // YouTube: Android client skips JS challenge and age-gate — measurably faster
                val h = hostOf(url)
                if (h.contains("youtube") || h.contains("youtu.be")) {
                    addOption("--extractor-args", "youtube:player_client=ios,android,web")
                }
            }
            val out = YoutubeDL.getInstance().execute(req) { pct, _, _ ->
                progressListener?.invoke(pct.toDouble())
            }.out
            if (out.isBlank()) return null

            // Cache raw JSON so getFormats() is INSTANT
            val key = cacheKeyOf(url)
            rawJsonCache[key] = System.currentTimeMillis() to out

            val root = JSONObject(out)

            // Extract duration
            val duration = root.optLong("duration", 0L)

            // Parse all formats using FormatInfo for accurate categorization
            val allFormats = FormatInfo.parseAll(root)

            // Find best muxed format (has video + audio) - AVOID AV1
            val bestMuxed = allFormats
                .filter { it.category == FormatInfo.Category.MUXED && it.height <= maxHeight && it.vcodec != "av1" }
                .maxByOrNull { it.height * 1000 + it.tbr.toInt() }
                ?: allFormats
                    .filter { it.hasVideo && it.vcodec != "av1" }
                    .maxByOrNull { it.height * 1000 + it.tbr.toInt() }

            val bestUrl = bestMuxed?.url
                ?: root.optString("url", "").ifEmpty { null }
                ?: return null

            if (!bestUrl.startsWith("http")) return null

            val hdrs = mutableMapOf<String, String>()
            root.optJSONObject("http_headers")?.let { h ->
                h.keys().forEach { k -> hdrs[k] = h.optString(k) }
            }

            val fileSize = bestMuxed?.estimatedBytes
                ?: root.optLong("filesize", -1).let {
                    if (it <= 0) root.optLong("filesize_approx", -1) else it
                }

            val info = VideoInfo(
                streamUrl = bestUrl,
                pageUrl = url,
                headers = hdrs,
                isHls = isM3U8(bestUrl),
                fileSize = fileSize,
                duration = duration,
                fileType = bestMuxed?.fileType ?: "video",
                hasAudio = bestMuxed?.hasAudio ?: true,
                formatId = bestMuxed?.formatId
            )

            autoCacheHls(info)
            info
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp: ${e.message?.take(120)}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // parseVideoInfo — used by DownloadFormLink to extract quick info
    // ═══════════════════════════════════════════════════════════════

    fun parseVideoInfo(json: String, maxHeight: Int = 1080): VideoInfo? {
        return try {
            val root = JSONObject(json)
            val allFormats = FormatInfo.parseAll(root)
            val duration = root.optLong("duration", 0L)

            // ✅ FIX: Filter out AV1 formats to ensure thumbnail and seekbar compatibility
            val bestMuxed = allFormats
                .filter { it.category == FormatInfo.Category.MUXED && it.height <= maxHeight && it.vcodec != "av1" }
                .maxByOrNull { it.height * 1000 + it.tbr.toInt() }
                ?: allFormats.filter { it.hasVideo && it.vcodec != "av1" }.maxByOrNull { it.height * 1000 + it.tbr.toInt() }

            val bestUrl = bestMuxed?.url
                ?: root.optString("url", "").ifEmpty { null }
                ?: return null

            val hdrs = mutableMapOf<String, String>()
            root.optJSONObject("http_headers")?.let { h ->
                h.keys().forEach { k -> hdrs[k] = h.optString(k) }
            }

            VideoInfo(
                streamUrl = bestUrl,
                pageUrl = root.optString("webpage_url", root.optString("original_url", "")),
                headers = hdrs,
                isHls = isM3U8(bestUrl),
                fileSize = bestMuxed?.estimatedBytes ?: -1L,
                duration = duration,
                fileType = bestMuxed?.fileType ?: "video",
                hasAudio = bestMuxed?.hasAudio ?: true,
                formatId = bestMuxed?.formatId
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseVideoInfo error: ${e.message}")
            null
        }
    }

    private fun fastScrape(url: String, host: String): VideoInfo? {
        val html = fetchHtml(url) ?: return null
        return siteSpecificScrape(html, url, host) ?: genericScrape(html, url)
    }

    private fun validateStreamUrl(info: VideoInfo): VideoInfo? {
        if (info.useYtDlp) return info
        return try {
            val req = Request.Builder().url(info.streamUrl).head().apply {
                header("User-Agent", USER_AGENT)
                header("Referer", info.pageUrl)
                header("Origin", originOf(info.pageUrl))
                info.headers.forEach { (k, v) -> header(k, v) }
            }.build()
            val resp = http.newCall(req).execute()
            val code = resp.code
            val contentType = resp.header("Content-Type", "")?.lowercase() ?: ""
            val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            resp.close()
            when {
                code == 403 || code == 401 -> info
                code !in 200..399 -> null
                contentType.contains("text/html") && !info.isHls -> null
                else -> info.copy(fileSize = contentLength, contentType = contentType)
            }
        } catch (_: Exception) { info }
    }

    // WebView layer
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun webViewIntercept(pageUrl: String): VideoInfo? {
        val done = CompletableDeferred<VideoInfo?>()
        val handler = Handler(Looper.getMainLooper())
        var wvRef: WebView? = null
        var isCleanedUp = false

        fun cleanup() {
            if (isCleanedUp) return; isCleanedUp = true
            handler.post {
                try { wvRef?.stopLoading(); wvRef?.loadUrl("about:blank"); wvRef?.clearHistory(); wvRef?.removeAllViews(); wvRef?.destroy() } catch (_: Exception) {}
                wvRef = null
            }
        }

        handler.post {
            val ctx = appCtx() ?: run { done.complete(null); return@post }
            try {
                val wv = WebView(ctx); wvRef = wv
                wv.settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; loadWithOverviewMode = true
                    useWideViewPort = true; mediaPlaybackRequiresUserGesture = false
                    userAgentString = USER_AGENT; cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    blockNetworkImage = true; loadsImagesAutomatically = false
                }
                // Timeout for the whole WebView session
                handler.postDelayed({
                    if (!done.isCompleted) { done.complete(null); cleanup() }
                }, 10_000L)    // was 18_000L
                wv.webViewClient = object : WebViewClient() {
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldInterceptRequest(v: WebView?, u: String?): WebResourceResponse? {
                        return null
                    }

                    override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? {
                        val u = r?.url?.toString() ?: return null
                        
                        if (isIrrelevantAd(u)) {
                            Log.v(TAG, "🚫 Blocked ad URL: ${u.take(60)}...")
                            return null
                        }

                        if (!done.isCompleted && looksLikeVideoStream(u, pageUrl)) {
                            val h = buildMap {
                                r.requestHeaders?.forEach { (k, v2) -> put(k, v2) }
                                putIfAbsent("User-Agent", USER_AGENT)
                                putIfAbsent("Referer", pageUrl)
                                putIfAbsent("Origin", originOf(pageUrl))
                                val cookies = try { CookieManager.getInstance().getCookie(pageUrl) } catch (_: Exception) { null }
                                if (!cookies.isNullOrBlank()) putIfAbsent("Cookie", cookies)
                            }
                            val info = VideoInfo(u, pageUrl, h, isHls = isM3U8(u))
                            autoCacheHls(info); done.complete(info)
                            handler.postDelayed({ cleanup() }, 500)
                        }
                        return null
                    }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onReceivedError(v: WebView?, c: Int, d: String?, u: String?) {
                        if (u == pageUrl && !done.isCompleted) { done.complete(null); cleanup() }
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView?, u: String?): Boolean {
                        return false
                    }
                }
                wv.loadUrl(pageUrl)
            } catch (e: Exception) { cleanup(); if (!done.isCompleted) done.complete(null) }
        }
        return try { done.await() } finally { cleanup() }
    }

    private fun looksLikeVideoStream(u: String, pageUrl: String): Boolean {
        val l = u.lowercase()
        val videoHost = hostOf(u)
        val pageHost = hostOf(pageUrl)

        if (isIrrelevantAd(u)) return false

        if (l.endsWith(".js") || l.endsWith(".css") || l.endsWith(".png") || l.endsWith(".jpg") ||
            l.endsWith(".gif") || l.endsWith(".svg") || l.endsWith(".woff") || l.endsWith(".woff2") ||
            l.endsWith(".ttf") || l.contains("analytics") || l.contains("tracking") ||
            l.contains("googlesyndication") || l.contains("doubleclick") ||
            l.contains("adserver") || l.contains("facebook.com/tr") ||
            l.contains("pixel") || l.contains("/ads/") || l.contains("adskeeper") ||
            l.contains("taboola") || l.contains("outbrain") || l.contains("popads")
        ) return false
        
        // Stricter video matching
        val isVideo = isM3U8(u) || (l.contains(".mp4") && !l.contains(".mp4.js")) || 
                l.contains(".webm") || l.contains("videoplayback") || 
                l.contains("/hls/") || l.contains("get_file") ||
                (l.contains("/cdn") && l.contains("mp4")) || (l.contains("video") && l.contains("cdn"))

        if (!isVideo) return false

        // Problem 4: Host-matching logic to filter out ad-videos on the same page
        if (pageHost.isNotEmpty()) {
            if (pageHost.contains("xhamster") && !videoHost.contains("xhcdn.com") && !videoHost.contains("xhamster")) {
                return false
            }
            if ((pageHost.contains("youtube.com") || pageHost.contains("youtu.be")) && 
                !videoHost.contains("googlevideo.com") && !videoHost.contains("youtube.com")) {
                return false
            }
            if (pageHost.contains("instagram.com") && !videoHost.contains("cdninstagram.com") && !videoHost.contains("instagram.com")) {
                return false
            }
        }

        // Avoid short fragments or low-quality ad segments
        if (l.contains("ad_") || l.contains("_ad") || l.contains("segment") || l.contains("/frag/")) {
            Log.v(TAG, "⚠️ Potential ad segment skipped: ${u.take(60)}")
            return false
        }

        // ❌ AVOID AV1: Most Android devices fail to generate thumbnails for AV1
        if (l.contains(".av1.")) {
            Log.v(TAG, "❌ Skipping AV1 stream: ${u.take(60)}")
            return false
        }

        return true
    }

    private fun isIrrelevantAd(u: String): Boolean {
        val l = u.lowercase()
        val adDomains = listOf(
            "doubleclick.net", "googlesyndication.com", "adnxs.com", "mads.amazon.com",
            "taboola.com", "outbrain.com", "mgid.com", "popads.net", "propellerads.com",
            "exoclick.com", "juicyads.com", "ero-advertising.com", "trafficjunky.com",
            "realsrv.com", "yads.io", "openx.net", "ad-system.com", "mobicow.com",
            "syndication.exoclick.com", "syndication.realsrv.com"
        )
        return adDomains.any { l.contains(it) } || l.contains("/ads/") || l.contains("ad-system") || l.contains("pixel")
    }

    private fun siteSpecificScrape(html: String, pageUrl: String, host: String): VideoInfo? {
        return null
    }

    private fun genericScrape(html: String, pageUrl: String): VideoInfo? {
        val m3u8 = Regex("""['"](\bhttps?://[^'"]+\.m3u8[^'"]*)['"]""").findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") }
            .firstOrNull { !it.contains(".av1.") }
            
        if (!m3u8.isNullOrBlank()) {
            val info = VideoInfo(resolveUrl(m3u8, pageUrl), pageUrl, mapOf("Referer" to pageUrl), isHls = true)
            autoCacheHls(info); return info
        }
        
        val mp4 = Regex("""['"](\bhttps?://[^'"]+\.mp4(?:[?#][^'"]*)?)['"]""").findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") }
            .firstOrNull { !it.contains(".av1.") }

        if (!mp4.isNullOrBlank()) return VideoInfo(resolveUrl(mp4, pageUrl), pageUrl, mapOf("Referer" to pageUrl))
        val src = Regex("""<(?:video|source)[^>]+src=['"](\bhttps?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
        if (!src.isNullOrBlank()) { val info = VideoInfo(src, pageUrl, mapOf("Referer" to pageUrl), isHls = isM3U8(src)); autoCacheHls(info); return info }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun autoCacheHls(info: VideoInfo) {
        if (info.isHls && info.streamUrl.isNotBlank() && info.pageUrl.isNotBlank()) {
            cacheHlsUrl(info.pageUrl, info.streamUrl)
        }
    }

    private fun cacheKeyOf(url: String): String =
        normalizeUrl(url).substringBefore("?").substringBefore("#").trimEnd('/').lowercase()

    private fun fetchHtml(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,*/*;q=0.8").header("Accept-Language", "en-US,en;q=0.5")
            .header("Referer", url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) { null }

    fun isM3U8(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".m3u8") || (l.contains("m3u8") && l.contains("playlist"))
    }

    private fun hostOf(url: String): String {
        return try {
            val noProtocol = url.substringAfter("://")
            val hostPort = noProtocol.substringBefore("/")
            hostPort.substringBefore(":").lowercase()
        } catch (_: Exception) {
            ""
        }
    }

    private fun originOf(url: String): String {
        return try {
            val protocol = url.substringBefore("://")
            val noProtocol = url.substringAfter("://")
            val hostPort = noProtocol.substringBefore("/")
            "$protocol://$hostPort"
        } catch (_: Exception) {
            url
        }
    }

    private fun resolveUrl(path: String, base: String): String {
        return when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> {
                val origin = originOf(base)
                "$origin$path"
            }
            else -> {
                val slash = base.lastIndexOf('/')
                if (slash >= 0) base.substring(0, slash + 1) + path else path
            }
        }
    }
    private fun normalizeUrl(url: String): String = url
    private fun appCtx(): Context? = try { YoutubeDL.getInstance().javaClass.getDeclaredField("appContext").also { it.isAccessible = true }.get(YoutubeDL.getInstance()) as? Context } catch (_: Exception) { null }

    private fun infoToJson(i: VideoInfo): String = JSONObject().apply {
        put("url", i.streamUrl); put("pageUrl", i.pageUrl); put("useYtDlp", i.useYtDlp)
        put("isHls", i.isHls); put("duration", i.duration)
        i.formatId?.let { put("formatId", it) }
        if (i.fileSize > 0) put("filesize", i.fileSize)
        if (i.headers.isNotEmpty()) put("http_headers", JSONObject(i.headers))
        put("title", "Secured Media"); put("extractor_key", "nightlibrary_hybrid")
        put("vcodec", "h264"); put("acodec", if (i.hasAudio) "aac" else "none")
        put("formats", JSONArray().apply {
            put(JSONObject().apply {
                put("url", i.streamUrl); put("format_note", if (i.isHls) "HLS Stream" else "Direct Stream")
                put("ext", "mp4"); put("vcodec", "h264"); put("acodec", if (i.hasAudio) "aac" else "none")
                put("protocol", if (i.isHls) "m3u8" else "https")
                if (i.fileSize > 0) put("filesize", i.fileSize)
                if (i.duration > 0) put("duration", i.duration)
            })
        })
    }.toString()
}
