package com.example.nightlibrary.repository

import com.example.nightlibrary.dao.ContactDao
import com.example.nightlibrary.entity.ContactEntity

class ContactRepository(private val dao: ContactDao) {

    suspend fun add(name: String, phone: String, notes: String?) {
        dao.insert(ContactEntity(name = name, phone = phone, notes = notes))
    }

    suspend fun addAll(contacts: List<ContactEntity>): Int {
        val results = dao.insertAll(contacts)
        return results.count { it != -1L }
    }

    suspend fun isDuplicate(phone: String): Boolean {
        return dao.getByPhone(phone) != null
    }

    suspend fun delete(contact: ContactEntity) {
        dao.delete(contact)
    }

    suspend fun update(contact: ContactEntity) {
        dao.update(contact)
    }

    fun getAll() = dao.getAll()
    fun getCount() = dao.getCount()

    // ✅ NEW: Bulk operations
    suspend fun deleteByIds(ids: List<Long>) {
        dao.deleteByIds(ids)
    }

    suspend fun getByIds(ids: List<Long>): List<ContactEntity> {
        return dao.getByIds(ids)
    }
}