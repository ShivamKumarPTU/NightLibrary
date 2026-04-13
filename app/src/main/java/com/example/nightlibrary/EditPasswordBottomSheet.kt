package com.example.nightlibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import com.example.nightlibrary.core.security.PasswordCryptoManager
import com.example.nightlibrary.databinding.BottomSheetAddPasswordBinding
import com.example.nightlibrary.entity.PasswordEntity
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class EditPasswordBottomSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(password: PasswordEntity): EditPasswordBottomSheet {
            return EditPasswordBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong("id", password.id)
                    putString("serviceName", password.serviceName)
                    putString("username", password.username)
                    putString("encryptedPassword", password.encryptedPassword)
                    putString("notes", password.notes)
                    putLong("createdAt", password.createdAt)
                }
            }
        }
    }

    private var _binding: BottomSheetAddPasswordBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: VaultViewModel
    private val cryptoManager = PasswordCryptoManager()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Reuses the SAME layout as AddPasswordBottomSheet
        _binding = BottomSheetAddPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val factory = (requireActivity().application as NightLibraryApp)
            .container.vaultViewModelFactory
        viewModel = ViewModelProvider(requireActivity(), factory)[VaultViewModel::class.java]

        val id = requireArguments().getLong("id")
        val serviceName = requireArguments().getString("serviceName", "")
        val username = requireArguments().getString("username", "")
        val encryptedPassword = requireArguments().getString("encryptedPassword", "")
        val notes = requireArguments().getString("notes", "")
        val createdAt = requireArguments().getLong("createdAt")

        // Change title & button text
        binding.bottomSheetTitle.text = "Edit Credential"
        binding.buttonSavePassword.text = "Update"

        // Pre-fill fields
        binding.editTextTitle.setText(serviceName)
        binding.editTextMainCredential.setText(username)
        binding.editTextNotesPassword.setText(notes)

        // Decrypt and pre-fill password
        try {
            val decrypted = cryptoManager.decrypt(encryptedPassword)
            binding.editTextSecret.setText(decrypted)
        } catch (_: Exception) {
            binding.editTextSecret.setText("")
            binding.layoutSecret.error = "Could not decrypt — enter new password"
        }

        binding.buttonCancelPassword.setOnClickListener { dismiss() }

        binding.buttonSavePassword.setOnClickListener {
            val newService = binding.editTextTitle.text.toString().trim()
            val newUsername = binding.editTextMainCredential.text.toString().trim()
            val newPassword = binding.editTextSecret.text.toString().trim()
            val newNotes = binding.editTextNotesPassword.text.toString().trim()

            if (newService.isEmpty() || newUsername.isEmpty() || newPassword.isEmpty()) {
                if (newService.isEmpty()) binding.layoutTitle.error = "Required"
                if (newUsername.isEmpty()) binding.layoutMainCredential.error = "Required"
                if (newPassword.isEmpty()) binding.layoutSecret.error = "Required"
                return@setOnClickListener
            }

            // Re-encrypt with new password
            val newEncrypted = cryptoManager.encrypt(newPassword)

            viewModel.updatePassword(
                PasswordEntity(
                    id = id,
                    serviceName = newService,
                    username = newUsername,
                    encryptedPassword = newEncrypted,
                     notes = newNotes.ifEmpty { null },
                    createdAt = createdAt
                )
            )
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}