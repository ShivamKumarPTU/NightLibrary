package com.example.nightlibrary.entity

data class QualityItem(
    val quality: String,
    val url: String,
    val size: String,
    val formatId: String? = null,
    val headers: Map<String, String>? = null
)
