package com.example.nightlibrary

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkManager
import com.example.nightlibrary.adapter.VaultPagerAdapter
import com.example.nightlibrary.core.security.PasswordCryptoManager
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.databinding.FragmentSecretVaultBinding
import com.example.nightlibrary.repository.ContactRepository
import com.example.nightlibrary.repository.MediaRepository
import com.example.nightlibrary.repository.PasswordRepository
import com.example.nightlibrary.security.SecureScreenManager
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.example.nightlibrary.viewmodel.VaultViewModelFactory
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SecretVaultFragment : Fragment() {

    companion object {
        private const val TAG = "SecretVaultFragment"
    }

    private var _binding: FragmentSecretVaultBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: VaultViewModel

    private enum class VaultCategory { MEDIA, PASSWORDS, CONTACTS }
    private var currentCategory = VaultCategory.MEDIA

    // ✅ NEW: Track if badge has been set up
    private var tabMediator: TabLayoutMediator? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSecretVaultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val database = VaultDatabase.getDatabase(requireContext())
        val contactRepository = ContactRepository(database.contactDao())
        val passwordRepository = PasswordRepository(database.passwordDao(), PasswordCryptoManager())
        val mediaRepository = MediaRepository(database.mediaDao())
        val workManager = WorkManager.getInstance(requireContext().applicationContext)
        SecureScreenManager.enable(requireActivity())

        val factory = VaultViewModelFactory(
            application = requireActivity().application,
            contactRepository,
            passwordRepository,
            mediaRepository,
            workManager
        )
        viewModel = ViewModelProvider(requireActivity(), factory)[VaultViewModel::class.java]

        setupTabs()
        setupFab()
        setupViewPager()
        setupSummaryCards()
        updateUiForCategory(VaultCategory.MEDIA)

        // ✅ NEW: Observe in-progress count for tab badge
        observeInProgressBadge()

        // ✅ NEW: Observe operation events for auto-switching
        observeOperationEvents()

        // ✅ Handle start_tab argument (from import or download)
        val startTab = arguments?.getInt("start_tab", -1) ?: -1
        if (startTab == 0) {
            Log.d(TAG, "start_tab=0 → switching to In-Progress tab")
            binding.viewPagerVault.post {
                binding.viewPagerVault.setCurrentItem(0, true)
            }
        }

        // ✅ NEW: Auto-switch to In Progress when items exist and coming from import
        if (startTab == 0) {
            autoSwitchToInProgressIfNeeded()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TABS (Media / Passwords / Contacts)
    // ═══════════════════════════════════════════════════════════════

    private fun setupTabs() {
        binding.tabLayoutCategories.getTabAt(0)?.select()
        binding.tabLayoutCategories.addOnTabSelectedListener(object :
            TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentCategory = when (tab?.position) {
                    0 -> VaultCategory.MEDIA
                    1 -> VaultCategory.PASSWORDS
                    2 -> VaultCategory.CONTACTS
                    else -> VaultCategory.MEDIA
                }
                updateUiForCategory(currentCategory)
            }

            override fun onTabUnselected(p0: TabLayout.Tab?) {}
            override fun onTabReselected(p0: TabLayout.Tab?) {}
        })
    }

    private fun updateUiForCategory(category: VaultCategory) {
        binding.mediaContentLayout.isVisible = category == VaultCategory.MEDIA
        binding.passwordSummaryCard.root.isVisible = category == VaultCategory.PASSWORDS
        binding.contactsSummaryCard.root.isVisible = category == VaultCategory.CONTACTS
        setFabVisibility()
    }

    // ═══════════════════════════════════════════════════════════════
    // ✅ IMPROVED: ViewPager (In Progress / Completed) with Badge
    // ═══════════════════════════════════════════════════════════════

    private fun setupViewPager() {
        binding.viewPagerVault.adapter = VaultPagerAdapter(requireActivity())
        binding.viewPagerVault.isUserInputEnabled = false // ✅ Disable swiping between tabs
        binding.viewPagerVault.setCurrentItem(1, false) // default = Completed

        tabMediator = TabLayoutMediator(
            binding.tabLayoutStatus,
            binding.viewPagerVault
        ) { tab, position ->
            tab.text = if (position == 0) "In Progress" else "Completed"
        }
        tabMediator?.attach()
    }

    // ═══════════════════════════════════════════════════════════════
    // ✅ NEW: In Progress Badge — Shows count of active operations
    // ═══════════════════════════════════════════════════════════════

    private fun observeInProgressBadge() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.totalActiveOperations.collectLatest { count ->
                updateInProgressBadge(count)
            }
        }
    }

    /**
     * ✅ NEW: Consolidate reactive UI events
     */
    private fun observeOperationEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.operationEvents.collect { event ->
                when (event) {
                    is VaultViewModel.OperationEvent.DownloadStarted,
                    is VaultViewModel.OperationEvent.ImportStarted,
                    is VaultViewModel.OperationEvent.ShareStarted -> {
                        // Switch to In Progress tab when a background task begins
                        if (_binding != null && binding.viewPagerVault.currentItem != 0) {
                            binding.viewPagerVault.setCurrentItem(0, true)
                        }
                    }
                    is VaultViewModel.OperationEvent.TaskCompleted -> {
                        // Refresh logic if needed, but StateFlows handle most of it
                        Log.d(TAG, "Operation completed event received")
                    }
                }
            }
        }
    }

    /**
     * Updates the "In Progress" tab to show badge with active count.
     * Examples:
     *   0 active → "In Progress"
     *   3 active → "In Progress (3)"
     */
    private fun updateInProgressBadge(count: Int) {
        if (_binding == null) return
        val tab = binding.tabLayoutStatus.getTabAt(0) ?: return

        tab.text = if (count > 0) {
            "In Progress ($count)"
        } else {
            "In Progress"
        }

        // ✅ Auto-switch to In Progress tab if there are active items
        // and user is on Completed tab with nothing happening
        // (Only on first detection, not every update)
    }

    /**
     * ✅ NEW: When navigating from ImportMedia or DownloadFormLink,
     * auto-switch to In Progress tab if there are active operations.
     */
    private fun autoSwitchToInProgressIfNeeded() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Small delay to let the DB entry be created
            kotlinx.coroutines.delay(300)

            val count = viewModel.totalActiveOperations.value
            if (count > 0 && _binding != null) {
                binding.viewPagerVault.setCurrentItem(0, true)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUMMARY CARDS
    // ═══════════════════════════════════════════════════════════════

    private fun setupSummaryCards() {
        binding.passwordSummaryCard.apply {
            summaryIcon.setImageResource(R.drawable.lockicon)
            summaryTitle.text = "Manage Passwords"
            root.setOnClickListener {
                findNavController().navigate(R.id.action_secretVaultFragment_to_managePassword)
            }
        }
        binding.contactsSummaryCard.apply {
            summaryIcon.setImageResource(R.drawable.ic_contacts)
            summaryTitle.text = "Manage Contacts"
            root.setOnClickListener {
                findNavController().navigate(R.id.action_secretVaultFragment_to_manageContact)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.passwordCount.collect { count ->
                if (_binding != null) {
                    binding.passwordSummaryCard.summaryItemCount.text = "$count items"
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.contactCount.collect { count ->
                if (_binding != null) {
                    binding.contactsSummaryCard.summaryItemCount.text = "$count contacts"
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FAB
    // ═══════════════════════════════════════════════════════════════

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            if (currentCategory == VaultCategory.MEDIA) {
                findNavController().navigate(R.id.action_secretVaultFragment_to_import_media)
            }
        }
    }

    private fun setFabVisibility() {
        binding.fabAdd.visibility =
            if (currentCategory == VaultCategory.MEDIA) View.VISIBLE else View.GONE
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    override fun onDestroyView() {
        tabMediator?.detach()
        tabMediator = null
        super.onDestroyView()
        _binding = null
    }
}