package com.example.nightlibrary.core.security

import android.content.Context
import com.example.nightlibrary.debug.VaultLogger

object SecurityManager {

    fun performStartupChecks(context: Context): Boolean {

      //  VaultLogger.d("SecurityManager", "Running startup security checks")

        if (RootDetector.isDeviceRooted()) {
        //    VaultLogger.w("SecurityManager", "Device is rooted")
            return false
        }

        if (!SignatureVerifier.isSignatureValid(context)) {
        //    VaultLogger.w("SecurityManager", "Signature mismatch detected")
            return false
        }

   //     VaultLogger.d("SecurityManager", "Startup checks passed")
        return true
    }
}