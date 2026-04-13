package com.example.nightlibrary

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.example.nightlibrary.databinding.FragmentOnboardingScreen2Binding


class OnboardingScreen2 : Fragment() {
    private var _binding : FragmentOnboardingScreen2Binding? = null
    private val binding get() = _binding!!
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentOnboardingScreen2Binding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
val  continueButton = binding.continueButton2
        continueButton.setOnClickListener {
            findNavController().navigate(R.id.action_onboardingScreen2_to_onboardingScreen3)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}