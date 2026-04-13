package com.example.nightlibrary

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.example.nightlibrary.databinding.FragmentOnboardingScreen4Binding

class OnboardingScreen4 : Fragment() {
    private var _binding: FragmentOnboardingScreen4Binding? = null
    private val binding get() = _binding!!
    private var accessTapCount = 0
    private var isNavigating = false
    private val requiredClicks = 5
    private val resetHandler = Handler(Looper.getMainLooper())
    private val resetRunnable = Runnable {
        accessTapCount = 0
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentOnboardingScreen4Binding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tryItNowButton.setOnClickListener{
            findNavController().navigate(R.id.action_onboardingScreen4_to_createPinFragment)
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        resetHandler.removeCallbacks(resetRunnable)
        _binding = null
    }
}
