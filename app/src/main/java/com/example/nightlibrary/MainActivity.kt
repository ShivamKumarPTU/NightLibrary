// ════════════════════════════════════════════════════════════════════
// FIXED BY CLAUDE  ▸  MainActivity.kt
//
// BUG FIXED:
//   Auth was triggered on EVERY onResume() — including when returning
//   from the camera, file picker, dialogs, or navigating between
//   fragments. This caused double-PIN screens, crash from
//   IllegalArgumentException (navigate while already on auth screen),
//   and auth mid-import.
//
// FIX:
//   Use onStop()/onStart() instead of onPause()/onResume() for auth
//   gating. onStop() fires ONLY on genuine background transitions
//   (home button, recents, screen off). Camera, file pickers, dialogs,
//   and fragment nav never call onStop() on the host activity.
// ════════════════════════════════════════════════════════════════════
package com.example.nightlibrary

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.example.nightlibrary.core.media.MediaScanner
import com.example.nightlibrary.databinding.ActivityMainBinding
import com.example.nightlibrary.preferences.SecurityPreferenceManager
import com.example.nightlibrary.security.BiometricMode
import com.example.nightlibrary.security.SecureScreenManager
import com.example.nightlibrary.setting.BaseActivity
import com.example.nightlibrary.setting.VaultSessionManager
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.example.nightlibrary.worker.PreviewPlayerManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    // FIX: auth triggered in onResume() via NightLibraryApp.wentToBackground.
    // ProcessLifecycleOwner.onStop() fires ONLY on genuine background transitions
    // (home button, recents, screen off, quick-launcher). It does NOT fire when
    // share sheets, file managers, Gmail, or the Android chooser dialog appear on
    // top of us — those only call Activity.onStop(), not the process-level observer.
    // So wentToBackground is only true after a REAL background→foreground round-trip.
    private var stoppedForBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(binding.root)

       SecureScreenManager.enable(this)
        
        // 🔥 10/10 Performance: Delay scanner to prevent startup "Davey" lag
        lifecycleScope.launch {
            kotlinx.coroutines.delay(3000)
            MediaScanner.scanVault(this@MainActivity) 
        }

        application.registerComponentCallbacks(PreviewPlayerManager)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val wic = WindowCompat.getInsetsController(window, window.decorView)
        wic.hide(WindowInsetsCompat.Type.systemBars())
        wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, 0)
            insets
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainer) as NavHostFragment
        val navController = navHostFragment.navController
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavView)
        NavigationUI.setupWithNavController(bottomNav, navController)

        // Clear thumbnail cache once on cold start only — NOT on every fragment nav
        //   ThumbnailCache.clear()

        navController.addOnDestinationChangedListener { _, destination, _ ->
            Log.d(TAG, "Navigated to: ${destination.label} (${destination.id})")
            bottomNav.visibility = when (destination.id) {
                R.id.homeFragment,
                R.id.libraryFragment,
                R.id.settingsFragment -> View.VISIBLE
                else -> View.GONE
            }
        }
    }

    // ── Auth Gate ────────────────────────────────────────────────────────
    // Strategy: Activity.onStop() fires for ALL transitions (home, camera, Gmail, share
    // sheet). We distinguish "intentional" external launches (camera, share, file picker)
    // from genuine background exits using isIgnoringNextLock:
    //
    //   • Callers that open an external Activity set isIgnoringNextLock = true BEFORE
    //     calling startActivity(). onStop() sees the flag and skips locking.
    //   • Home button / recents / screen-off: no flag → lock + set stoppedForBackground.
    //   • onStart() checks stoppedForBackground and fires auth if needed.
    //
    // This is simpler and more reliable than ProcessLifecycleOwner, which fires for ANY
    // external-app transition and cannot be suppressed per-launch.

    override fun onStop() {
        super.onStop()
        val app = application as NightLibraryApp
        
        // Use ViewModel state to check for external intent
        val factory = app.container.vaultViewModelFactory
        val viewModel = ViewModelProvider(this, factory)[VaultViewModel::class.java]
        
        val isExternalIntent = app.isIgnoringNextLock || viewModel.isLaunchingExternalIntent.value

        if (isExternalIntent) {
            // Intentional external launch (share target, camera, file picker).
            // Don't lock — but DON'T reset the flag here; onStart/ProcessLifecycleOwner handles it.
            Log.d(TAG, "onStop → skipping lock (External Intent Detected)")
            stoppedForBackground = false
        } else if (VaultSessionManager.isUnlocked()) {
            stoppedForBackground = true
            VaultSessionManager.lock()
            Log.d(TAG, "onStop → session LOCKED")
        } else {
            Log.d(TAG, "onStop → already locked")
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as NightLibraryApp
        Log.d(TAG, "onStart → stoppedForBackground=$stoppedForBackground app.needsAuth=${app.needsAuth}")
        
        // Reset the external intent guard
        val factory = app.container.vaultViewModelFactory
        val viewModel = ViewModelProvider(this, factory)[VaultViewModel::class.java]
        viewModel.resetExternalIntentState()
        app.isIgnoringNextLock = false

        val fromFloating = intent.getBooleanExtra("from_floating", false)

        if ((stoppedForBackground || app.needsAuth || fromFloating) && !VaultSessionManager.isUnlocked()) {
            stoppedForBackground = false
            app.needsAuth = false
            // Clear the extra so it doesn't trigger again on rotation/etc if not needed
            intent.removeExtra("from_floating") 
            openAuthLayer(AuthMode.FULL_LOGIN)
        } else {
            stoppedForBackground = false
            app.needsAuth = false
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("from_floating", false)) {
            (application as NightLibraryApp).needsAuth = true
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
    }

    private fun openAuthLayer(authMode: AuthMode) {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainer) as NavHostFragment
        val navController = navHostFragment.navController

        // Guard: never navigate if already on an auth screen
        val currentId = navController.currentDestination?.id
        if (currentId == R.id.biometricFragment ||
            currentId == R.id.confirmPinFragment ||
            currentId == R.id.splashFragment) {
            Log.d(TAG, "openAuthLayer SKIPPED — already on auth screen id=$currentId")
            return
        }

        val prefs = SecurityPreferenceManager(this)
        Log.d(TAG, "openAuthLayer authMode=$authMode biometric=${prefs.isBiometricEnabled}")

        when (authMode) {
            AuthMode.FULL_LOGIN -> {
                if (prefs.isBiometricEnabled) {
                    navController.navigate(
                        NavGraphDirections.actionGlobalBiometricFragment(BiometricMode.LOGIN)
                    )
                } else {
                    navController.navigate(R.id.action_global_confirmPinFragment)
                }
            }
            AuthMode.INTERRUPT_UNLOCK -> {
                if (prefs.isBiometricEnabled) {
                    navController.navigate(
                        NavGraphDirections.actionGlobalBiometricFragment(BiometricMode.UNLOCK)
                    )
                } else {
                    navController.navigate(R.id.action_global_confirmPinFragment)
                }
            }
        }
    }

    enum class AuthMode {
        FULL_LOGIN,       // App returning from background
        INTERRUPT_UNLOCK  // Quick unlock
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.d(TAG, "onSaveInstanceState")
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainer) as NavHostFragment
        navHostFragment.navController.saveState()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Log.d(TAG, "onRestoreInstanceState")
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainer) as NavHostFragment
        navHostFragment.navController.restoreState(savedInstanceState)
    }
}