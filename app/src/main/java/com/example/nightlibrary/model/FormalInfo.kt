package com.example.nightlibrary.model

import org.json.JSONObject

/**
 * Represents one downloadable format from yt-dlp JSON.
 * Drives: quality sheet sections, audio indicators, mime types, file types.
 */
data class FormatInfo(
    val formatId: String,
    val url: String,
    val ext: String,
    val height: Int,
    val width: Int,
    val fps: Int,
    val vcodec: String,
    val acodec: String,
    val protocol: String,
    val tbr: Double,
    val abr: Double,
    val vbr: Double,
    val filesize: Long,
    val filesizeApprox: Long,
    val formatNote: String,
    val headers: Map<String, String>,
    val duration: Long,
    val category: Category,
    val hasAudio: Boolean,
    val hasVideo: Boolean
) {
    enum class Category { MUXED, VIDEO_ONLY, AUDIO_ONLY, UNKNOWN }

    val audioIndicator: String get() = when (category) {
        Category.MUXED -> "🔊"; Category.VIDEO_ONLY -> "🔇"
        Category.AUDIO_ONLY -> "🎵"; Category.UNKNOWN -> "❓"
    }

    val displayLabel: String get() = buildString {
        append(ext.uppercase())
        append(" $audioIndicator ")
        when (category) {
            Category.AUDIO_ONLY -> {
                val br = when {
                    abr > 0 -> "${abr.toInt()}kbps"
                    tbr > 0 -> "${tbr.toInt()}kbps"
                    else -> ""
                }
                if (br.isNotEmpty()) append(br) else append(formatNote.ifEmpty { "Audio" })
            }
            else -> {
                when {
                    height > 0 -> append("${height}p")
                    width > 0 -> append("${width}w")
                    formatNote.isNotEmpty() -> append(formatNote)
                    else -> append("Unknown")
                }
                if (fps > 30) append(" ${fps}fps")
            }
        }
    }

    val codecLabel: String get() {
        val vLabel = when {
            !hasVideo -> null
            vcodec.contains("av01") || vcodec.contains("av1") -> "AV1"
            vcodec.contains("vp9") || vcodec.contains("vp09") -> "VP9"
            vcodec.contains("avc") || vcodec.contains("h264") -> "H.264"
            vcodec.contains("hevc") || vcodec.contains("h265") || vcodec.contains("hev") -> "H.265"
            vcodec != "none" && vcodec.isNotEmpty() -> vcodec.take(8)
            else -> null
        }
        val aLabel = when {
            !hasAudio -> null
            acodec.contains("mp4a") || acodec.contains("aac") -> "AAC"
            acodec.contains("opus") -> "Opus"
            acodec.contains("mp3") || acodec.contains("lame") -> "MP3"
            acodec.contains("vorbis") -> "Vorbis"
            acodec.contains("flac") -> "FLAC"
            acodec != "none" && acodec.isNotEmpty() -> acodec.take(8)
            isHls -> "AAC"
            else -> null
        }
        return listOfNotNull(vLabel, aLabel).joinToString(" + ").ifEmpty { ext.uppercase() }
    }

    val estimatedBytes: Long get() = when {
        filesize > 0 -> filesize
        filesizeApprox > 0 -> filesizeApprox
        tbr > 0 && duration > 0 -> (tbr * 1000.0 / 8.0 * duration).toLong()
        abr > 0 && duration > 0 -> (abr * 1000.0 / 8.0 * duration).toLong()
        else -> -1L
    }

    val estimatedSizeLabel: String get() {
        val bytes = estimatedBytes
        return when {
            bytes <= 0 -> "Size unknown"
            bytes < 1024L * 1024 -> "~${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "~${bytes / (1024 * 1024)} MB"
            else -> "~%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    val mimeType: String get() = when (category) {
        Category.AUDIO_ONLY -> when (ext.lowercase()) {
            "m4a", "aac" -> "audio/mp4"; "mp3" -> "audio/mpeg"
            "opus" -> "audio/opus"; "ogg", "oga" -> "audio/ogg"
            "webm" -> "audio/webm"; "flac" -> "audio/flac"
            "wav" -> "audio/wav"; else -> "audio/mp4"
        }
        else -> when (ext.lowercase()) {
            "mp4" -> "video/mp4"; "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"; "3gp" -> "video/3gpp"
            "mov" -> "video/quicktime"; else -> "video/mp4"
        }
    }

    val fileType: String get() = if (category == Category.AUDIO_ONLY) "audio" else "video"

    val isHls: Boolean get() = protocol.contains("m3u8") || url.lowercase().contains(".m3u8")

    val codecMime: String? get() = when {
        vcodec.contains("avc") || vcodec.contains("h264") -> "video/avc"
        vcodec.contains("hevc") || vcodec.contains("h265") || vcodec.contains("hev") -> "video/hevc"
        vcodec.contains("vp9") || vcodec.contains("vp09") -> "video/x-vnd.on2.vp9"
        vcodec.contains("av01") || vcodec.contains("av1") -> "video/av01"
        else -> null
    }

    companion object {
        fun fromJson(obj: JSONObject, duration: Long, globalHeaders: Map<String, String>): FormatInfo? {
            val url = obj.optString("url", "").ifEmpty { obj.optString("manifest_url", "") }
            if (url.isBlank() || url.contains(".mpd")) return null

            val formatId = obj.optString("format_id", "unknown")
            val ext = obj.optString("ext", "mp4").lowercase()
            val height = obj.optInt("height", 0)
            val width = obj.optInt("width", 0)
            val fps = obj.optInt("fps", 0)
            val vcodec = obj.optString("vcodec", "none").lowercase().trim()
            val acodec = obj.optString("acodec", "none").lowercase().trim()
            val protocol = obj.optString("protocol", "https").lowercase()
            val tbr = obj.optDouble("tbr", 0.0)
            val abr = obj.optDouble("abr", 0.0)
            val vbr = obj.optDouble("vbr", 0.0)
            val formatNote = obj.optString("format_note", "")
            val filesize = obj.optLong("filesize", -1L).let {
                if (it <= 0) obj.optLong("filesize_approx", -1L) else it
            }
            val filesizeApprox = obj.optLong("filesize_approx", -1L)

            val headers = mutableMapOf<String, String>().apply {
                putAll(globalHeaders)
                obj.optJSONObject("http_headers")?.let { h ->
                    h.keys().forEach { k -> put(k, h.optString(k)) }
                }
            }

            // Problem 7: HLS audio detection
            val isHlsProtocol = protocol.contains("m3u8") || url.lowercase().contains(".m3u8")
            val rawHasVideo = vcodec != "none" && vcodec.isNotEmpty()
            val rawHasAudio = acodec != "none" && acodec.isNotEmpty()
            val effectiveHasVideo = rawHasVideo || (isHlsProtocol && height > 0)
            val effectiveHasAudio = when {
                rawHasAudio -> true
                isHlsProtocol -> !formatNote.lowercase().contains("video only")
                else -> false
            }

            val category = when {
                effectiveHasVideo && effectiveHasAudio -> Category.MUXED
                effectiveHasVideo && !effectiveHasAudio -> Category.VIDEO_ONLY
                !effectiveHasVideo && effectiveHasAudio -> Category.AUDIO_ONLY
                ext in listOf("m4a", "mp3", "opus", "ogg", "oga", "flac", "wav", "webm")
                        && !rawHasVideo -> Category.AUDIO_ONLY
                else -> Category.UNKNOWN
            }

            if (!effectiveHasVideo && !effectiveHasAudio && category == Category.UNKNOWN) return null

            return FormatInfo(
                formatId, url, ext, height, width, fps, vcodec, acodec, protocol,
                tbr, abr, vbr, filesize, filesizeApprox, formatNote, headers,
                duration, category, effectiveHasAudio, effectiveHasVideo
            )
        }

        fun parseAll(rootJson: JSONObject): List<FormatInfo> {
            val duration = rootJson.optLong("duration", 0L)
            val globalHeaders = mutableMapOf<String, String>()
            rootJson.optJSONObject("http_headers")?.let { h ->
                h.keys().forEach { k -> globalHeaders[k] = h.optString(k) }
            }
            val formats = rootJson.optJSONArray("formats") ?: return emptyList()
            val parsed = mutableListOf<FormatInfo>()
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                fromJson(f, duration, globalHeaders)?.let { parsed.add(it) }
            }
            if (parsed.isEmpty()) {
                val rootUrl = rootJson.optString("url", "")
                if (rootUrl.isNotBlank() && rootUrl.startsWith("http") && !rootUrl.contains(".mpd")) {
                    parsed.add(FormatInfo(
                        rootJson.optString("format_id", "best"), rootUrl,
                        rootJson.optString("ext", "mp4"), rootJson.optInt("height", 0),
                        rootJson.optInt("width", 0), rootJson.optInt("fps", 0),
                        rootJson.optString("vcodec", "h264"), rootJson.optString("acodec", "aac"),
                        rootJson.optString("protocol", "https"), rootJson.optDouble("tbr", 0.0),
                        rootJson.optDouble("abr", 0.0), rootJson.optDouble("vbr", 0.0),
                        rootJson.optLong("filesize", -1L), rootJson.optLong("filesize_approx", -1L),
                        rootJson.optString("format_note", "Best Available"), globalHeaders,
                        duration, Category.MUXED, true, true
                    ))
                }
            }
            return deduplicate(parsed).sortedWith(
                compareBy<FormatInfo> { it.category.ordinal }
                    .thenByDescending { it.height }.thenByDescending { it.tbr }.thenByDescending { it.abr }
            )
        }

        fun groupByCategory(formats: List<FormatInfo>): LinkedHashMap<Category, List<FormatInfo>> {
            val map = LinkedHashMap<Category, List<FormatInfo>>()
            val muxed = formats.filter { it.category == Category.MUXED }.sortedByDescending { it.height * 100 + it.fps }
            val videoOnly = formats.filter { it.category == Category.VIDEO_ONLY }.sortedByDescending { it.height * 100 + it.fps }
            val audioOnly = formats.filter { it.category == Category.AUDIO_ONLY }.sortedByDescending { it.abr.takeIf { b -> b > 0 } ?: it.tbr }
            if (muxed.isNotEmpty()) map[Category.MUXED] = muxed
            if (videoOnly.isNotEmpty()) map[Category.VIDEO_ONLY] = videoOnly
            if (audioOnly.isNotEmpty()) map[Category.AUDIO_ONLY] = audioOnly
            return map
        }

        fun deduplicate(formats: List<FormatInfo>): List<FormatInfo> {
            return formats.groupBy { f ->
                Triple(f.category, when (f.category) {
                    Category.AUDIO_ONLY -> "${f.ext}_${f.abr.toInt()}"
                    else -> "${f.height}_${f.fps}_${f.vcodec.take(4)}"
                }, f.ext)
            }.values.map { group -> group.maxByOrNull { it.tbr + it.abr } ?: group.first() }
        }
    }
}