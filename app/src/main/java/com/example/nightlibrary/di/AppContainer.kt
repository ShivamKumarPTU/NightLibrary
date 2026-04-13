package com.example.nightlibrary.di

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import com.example.nightlibrary.NightLibraryApp
import com.example.nightlibrary.core.security.PasswordCryptoManager
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.repository.ContactRepository
import com.example.nightlibrary.repository.MediaRepository
import com.example.nightlibrary.repository.PasswordRepository
import com.example.nightlibrary.viewmodel.VaultViewModelFactory

class AppContainer(context: Context) {
  private val workManager = WorkManager.getInstance(context)
    private val database = VaultDatabase.getDatabase(context)
private val application = context.applicationContext as Application
    private val contactRepository =
        ContactRepository(database.contactDao())
    private val passwordRepository =
        PasswordRepository(
            database.passwordDao(),
            PasswordCryptoManager()
        )

    val mediaRepository =
        MediaRepository(database.mediaDao())

    val vaultViewModelFactory =
        VaultViewModelFactory(
          application ,
            contactRepository,
            passwordRepository,
            mediaRepository,
            workManager
        )
}