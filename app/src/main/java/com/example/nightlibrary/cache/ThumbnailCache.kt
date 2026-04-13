package com.example.nightlibrary.cache

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache

object ThumbnailCache {

    private const val TAG = "VaultThumbCache"

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(maxSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    fun get(key: String): Bitmap? {
        val bitmap = cache.get(key)
        Log.d(TAG, if (bitmap != null) "CACHE HIT $key" else "CACHE MISS $key")
        return bitmap
    }

    fun put(key: String, bitmap: Bitmap) {
        Log.d(TAG, "CACHE PUT $key")
        cache.put(key, bitmap)
    }

    /**
     * Evicts all entries.
     *
     * NOTE: Do NOT call this from a Fragment's onViewCreated() / onResume().
     * Doing so destroys warm bitmaps on every navigation event and causes the
     * placeholder → bitmap flash loop. Call clear() only on explicit user
     * action (e.g. "clear cache" in settings) or when memory is critically low.
     */
    fun clear() {
        Log.d(TAG, "CACHE CLEAR")
        cache.evictAll()
    }

    fun snapshot(): LruCache<String, Bitmap> = cache

    private fun maxSize(): Int = (Runtime.getRuntime().maxMemory() / 8).toInt()
}
