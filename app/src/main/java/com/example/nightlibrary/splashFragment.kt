package com.example.nightlibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.media3.common.util.Log
import androidx.navigation.fragment.findNavController
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import com.example.nightlibrary.security.BiometricMode
import com.example.nightlibrary.security.SecureScreenManager
import com.example.nightlibrary.setting.VaultSessionManager

class SplashFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_splash, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SecureScreenManager.enable(requireActivity())
        val preferenceManager = SecurityPreferenceManager(requireContext())

        // 1. If Setup is not complete, always go to Onboarding
        if (!preferenceManager.isSetupComplete) {
            findNavController().navigate(R.id.action_splashFragment_to_onboardingScreen1Fragment)
            return
        }

        // 2. PREVENT OVERRIDE: If the session is already unlocked, go straight to Home
        // This prevents re-authentication if the app is simply resumed
        if (VaultSessionManager.isUnlocked()) {
            findNavController().navigate(R.id.action_splashFragment_to_homeFragment)
            return
        }

        // 3. If session is LOCKED, trigger Authentication
        if (preferenceManager.isBiometricEnabled) {
            findNavController().navigate(
                SplashFragmentDirections.actionSplashFragmentToBiometricFragment(
                    BiometricMode.LOGIN
                )
            )
        } else {
            findNavController().navigate(R.id.action_splashFragment_to_confirmPinFragment)
        }
    }
}