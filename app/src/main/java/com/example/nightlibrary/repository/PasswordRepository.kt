package com.example.nightlibrary.repository

import android.util.Log
import com.example.nightlibrary.core.security.PasswordCryptoManager
import com.example.nightlibrary.dao.PasswordDao
import com.example.nightlibrary.entity.PasswordEntity

class PasswordRepository(
    private val dao: PasswordDao,
    private val cryptoManager: PasswordCryptoManager
) {

    suspend fun add(
        service: String,
        username: String,
        plainPassword: String,
        notes: String?
    ) {
        val encrypted = cryptoManager.encrypt(plainPassword)
        dao.insert(
            PasswordEntity(
                serviceName = service,
                username = username,
                encryptedPassword = encrypted,
                notes = notes
            )
        )
        Log.d("PasswordRepository", "Password saved encrypted")
    }

    suspend fun update(password: PasswordEntity) {
        dao.updaet(password)
    }

    suspend fun delete(password: PasswordEntity) {
        dao.delete(password)
    }

    fun getAll() = dao.getAll()

    fun decryptPassword(encrypted: String): String {
        return cryptoManager.decrypt(encrypted)
    }

    fun getCount() = dao.getCount()

    // ✅ NEW: Bulk operations
    suspend fun deleteByIds(ids: List<Long>) {
        dao.deleteByIds(ids)
    }

    suspend fun getByIds(ids: List<Long>): List<PasswordEntity> {
        return dao.getByIds(ids)
    }
}