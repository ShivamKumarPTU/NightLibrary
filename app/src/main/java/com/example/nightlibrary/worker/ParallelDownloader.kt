package com.example.nightlibrary.worker

import com.example.nightlibrary.util.UserAgentManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Multi-connection parallel HTTP downloader.
 *
 * Splits file into N segments, downloads simultaneously to different
 * file offsets using RandomAccessFile. No merge step needed.
 *
 * SPEED COMPARISON (500MB file, typical CDN):
 *   Single connection:    2-5 MB/s  →  100-250 seconds
 *   4 parallel:           8-20 MB/s →  25-60 seconds
 *   6 parallel:          12-30 MB/s →  15-40 seconds
 *
 * Falls back to single connection if server doesn't support Range.
 */
object ParallelDownloader {

    private const val TAG = "ParallelDL"

    // Adaptive based on CPU cores (most phones: 4-8 cores → up to 12 connections)
    val CONNECTIONS = Runtime.getRuntime().availableProcessors().coerceIn(4, 12)

    private const val BUFFER_SIZE = 1 * 1024 * 1024          // 1MB per connection — Fast extraction/write
    private const val MIN_PARALLEL_SIZE = 2 * 1024 * 1024L  // 2MB minimum for parallel
    private const val SEGMENT_RETRY_COUNT = 5
    private const val SEGMENT_RETRY_DELAY_MS = 2000L

    // 🔥 Phase 5: Rotated User-Agent
    private val USER_AGENT get() = UserAgentManager.getRandomUA()

    // OkHttp with connection pooling — reuses TCP sockets
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))  // ✅ HTTP/2 multiplexing
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .build()

    // ═══════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════

    data class ProbeResult(
        val contentLength: Long,
        val supportsRange: Boolean,
        val contentType: String?,
        val finalUrl: String
    )

    // ═══════════════════════════════════════════════════════════════
    // PROBE — HEAD request to check size + Range support
    // ═══════════════════════════════════════════════════════════════

    suspend fun probe(
        url: String,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): ProbeResult? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Origin", originOf(referer))
                .header("Accept", "*/*")
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                .build()

            val response = client.newCall(request).execute()

            if (response.code !in 200..299) {
                response.close()
                return@withContext null
            }

            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val acceptRanges = response.header("Accept-Ranges")?.lowercase()

            // Some servers don't send Accept-Ranges but still support it
            // Heuristic: if Content-Length is present, try Range
            val supportsRange = acceptRanges == "bytes" ||
                    (contentLength > 0 && acceptRanges != "none")

            val contentType = response.header("Content-Type")
            val finalUrl = response.request.url.toString()

            response.close()

            Log.d(TAG, "Probe: size=$contentLength range=$supportsRange " +
                    "type=$contentType redirected=${finalUrl != url}")

            ProbeResult(contentLength, supportsRange, contentType, finalUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Probe failed: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MAIN DOWNLOAD — Auto-selects parallel or single
    // ═══════════════════════════════════════════════════════════════

    /**
     * Downloads URL to outputFile using parallel connections when possible.
     *
     * @return Total bytes downloaded
     */
    suspend fun download(
        url: String,
        outputFile: File,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
        resumeFromBytes: Long = 0L,
        totalSize: Long = -1L,
        supportsRange: Boolean = false,
        connectionCount: Int = CONNECTIONS,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {

        val canParallel = supportsRange &&
                totalSize > MIN_PARALLEL_SIZE &&
                resumeFromBytes == 0L

        if (canParallel) {
            val effectiveConnections = connectionCount.coerceIn(2, 16)
            Log.d(TAG, "⚡ Parallel: $effectiveConnections connections, " +
                    "${totalSize / (1024 * 1024)}MB")

            try {
                parallelDownload(url, outputFile, referer, extraHeaders,
                    totalSize, effectiveConnections, onProgress)
            } catch (e: Exception) {
                // If parallel fails, fall back to single
                Log.w(TAG, "Parallel failed, falling back to single: ${e.message}")
                if (outputFile.exists()) outputFile.delete()
                singleDownload(url, outputFile, referer, extraHeaders,
                    0L, totalSize, onProgress)
            }
        } else {
            Log.d(TAG, "Single connection: resume=$resumeFromBytes " +
                    "size=$totalSize range=$supportsRange")
            singleDownload(url, outputFile, referer, extraHeaders,
                resumeFromBytes, totalSize, onProgress)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PARALLEL DOWNLOAD
    // ═══════════════════════════════════════════════════════════════

    private suspend fun parallelDownload(
        url: String,
        outputFile: File,
        referer: String,
        extraHeaders: Map<String, String>,
        totalSize: Long,
        connectionCount: Int,
        onProgress: suspend (Long, Long) -> Unit
    ): Long = coroutineScope {

        val numSegments = connectionCount
        val segmentSize = totalSize / numSegments

        // Pre-allocate output file
        outputFile.parentFile?.mkdirs()
        RandomAccessFile(outputFile, "rw").use { it.setLength(totalSize) }

        val totalDownloaded = AtomicLong(0L)
        val lastReportTime = AtomicLong(0L)   // ✅ FIX 1: AtomicLong instead of @Volatile

        // Define segments
        val segments = (0 until numSegments).map { i ->
            val start = i * segmentSize
            val end = if (i == numSegments - 1) totalSize - 1
            else (i + 1) * segmentSize - 1
            Triple(i, start, end)
        }

        Log.d(TAG, "Segments: ${segments.map {
            "${it.first}: ${it.second / (1024*1024)}MB-${it.third / (1024*1024)}MB"
        }}")

        // Download ALL segments in parallel
        val jobs = segments.map { (index, start, end) ->
            async(Dispatchers.IO) {
                downloadSegment(
                    url = url,
                    outputFile = outputFile,
                    referer = referer,
                    extraHeaders = extraHeaders,
                    rangeStart = start,
                    rangeEnd = end,
                    segmentIndex = index
                ) { bytesRead ->                    // ✅ FIX 2: Now suspend lambda
                    val current = totalDownloaded.addAndGet(bytesRead)
                    val now = System.currentTimeMillis()
                    if (now - lastReportTime.get() >= 300) {
                        lastReportTime.set(now)
                        onProgress(current, totalSize)  // ✅ Works now — both are suspend
                    }
                }
            }
        }

        // Wait for all segments to complete
        jobs.awaitAll()

        val finalBytes = totalDownloaded.get()
        onProgress(finalBytes, totalSize)

        Log.d(TAG, "✅ Parallel download done: ${finalBytes / (1024 * 1024)}MB")
        finalBytes
    }
    // ═══════════════════════════════════════════════════════════════
    // SINGLE SEGMENT DOWNLOAD (with retry)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun downloadSegment(
        url: String,
        outputFile: File,
        referer: String,
        extraHeaders: Map<String, String>,
        rangeStart: Long,
        rangeEnd: Long,
        segmentIndex: Int,
        onBytesRead: suspend (Long) -> Unit    // ✅ FIX 2: Added "suspend"
    ) {
        var attempt = 0
        var lastException: Exception? = null
        var bytesWrittenSoFar = 0L

        while (attempt < SEGMENT_RETRY_COUNT) {
            try {
                val effectiveStart = rangeStart + bytesWrittenSoFar

                if (effectiveStart > rangeEnd) return

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", referer)
                    .header("Origin", originOf(referer))
                    .header("Range", "bytes=$effectiveStart-$rangeEnd")
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                    .build()

                val response = client.newCall(request).execute()

                if (response.code !in 200..299) {
                    response.close()
                    throw Exception("Segment $segmentIndex: HTTP ${response.code}")
                }

                val body = response.body
                    ?: throw Exception("Segment $segmentIndex: empty body")

                try {
                    RandomAccessFile(outputFile, "rw").use { raf ->
                        raf.seek(effectiveStart)

                        body.byteStream().buffered(BUFFER_SIZE).use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var position = effectiveStart

                            while (position <= rangeEnd) {
                                coroutineContext.ensureActive()

                                val maxRead = minOf(
                                    BUFFER_SIZE.toLong(),
                                    rangeEnd - position + 1
                                ).toInt()

                                val n = input.read(buffer, 0, maxRead)
                                if (n == -1) break

                                raf.write(buffer, 0, n)
                                position += n
                                bytesWrittenSoFar += n
                                onBytesRead(n.toLong())

                                // 🔥 CRITICAL: Force yield to check for cancellation
                                kotlinx.coroutines.yield()
                            }
                        }
                    }
                } finally {
                    response.close()
                }

                Log.d(TAG, "Segment $segmentIndex done: " +
                        "${rangeStart / (1024*1024)}MB-${rangeEnd / (1024*1024)}MB")
                return

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                attempt++
                if (attempt < SEGMENT_RETRY_COUNT) {
                    Log.w(TAG, "Segment $segmentIndex attempt $attempt failed: " +
                            "${e.message}, retrying...")
                    delay(SEGMENT_RETRY_DELAY_MS * attempt)
                }
            }
        }

        throw lastException ?: Exception("Segment $segmentIndex failed after retries")
    }

    // ═══════════════════════════════════════════════════════════════
    // SINGLE CONNECTION FALLBACK (bigger buffer than original)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun singleDownload(
        url: String,
        outputFile: File,
        referer: String,
        extraHeaders: Map<String, String>,
        resumeFromBytes: Long,
        totalSize: Long,
        onProgress: suspend (Long, Long) -> Unit
    ): Long {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", originOf(referer))
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }

        if (resumeFromBytes > 0) {
            requestBuilder.header("Range", "bytes=$resumeFromBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 416) {
            response.close()
            if (outputFile.exists() && outputFile.length() > 1024) {
                return outputFile.length()
            }
            throw Exception("Range not satisfiable")
        }

        if (response.code !in 200..299) {
            response.close()
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw Exception("Empty response body")

        val reportedTotal = if (response.code == 206) {
            response.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                ?: (body.contentLength() + resumeFromBytes)
        } else {
            body.contentLength().let { if (it > 0) it else totalSize }
        }

        outputFile.parentFile?.mkdirs()
        var downloaded = resumeFromBytes

        try {
            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.seek(resumeFromBytes)

                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)

                    while (true) {
                        coroutineContext.ensureActive()

                        val n = input.read(buffer)
                        if (n == -1) break

                        raf.write(buffer, 0, n)
                        downloaded += n

                        onProgress(downloaded, reportedTotal)
                    }
                }
            }
        } finally {
            response.close()
        }

        return downloaded
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun originOf(url: String) = try {
        val u = android.net.Uri.parse(url)
        "${u.scheme}://${u.host}"
    } catch (_: Exception) { url }
}