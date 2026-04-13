package com.example.nightlibrary.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DatabaseKeyManager(private val context: Context) {

    companion object {
        private const val KEY_ALIAS = "VaultDatabaseMasterKey"
        private const val PREF_NAME = "secure_prefs"
        private const val PREF_DB_KEY = "encrypted_db_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun getOrCreateMasterKey(): SecretKey {

        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun getPassphrase(): ByteArray {

        val encryptedStoredKey = prefs.getString(PREF_DB_KEY, null)

        if (encryptedStoredKey != null) {
            Log.d("DBKeyManager", "Using existing DB passphrase")
            return decrypt(Base64.decode(encryptedStoredKey, Base64.DEFAULT))
        }

        Log.d("DBKeyManager", "Generating new DB passphrase")

        // Generate random 32-byte key
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)

        val encrypted = encrypt(randomBytes)

        prefs.edit()
            .putString(PREF_DB_KEY, Base64.encodeToString(encrypted, Base64.DEFAULT))
            .apply()

        return randomBytes
    }

    private fun encrypt(data: ByteArray): ByteArray {

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())

        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)

        return iv + encrypted
    }

    private fun decrypt(encryptedData: ByteArray): ByteArray {

        val iv = encryptedData.copyOfRange(0, 12)
        val encrypted = encryptedData.copyOfRange(12, encryptedData.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)

        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), spec)

        return cipher.doFinal(encrypted)
    }
}