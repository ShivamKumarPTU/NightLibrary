package com.example.nightlibrary

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nightlibrary.adapter.PasswordAdapter
import com.example.nightlibrary.core.security.PasswordCryptoManager
import com.example.nightlibrary.databinding.DialogDeleteConfirmationBinding
import com.example.nightlibrary.databinding.DialogSharePasswordBinding
import com.example.nightlibrary.databinding.FragmentManagePasswordBinding
import com.example.nightlibrary.entity.PasswordEntity
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ManagePassword : Fragment() {

    companion object {
        private const val TAG = "ManagePassword"
        private const val TAG_ADD_SHEET = "AddPasswordBottomSheet"
        private const val TAG_EDIT_SHEET = "EditPasswordBottomSheet"
    }

    private var _binding: FragmentManagePasswordBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: VaultViewModel
    private lateinit var adapter: PasswordAdapter

    private val backPressCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.clearPasswordSelection()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManagePasswordBinding.inflate(inflater, container, false)
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
        viewModel.clearPasswordSelection()
        super.onDestroyView()
        _binding = null
    }

    // ═══════════════════════════════════════════════════════════════
    // TOOLBAR
    // ═══════════════════════════════════════════════════════════════

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (viewModel.isPasswordSelectionMode.value) {
                viewModel.clearPasswordSelection()
            } else {
                findNavController().navigateUp()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RECYCLERVIEW
    // ═══════════════════════════════════════════════════════════════

    private fun setupRecyclerView() {
        val cryptoManager = PasswordCryptoManager()
        adapter = PasswordAdapter(
            cryptoManager = cryptoManager,
            onMenuClickListener = { password, anchor ->
                showPasswordMenu(password, anchor)
            },
            onItemClick = { password ->
                if (viewModel.isPasswordSelectionMode.value) {
                    viewModel.togglePasswordSelection(password.id)
                }
            },
            onItemLongClick = { password ->
                viewModel.togglePasswordSelection(password.id)
            }
        )
        binding.passwordsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.passwordsRecycler.adapter = adapter
    }

    // ═══════════════════════════════════════════════════════════════
    // FAB
    // ═══════════════════════════════════════════════════════════════

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            if (viewModel.isPasswordSelectionMode.value) {
                viewModel.selectAllPasswords()
            } else {
                AddPasswordBottomSheet().show(childFragmentManager, TAG_ADD_SHEET)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SELECTION ACTION BAR
    // ═══════════════════════════════════════════════════════════════

    private fun setupSelectionActions() {
        binding.btnCancelSelection.setOnClickListener {
            viewModel.clearPasswordSelection()
        }

        binding.btnShareSelected.setOnClickListener {
            // Security prompt: include passwords or not?
            val db = DialogSharePasswordBinding.inflate(layoutInflater)
            val dialog = AlertDialog.Builder(requireContext()).setView(db.root).create()
            db.passwordHideButton.setOnClickListener {
                    sharePasswords(includePasswords = false)
                }
              db.passwordShareButton.setOnClickListener {
                    sharePasswords(includePasswords = true)
                }
                  db.cancelButton.setOnClickListener{dialog.dismiss()}
            dialog.window?.setDimAmount(0.75f)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.show()
        }

        binding.btnDeleteSelected.setOnClickListener {
            val count = viewModel.selectedPasswordIds.value.size
            val db = DialogDeleteConfirmationBinding.inflate(layoutInflater)
            val dialog = AlertDialog.Builder(requireContext()).setView(db.root).create()
            db.dialogTitle.text="Delete $count password${if (count != 1) "s" else ""}?"
            db.deleteConfirmationText.text = "Are you sure you want to delete your passwords permanently? This action cannot be undone."
            db.cancelButton.setOnClickListener{dialog.dismiss()}
            db.deleteButton.setOnClickListener {
                viewModel.deleteSelectedPasswords()
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

    private fun sharePasswords(includePasswords: Boolean) {
        val text = viewModel.shareSelectedPasswords(includePasswords)
        if (text.isNotBlank()) {
            viewModel.launchTextShare(text, "Share Passwords")
            viewModel.clearPasswordSelection()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // OBSERVE DATA
    // ═══════════════════════════════════════════════════════════════

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.passwords.collectLatest { list ->
                adapter.submitList(list)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.passwordCount.collectLatest { count ->
                if (_binding != null) {
                    binding.passwordCountText.text =
                        "$count password${if (count != 1) "s" else ""}"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.selectedPasswordIds,
                viewModel.isPasswordSelectionMode
            ) { ids, selecting -> Pair(ids, selecting) }
                .collectLatest { (ids, selecting) ->
                    if (_binding == null) return@collectLatest

                    adapter.updateSelectedItems(ids)
                    binding.selectionActionsLayout.isVisible = selecting
                    binding.fabAdd.isVisible = !selecting
                    backPressCallback.isEnabled = selecting

                    if (selecting) {
                        val total = viewModel.passwords.value.size
                        binding.toolbar.title = "${ids.size} selected"
                        binding.toolbar.setNavigationIcon(R.drawable.ic_cross)
                        binding.passwordCountText.text = "${ids.size} of $total selected"
                    } else {
                        binding.toolbar.title = "Password Vault"
                        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left_with_bg)
                    }
                }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POPUP MENU (single item 3-dot)
    // ═══════════════════════════════════════════════════════════════

    private fun showPasswordMenu(password: PasswordEntity, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_password_item, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select -> {
                    viewModel.togglePasswordSelection(password.id)
                    true
                }
                R.id.action_edit -> {
                    EditPasswordBottomSheet.newInstance(password)
                        .show(childFragmentManager, TAG_EDIT_SHEET)
                    true
                }
                R.id.action_share -> {
                    val decrypted = try {
                        viewModel.decryptPassword(password.encryptedPassword)
                    } catch (_: Exception) { "[error]" }

                    val text = buildString {
                        appendLine("Service: ${password.serviceName}")
                        appendLine("Username: ${password.username}")
                        appendLine("Password: $decrypted")
                        if (!password.notes.isNullOrBlank()) appendLine("Notes: ${password.notes}")
                    }
                    viewModel.launchTextShare(text, "Share Password")
                    true
                }
                R.id.action_delete -> {
                    val db = DialogDeleteConfirmationBinding.inflate(layoutInflater)
                    val dialog = AlertDialog.Builder(requireContext()).setView(db.root).create()
                    db.dialogTitle.text="Delete Password?"
                    db.deleteConfirmationText.text = "Are you sure you want to delete this password permanently? This action cannot be undone."
                    db.cancelButton.setOnClickListener{dialog.dismiss()}
                    db.deleteButton.setOnClickListener {
                        viewModel.deletePassword(password)
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
}