package com.example.nightlibrary

// CORRECT IMPORTS - These are from the library you have in your build.gradle
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.nightlibrary.security.BiometricMode
import com.example.nightlibrary.setting.VaultSessionManager


class BiometricFragment : Fragment() {

    private var hasShownPrompt = false // Add this flag
    private val args: BiometricFragmentArgs by navArgs()
    private lateinit var biometricMode: BiometricMode

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_biometric, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        biometricMode = args.mode
    }
    // THIS IS THE FIX: Call the prompt from onResume()
    override fun onResume() {
        super.onResume()
        // Use a flag to ensure the prompt is only shown once, even if onResume is called multiple times.
        if (!hasShownPrompt) {
            showBiometricPrompt()
            hasShownPrompt = true
        }
    }

    // REMOVED onViewCreated() as it's no longer needed to call the prompt.

    private fun showBiometricPrompt() {
        // Add a check for activity context, as it could be null if the user navigates away quickly
        val activity = activity ?: return
        if (!isAdded) return

        val executor = ContextCompat.getMainExecutor(requireContext())
        val biometricManager = BiometricManager.from(requireContext())


        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Biometric not available or not set up, fall back to PIN
            findNavController().navigate(R.id.action_biometricFragment_to_confirmPinFragment) // Make sure ID is correct
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric unlock for NightVault")
            .setSubtitle("Unlock your private vault with biometrics")
            .setNegativeButtonText("Use account PIN")
            .build()

        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    VaultSessionManager.unlock()

                    // Ensure the fragment is still attached before navigating
                    if (isAdded) {
                        val target = VaultSessionManager.resumeDestinationId

                        if (target != null) {
                            findNavController().navigate(target)
                            VaultSessionManager.resumeDestinationId = null
                        } else {
                            when (biometricMode) {
                                BiometricMode.LOGIN -> {
                                    findNavController().navigate(
                                        BiometricFragmentDirections.actionBiometricFragmentToHomeFragment()
                                    )
                                }
                                BiometricMode.RESET -> {
                                    findNavController().navigate(
                                        BiometricFragmentDirections.actionBiometricFragmentToCreatePinFragment()
                                    )
                                }
                                BiometricMode.UNLOCK -> {
                                    findNavController().popBackStack()
                                }
                            }
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // User likely hit "Use account PIN" or cancelled
                    // Ensure the fragment is still attached before navigating
                    if (isAdded) {
                        when (biometricMode) {

                            BiometricMode.LOGIN -> {
                                findNavController().navigate(
                                    BiometricFragmentDirections.actionBiometricFragmentToConfirmPinFragment()
                                )
                            }

                            BiometricMode.RESET -> {
                                findNavController().popBackStack()
                            }
                            BiometricMode.UNLOCK -> {
                                findNavController().popBackStack()
                            }
                        }
                    }
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }
}
