package com.example.nightlibrary.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.example.nightlibrary.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(media: MediaEntity)
    // ═══════════════════════════════════════════════════════════════
    // ⚡ Bug 2: Duplicate URL detection
    // ═══════════════════════════════════════════════════════════════

    /**
     * Find an active (not completed, not trashed) download with matching URL.
     * Used by DownloadQueueManager to prevent duplicate enqueues.
     */
    @Query("""
        SELECT * FROM media 
        WHERE isInTrash = 0 
          AND isCompleted = 0 
          AND downloadUrl = :url 
        LIMIT 1
    """)
    suspend fun getActiveDownloadByUrl(url: String): MediaEntity?
    @Update
    suspend fun update(media: MediaEntity)

    @Delete
    suspend fun delete(media: MediaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAndGetId(media: MediaEntity): Long

    @Query("SELECT * FROM media WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE id = :id LIMIT 1")
    suspend fun getMediaByIdBlocking(id: Long): MediaEntity?

    @Query("DELETE FROM media WHERE id = :mediaId")
    suspend fun deleteById(mediaId: Long)

    @Query("""
        SELECT * FROM media
        WHERE isInTrash = 0 
        AND isCompleted = 1
        AND isPrivate = 0
        ORDER BY createdAt DESC
        LIMIT 500
    """)
    fun getCompleted(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE isInTrash = 0 AND isPrivate = 0 ORDER BY createdAt DESC")
    fun pagingMedia(): PagingSource<Int, MediaEntity>

    @Query("SELECT * FROM media WHERE isInTrash = 0 AND isPrivate = 0 AND fileType = :type ORDER BY createdAt DESC")
    suspend fun getByTypeOnce(type: String): List<MediaEntity>

    @Query("SELECT * FROM media WHERE isInTrash = 0 AND isPrivate = 0 AND fileType = 'image' ORDER BY createdAt DESC")
    fun getImages(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE isInTrash = 0 AND isPrivate = 0 AND fileType = 'image' ORDER BY createdAt DESC")
    suspend fun getImagesOrdered(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE isInTrash = 0 AND isPrivate = 0 AND fileType = 'image' ORDER BY createdAt DESC")
    suspend fun getImagesOnce(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE isInTrash = 0 AND isPrivate = 0 AND fileType LIKE '%video%' ORDER BY createdAt DESC")
    suspend fun getVideosOnce(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE vaultFolder = :folder LIMIT 1")
    suspend fun getByVaultFolder(folder: String): MediaEntity?


    @Query("""
        SELECT * FROM media 
        WHERE isInTrash = 0 
        AND isCompleted = 0 
        AND isPrivate = 0
        ORDER BY createdAt DESC
    """)
    fun getInProgress(): Flow<List<MediaEntity>>

    @Query("""
        SELECT * FROM media 
        WHERE isInTrash = 0 
        AND isCompleted = 0 
        AND isPrivate = 0
        AND downloadUrl IS NOT NULL 
        AND downloadUrl != ''
        ORDER BY createdAt DESC
    """)
    fun getActiveDownloads(): Flow<List<MediaEntity>>

    /**
     * ✅ NEW: Active IMPORTS only — items imported from gallery/camera (no URL).
     * Used by In Progress → "Imports" section.
     */
    @Query("""
        SELECT * FROM media 
        WHERE isInTrash = 0 
        AND isCompleted = 0 
        AND isPrivate = 0
        AND (downloadUrl IS NULL OR downloadUrl = '')
        ORDER BY createdAt DESC
    """)
    fun getActiveImports(): Flow<List<MediaEntity>>

    // ═══════════════════════════════════════════════════════════════
    // ✅ PROGRESS UPDATES — Enhanced with speed
    // ═══════════════════════════════════════════════════════════════

    /**
     * Basic progress update (backward compatible).
     */
    @Query("UPDATE media SET progress = :progress, resumeBytes = :downloaded WHERE id = :mediaId")
    suspend fun updateProgress(mediaId: Long, progress: Int, downloaded: Long)

    /**
     * ✅ NEW: Full progress update — includes speed + downloadedBytes.
     * Called by MediaDownloadWorker every 500ms during active download.
     * This is the PRIMARY progress update method going forward.
     */
    @Query("""
        UPDATE media SET 
            progress = :progress, 
            resumeBytes = :resumeBytes,
            downloadedBytes = :downloadedBytes,
            currentSpeed = :speed,
            isPaused = 0,
            isFailed = 0
        WHERE id = :mediaId
    """)
    suspend fun updateProgressFull(
        mediaId: Long,
        progress: Int,
        resumeBytes: Long,
        downloadedBytes: Long,
        speed: Double
    )

    /**
     * ✅ NEW: Clear speed when download is paused/complete/failed.
     * Prevents stale "2.3 MB/s" showing after pause.
     */
    @Query("UPDATE media SET currentSpeed = 0.0 WHERE id = :mediaId")
    suspend fun clearSpeed(mediaId: Long)

    /**
     * ✅ NEW: Mark paused AND clear speed in one query.
     */
    @Query("""
        UPDATE media SET 
            isPaused = :isPaused, 
            currentSpeed = CASE WHEN :isPaused = 1 THEN 0.0 ELSE currentSpeed END 
        WHERE id = :id
    """)
    suspend fun setPaused(id: Long, isPaused: Boolean)

    @Query("UPDATE media SET downloadedBytes = :bytes WHERE id = :id")
    suspend fun updateDownloadedBytes(id: Long, bytes: Long)

    @Query("UPDATE media SET checksum = :checksum WHERE id = :id")
    suspend fun updateChecksum(id: Long, checksum: String)

    @Query("UPDATE media SET thumbnailPath = :path WHERE id = :mediaId")
    suspend fun updateThumbnailPath(mediaId: Long, path: String?)

    // ═══════════════════════════════════════════════════════════════
    // FAILURE / RECOVERY
    // ═══════════════════════════════════════════════════════════════

    @Query("""
        UPDATE media SET 
            isFailed = 1, 
            failReason = :reason, 
            currentSpeed = 0.0 
        WHERE id = :id
    """)
    suspend fun markFailed(id: Long, reason: String)

    @Query("UPDATE media SET isFailed = 0, failReason = NULL WHERE id = :id")
    suspend fun clearFailure(id: Long)

    @Query("SELECT * FROM media WHERE isFailed = 1 AND isCompleted = 0")
    suspend fun getFailedDownloads(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE isCompleted = 0 AND isFailed = 0 AND isPaused = 0")
    suspend fun getActiveDownloadsOnce(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE isPaused = 1")
    suspend fun getPausedDownloads(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE isCompleted = 0")
    suspend fun getIncompleteImports(): List<MediaEntity>

    // ═══════════════════════════════════════════════════════════════
    // COUNTS & AGGREGATES
    // ═══════════════════════════════════════════════════════════════

    @Query("SELECT COUNT(*) FROM media WHERE isInTrash = 0 AND isPrivate = 0")
    fun getCount(): Flow<Int>

    /**
     * ✅ NEW: Count of active operations — used for tab badge.
     */
    @Query("SELECT COUNT(*) FROM media WHERE isInTrash = 0 AND isCompleted = 0 AND isPrivate = 0")
    fun getInProgressCount(): Flow<Int>

    @Query("SELECT SUM(fileSize) FROM media WHERE isInTrash = 0 AND isPrivate = 0")
    fun getTotalStorageUsed(): Flow<Long?>

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM media WHERE isPrivate = 0")
    fun getTotalSize(): Flow<Long>

    // ═══════════════════════════════════════════════════════════════
    // TRASH
    // ═══════════════════════════════════════════════════════════════

    @Query("UPDATE media SET isInTrash = 1 WHERE id = :id")
    suspend fun moveToTrash(id: Long)

    @Query("SELECT * FROM media WHERE isInTrash = 1 ORDER BY createdAt DESC")
    fun getTrash(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE isInTrash = 1 ORDER BY createdAt DESC")
    suspend fun getTrashOnce(): List<MediaEntity>

    @Query("UPDATE media SET isInTrash = 0 WHERE id = :id")
    suspend fun restoreFromTrash(id: Long)

    @Query("DELETE FROM media WHERE isInTrash = 1")
    suspend fun deleteAllTrash()

    // ═══════════════════════════════════════════════════════════════
    // DUPLICATE CHECK
    // ═══════════════════════════════════════════════════════════════

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM media 
            WHERE checksum = :checksum 
            AND checksum != '' 
            AND isCompleted = 1 
            LIMIT 1
        )
    """)
    suspend fun existsByChecksum(checksum: String): Boolean

    // ═══════════════════════════════════════════════════════════════
    // ✅ NEW: Audio queries — Feature A + Feature B
    // ═══════════════════════════════════════════════════════════════

    @Query("""
        SELECT * FROM media 
        WHERE isInTrash = 0 
        AND fileType = 'audio' 
        AND isCompleted = 1
        ORDER BY createdAt DESC
    """)
    fun getAudioCompleted(): Flow<List<MediaEntity>>

    @Query("""
        SELECT * FROM media 
        WHERE isInTrash = 0 
        AND fileType = 'audio' 
        AND isCompleted = 1
        ORDER BY createdAt DESC
    """)
    suspend fun getAudioOnce(): List<MediaEntity>

    // ═══════════════════════════════════════════════════════════════
    // ✅ NEW: Generic type query as Flow — for chip filtering
    // ═══════════════════════════════════════════════════════════════

    @Query("""
        SELECT * FROM media 
        WHERE isInTrash = 0 
        AND fileType = :type 
        AND isCompleted = 1
        AND isPrivate = 0
        ORDER BY createdAt DESC
    """)
    fun getCompletedByType(type: String): Flow<List<MediaEntity>>

    // ═══════════════════════════════════════════════════════════════
    // 🔒 PRIVATE QUERIES
    // ═══════════════════════════════════════════════════════════════

    @Query("""
        SELECT * FROM media 
        WHERE isInTrash = 0 
        AND isCompleted = 1 
        AND isPrivate = 1 
        ORDER BY createdAt DESC
    """)
    fun getPrivateCompleted(): Flow<List<MediaEntity>>

    @Query("""
        SELECT * FROM media 
        WHERE isInTrash = 0 
        AND isCompleted = 0 
        AND isPrivate = 1 
        ORDER BY createdAt DESC
    """)
    fun getPrivateInProgress(): Flow<List<MediaEntity>>

    // ═══════════════════════════════════════════════════════════════
    // ✅ NEW: Duration update — Problem 6
    // Called by MediaDownloadWorker after download when duration is known.
    // ═══════════════════════════════════════════════════════════════

    @Query("UPDATE media SET duration = :duration WHERE id = :mediaId")
    suspend fun updateDuration(mediaId: Long, duration: Long)

    /**
     * ✅ NEW: Full completion update — sets completed + duration + speed=0 in one query.
     * Called by MediaDownloadWorker when download finishes.
     */
    @Query("""
        UPDATE media SET 
            isCompleted = 1, 
            progress = 100, 
            currentSpeed = 0.0,
            isPaused = 0,
            isFailed = 0,
            duration = :duration,
            fileSize = CASE WHEN :finalSize > 0 THEN :finalSize ELSE fileSize END
        WHERE id = :mediaId
    """)
    suspend fun markCompleted(mediaId: Long, duration: Long, finalSize: Long)

    @Query("UPDATE media SET streamUrl = :streamUrl WHERE id = :mediaId")
    suspend fun updateStreamUrl(mediaId: Long, streamUrl: String?)
// Add to MediaDao.kt — anywhere in the interface

    /**
     * Find an incomplete import with matching checksum.
     * Used by LocalImportWorker to clean up stale entries on restart.
     */
    @Query("""
    SELECT * FROM media 
    WHERE checksum = :checksum 
    AND checksum != '' 
    AND isCompleted = 0 
    LIMIT 1
""")
    suspend fun getIncompleteByChecksum(checksum: String): MediaEntity?
}
