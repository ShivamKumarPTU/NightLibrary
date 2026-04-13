package com.example.nightlibrary.worker

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MediaExtractorTest {

    @Test
    fun testIsDirectMediaUrl() {
        assertTrue(MediaExtractor.isDirectMediaUrl("https://example.com/video.mp4"))
        assertTrue(MediaExtractor.isDirectMediaUrl("https://example.com/audio.mp3"))
        assertTrue(MediaExtractor.isDirectMediaUrl("https://example.com/stream.m3u8"))
        assertFalse(MediaExtractor.isDirectMediaUrl("https://example.com/page.html"))
        assertTrue(MediaExtractor.isDirectMediaUrl("https://example.com/video.mp4?token=123"))
    }

    @Test
    fun testIsM3U8() {
        assertTrue(MediaExtractor.isM3U8("https://example.com/playlist.m3u8"))
        assertTrue(MediaExtractor.isM3U8("https://example.com/m3u8/stream"))
        assertFalse(MediaExtractor.isM3U8("https://example.com/video.mp4"))
    }

    @Test
    fun testParseVideoInfo() {
        val json = JSONObject().apply {
            put("url", "https://stream.url")
            put("webpage_url", "https://page.url")
            put("duration", 120)
            put("http_headers", JSONObject().apply {
                put("User-Agent", "Test-UA")
            })
        }.toString()

        val info = MediaExtractor.parseVideoInfo(json)
        assertNotNull(info)
        assertEquals("https://stream.url", info?.streamUrl)
        assertEquals(120L, info?.duration)
        assertEquals("Test-UA", info?.headers?.get("User-Agent"))
    }
}
