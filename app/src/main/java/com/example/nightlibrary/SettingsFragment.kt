package com.example.nightlibrary

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.nightlibrary.databinding.DialogDeletePhotoBinding
import com.example.nightlibrary.databinding.DialogQuickLaucherBinding
import com.example.nightlibrary.databinding.DialogShareProgressBinding
import com.example.nightlibrary.databinding.FragmentSettingsBinding
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.manager.DownloadQueueManager
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import com.example.nightlibrary.setting.FloatingLauncherService
import com.example.nightlibrary.viewmodel.VaultViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    companion object {
        private const val TAG = "SettingsFragment"

        // ✅ FIX: Channel IDs must match MediaDownloadWorker exactly
        private const val DOWNLOAD_CHANNEL_ID = "vault_download"
        private const val STEALTH_CHANNEL_ID = "vault_download_silent"
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: SecurityPreferenceManager
    private lateinit var viewModel: VaultViewModel

    // ✅ NEW: Modern permission launcher (replaces deprecated onActivityResult)
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            if (Settings.canDrawOverlays(requireContext())) {
                binding.switchQuickLauncher.isChecked = true
                startFloatingService()
                showFeedback("Quick Launcher enabled")
            } else {
                binding.switchQuickLauncher.isChecked = false
                prefs.isFloatingLauncherEnabled = false
                showFeedback("Quick Launcher permission not granted")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = SecurityPreferenceManager(requireContext())

        // Initialize ViewModel for storage usage
        val factory = (requireActivity().application as NightLibraryApp)
            .container.vaultViewModelFactory
        viewModel = ViewModelProvider(requireActivity(), factory)[VaultViewModel::class.java]

        setupSwitchStates()
        setupListeners()
        observePrivateMedia()

        if (prefs.isFloatingLauncherEnabled && Settings.canDrawOverlays(requireContext())) {
            startFloatingService()
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            binding.switchQuickLauncher.isChecked =
                prefs.isFloatingLauncherEnabled && Settings.canDrawOverlays(requireContext())
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SWITCH INITIALIZATION
    // ═══════════════════════════════════════════════════════════════

    private fun setupSwitchStates() {
        clearSwitchListeners()

        binding.switchBiometric.isChecked = prefs.isBiometricEnabled
        binding.switchQuickLauncher.isChecked =
            prefs.isFloatingLauncherEnabled && Settings.canDrawOverlays(requireContext())
        binding.switchEmergencyLock.isChecked = prefs.isEmergencyLockEnabled
        setupListeners()
    }

    private fun clearSwitchListeners() {
        binding.switchQuickLauncher.setOnCheckedChangeListener(null)
        binding.switchEmergencyLock.setOnCheckedChangeListener(null)
        binding.switchBiometric.setOnCheckedChangeListener(null)
    }

    // ═══════════════════════════════════════════════════════════════
    // ✅ IMPROVED: SWITCH LISTENERS
    // ═══════════════════════════════════════════════════════════════

    private fun observePrivateMedia() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.totalPrivateCount.collectLatest { count ->
                if (_binding == null) return@collectLatest
                
                if (count > 0) {
                    binding.tvPrivateCount.text = "$count item(s) in private vault"
                    binding.tvBadgeNew.visibility = View.VISIBLE
                } else {
                    binding.tvPrivateCount.text = "View items from Private Import Mode"
                    binding.tvBadgeNew.visibility = View.GONE
                }
            }
        }
    }

    private fun setupListeners() {
        // ── Private Imports ──────────────────────────────────────────
        binding.cardPrivateImports.setOnClickListener {
            // TODO: Navigate to PrivateVaultFragment
            Toast.makeText(requireContext(), "Opening Private Vault...", Toast.LENGTH_SHORT).show()
        }

        // ── Quick Launcher ──────────────────────────────────────────
        binding.switchQuickLauncher.setOnCheckedChangeListener { _, enabled ->
            if (enabled) {
                confirmQuickLauncherEnable()
            } else {
                stopFloatingService()
                showFeedback("Quick Launcher disabled")
            }
        }

        // ── Emergency Lock ──────────────────────────────────────────
        binding.switchEmergencyLock.setOnCheckedChangeListener { _, enabled ->
            prefs.isEmergencyLockEnabled = enabled
            val msg = if (enabled) "Shake phone to instantly lock vault" else "Shake-to-lock disabled"
            showFeedback(msg)
        }

        // ── Biometric ───────────────────────────────────────────────
        binding.switchBiometric.setOnCheckedChangeListener { _, enabled ->
            prefs.isBiometricEnabled = enabled
            val msg = if (enabled) "Biometric authentication enabled" else "Biometric disabled"
            showFeedback(msg)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ✅ FIXED: SILENT MODE — Correct channel IDs + proper approach
    // ═══════════════════════════════════════════════════════════════

    /**
     * Silent mode approach:
     *
     * We canNOT delete the notification channel because Android requires
     * ForegroundService workers to show a notification. Instead we:
     *
     * 1. Save the preference — MediaDownloadWorker reads this at start
     * 2. Worker switches to STEALTH_CHANNEL_ID (IMPORTANCE_MIN)
     * 3. Stealth notifications show minimal info, no filename, no sound
     * 4. On lockscreen: VISIBILITY_SECRET hides content entirely
     *
     * When silent mode is OFF:
     * - Worker uses normal DOWNLOAD_CHANNEL_ID (IMPORTANCE_LOW)
     * - Shows filename, progress, speed
     * - Completion notification appears briefly
     */


    /**
     * ✅ NEW: Cancels any visible download notifications.
     * Called when user enables silent mode mid-download.
     */
    private fun cancelActiveDownloadNotifications(nm: NotificationManager) {
        try {
            // Cancel all notifications from our app in the 9000+ range
            // (MediaDownloadWorker uses AtomicInteger starting at 9000)
            nm.activeNotifications.forEach { notification ->
                if (notification.id >= 9000) {
                    nm.cancel(notification.id)
                }
            }
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // INCOGNITO DIALOG
    // ═══════════════════════════════════════════════════════════════



    // ═══════════════════════════════════════════════════════════════
    // ✅ IMPROVED: QUICK LAUNCHER — Modern permission API
    // ═══════════════════════════════════════════════════════════════

    private fun confirmQuickLauncherEnable() {
        if (!isAdded) return
        val dialogBinding = DialogQuickLaucherBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.continueButton.setOnClickListener {
            prefs.isFloatingLauncherEnabled = true
            checkOverlayPermission()
            dialog.dismiss()
        }

        dialogBinding.cancelButton.setOnClickListener {
            prefs.isFloatingLauncherEnabled = false
            if (_binding != null) {
                binding.switchQuickLauncher.isChecked = false
            }
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.7f)
        dialog.show()
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    private fun checkOverlayPermission() {
        if (Settings.canDrawOverlays(requireContext())) {
            startFloatingService()
            showFeedback("Quick Launcher enabled")
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startFloatingService() {
        if (!Settings.canDrawOverlays(requireContext())) {
            binding.switchQuickLauncher.isChecked = false
            prefs.isFloatingLauncherEnabled = false
            return
        }

        prefs.isFloatingLauncherEnabled = true
        try {
            val intent = Intent(requireContext(), FloatingLauncherService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            Log.d(TAG, "Floating service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start floating service: ${e.message}")
            showFeedback("Could not start Quick Launcher")
        }
    }

    private fun stopFloatingService() {
        prefs.isFloatingLauncherEnabled = false
        try {
            requireContext().stopService(
                Intent(requireContext(), FloatingLauncherService::class.java)
            )
            Log.d(TAG, "Floating service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop floating service: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun showFeedback(message: String) {
        if (isAdded && _binding != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    override fun onDestroyView() {
        // ✅ Clear listeners to prevent leaks
        clearSwitchListeners()
        super.onDestroyView()
        _binding = null
    }
}
