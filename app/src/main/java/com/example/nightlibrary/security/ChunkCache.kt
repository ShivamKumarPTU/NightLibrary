// ════════════════════════════════════════════════════════════════════════════
// FILE 1/4  ▸  ChunkCache.kt
// DESTINATION: java/com/example/nightlibrary/security/ChunkCache.kt
//              (same package as before — replaces the file in place)
//
// CHANGES FROM OLD VERSION:
//   OLD: LinkedHashMap with NO accessOrder → simple FIFO, not real LRU
//        Stored RAW ENCRYPTED bytes (IV + ciphertext)
//        No synchronized() around get/put → race condition risk
//        MAX_CACHE_SIZE = 50 → 50 × 2MB = 100MB possible heap use = GC hell
//
//   NEW: LinkedHashMap(accessOrder=true) → real LRU (most-recently-used stays)
//        Stores DECRYPTED plaintext bytes → cipher.doFinal() called ONCE on
//        cache miss; all subsequent reads are ByteArrayInputStream.read() only
//        synchronized() on all public methods → thread-safe
//        MAX_CACHE_SIZE = 8 → 8 × 2MB = 16MB ceiling → no GC pressure
// ════════════════════════════════════════════════════════════════════════════

package com.example.nightlibrary.security

import android.util.Log

object ChunkCache {

    private const val TAG = "VaultChunkCache"
    private const val MAX_CACHE_SIZE = 8   // 8 × 2MB = 16MB ceiling

    // accessOrder=true → get() counts as "use" → proper LRU eviction
    private val cache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, ByteArray>?): Boolean {
            val evict = size > MAX_CACHE_SIZE
            if (evict && eldest != null) Log.d(TAG, "Cache EVICT chunk=${eldest.key}")
            return evict
        }
    }

    fun put(chunkIndex: Int, decryptedData: ByteArray) {
        synchronized(cache) {
            Log.d(TAG, "Cache PUT chunk=$chunkIndex size=${decryptedData.size}")
            cache[chunkIndex] = decryptedData
        }
    }

    fun get(chunkIndex: Int): ByteArray? {
        return synchronized(cache) {
            val data = cache[chunkIndex]
            if (data != null) Log.d(TAG, "Cache HIT chunk=$chunkIndex")
            else              Log.d(TAG, "Cache MISS chunk=$chunkIndex")
            data
        }
    }

    fun clear() {
        synchronized(cache) {
            Log.d(TAG, "Cache CLEAR")
            cache.clear()
        }
    }
}

