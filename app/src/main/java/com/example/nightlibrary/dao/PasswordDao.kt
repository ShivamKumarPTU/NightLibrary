package com.example.nightlibrary.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.nightlibrary.entity.PasswordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PasswordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(password: PasswordEntity)

    @Update
    suspend fun updaet(contact: PasswordEntity)  // keeping original typo for compatibility

    @Delete
    suspend fun delete(password: PasswordEntity)

    @Query("SELECT * FROM passwords ORDER BY createdAt DESC")
    fun getAll(): Flow<List<PasswordEntity>>

    @Query("SELECT COUNT(*) FROM passwords")
    fun getCount(): Flow<Int>

    // ✅ NEW: Bulk delete by IDs
    @Query("DELETE FROM passwords WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // ✅ NEW: Get specific passwords by IDs (for share)
    @Query("SELECT * FROM passwords WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<PasswordEntity>
}