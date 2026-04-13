package com.example.nightlibrary.util

import android.media.MediaCodecList
import android.util.Log

object DeviceCapabilityUtil {
    private const val TAG = "DeviceCapability"
    private val maxHeightCache = mutableMapOf<String, Int>()

    fun getMaxSupportedHeight(codecMime: String): Int {
        maxHeightCache[codecMime]?.let { return it }
        val result = try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            var maxHeight = 0
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                val caps = try { info.getCapabilitiesForType(codecMime) } catch (_: Exception) { continue }
                val videoCaps = caps?.videoCapabilities ?: continue
                val h = videoCaps.supportedHeights.upper
                if (h > maxHeight) maxHeight = h
            }
            if (maxHeight == 0) 1080 else maxHeight
        } catch (_: Exception) { 1080 }
        maxHeightCache[codecMime] = result
        return result
    }

    fun getSafeDownloadHeight(): Int {
        val avc = getMaxSupportedHeight("video/avc")
        val hevc = getMaxSupportedHeight("video/hevc")
        val vp9 = try { getMaxSupportedHeight("video/x-vnd.on2.vp9") } catch (_: Exception) { 0 }
        val best = maxOf(avc, hevc, vp9)
        return when {
            best >= 2160 -> 2160; best >= 1440 -> 1440
            best >= 1080 -> 1080; best >= 720 -> 720; else -> 480
        }
    }

    fun exceedsDevice(height: Int, vcodec: String): Boolean {
        if (height <= 0) return false
        val mime = when {
            vcodec.contains("avc") || vcodec.contains("h264") -> "video/avc"
            vcodec.contains("hevc") || vcodec.contains("h265") || vcodec.contains("hev") -> "video/hevc"
            vcodec.contains("vp9") || vcodec.contains("vp09") -> "video/x-vnd.on2.vp9"
            vcodec.contains("av01") || vcodec.contains("av1") -> "video/av01"
            else -> "video/avc"
        }
        return height > getMaxSupportedHeight(mime)
    }

    fun shouldShowWarning(formats: List<com.example.nightlibrary.model.FormatInfo>): Boolean =
        formats.any { it.hasVideo && it.height > 0 && exceedsDevice(it.height, it.vcodec) }

    fun getWarningText(): String {
        val maxH = getSafeDownloadHeight()
        return "⚠ Formats above ${maxH}p may not play smoothly on this device"
    }

    fun getFormatWarning(height: Int): String? {
        val maxH = getSafeDownloadHeight()
        return if (height > maxH) "⚠ ${height}p exceeds device max (${maxH}p)" else null
    }
}