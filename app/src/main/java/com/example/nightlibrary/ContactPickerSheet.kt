package com.example.nightlibrary

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nightlibrary.adapter.PickerContactAdapter
import com.example.nightlibrary.databinding.LayoutContactPickerSheetBinding
import com.example.nightlibrary.entity.ContactEntity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhoneContact(
    val id: Long,
    val name: String,
    val phone: String
)

class ContactPickerSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutContactPickerSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: PickerContactAdapter
    private var allContacts: List<PhoneContact> = emptyList()
    private var onImportListener: ((List<ContactEntity>) -> Unit)? = null

    fun setOnImportListener(listener: (List<ContactEntity>) -> Unit) {
        onImportListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = LayoutContactPickerSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Expand to full height
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        setupRecyclerView()
        setupSearch()
        setupButtons()
        loadContacts()
    }

    private fun setupRecyclerView() {
        adapter = PickerContactAdapter { updateImportButton() }
        binding.pickerRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.pickerRecycler.adapter = adapter
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                if (query.isEmpty()) {
                    adapter.submitList(allContacts)
                } else {
                    adapter.submitList(allContacts.filter {
                        it.name.lowercase().contains(query) ||
                                it.phone.contains(query)
                    })
                }
            }
        })
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener { dismiss() }

        binding.btnSelectAll.setOnClickListener {
            val currentList = adapter.currentList
            if (adapter.selectedIds.size == currentList.size) {
                adapter.clearSelection()
            } else {
                adapter.selectAll()
            }
            updateImportButton()
        }

        binding.btnImport.setOnClickListener {
            val selected = adapter.getSelectedContacts()
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), "Select at least one contact", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val entities = selected.map {
                ContactEntity(name = it.name, phone = it.phone)
            }

            onImportListener?.invoke(entities)
            dismiss()
        }
    }

    private fun loadContacts() {
        binding.pickerProgress.visibility = View.VISIBLE
        binding.pickerRecycler.visibility = View.GONE

        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) { queryPhoneContacts() }
            if (_binding == null) return@launch

            allContacts = contacts
            adapter.submitList(contacts)

            binding.pickerProgress.visibility = View.GONE
            binding.pickerRecycler.visibility = View.VISIBLE
            binding.tvContactCount.text = "${contacts.size} contacts on device"

            if (contacts.isEmpty()) {
                binding.tvContactCount.text = "No contacts found"
            }
        }
    }

    private fun queryPhoneContacts(): List<PhoneContact> {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val contacts = mutableMapOf<String, PhoneContact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val phone = cursor.getString(phoneCol) ?: continue

                // Deduplicate by normalized phone number
                val normalized = phone.replace(Regex("[^0-9+]"), "")
                if (normalized.length >= 4 && !contacts.containsKey(normalized)) {
                    contacts[normalized] = PhoneContact(id, name.trim(), phone.trim())
                }
            }
        }

        return contacts.values.toList()
    }

    private fun updateImportButton() {
        val count = adapter.selectedIds.size
        binding.btnImport.text = if (count > 0) "Import ($count)" else "Import"
        binding.btnImport.isEnabled = count > 0
        binding.btnImport.alpha = if (count > 0) 1f else 0.5f

        binding.btnSelectAll.text =
            if (count == adapter.currentList.size && count > 0) "Deselect All"
            else "Select All"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}