package com.example.nightlibrary

import android.app.Dialog
import android.os.Bundle
import android.view.*
import androidx.lifecycle.ViewModelProvider
import com.example.nightlibrary.databinding.BottomSheetAddContactBinding
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AddContactBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddContactBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: VaultViewModel
//forces the bottom sheet to resize
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = (requireActivity().application as NightLibraryApp)
            .container
            .vaultViewModelFactory

        viewModel = ViewModelProvider(requireActivity(), factory)[VaultViewModel::class.java]

        binding.buttonCancelContact.setOnClickListener {
            dismiss()
        }

        binding.buttonSaveContact.setOnClickListener {
            val name = binding.editTextContactName.text.toString().trim()
            val phone = binding.editTextPhoneNumber.text.toString().trim()
            val notes = binding.editTextNotesContact.text.toString().trim()

            if (name.isNotEmpty() && phone.isNotEmpty()) {
                viewModel.addContact(name, phone, notes.ifEmpty { null })
                dismiss()
            } else {
                binding.layoutContactName.error = if (name.isEmpty()) "Required" else null
                binding.layoutPhoneNumber.error = if (phone.isEmpty()) "Required" else null
            }
        }

        binding.buttonImportContact.setOnClickListener {
            val parent = parentFragment as? ManageContact

            if (parent != null) {
                // ✅ FIX: Use custom multi-select picker
                parent.checkContactPermission()
            } else {
                android.util.Log.e("BottomSheet", "Parent fragment not found")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}