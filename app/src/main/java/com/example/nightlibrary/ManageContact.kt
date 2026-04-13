package com.example.nightlibrary

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.adapter.ContactAdapter
import com.example.nightlibrary.databinding.DialogDeleteConfirmationBinding
import com.example.nightlibrary.databinding.DialogDeletePhotoBinding
import com.example.nightlibrary.databinding.FragmentManageContactBinding
import com.example.nightlibrary.entity.ContactEntity
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ManageContact : Fragment() {

    companion object {
        private const val TAG = "ManageContact"
        private const val TAG_ADD_SHEET = "AddContactBottomSheet"
        private const val TAG_EDIT_SHEET = "EditContactBottomSheet"
        private const val TAG_PICKER_SHEET = "ContactPickerSheet"
        // ✅ NEW: Swipe hint
        private const val PREF_NAME = "contact_prefs"
        private const val KEY_SWIPE_HINT_SHOWN = "swipe_hint_count"
        private const val MAX_HINT_SHOWS = 3
    }
    // ✅ NEW: Swipe hint

    private var _binding: FragmentManageContactBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: VaultViewModel
    private lateinit var adapter: ContactAdapter

    // ═══════════════════════════════════════════════════════════════
    // BACK PRESS — exits selection mode first
    // ═══════════════════════════════════════════════════════════════

    private val backPressCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.clearContactSelection()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTACT IMPORT — permission + picker
    // ═══════════════════════════════════════════════════════════════
    private val contactPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_CONTACTS] == true
        if (readGranted) {
            // ✅ FIX: Use custom picker instead of system picker
            openCustomContactPicker()
        } else {
            val showRationale = shouldShowRequestPermissionRationale(
                Manifest.permission.READ_CONTACTS
            )
            if (!showRationale) {
                showPermissionSettingsDialog(
                    "Contacts permission is required to import contacts."
                )
            } else {
                Toast.makeText(requireContext(), "Permission denied", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }
    private fun showPermissionSettingsDialog(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton("Settings") { _, _ ->
                viewModel.prepareForExternalIntent()
                (requireActivity().application as NightLibraryApp).isIgnoringNextLock = true
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val contactsToImport = mutableListOf<android.net.Uri>()

            if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    data.clipData!!.getItemAt(i).uri?.let { contactsToImport.add(it) }
                }
            } else if (data?.data != null) {
                contactsToImport.add(data.data!!)
            }

            if (contactsToImport.isNotEmpty()) {
                processSelectedContacts(contactsToImport)
            }
        }
    }

    // Called by AddContactBottomSheet
    fun checkContactPermission() {
        val permissions = arrayOf(Manifest.permission.READ_CONTACTS)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) !=
                    PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            // ✅ FIX: Use custom picker instead of system picker
            openCustomContactPicker()
        } else {
            contactPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun openContactPicker() {
        // Professional approach: Use ACTION_PICK with EXTRA_ALLOW_MULTIPLE for Google Contacts support,
        // and ACTION_GET_CONTENT as a fallback for other manufacturers.
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI).apply {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            // Some devices need this for multi-select in ACTION_PICK
            putExtra("com.google.android.gms.contacts.EXTRA_SELECTION_MAX", 100) 
        }
        
        try {
            contactPickerLauncher.launch(intent)
        } catch (e: Exception) {
            // Fallback to ACTION_GET_CONTENT if ACTION_PICK fails or doesn't support the URI
            val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            contactPickerLauncher.launch(fallbackIntent)
        }
    }

    private fun processSelectedContacts(contactUris: List<android.net.Uri>) {
        val contacts = mutableListOf<ContactEntity>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contactUris.forEach { uri ->
            try {
                requireContext().contentResolver.query(uri, projection, null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val name = cursor.getString(
                                cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                            ) ?: ""
                            val phone = cursor.getString(
                                cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            ) ?: ""

                            if (name.isNotBlank() && phone.isNotBlank()) {
                                contacts.add(ContactEntity(name = name, phone = phone))
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query contact $uri: ${e.message}")
            }
        }

        if (contacts.isNotEmpty()) {
            viewModel.addContacts(contacts)
        } else {
            Toast.makeText(requireContext(), "No valid contacts found", Toast.LENGTH_SHORT).show()
        }

        // Dismiss the add bottom sheet
        (childFragmentManager.findFragmentByTag(TAG_ADD_SHEET) as? BottomSheetDialogFragment)
            ?.dismiss()
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManageContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = (requireActivity().application as NightLibraryApp)
            .container.vaultViewModelFactory
        viewModel = ViewModelProvider(requireActivity(), factory)[VaultViewModel::class.java]

        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupSelectionActions()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressCallback)
        observeData()
    }
    override fun onDestroyView() {
        viewModel.clearContactSelection()
        super.onDestroyView()
        _binding = null
    }

    // ═══════════════════════════════════════════════════════════════
    // TOOLBAR
    // ═══════════════════════════════════════════════════════════════

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (viewModel.isContactSelectionMode.value) {
                viewModel.clearContactSelection()
            } else {
                findNavController().navigateUp()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RECYCLERVIEW
    // ═══════════════════════════════════════════════════════════════
    private fun setupRecyclerView() {
        adapter = ContactAdapter(
            onMenuClickListener = { contact, anchor ->
                showContactMenu(contact, anchor)
            },
            onItemClick = { contact ->
                if (viewModel.isContactSelectionMode.value) {
                    viewModel.toggleContactSelection(contact.id)
                }
            },
            onItemLongClick = { contact ->
                viewModel.toggleContactSelection(contact.id)
            }
        )
        binding.contactsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.contactsRecycler.adapter = adapter

        // ✅ Swipe to call with VISUAL FEEDBACK
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            // ✅ NEW: Draw green "Call" background while swiping
            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (dX > 0) {
                    val itemView = viewHolder.itemView
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#4CAF50")
                    }

                    // Green background
                    c.drawRoundRect(
                        itemView.left.toFloat(),
                        itemView.top.toFloat(),
                        itemView.left + dX + 20f,
                        itemView.bottom.toFloat(),
                        16f, 16f, paint
                    )

                    // Phone icon
                    val icon = ContextCompat.getDrawable(
                        requireContext(), R.drawable.ic_phone1
                    )
                    icon?.let {
                        val iconMargin = 32
                        val iconTop = itemView.top +
                                (itemView.height - it.intrinsicHeight) / 2
                        val iconLeft = itemView.left + iconMargin
                        val iconRight = iconLeft + it.intrinsicWidth
                        val iconBottom = iconTop + it.intrinsicHeight

                        it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        it.setTint(android.graphics.Color.WHITE)
                        it.draw(c)
                    }

                    // "Call" text
                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 36f
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }

                    val textX = (icon?.intrinsicWidth ?: 0) + 72f
                    val textY = itemView.top +
                            (itemView.height / 2f) + (textPaint.textSize / 3f)

                    if (dX > textX + 60) {
                        c.drawText("Call", textX, textY, textPaint)
                    }
                }

                super.onChildDraw(
                    c, recyclerView, viewHolder,
                    dX, dY, actionState, isCurrentlyActive
                )
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val contact = adapter.currentList[position]

                    val intent = Intent(
                        Intent.ACTION_DIAL,
                        android.net.Uri.parse("tel:${contact.phone}")
                    )
                    (requireActivity().application as NightLibraryApp)
                        .isIgnoringNextLock = true
                    startActivity(intent)
                    adapter.notifyItemChanged(position)
                }
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.contactsRecycler)

        // ✅ NEW: Show swipe hint on first visits
        showSwipeHintIfNeeded()
    }
    // ═══════════════════════════════════════════════════════════════
    // FAB
    // ═══════════════════════════════════════════════════════════════

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            if (viewModel.isContactSelectionMode.value) {
                viewModel.selectAllContacts()
            } else {
                AddContactBottomSheet().show(childFragmentManager, TAG_ADD_SHEET)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SELECTION ACTION BAR
    // ═══════════════════════════════════════════════════════════════

    private fun setupSelectionActions() {
        binding.btnCancelSelection.setOnClickListener {
            viewModel.clearContactSelection()
        }

        binding.btnShareSelected.setOnClickListener {
            val text = viewModel.shareSelectedContacts()
            if (text.isNotBlank()) {
                viewModel.launchTextShare(text, "Share Contacts")
                viewModel.clearContactSelection()
            }
        }

        binding.btnDeleteSelected.setOnClickListener {
            val count = viewModel.selectedContactIds.value.size
            val db = DialogDeleteConfirmationBinding.inflate(layoutInflater)
            val dialog = AlertDialog.Builder(requireContext()).setView(db.root).create()
            db.dialogTitle.text = "Delete $count contact${if (count != 1) "s" else ""}?"
            db.deleteConfirmationText.text =
                "Are you sure you want to delete these contacts permanently? This action cannot be undone."
            db.cancelButton.setOnClickListener { dialog.dismiss() }
            db.deleteButton.setOnClickListener {
                viewModel.deleteSelectedContacts()
                dialog.dismiss()
            }
            dialog.window?.setDimAmount(0.75f)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
            dialog.show()
        }

    }

    // ═══════════════════════════════════════════════════════════════
    // OBSERVE DATA
    // ═══════════════════════════════════════
    private fun observeData() {
        // Contacts list
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.contacts.collectLatest { list ->
                adapter.submitList(list)
            }
        }

        // Contact status messages (duplicate toast, etc)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.contactStatus.collectLatest { msg ->
                if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        // Count text
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.contactCount.collectLatest { count ->
                if (_binding != null) {
                    binding.contactCountText.text =
                        "$count contact${if (count != 1) "s" else ""}"
                }
            }
        }

        // Selection state → UI
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.selectedContactIds,
                viewModel.isContactSelectionMode
            ) { ids, selecting -> Pair(ids, selecting) }
                .collectLatest { (ids, selecting) ->
                    if (_binding == null) return@collectLatest

                    adapter.updateSelectedItems(ids)
                    binding.selectionActionsLayout.isVisible = selecting
                    binding.fabAdd.isVisible = !selecting
                    backPressCallback.isEnabled = selecting

                    if (selecting) {
                        val total = viewModel.contacts.value.size
                        binding.toolbar.title = "${ids.size} selected"
                        binding.toolbar.setNavigationIcon(R.drawable.ic_cross)
                        binding.contactCountText.text = "${ids.size} of $total selected"
                    } else {
                        binding.toolbar.title = "Private Contacts"
                        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left_with_bg)
                    }
                }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POPUP MENU (single item 3-dot)
    // ═══════════════════════════════════════════════════════════════

    private fun showContactMenu(contact: ContactEntity, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_contact_item, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select -> {
                    viewModel.toggleContactSelection(contact.id)
                    true
                }
                R.id.action_edit -> {
                    EditContactBottomSheet.newInstance(contact)
                        .show(childFragmentManager, TAG_EDIT_SHEET)
                    true
                }
                R.id.action_share -> {
                    val text = buildString {
                        appendLine(contact.name)
                        appendLine(contact.phone)
                        if (!contact.notes.isNullOrBlank()) appendLine(contact.notes)
                    }
                    viewModel.launchTextShare(text, "Share Contact")
                    true
                }
                R.id.action_delete -> {
                    val db = DialogDeleteConfirmationBinding.inflate(layoutInflater)
                    val dialog = AlertDialog.Builder(requireContext()).setView(db.root).create()
                    db.dialogTitle.text = "Delete Contact?"
                    db.deleteConfirmationText.text =
                        "Are you sure you want to delete this contact permanently? This action cannot be undone."
                    db.cancelButton.setOnClickListener { dialog.dismiss() }
                    db.deleteButton.setOnClickListener {
                        viewModel.deleteContact(contact)
                        dialog.dismiss()
                    }
                    dialog.window?.setDimAmount(0.75f)
                    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    dialog.window?.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                    dialog.show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
    // ═══════════════════════════════════════════════════════════════
// ✅ NEW: Swipe hint — shown on first 3 visits
// ═══════════════════════════════════════════════════════════════

    private fun showSwipeHintIfNeeded() {
        val prefs = requireContext().getSharedPreferences(PREF_NAME, 0)
        val count = prefs.getInt(KEY_SWIPE_HINT_SHOWN, 0)

        if (count < MAX_HINT_SHOWS) {
            prefs.edit().putInt(KEY_SWIPE_HINT_SHOWN, count + 1).apply()

            viewLifecycleOwner.lifecycleScope.launch {
                delay(800) // Wait for list to load
                if (_binding != null && isAdded) {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        "Swipe right on any contact to open your phone app",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).setAction("Got it") { }
                        .setActionTextColor(
                            ContextCompat.getColor(requireContext(), R.color.nav_active)
                        )
                        .show()
                }
            }
        }
    }

// ═══════════════════════════════════════════════════════════════
// ✅ FIX: Persist call return flag (survives process death)
// ═══════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════
// ✅ NEW: Custom contact picker (multi-select)
// ═══════════════════════════════════════════════════════════════

    fun openCustomContactPicker() {
        val picker = ContactPickerSheet()
        picker.setOnImportListener { contacts ->
            if (contacts.isNotEmpty()) {
                viewModel.addContacts(contacts)
            }
            // Dismiss the add sheet too
            (childFragmentManager.findFragmentByTag(TAG_ADD_SHEET)
                    as? BottomSheetDialogFragment)?.dismiss()
        }
        picker.show(childFragmentManager, TAG_PICKER_SHEET)
    }
}
