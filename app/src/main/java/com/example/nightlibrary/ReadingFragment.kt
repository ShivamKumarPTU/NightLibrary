package com.example.nightlibrary

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.nightlibrary.databinding.FragmentReadingBinding

class ReadingFragment : Fragment() {
    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!

    // Optional alternate-access gesture for users who enable discreet entry.
    private var accessTapCount = 0
    private val requiredClicks = 5
    private var isNavigating = false
    private val resetHandler = Handler(Looper.getMainLooper())
    private val resetRunnable = Runnable {
        accessTapCount = 0
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val navController = findNavController()
        binding.toolbarReading.setNavigationOnClickListener {
            navController.popBackStack()
        }

        binding.searchEditText.setOnClickListener {
            handleVaultAccessTap()
        }
    }

    private fun handleVaultAccessTap() {
        if (isNavigating) return  // prevent double execution
        // 1. Reset the timeout timer
        resetHandler.removeCallbacks(resetRunnable)

        // 2. Increment the single, correct counter
        accessTapCount++

        // 3. Calculate how many clicks are left
        val tapsLeft = requiredClicks - accessTapCount

        if (tapsLeft > 0) {
            binding.secretStatusText.text = "Tap $tapsLeft more times to open vault"
            binding.secretStatusText.animate()
                .alpha(1f)
                .setDuration(100)
                .start()
        } else {
            isNavigating = true
            binding.secretStatusText.text = "Opening vault..."
            findNavController().navigate(R.id.action_readingFragment_to_secretVaultFragment)
            accessTapCount = 0
        }

        // 4. Set the timer to reset the count after 2 seconds of inactivity
        resetHandler.removeCallbacksAndMessages(null) // clear previous timers
        resetHandler.postDelayed({
            if (_binding != null) {
                binding.secretStatusText.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .start()
            }
            accessTapCount = 0

        }, 5000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        resetHandler.removeCallbacks(resetRunnable)
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        isNavigating = false
        accessTapCount = 0

        binding.secretStatusText.alpha = 0f
        binding.secretStatusText.text = ""

        resetHandler.removeCallbacksAndMessages(null)
    }
}
