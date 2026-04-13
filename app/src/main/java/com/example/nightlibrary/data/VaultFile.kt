package com.example.nightlibrary.data


data class VaultFile(
    val id: String,
    val name: String,
    val sizeMb: Double,
    val progress: Int,
    val isCompleted: Boolean,
    val fileType: com.example.nightlibrary.data.FileType
)
