package com.example.nightlibrary

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.example.nightlibrary.databinding.FragmentOnboardingScreen1Binding


class OnboardingScreen1 : Fragment() {
   private var _binding : FragmentOnboardingScreen1Binding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentOnboardingScreen1Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val startButton= binding.continueButton

        startButton.setOnClickListener {
            findNavController().navigate(R.id.action_onboardingScreen1Fragment_to_onboardingScreen2)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

    }
}