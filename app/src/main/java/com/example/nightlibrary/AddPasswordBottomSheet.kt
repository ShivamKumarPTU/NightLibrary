package com.example.nightlibrary

import android.os.Bundle
import android.view.*
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import com.example.nightlibrary.databinding.BottomSheetAddPasswordBinding
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AddPasswordBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddPasswordBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: VaultViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = (requireActivity().application as NightLibraryApp)
            .container
            .vaultViewModelFactory

        viewModel = ViewModelProvider(
            requireActivity(),
            factory
        )[VaultViewModel::class.java]

        binding.buttonCancelPassword.setOnClickListener {
            dismiss()
        }

        binding.buttonSavePassword.setOnClickListener {

            val service = binding.editTextTitle.text.toString().trim()
            val username = binding.editTextMainCredential.text.toString().trim()
            val password = binding.editTextSecret.text.toString().trim()
            val notes = binding.editTextNotesPassword.text.toString().trim()

            if (service.isEmpty() || username.isEmpty() || password.isEmpty()) {
                if (service.isEmpty())
                    binding.layoutTitle.error = "Required"

                if (username.isEmpty())
                    binding.layoutMainCredential.error = "Required"

                if (password.isEmpty())
                    binding.layoutSecret.error = "Required"

                return@setOnClickListener
            }

            viewModel.addPassword(
                service,
                username,
                password,
                notes.ifEmpty { null }
            )

            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}