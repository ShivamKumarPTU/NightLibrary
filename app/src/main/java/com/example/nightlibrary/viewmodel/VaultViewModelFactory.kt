package com.example.nightlibrary.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import com.example.nightlibrary.repository.ContactRepository
import com.example.nightlibrary.repository.MediaRepository
import com.example.nightlibrary.repository.PasswordRepository

class VaultViewModelFactory(
    private val application: Application,
    private val contactRepository: ContactRepository,
    private val passwordRepository: PasswordRepository,
    private val mediaRepository: MediaRepository,
    private val workManager: WorkManager
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {

        if (modelClass.isAssignableFrom(VaultViewModel::class.java)) {
            return VaultViewModel(
                application,
                contactRepository,
                passwordRepository,
                mediaRepository,
                workManager
            ) as T
        }

        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}