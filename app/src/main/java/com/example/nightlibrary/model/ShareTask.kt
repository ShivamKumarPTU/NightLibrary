package com.example.nightlibrary.model

/**
 * Tracks a multi-file share operation.
 *
 * Share operations run as ViewModel coroutines (not WorkManager)
 * because they need the Activity context for launching intents.
 *
 * Displayed in the In Progress tab → "Sharing" section.
 */
data class ShareTask(
    /** Unique ID for this share operation */
    val id: String,

    /** IDs of media items being shared */
    val mediaIds: List<Long>,

    /** Display names of files being shared */
    val fileNames: List<String>,

    /** 0-based index of the file currently being decrypted */
    val currentFileIndex: Int = 0,

    /** Total number of files in this share batch */
    val totalFiles: Int = 0,

    /** Progress of the current file decryption (0-100) */
    val currentFileProgress: Int = 0,

    /** Overall progress across all files (0-100) */
    val overallProgress: Int = 0,

    /** Human-readable status: "Decrypting 2/5…", "Launching share…" */
    val status: String = "Preparing…",

    /** Set to true when user cancels this share */
    val isCancelled: Boolean = false,

    /** Set when share completes successfully */
    val isCompleted: Boolean = false,

    /** Error message if share failed */
    val error: String? = null,

    /** Timestamp when share started — used for auto-cleanup */
    val startedAt: Long = System.currentTimeMillis()
) {
    /**
     * Returns a user-friendly summary string.
     * Used by the adapter for display.
     */
    val displayStatus: String
        get() = when {
            isCompleted -> "✓ Shared ${totalFiles} file(s)"
            isCancelled -> "Cancelled"
            error != null -> "Failed: ${error?.take(50)}"
            totalFiles <= 1 -> status
            else -> "Decrypting ${currentFileIndex + 1}/$totalFiles…"
        }

    /**
     * Whether this task is still actively running.
     */
    val isActive: Boolean
        get() = !isCompleted && !isCancelled && error == null
}