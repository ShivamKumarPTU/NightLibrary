package com.example.nightlibrary

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.text
import androidx.navigation.fragment.findNavController
import com.example.nightlibrary.databinding.FragmentCreatePinBinding


class createPinFragment : Fragment() {

 private var _binding: FragmentCreatePinBinding? = null
    private val binding get() = _binding!!

    private val pinStringBuilder = StringBuilder()
    private lateinit var pinDots: List<View>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentCreatePinBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

// Initialise the list of dot views
        pinDots = listOf(
            binding.dot1, binding.dot2, binding.dot3, binding.dot4
        )

     setupKeypad()
    }
    private fun setupKeypad() {
        // Set click listeners for number buttons
        val numberButtons = with(binding) {
            listOf(
                button1, button2, button3, button4, button5,
                button6, button7, button8, button9, button0
            )
        }
        numberButtons.forEach { button ->
            button.setOnClickListener {
                onNumberClicked((it as android.widget.Button).text.toString())
            }
        }

        // Set click listener for backspace
        binding.buttonBackspace.setOnClickListener {
            onBackspaceClicked()
        }
    }

    private fun onNumberClicked(number: String) {
        if (pinStringBuilder.length < 4) {
            pinStringBuilder.append(number)
            updatePinDots()

            // If PIN is now 4 digits, navigate
            if (pinStringBuilder.length == 4) {
                val createdPin = pinStringBuilder.toString()
                // Use the action defined in your nav_graph.xml
                // Make sure the action accepts a string argument for the PIN
                val action = createPinFragmentDirections.createPinToConfirmPin(createdPin)
                findNavController().navigate(action)
            }
        }
    }

    private fun onBackspaceClicked() {
        if (pinStringBuilder.isNotEmpty()) {
            pinStringBuilder.deleteCharAt(pinStringBuilder.length - 1)
            updatePinDots()
        }
    }

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
    }

