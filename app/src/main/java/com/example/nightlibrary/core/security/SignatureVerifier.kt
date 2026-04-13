package com.example.nightlibrary.core.security

import android.content.Context
import android.content.pm.PackageManager
import com.example.nightlibrary.debug.VaultLogger
import java.security.MessageDigest

object SignatureVerifier {

    fun isSignatureValid(context: Context): Boolean {
        return try {

            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )

            val signatures = packageInfo.signingInfo?.apkContentsSigners

            val actualHash = signatures?.first()?.toByteArray()?.sha256()

          //  VaultLogger.d("SignatureVerifier", "App signature hash: $actualHash")

            // Replace this with your RELEASE hash later
            val expectedHash = actualHash

            actualHash == expectedHash

        } catch (e: Exception) {
         //   VaultLogger.e("SignatureVerifier", "Signature verification failed", e)
            false
        }
    }

    private fun ByteArray.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(this)
        return digest.joinToString("") { "%02x".format(it) }
    }
}