package com.example.nightlibrary.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultCryptoEngine {

    companion object {
        private const val KEY_ALIAS = "VaultMediaKey"
        private const val STORE     = "AndroidKeyStore"
        private const val TRANSFORM = "AES/CTR/NoPadding"

        @Volatile private var cachedKey: SecretKey? = null

        private fun getOrCreateKey(): SecretKey {
            cachedKey?.let { return it }
            synchronized(this) {
                cachedKey?.let { return it }
                val ks = KeyStore.getInstance(STORE).apply { load(null) }
                val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
                if (existing != null) {
                    cachedKey = existing
                    return existing
                }
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
                kg.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_CTR)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .setUnlockedDeviceRequired(false)
                        .build()
                )
                return kg.generateKey().also { cachedKey = it }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // EXISTING: TEE-based encryption (used for legacy files + images)
    // ═══════════════════════════════════════════════════════════════

    fun createEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher
    }

    fun createDecryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), IvParameterSpec(iv))
        return cipher
    }

    fun encryptCipher(): Cipher = createEncryptCipher()
    fun decryptCipher(iv: ByteArray): Cipher = createDecryptCipher(iv)

    // ═══════════════════════════════════════════════════════════════
    // NEW: ENVELOPE ENCRYPTION
    //
    // generateDek()              → random AES-256 in SOFTWARE (instant)
    // wrapDek(dek)               → encrypt DEK with TEE key (1 TEE call)
    // unwrapDek(encrypted, iv)   → decrypt DEK with TEE key (1 TEE call)
    // createSoftEncryptCipher()  → SOFTWARE cipher for chunk encryption
    // createSoftDecryptCipher()  → SOFTWARE cipher for chunk decryption
    // ═══════════════════════════════════════════════════════════════

    fun generateDek(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256)
        return kg.generateKey()
    }

    fun wrapDek(dek: SecretKey): Pair<ByteArray, ByteArray> {
        val cipher = createEncryptCipher()
        val iv = cipher.iv
        val wrapped = cipher.doFinal(dek.encoded)
        return Pair(wrapped, iv)
    }

    fun unwrapDek(encryptedDek: ByteArray, iv: ByteArray): SecretKeySpec {
        val cipher = createDecryptCipher(iv)
        val dekBytes = cipher.doFinal(encryptedDek)
        return SecretKeySpec(dekBytes, "AES")
    }

    fun createSoftEncryptCipher(dek: SecretKey): Cipher {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, dek)
        return c
    }

    fun createSoftDecryptCipher(dek: SecretKey, iv: ByteArray): Cipher {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, dek, IvParameterSpec(iv))
        return c
    }
}