package com.example.nightlibrary.model

import com.example.nightlibrary.entity.MediaEntity

sealed class VaultSection{
    data class PhotoSection(val items:List<MediaEntity>): VaultSection()
    data class VideoSection(val items:List<MediaEntity>): VaultSection()
    data class  AudioSection(val items:List<MediaEntity>): VaultSection()
    data class PdfSection(val items:List<MediaEntity>): VaultSection()








}