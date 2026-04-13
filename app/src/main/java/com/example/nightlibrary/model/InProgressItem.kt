package com.example.nightlibrary.model

import com.example.nightlibrary.entity.MediaEntity

/**
 * Sealed class representing items in the In Progress RecyclerView.
 *
 * The adapter builds a flat list from three data sources:
 *   1. ViewModel.activeShareTasks  → ShareItem
 *   2. dao.getActiveDownloads()    → DownloadItem
 *   3. dao.getActiveImports()      → ImportItem
 *
 * Each section has a header. If all sections are empty, show EmptyState.
 *
 * Layout:
 *   ┌─ SectionHeader("Sharing", 📤, 2) ─────┐
 *   │  ShareItem(task1)                       │
 *   │  ShareItem(task2)                       │
 *   ├─ SectionHeader("Downloads", ⬇️, 3) ────┤
 *   │  DownloadItem(media1)                   │
 *   │  DownloadItem(media2)                   │
 *   │  DownloadItem(media3)                   │
 *   ├─ SectionHeader("Imports", 📥, 1) ──────┤
 *   │  ImportItem(media4)                     │
 *   └────────────────────────────────────────┘
 */
sealed class InProgressItem {

    /**
     * Stable ID for DiffUtil.
     * Must be unique across ALL item types.
     */
    abstract val stableId: Long

    // ── Section Header ────────────────────────────────────────────

    data class SectionHeader(
        val title: String,
        val iconRes: Int,
        val activeCount: Int,
        /** Optional: show "Cancel All" button for this section */
        val showCancelAll: Boolean = false,
        /** Section type identifier for cancel-all routing */
        val sectionType: SectionType = SectionType.DOWNLOADS
    ) : InProgressItem() {
        override val stableId: Long
            get() = title.hashCode().toLong() + 100_000L
    }

    // ── Share Progress Card ───────────────────────────────────────

    data class ShareItem(
        val task: ShareTask
    ) : InProgressItem() {
        override val stableId: Long
            get() = task.id.hashCode().toLong() + 200_000L
    }

    // ── Download Progress Card ────────────────────────────────────

    data class DownloadItem(
        val media: MediaEntity
    ) : InProgressItem() {
        override val stableId: Long
            get() = media.id + 300_000L
    }

    // ── Import Progress Card ──────────────────────────────────────

    data class ImportItem(
        val media: MediaEntity
    ) : InProgressItem() {
        override val stableId: Long
            get() = media.id + 400_000L
    }

    // ── Empty State ───────────────────────────────────────────────

    data object EmptyState : InProgressItem() {
        override val stableId: Long = -1L
    }


    // ── Section Type Enum ─────────────────────────────────────────

    enum class SectionType {
        SHARING, DOWNLOADS, IMPORTS
    }
}