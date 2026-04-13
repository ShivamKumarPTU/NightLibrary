package com.example.nightlibrary


import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.nightlibrary.databinding.FragmentConfirmPinBinding
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import com.example.nightlibrary.security.AuthManager
import com.example.nightlibrary.setting.VaultSessionManager


// CORRECTED: Import your PreferenceManager with its full package name
class ConfirmPinFragment : Fragment() {
    // Pin Verification
    private lateinit var authManager: AuthManager
    private lateinit var securePrefs: SecurityPreferenceManager
    private var _binding: FragmentConfirmPinBinding? = null
    private val binding get() = _binding!!

    // Safe Args to get the PIN from CreatePinFragment
    private val args: ConfirmPinFragmentArgs by navArgs()

    // Define preferenceManager to be used in this class

    private val pinStringBuilder = StringBuilder()
    private lateinit var pinDots: List<View>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfirmPinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // CORRECTED: Initialize the preferenceManager
        securePrefs = SecurityPreferenceManager(requireContext())
        authManager = AuthManager(securePrefs)

        // CORRECTED: Adjust UI based on whether we are setting up or verifying
        if (args.createdPin == null) {
            // This is "Verification Mode" (user is logging in)
            binding.tvHeader.text = "Enter Your PIN"
            binding.tvSubHeader.text = "Enter your PIN to unlock"
        } else {
            // This is "Setup Mode" (user is creating a new PIN)
            binding.tvHeader.text = "Confirm Your PIN"
            binding.tvSubHeader.text = "Re-enter your PIN to confirm"
        }

        pinDots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)
        setupKeypad()
        // Setting forgot pin functionality
        binding.forgotPinButton.setOnClickListener {
            val bottomSheet = ResetVaultBottomSheet {
                authManager.resetSecurityData()
                securePrefs.isSetupComplete = false

                findNavController().navigate(
                    ConfirmPinFragmentDirections
                        .actionConfirmPinFragmentToOnboardingScreen1Fragment()
                )
            }
            bottomSheet.show(parentFragmentManager, "ConfirmCancelSheet")
        }
    }

    private fun setupKeypad() {
        val numberButtons = with(binding) {
            listOf(
                button1, button2, button3, button4, button5,
                button6, button7, button8, button9, button0
            )
        }
        numberButtons.forEach { button ->
            button.setOnClickListener {
                onNumberClicked((it as Button).text.toString())
            }
        }

        binding.buttonBackspace.setOnClickListener {
            onBackspaceClicked()
        }
    }

    private fun onNumberClicked(number: String) {
        if (pinStringBuilder.length < 4) {
            pinStringBuilder.append(number)
            updatePinDots()

            if (pinStringBuilder.length == 4) {
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        checkPin()
                    }, 150
                )
            }
        }
        Log.d("PIN_DEBUG", "Current length = ${pinStringBuilder.length}")
    }

    private fun onBackspaceClicked() {
        if (pinStringBuilder.isNotEmpty()) {
            pinStringBuilder.deleteCharAt(pinStringBuilder.length - 1)
            updatePinDots()
        }
    }

    private fun checkPin() {
        val enteredPin = pinStringBuilder.toString()

        // 🟢 SETUP MODE
        if (args.createdPin != null) {

            if (enteredPin == args.createdPin) {

                authManager.savePin(enteredPin)
                securePrefs.isSetupComplete = true

                Toast.makeText(
                    requireContext(),
                    "PIN created successfully!",
                    Toast.LENGTH_SHORT
                ).show()

                findNavController().navigate(
                    ConfirmPinFragmentDirections.actionConfirmPinToHome()
                )

            } else {
                showError()
            }

            return
        }

        // 🔵 VERIFICATION MODE
        if (authManager.isLocked()) {

            val remaining = authManager.getRemainingLockTime() / 1000

            Toast.makeText(
                requireContext(),
                "Too many attempts. Try again in ${remaining}s",
                Toast.LENGTH_LONG
            ).show()

            resetInput()
            return
        }

        val isValid = authManager.verifyPin(enteredPin)

        if (isValid) {
            VaultSessionManager.unlock()

            // START: Resume Destination Logic
            val target = VaultSessionManager.resumeDestinationId

            if (target != null) {
                // Navigate back to where the user was (e.g., from the Floating Launcher)
                findNavController().navigate(target)
                VaultSessionManager.resumeDestinationId = null
            } else {
                // Default navigation to Home
                findNavController().navigate(
                    ConfirmPinFragmentDirections.actionConfirmPinToHome()
                )
            }
            // END: Resume Destination Logic

        } else {
            showError()
        }
    }
    // CORRECTED: The old saveSetupCompleteFlag() function is no longer needed
    // as this logic is now handled inside checkPin() using the PreferenceManager.

    private fun updatePinDots() {
        for (i in pinDots.indices) {
            if (i < pinStringBuilder.length) {
                pinDots[i].setBackgroundResource(R.drawable.bg_pin_dot_filled)
            } else {
                pinDots[i].setBackgroundResource(R.drawable.bg_pin_dot_empty)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showError() {
        val attemptsLeft = authManager.getRemainingAttempts()
        Toast.makeText(
            requireContext(),
            "Incorrect Pin. ${attemptsLeft} attempts left.",
            Toast.LENGTH_SHORT
        ).show()
        resetInput()
    }

    private fun resetInput() {
        pinStringBuilder.clear()
        updatePinDots()
    }
}