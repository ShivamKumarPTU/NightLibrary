/*
package com.example.nightlibrary.repository



import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.nightlibrary.dao.MediaDao
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.security.SecureWipe
import com.example.nightlibrary.security.VaultSecureDeleteManager
import kotlinx.coroutines.flow.Flow
import java.io.File

class MediaRepository(private val dao: MediaDao) {

    suspend fun getImagesOrdered(): List<MediaEntity> {
        return dao.getImagesOrdered()
    }
    suspend fun insert(media: MediaEntity) {
        dao.insert(media)
    }

    suspend fun update(media: MediaEntity) {
        dao.update(media)
    }

    suspend fun delete(media: MediaEntity) {
        dao.delete(media)
    }
    suspend fun getMediaByTypeOnce(type: String): List<MediaEntity> {
        return dao.getByTypeOnce(type)
    }
    fun getInProgress(): Flow<List<MediaEntity>> = dao.getInProgress()

    suspend fun getVideosOnce(): List<MediaEntity> {
        return dao.getVideosOnce()
    }
    fun getCompleted(): Flow<List<MediaEntity>> = dao.getCompleted()

    fun getTrash(): Flow<List<MediaEntity>> = dao.getTrash()
    
    suspend fun getTrashOnce(): List<MediaEntity> = dao.getTrashOnce()

    fun getCount(): Flow<Int> = dao.getCount()

    fun getTotalStorageUsed(): Flow<Long?> = dao.getTotalStorageUsed()
    
    suspend fun setPaused(id: Long, isPaused: Boolean) = dao.setPaused(id, isPaused)

    suspend fun permanentlyDelete(media: MediaEntity) {
        VaultSecureDeleteManager.deleteMedia(media)
        dao.deleteById(media.id)
    }
    fun getTotalStorageUsage(): Flow<Long> =
        dao.getTotalSize()

    fun getImages(): Flow<List<MediaEntity>> =
        dao.getImages()

    suspend fun getById(id: Long): MediaEntity? =
        dao.getById(id)

    // FIX 3: used by SecureVideoActivity to load MediaEntity from vault folder path
    suspend fun getByVaultFolder(folder: String): MediaEntity? =
        dao.getByVaultFolder(folder)
    suspend fun getImagesOnce() = dao.getImagesOnce()

    fun getPageMedia():Flow<PagingData<MediaEntity>>{
        return Pager(
            config = PagingConfig(
                pageSize = 30,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                dao.pagingMedia()
            }
        ).flow
    }
}*/
package com.example.nightlibrary.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.nightlibrary.dao.MediaDao
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.security.SecureWipe
import com.example.nightlibrary.security.VaultSecureDeleteManager
import kotlinx.coroutines.flow.Flow
import java.io.File

class MediaRepository(private val dao: MediaDao) {

    // ═══════════════════════════════════════════════════════════════
    // BASIC CRUD
    // ═══════════════════════════════════════════════════════════════

    suspend fun insert(media: MediaEntity) = dao.insert(media)

    suspend fun update(media: MediaEntity) = dao.update(media)

    suspend fun delete(media: MediaEntity) = dao.delete(media)

    suspend fun getById(id: Long): MediaEntity? = dao.getById(id)

    suspend fun getByVaultFolder(folder: String): MediaEntity? =
        dao.getByVaultFolder(folder)

    // ═══════════════════════════════════════════════════════════════
    // GALLERY / COMPLETED
    // ═══════════════════════════════════════════════════════════════

    fun getCompleted(): Flow<List<MediaEntity>> = dao.getCompleted()

    fun getImages(): Flow<List<MediaEntity>> = dao.getImages()

    suspend fun getImagesOnce() = dao.getImagesOnce()

    suspend fun getImagesOrdered(): List<MediaEntity> = dao.getImagesOrdered()

    suspend fun getVideosOnce(): List<MediaEntity> = dao.getVideosOnce()

    suspend fun getMediaByTypeOnce(type: String): List<MediaEntity> =
        dao.getByTypeOnce(type)

    // ═══════════════════════════════════════════════════════════════
    // ✅ IN PROGRESS — Sectioned queries
    // ═══════════════════════════════════════════════════════════════

    /** All in-progress items (backward compatible) */
    fun getInProgress(): Flow<List<MediaEntity>> = dao.getInProgress()

    /** ✅ NEW: Active downloads only (has downloadUrl) */
    fun getActiveDownloads(): Flow<List<MediaEntity>> = dao.getActiveDownloads()

    /** ✅ NEW: Active imports only (no downloadUrl) */
    fun getActiveImports(): Flow<List<MediaEntity>> = dao.getActiveImports()

    /** ✅ NEW: Count of active operations for tab badge */
    fun getInProgressCount(): Flow<Int> = dao.getInProgressCount()

    // ═══════════════════════════════════════════════════════════════
    // ✅ PROGRESS UPDATES
    // ═══════════════════════════════════════════════════════════════

    /** Basic progress update (backward compatible) */
    suspend fun updateProgress(mediaId: Long, progress: Int, downloaded: Long) =
        dao.updateProgress(mediaId, progress, downloaded)

    /** ✅ NEW: Full progress update with speed */
    suspend fun updateProgressFull(
        mediaId: Long,
        progress: Int,
        resumeBytes: Long,
        downloadedBytes: Long,
        speed: Double
    ) = dao.updateProgressFull(mediaId, progress, resumeBytes, downloadedBytes, speed)

    /** ✅ NEW: Clear speed display */
    suspend fun clearSpeed(mediaId: Long) = dao.clearSpeed(mediaId)

    // ═══════════════════════════════════════════════════════════════
    // PAUSE / FAILURE
    // ═══════════════════════════════════════════════════════════════

    suspend fun setPaused(id: Long, isPaused: Boolean) = dao.setPaused(id, isPaused)

    suspend fun markFailed(id: Long, reason: String) = dao.markFailed(id, reason)

    suspend fun clearFailure(id: Long) = dao.clearFailure(id)

    suspend fun getFailedDownloads() = dao.getFailedDownloads()

    suspend fun getPausedDownloads() = dao.getPausedDownloads()

    // ═══════════════════════════════════════════════════════════════
    // TRASH & DELETE
    // ═══════════════════════════════════════════════════════════════

    fun getTrash(): Flow<List<MediaEntity>> = dao.getTrash()

    suspend fun getTrashOnce(): List<MediaEntity> = dao.getTrashOnce()

    suspend fun permanentlyDelete(media: MediaEntity) {
        VaultSecureDeleteManager.deleteMedia(media)
        dao.deleteById(media.id)
    }

    // ═══════════════════════════════════════════════════════════════
    // COUNTS & STORAGE
    // ═══════════════════════════════════════════════════════════════

    fun getCount(): Flow<Int> = dao.getCount()

    fun getTotalStorageUsed(): Flow<Long?> = dao.getTotalStorageUsed()

    fun getTotalStorageUsage(): Flow<Long> = dao.getTotalSize()

    // ═══════════════════════════════════════════════════════════════
    // PAGING
    // ═══════════════════════════════════════════════════════════════

    fun getPageMedia(): Flow<PagingData<MediaEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 30,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { dao.pagingMedia() }
        ).flow
    }
}