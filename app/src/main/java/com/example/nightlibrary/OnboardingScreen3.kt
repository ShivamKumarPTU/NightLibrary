package com.example.nightlibrary

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.example.nightlibrary.databinding.FragmentOnboardingScreen2Binding
import com.example.nightlibrary.databinding.FragmentOnboardingScreen3Binding


class OnboardingScreen3 : Fragment() {
 private var _binding : FragmentOnboardingScreen3Binding? = null
    private val binding get() = _binding!!
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentOnboardingScreen3Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val continueButton = binding.tryItNowButton

        continueButton.setOnClickListener {
            findNavController().navigate(R.id.action_onboardingScreen3_to_onboardingScreen4)
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}