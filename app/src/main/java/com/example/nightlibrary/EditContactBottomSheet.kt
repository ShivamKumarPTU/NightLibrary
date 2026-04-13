package com.example.nightlibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import com.example.nightlibrary.databinding.BottomSheetAddContactBinding
import com.example.nightlibrary.entity.ContactEntity
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class EditContactBottomSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(contact: ContactEntity): EditContactBottomSheet {
            return EditContactBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong("id", contact.id)
                    putString("name", contact.name)
                    putString("phone", contact.phone)
                    putString("notes", contact.notes)
                    putLong("createdAt", contact.createdAt)
                }
            }
        }
    }

    private var _binding: BottomSheetAddContactBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: VaultViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Reuses the SAME layout as AddContactBottomSheet
        _binding = BottomSheetAddContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = (requireActivity().application as NightLibraryApp)
            .container.vaultViewModelFactory
        viewModel = ViewModelProvider(requireActivity(), factory)[VaultViewModel::class.java]
        dialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val id = requireArguments().getLong("id")
        val name = requireArguments().getString("name", "")
        val phone = requireArguments().getString("phone", "")
        val notes = requireArguments().getString("notes", "")
        val createdAt = requireArguments().getLong("createdAt")

        // Change title & button text
        binding.bottomSheetTitle.text = "Edit Contact"
        binding.buttonSaveContact.text = "Update"

        // Hide import button — not needed for editing
        binding.buttonImportContact.visibility = View.GONE

        // Pre-fill fields
        binding.editTextContactName.setText(name)
        binding.editTextPhoneNumber.setText(phone)
        binding.editTextNotesContact.setText(notes)

        binding.buttonCancelContact.setOnClickListener { dismiss() }

        binding.buttonSaveContact.setOnClickListener {
            val newName = binding.editTextContactName.text.toString().trim()
            val newPhone = binding.editTextPhoneNumber.text.toString().trim()
            val newNotes = binding.editTextNotesContact.text.toString().trim()

            if (newName.isNotEmpty() && newPhone.isNotEmpty()) {
                viewModel.updateContact(
                    ContactEntity(
                        id = id,
                        name = newName,
                        phone = newPhone,
                        notes = newNotes.ifEmpty { null },
                        createdAt = createdAt
                    )
                )
                dismiss()
            } else {
                binding.layoutContactName.error = if (newName.isEmpty()) "Required" else null
                binding.layoutPhoneNumber.error = if (newPhone.isEmpty()) "Required" else null
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}