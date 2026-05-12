package com.example.nightlibrary

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.adapter.InProgressSectionAdapter
import com.example.nightlibrary.adapter.VaultListAdapter
import com.example.nightlibrary.adapter.VaultSectionAdapter
import com.example.nightlibrary.core.security.VaultFileManager
import com.example.nightlibrary.databinding.DialogDeletePhotoBinding
import com.example.nightlibrary.databinding.DialogRenameMediaBinding
import com.example.nightlibrary.databinding.FragmentVaultListBinding
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.manager.DownloadQueueManager
import com.example.nightlibrary.model.InProgressItem
import com.example.nightlibrary.model.VaultSection
import com.example.nightlibrary.securefileactivity.SecureAudioActivity
import com.example.nightlibrary.securefileactivity.SecureImageActivity
import com.example.nightlibrary.securefileactivity.SecurePdfActivity
import com.example.nightlibrary.securefileactivity.SecureVideoActivity
import com.example.nightlibrary.security.SecureScreenManager
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.example.nightlibrary.worker.VideoPlayerPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class VaultListFragment : Fragment(R.layout.fragment_vault_list) {

    companion object {
        private const val TAG = "VaultListFragment"

        fun newInstance(isCompleted: Boolean) = VaultListFragment().apply {
            arguments = Bundle().apply { putBoolean("is_completed_list", isCompleted) }
        }
    }

    private lateinit var viewModel: VaultViewModel
    private var isCompletedList: Boolean = false
    private var _binding: FragmentVaultListBinding? = null
    private val binding get() = _binding!!

    private var currentTypeFilter: String = "all"

    // Completed tab adapters
    private var vaultListAdapter: VaultListAdapter? = null
    private var vaultSectionAdapter: VaultSectionAdapter? = null
    private var currentCompletedAdapter: Any? = null

    // ✅ NEW: In Progress tab adapter
    private var inProgressAdapter: InProgressSectionAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentVaultListBinding.bind(view)
        isCompletedList = arguments?.getBoolean("is_completed_list") ?: false
        SecureScreenManager.enable(requireActivity())

        val factory = (requireActivity().application as NightLibraryApp)
            .container.vaultViewModelFactory
        viewModel = ViewModelProvider(requireActivity(), factory)[VaultViewModel::class.java]

        cleanupStaleShareFiles()

        if (!isCompletedList) {
            setupInProgressTab()
        } else {
            setupCompletedTab()
        }

        binding.vaultRecyclerView.setItemViewCacheSize(30)
        binding.vaultRecyclerView.setHasFixedSize(false)
        binding.vaultRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) VideoPlayerPool.releaseAll()
            }
        })

        observeSelectionState()
    }

    // ═══════════════════════════════════════════════════════════════
    // ✅ NEW: IN PROGRESS TAB — Sectioned view
    // ═══════════════════════════════════════════════════════════════

    private fun setupInProgressTab() {
        binding.filterScroll.visibility = View.GONE
        binding.selectionActionsLayout.visibility = View.GONE

        val queueManager = DownloadQueueManager(requireContext())

        inProgressAdapter = InProgressSectionAdapter(
            onPauseResume = { media ->
                if (media.isPaused) {
                    queueManager.resumeDownload(media)
                    Toast.makeText(requireContext(), "Resuming…", Toast.LENGTH_SHORT).show()
                } else {
                    queueManager.pauseDownload(media)
                    Toast.makeText(requireContext(), "Paused", Toast.LENGTH_SHORT).show()
                }
            },
            onCancelDownload = { media ->
                showCancelDownloadDialog(media, queueManager)
            },
            onCancelImport = { media ->
                showCancelImportDialog(media)
            },
            onCancelShare = { taskId ->
                viewModel.cancelShare(taskId)
                Toast.makeText(requireContext(), "Share cancelled", Toast.LENGTH_SHORT).show()
            },
            onRetryDownload = { media ->
                queueManager.retryDownload(media)
                Toast.makeText(requireContext(), "Retrying…", Toast.LENGTH_SHORT).show()
            },
            onCancelAllInSection = { sectionType ->
                when (sectionType) {
                    InProgressItem.SectionType.SHARING -> {
                        viewModel.cancelAllShares()
                        Toast.makeText(requireContext(), "All shares cancelled", Toast.LENGTH_SHORT).show()
                    }
                    InProgressItem.SectionType.DOWNLOADS -> {
                        showCancelAllDownloadsDialog(queueManager)
                    }
                    InProgressItem.SectionType.IMPORTS -> {
                        Toast.makeText(requireContext(), "Imports cannot be bulk cancelled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        binding.vaultRecyclerView.adapter = inProgressAdapter
        binding.vaultRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        observeInProgress()
    }

    /**
     * ✅ NEW: Combines three data sources into a sectioned list.
     */
    @OptIn(FlowPreview::class, FlowPreview::class, FlowPreview::class)
    private fun observeInProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.activeShareTasks,
                viewModel.activeDownloads,
                viewModel.activeImports
            ) { shareTasks, downloads, imports ->
                withContext(Dispatchers.Default) {
                    buildInProgressList(shareTasks, downloads, imports)
                }
            }.debounce(200)
            .collectLatest { items ->
                inProgressAdapter?.submitList(items)
            }
        }
    }

    /**
     * ✅ NEW: Builds a flat list with section headers from three sources.
     */
    private fun buildInProgressList(
        shareTasks: List<com.example.nightlibrary.model.ShareTask>,
        downloads: List<MediaEntity>,
        imports: List<MediaEntity>
    ): List<InProgressItem> {
        val items = mutableListOf<InProgressItem>()

        // ── Sharing Section ────────────────────────────────────────
        // Only show tasks that are genuinely running. isCancelled and isCompleted tasks
        // are removed by the ViewModel; filtering here prevents a brief flash of stale cards.
        val activeShares = shareTasks.filter { it.isActive }
        if (activeShares.isNotEmpty()) {
            items.add(
                InProgressItem.SectionHeader(
                    title = "SHARING",
                    iconRes = R.drawable.ic_share,
                    activeCount = activeShares.size,
                    showCancelAll = activeShares.size > 1,
                    sectionType = InProgressItem.SectionType.SHARING
                )
            )
            activeShares.forEach { task ->
                items.add(InProgressItem.ShareItem(task))
            }
        }

        // ── Downloads Section ──────────────────────────────────────
        if (downloads.isNotEmpty()) {
            items.add(
                InProgressItem.SectionHeader(
                    title = "DOWNLOADS",
                    iconRes = R.drawable.ic_download,
                    activeCount = downloads.size,
                    showCancelAll = downloads.size > 1,
                    sectionType = InProgressItem.SectionType.DOWNLOADS
                )
            )
            downloads.forEach { media ->
                items.add(InProgressItem.DownloadItem(media))
            }
        }

        // ── Imports Section ────────────────────────────────────────
        if (imports.isNotEmpty()) {
            items.add(
                InProgressItem.SectionHeader(
                    title = "IMPORTS",
                    iconRes = R.drawable.ic_gallery,
                    activeCount = imports.size,
                    showCancelAll = false,
                    sectionType = InProgressItem.SectionType.IMPORTS
                )
            )
            imports.forEach { media ->
                items.add(InProgressItem.ImportItem(media))
            }
        }

        // ── Empty State ────────────────────────────────────────────
        if (items.isEmpty()) {
            items.add(InProgressItem.EmptyState)
        }

        return items
    }

    // ═══════════════════════════════════════════════════════════════
    // COMPLETED TAB — (Mostly unchanged from your original)
    // ═══════════════════════════════════════════════════════════════

    private fun setupCompletedTab() {
        vaultListAdapter = VaultListAdapter(
            onItemClick = { media ->
                if (viewModel.selectedItems.value.isNotEmpty()) {
                    viewModel.toggleSelection(media.id)
                } else {
                    openMedia(media)
                }
            },
            onLongClick = { media -> viewModel.toggleSelection(media.id) },
            onMenuClick = { media, anchor ->
                if (viewModel.selectedItems.value.isEmpty()) {
                    showMediaPopupMenu(media, anchor)
                }
            },
            onCancelClick = { media ->
                viewModel.cancelDownload(media)
                Toast.makeText(requireContext(), "Cancelled", Toast.LENGTH_SHORT).show()
            }
        )

        vaultSectionAdapter = VaultSectionAdapter(
            onClick = { media ->
                if (viewModel.selectedItems.value.isNotEmpty()) {
                    viewModel.toggleSelection(media.id)
                } else {
                    openMedia(media)
                }
            },
            onMenuClick = { v, media ->
                if (viewModel.selectedItems.value.isEmpty()) {
                    showMediaPopupMenu(media, v)
                }
            },
            onLongClick = { media -> viewModel.toggleSelection(media.id) }
        )

        currentCompletedAdapter = vaultListAdapter
        binding.vaultRecyclerView.adapter = vaultListAdapter
        setupLayoutManager(vaultListAdapter!!)
        binding.filterScroll.visibility = View.VISIBLE

        setupChipFilters()
        setupSelectionButtons()
        observeCompletedMedia()
    }

    private fun setupLayoutManager(adapter: VaultListAdapter) {
        val grid = GridLayoutManager(requireContext(), 2)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val type = adapter.getItemViewType(position)
                return if (type == VaultListAdapter.TYPE_PHOTO ||
                    type == VaultListAdapter.TYPE_VIDEO
                ) 1 else 2
            }
        }
        binding.vaultRecyclerView.layoutManager = grid
    }

    private fun setupChipFilters() {
        binding.chipAll.isChecked = true
        binding.filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentTypeFilter = when (checkedIds.firstOrNull()) {
                R.id.chip_photos -> "image"
                R.id.chip_videos -> "video"
                R.id.chip_audio -> "audio"
                R.id.chip_pdf -> "pdf"
                else -> "all"
            }
            observeCompletedMedia()
        }
    }

    private fun observeCompletedMedia() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.mediaCompleted.collectLatest { allItems ->
                val completedOnly = allItems.filter { it.isCompleted }

                if (currentTypeFilter == "all") {
                    if (currentCompletedAdapter != vaultSectionAdapter) {
                        currentCompletedAdapter = vaultSectionAdapter
                        binding.vaultRecyclerView.adapter = vaultSectionAdapter
                        binding.vaultRecyclerView.layoutManager =
                            LinearLayoutManager(requireContext())
                    }
                    val sections = withContext(Dispatchers.Default) {
                        listOf(
                            VaultSection.PhotoSection(completedOnly.filter { it.fileType == "image" }),
                            VaultSection.VideoSection(completedOnly.filter { it.fileType == "video" }),
                            VaultSection.AudioSection(completedOnly.filter { it.fileType == "audio" }),
                            VaultSection.PdfSection(completedOnly.filter { 
                                it.fileType == "pdf" || it.fileType == "document" 
                            })
                        ).filter { s ->
                            when (s) {
                                is VaultSection.PhotoSection -> s.items.isNotEmpty()
                                is VaultSection.VideoSection -> s.items.isNotEmpty()
                                is VaultSection.AudioSection -> s.items.isNotEmpty()
                                is VaultSection.PdfSection -> s.items.isNotEmpty()
                            }
                        }
                    }
                    vaultSectionAdapter?.submitSections(sections)
                    vaultSectionAdapter?.updateSelectedItems(viewModel.selectedItems.value)
                } else {
                    if (currentCompletedAdapter != vaultListAdapter) {
                        currentCompletedAdapter = vaultListAdapter
                        binding.vaultRecyclerView.adapter = vaultListAdapter
                        setupLayoutManager(vaultListAdapter!!)
                    }
                    val filtered = withContext(Dispatchers.Default) {
                        if (currentTypeFilter == "pdf") {
                            completedOnly.filter { it.fileType == "pdf" || it.fileType == "document" }
                        } else {
                            completedOnly.filter { it.fileType == currentTypeFilter }
                        }
                    }
                    vaultListAdapter?.submitList(filtered)
                    vaultListAdapter?.updateSelectedItems(viewModel.selectedItems.value)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SELECTION STATE — Shared between tabs
    // ═══════════════════════════════════════════════════════════════

    private fun observeSelectionState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedItems.collect { selectedIds ->
                when (currentCompletedAdapter) {
                    is VaultListAdapter -> vaultListAdapter?.updateSelectedItems(selectedIds)
                    is VaultSectionAdapter -> vaultSectionAdapter?.updateSelectedItems(selectedIds)
                }

                if (isCompletedList) {
                    val count = selectedIds.size
                    if (count > 0) {
                        binding.selectionActionsLayout.visibility = View.VISIBLE
                        binding.btnShareSelected.text = "Share ($count)"
                        binding.btnDeleteSelected.text = "Delete ($count)"
                    } else {
                        binding.selectionActionsLayout.visibility = View.GONE
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ✅ NEW: SELECTION BUTTONS — With cancel selection
    // ═══════════════════════════════════════════════════════════════

    private fun setupSelectionButtons() {
        // ✅ NEW: Cancel selection button
        binding.btnCancelSelection.setOnClickListener {
            viewModel.clearSelection()
        }

        binding.btnDeleteSelected.setOnClickListener {
            val ids = viewModel.selectedItems.value
            if (ids.isEmpty()) return@setOnClickListener
            showDeleteDialog(
                viewModel.mediaCompleted.value.filter { ids.contains(it.id) }
            )
        }

        // ✅ CHANGED: Share now goes through ViewModel (background operation)
        binding.btnShareSelected.setOnClickListener {
            val ids = viewModel.selectedItems.value
            if (ids.isEmpty()) return@setOnClickListener

            val selected = viewModel.mediaCompleted.value
                .filter { ids.contains(it.id) && it.isCompleted }

            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), "No completed files selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // ✅ NEW: Start share via ViewModel — shows in In Progress tab
            val taskId = viewModel.startShareOperation(selected.map { it.id })
            if (taskId.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "Preparing ${selected.size} file(s) for sharing…",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(requireContext(), "No files to share", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // IN PROGRESS DIALOGS
    // ═══════════════════════════════════════════════════════════════

    private fun showCancelDownloadDialog(media: MediaEntity, queueManager: DownloadQueueManager) {
        val db = DialogDeletePhotoBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(requireContext()).setView(db.root).create()
        db.dialogTitle.text = "Cancel Download"
        db.deleteConfirmationText.text =
            "Cancel downloading \"${media.fileName}\"? Progress will be lost."
        db.deleteButton.text = "Cancel Download"
        db.deleteButton.setOnClickListener {
            queueManager.cancelDownload(media)
            d.dismiss()
            Toast.makeText(requireContext(), "Download cancelled", Toast.LENGTH_SHORT).show()
        }
        db.cancelButton.setOnClickListener { d.dismiss() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.7f)
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
    }

    private fun showCancelImportDialog(media: MediaEntity) {
        val db = DialogDeletePhotoBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(requireContext()).setView(db.root).create()
        db.dialogTitle.text = "Cancel Import"
        db.deleteConfirmationText.text =
            "Cancel importing \"${media.fileName}\"?"
        db.deleteButton.text = "Cancel Import"
        db.deleteButton.setOnClickListener {
            viewModel.cancelDownload(media)
            d.dismiss()
            Toast.makeText(requireContext(), "Import cancelled", Toast.LENGTH_SHORT).show()
        }
        db.cancelButton.setOnClickListener { d.dismiss() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.7f)
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
    }

    private fun showCancelAllDownloadsDialog(queueManager: DownloadQueueManager) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Cancel All Downloads?")
            .setMessage("All active downloads will be cancelled. Completed files are safe.")
            .setPositiveButton("Cancel All") { dialog, _ ->
                queueManager.cancelAllDownloads()
                dialog.dismiss()
                Toast.makeText(requireContext(), "All downloads cancelled", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Keep") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    // COMPLETED TAB DIALOGS (unchanged from original)
    // ═══════════════════════════════════════════════════════════════

    private fun showDeleteDialog(selected: List<MediaEntity>) {
        if (selected.isEmpty()) return
        val db = DialogDeletePhotoBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(requireContext()).setView(db.root).create()
        db.dialogTitle.text = if (selected.size > 1) "Delete Items" else "Delete Media"
        db.deleteConfirmationText.text =
            "Permanently delete ${selected.size} items? This cannot be undone."
        db.deleteButton.setOnClickListener {
            viewModel.deleteSelectedMedia()
            d.dismiss()
            Toast.makeText(
                requireContext(),
                "${selected.size} items securely wiped",
                Toast.LENGTH_SHORT
            ).show()
        }
        db.cancelButton.setOnClickListener { d.dismiss() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.7f)
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
    }

    @OptIn(UnstableApi::class)
    private fun openMedia(media: MediaEntity) {
        (requireActivity().application as NightLibraryApp).isIgnoringNextLock = true
        if (!media.isCompleted) {
            Toast.makeText(
                requireContext(),
                "File is still being secured…",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        when (media.fileType) {
            "image" -> {
                val intent = SecureImageActivity.newIntent(requireContext(), media.id).apply {
                    putExtra("thumbnail_path", media.thumbnailPath)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            }
            "video" -> {
                startActivity(
                    Intent(requireContext(), SecureVideoActivity::class.java)
                        .putExtra("id", media.id)
                )
            }
            "audio" -> {
                startActivity(SecureAudioActivity.newIntent(requireContext(), media.id))
            }
            "pdf", "document" -> {
                startActivity(SecurePdfActivity.newIntent(requireContext(), media.id))
            }
            else -> {
                Toast.makeText(requireContext(), "Unsupported file type", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SINGLE FILE SHARE (from popup menu)
    // ═══════════════════════════════════════════════════════════════

    private fun shareMediaFile(media: MediaEntity) {
        if (!media.isCompleted) return
        // Use ViewModel share for single file too — shows in In Progress
        viewModel.startShareOperation(listOf(media.id))
        Toast.makeText(requireContext(), "Preparing to share…", Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════════════════════════════════════════════════
    // POPUP MENU
    // ═══════════════════════════════════════════════════════════════

    private fun showMediaPopupMenu(media: MediaEntity, anchor: View) {
        val popup = PopupMenu(
            ContextThemeWrapper(requireContext(), R.style.AppTheme_PopupMenu),
            anchor
        )
        popup.menuInflater.inflate(R.menu.media_menu, popup.menu)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_share_media -> { shareMediaFile(media); true }
                R.id.action_rename_media -> { showRenameDialog(media); true }
                R.id.action_delete_media -> { showDeleteConfirmationDialog(media); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(media: MediaEntity) {
        val db = DialogRenameMediaBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(requireContext()).setView(db.root).create()
        db.dialogTitle.text = "Rename ${media.fileType.uppercase()}"
        db.editTextEditName.setText(media.fileName)
        db.saveChangesButton.setOnClickListener {
            val name = db.editTextEditName.text.toString().trim()
            if (name.isNotEmpty()) {
                lifecycleScope.launch {
                    (requireActivity().application as NightLibraryApp).container.mediaRepository
                        .update(media.copy(fileName = name))
                    d.dismiss()
                }
            }
        }
        db.cancelButton.setOnClickListener { d.dismiss() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.7f)
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
    }

    private fun showDeleteConfirmationDialog(media: MediaEntity) {
        val db = DialogDeletePhotoBinding.inflate(layoutInflater)
        val d = AlertDialog.Builder(requireContext()).setView(db.root).create()
        db.deleteConfirmationText.text = "Permanently delete ${media.fileName}?"
        db.cancelButton.setOnClickListener { d.dismiss() }
        db.deleteButton.setOnClickListener {
            viewModel.permanentDelete(media)
            Toast.makeText(
                requireContext(),
                "${media.fileType} deleted",
                Toast.LENGTH_SHORT
            ).show()
            d.dismiss()
        }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.7f)
        d.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        d.show()
    }

    // ═══════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════

    private fun cleanupStaleShareFiles() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val shareDir = File(requireContext().filesDir, "vault_share")
                if (!shareDir.exists()) return@launch
                val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
                shareDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < tenMinutesAgo) file.delete()
                }
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    override fun onDestroyView() {
        super.onDestroyView()
        vaultListAdapter?.cancelAllJobs()
        _binding = null
        vaultListAdapter = null
        vaultSectionAdapter = null
        inProgressAdapter = null
    }

    override fun onPause() {
        super.onPause()
        if (isCompletedList) {
            binding.vaultRecyclerView.adapter?.notifyDataSetChanged()
        }
    }
}