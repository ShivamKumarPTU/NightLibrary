package com.example.nightlibrary.core.security

import com.example.nightlibrary.security.ChunkIndex
import org.json.JSONObject
import java.io.File

class ChunkIndexReader {

    fun read(folder: File): ChunkIndex = readIndex(folder)

    fun readIndex(folder: File): ChunkIndex {
        val indexFile = File(folder, "index.json")
        val json = JSONObject(indexFile.readText())

        return ChunkIndex(
            chunkCount    = json.getInt("chunkCount"),
            chunkSize     = json.getInt("chunkSize"),
            totalFileSize = json.getLong("totalFileSize"),
            wrappedKey    = if (json.has("wrappedKey")) json.getString("wrappedKey") else null,
            keyIv         = if (json.has("keyIv")) json.getString("keyIv") else null
        )
    }
}