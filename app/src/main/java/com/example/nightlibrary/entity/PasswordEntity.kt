package com.example.nightlibrary.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "passwords")
data class PasswordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serviceName: String,
    val username: String,
    val encryptedPassword: String,
    val notes: String?,
    val createdAt: Long = System.currentTimeMillis()
)