package com.example.nightlibrary.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.nightlibrary.NightLibraryApp
import com.example.nightlibrary.core.security.CachingFileProvider
import com.example.nightlibrary.core.security.SecureShareHelper
import com.example.nightlibrary.core.security.VaultFileManager
import com.example.nightlibrary.core.security.ZipShareHelper
import com.example.nightlibrary.entity.ContactEntity
import com.example.nightlibrary.entity.MediaEntity
import com.example.nightlibrary.entity.PasswordEntity
import com.example.nightlibrary.model.ShareTask
import com.example.nightlibrary.repository.ContactRepository
import com.example.nightlibrary.repository.MediaRepository
import com.example.nightlibrary.repository.PasswordRepository
import com.example.nightlibrary.security.SecureWipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class VaultViewModel(
    private val application: Application,
    private val contactRepository: ContactRepository,
    private val passwordRepository: PasswordRepository,
    private val mediaRepository: MediaRepository,
    private val workManager: WorkManager
) : ViewModel() {

    companion object {
        private const val TAG = "VaultVM"

        // ✅ NEW: Share strategy thresholds
        private const val ZIP_THRESHOLD = 3          // 3+ files → ZIP
        private const val LARGE_FILE_MB = 100L       // Files > 100MB get special handling
    }

    // ════════════════════════════════════════════════════════════════
    // SHARE TRACKING (Must be declared before totalActiveOperations)
    // ════════════════════════════════════════════════════════════════

    private val _activeShareTasks = MutableStateFlow<List<ShareTask>>(emptyList())
    val activeShareTasks: StateFlow<List<ShareTask>> = _activeShareTasks

    sealed class OperationEvent {
        object DownloadStarted : OperationEvent()
        object ImportStarted : OperationEvent()
        data class ShareStarted(val taskId: String) : OperationEvent()
        object TaskCompleted : OperationEvent()
    }

    private val _operationEvents = MutableSharedFlow<OperationEvent>(extraBufferCapacity = 1)
    val operationEvents: SharedFlow<OperationEvent> = _operationEvents.asSharedFlow()

    fun notifyDownloadStarted() {
        viewModelScope.launch {
            _operationEvents.emit(OperationEvent.DownloadStarted)
        }
    }

    fun notifyImportStarted() {
        viewModelScope.launch {
            _operationEvents.emit(OperationEvent.ImportStarted)
        }
    }

    private val _isLaunchingExternalIntent = MutableStateFlow(false)
    val isLaunchingExternalIntent: StateFlow<Boolean> = _isLaunchingExternalIntent.asStateFlow()

    private val shareJobs = mutableMapOf<String, Job>()

    // ✅ NEW: Dedicated dispatcher for share operations
    private val shareDispatcher = Dispatchers.IO.limitedParallelism(3)

    // ════════════════════════════════════════════════════════════════
    // CONTACT
    // ════════════════════════════════════════════════════════════════

    val contactCount: StateFlow<Int> =
        contactRepository.getCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    val contacts =
        contactRepository.getAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    fun addContact(name: String, phone: String, notes: String?) {
        viewModelScope.launch {
            if (contactRepository.isDuplicate(phone)) {
                _contactStatus.emit("Contact already exists")
                return@launch
            }
            contactRepository.add(name, phone, notes)
            _operationEvents.emit(OperationEvent.TaskCompleted)
            _contactStatus.emit("Contact added")
            Log.d(TAG, "Contact added")
        }
    }

    fun addContacts(contacts: List<ContactEntity>) {
        if (contacts.isEmpty()) return
        viewModelScope.launch {
            val addedCount = contactRepository.addAll(contacts)
            _operationEvents.emit(OperationEvent.TaskCompleted)
            val msg = if (addedCount == contacts.size) {
                "Imported ${contacts.size} contacts"
            } else {
                "Imported $addedCount contacts (${contacts.size - addedCount} duplicates skipped)"
            }
            _contactStatus.emit(msg)
            Log.d(TAG, msg)
        }
    }

    private val _contactStatus = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val contactStatus: SharedFlow<String> = _contactStatus.asSharedFlow()

    fun updateContact(contact: ContactEntity) {
        viewModelScope.launch {
            contactRepository.update(contact)
            _operationEvents.emit(OperationEvent.TaskCompleted)
        }
    }

    fun deleteContact(contact: ContactEntity) {
        viewModelScope.launch {
            contactRepository.delete(contact)
            _operationEvents.emit(OperationEvent.TaskCompleted)
        }
    }

    /**
     * Call this immediately before launching Camera, File Manager, or Gmail.
     * It prevents the Biometric/Password Auth from triggering during the transition.
     */
    fun prepareForExternalIntent() {
        _isLaunchingExternalIntent.value = true
    }

    /**
     * Call this in the Activity's onResume() or the Fragment's onResume()
     * to reset the security gate.
     */
    fun resetExternalIntentState() {
        _isLaunchingExternalIntent.value = false
    }
    // ════════════════════════════════════════════════════════════════
    // ✅ NEW: CONTACT SELECTION — Multi-select support
    // ════════════════════════════════════════════════════════════════

    val selectedContactIds = MutableStateFlow<Set<Long>>(emptySet())

    val isContactSelectionMode: StateFlow<Boolean> =
        selectedContactIds.map { it.isNotEmpty() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    fun toggleContactSelection(id: Long) {
        selectedContactIds.update { current ->
            if (current.contains(id)) current - id else current + id
        }
    }

    fun clearContactSelection() {
        selectedContactIds.value = emptySet()
    }

    fun selectAllContacts() {
        val allIds = contacts.value.map { it.id }.toSet()
        selectedContactIds.value = allIds
    }

    /** ✅ Bulk delete selected contacts */
    fun deleteSelectedContacts() {
        val ids = selectedContactIds.value.toList()
        if (ids.isEmpty()) return

        selectedContactIds.value = emptySet()

        viewModelScope.launch {
            try {
                contactRepository.deleteByIds(ids)
                _operationEvents.emit(OperationEvent.TaskCompleted)
                Log.d(TAG, "Deleted ${ids.size} contacts")
            } catch (e: Exception) {
                Log.e(TAG, "Bulk contact delete failed: ${e.message}")
            }
        }
    }

    /** ✅ Share selected contacts as formatted text */
    fun shareSelectedContacts(): String {
        val ids = selectedContactIds.value
        if (ids.isEmpty()) return ""

        val toShare = contacts.value.filter { ids.contains(it.id) }
        if (toShare.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("📱 Shared from NightLibrary")
        sb.appendLine()

        toShare.forEachIndexed { index, contact ->
            sb.appendLine(contact.name)
            sb.appendLine(contact.phone)
            if (!contact.notes.isNullOrBlank()) {
                sb.appendLine(contact.notes)
            }
            if (index < toShare.size - 1) {
                sb.appendLine("───────────────")
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    // ════════════════════════════════════════════════════════════════
    // PASSWORD
    // ════════════════════════════════════════════════════════════════

    val passwordCount: StateFlow<Int> =
        passwordRepository.getCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    val passwords =
        passwordRepository.getAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    fun addPassword(service: String, username: String, password: String, notes: String?) {
        viewModelScope.launch {
            passwordRepository.add(service, username, password, notes)
            _operationEvents.emit(OperationEvent.TaskCompleted)
        }
    }

    fun decryptPassword(encrypted: String): String {
        return passwordRepository.decryptPassword(encrypted)
    }

    fun updatePassword(password: PasswordEntity) {
        viewModelScope.launch {
            passwordRepository.update(password)
            _operationEvents.emit(OperationEvent.TaskCompleted)
        }
    }

    fun deletePassword(password: PasswordEntity) {
        viewModelScope.launch {
            passwordRepository.delete(password)
            _operationEvents.emit(OperationEvent.TaskCompleted)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ✅ NEW: PASSWORD SELECTION — Multi-select support
    // ════════════════════════════════════════════════════════════════

    val selectedPasswordIds = MutableStateFlow<Set<Long>>(emptySet())

    val isPasswordSelectionMode: StateFlow<Boolean> =
        selectedPasswordIds.map { it.isNotEmpty() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    fun togglePasswordSelection(id: Long) {
        selectedPasswordIds.update { current ->
            if (current.contains(id)) current - id else current + id
        }
    }

    fun clearPasswordSelection() {
        selectedPasswordIds.value = emptySet()
    }

    fun selectAllPasswords() {
        val allIds = passwords.value.map { it.id }.toSet()
        selectedPasswordIds.value = allIds
    }

    /** ✅ Bulk delete selected passwords */
    fun deleteSelectedPasswords() {
        val ids = selectedPasswordIds.value.toList()
        if (ids.isEmpty()) return

        selectedPasswordIds.value = emptySet()

        viewModelScope.launch {
            try {
                passwordRepository.deleteByIds(ids)
                _operationEvents.emit(OperationEvent.TaskCompleted)
                Log.d(TAG, "Deleted ${ids.size} passwords")
            } catch (e: Exception) {
                Log.e(TAG, "Bulk password delete failed: ${e.message}")
            }
        }
    }

    /**
     * ✅ Share selected passwords as text.
     * ⚠️ Only shares service + username — NOT the actual password for security.
     * Set includePasswords = true if you want to include decrypted passwords.
     */
    fun shareSelectedPasswords(includePasswords: Boolean = false): String {
        val ids = selectedPasswordIds.value
        if (ids.isEmpty()) return ""

        val toShare = passwords.value.filter { ids.contains(it.id) }
        if (toShare.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("🔐 Shared from NightLibrary")
        sb.appendLine()

        toShare.forEachIndexed { index, pw ->
            sb.appendLine("Service: ${pw.serviceName}")
            sb.appendLine("Username: ${pw.username}")

            if (includePasswords) {
                try {
                    val decrypted = passwordRepository.decryptPassword(pw.encryptedPassword)
                    sb.appendLine("Password: $decrypted")
                } catch (_: Exception) {
                    sb.appendLine("Password: [decryption error]")
                }
            }

            if (!pw.notes.isNullOrBlank()) {
                sb.appendLine("Notes: ${pw.notes}")
            }

            if (index < toShare.size - 1) {
                sb.appendLine("───────────────")
                sb.appendLine()
            }
        }

        if (!includePasswords) {
            sb.appendLine()
            sb.appendLine("⚠️ Passwords not included for security.")
        }

        return sb.toString()
    }

    // ════════════════════════════════════════════════════════════════
    // MEDIA — Gallery & Counts (unchanged)
    // ════════════════════════════════════════════════════════════════

    val mediaCompleted: StateFlow<List<MediaEntity>> =
        mediaRepository.getCompleted()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val mediaCount: StateFlow<Int> =
        mediaRepository.getCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    val images =
        mediaRepository.getImages()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    fun getPagedMedia(): Flow<PagingData<MediaEntity>> {
        return mediaRepository.getPageMedia()
            .cachedIn(viewModelScope)
    }

    val mediaInProgress: StateFlow<List<MediaEntity>> =
        mediaRepository.getInProgress()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    // 🔒 PRIVATE MEDIA
    val privateMediaCompleted: StateFlow<List<MediaEntity>> =
        mediaRepository.getPrivateCompleted()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val privateMediaInProgress: StateFlow<List<MediaEntity>> =
        mediaRepository.getPrivateInProgress()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val totalPrivateCount: StateFlow<Int> =
        combine(privateMediaCompleted, privateMediaInProgress) { completed, inProgress ->
            completed.size + inProgress.size
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val activeDownloads: StateFlow<List<MediaEntity>> =
        mediaRepository.getActiveDownloads()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    val activeImports: StateFlow<List<MediaEntity>> =
        mediaRepository.getActiveImports()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    val inProgressCount: StateFlow<Int> =
        mediaRepository.getInProgressCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = 0
            )

    val totalActiveOperations: StateFlow<Int> =
        combine(
            mediaRepository.getInProgressCount(),
            _activeShareTasks
        ) { dbCount, shares ->
            dbCount + shares.count { it.isActive }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0
        )

    fun observeDownload(fileName: String): LiveData<List<WorkInfo>> {
        return workManager.getWorkInfosByTagLiveData(fileName)
    }

    // ════════════════════════════════════════════════════════════════
    // MEDIA SELECTION (unchanged)
    // ════════════════════════════════════════════════════════════════

    val selectedItems = MutableStateFlow<Set<Long>>(emptySet())

    val isSelectionMode: StateFlow<Boolean> =
        selectedItems.map { it.isNotEmpty() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false
            )

    fun toggleSelection(id: Long) {
        selectedItems.value = selectedItems.value.toMutableSet().apply {
            if (contains(id)) remove(id) else add(id)
        }
    }

    fun clearSelection() {
        selectedItems.value = emptySet()
    }

    fun selectAll() {
        val allIds = mediaCompleted.value.map { it.id }.toSet()
        selectedItems.value = allIds
    }

    // ════════════════════════════════════════════════════════════════
    // MEDIA SHARE OPERATIONS — UPDATED WITH SMART STRATEGY
    // ════════════════════════════════════════════════════════════════

    fun startShareOperation(mediaIds: List<Long>): String {
        val taskId = UUID.randomUUID().toString()

        val allMedia = mediaCompleted.value
        val toShare = allMedia.filter { mediaIds.contains(it.id) && it.isCompleted }

        if (toShare.isEmpty()) {
            Log.w(TAG, "No completed files to share")
            return ""
        }

        val fileNames = toShare.map { it.fileName }
        val shouldZip = toShare.size >= ZipShareHelper.AUTO_ZIP_THRESHOLD

        val task = ShareTask(
            id = taskId,
            mediaIds = mediaIds,
            fileNames = fileNames,
            totalFiles = toShare.size,
            status = if (shouldZip) {
                "Preparing ${toShare.size} files for ZIP…"
            } else {
                "Preparing ${toShare.size} file(s)…"
            },
            isZipped = shouldZip
        )

        _activeShareTasks.update { current -> current + task }
        clearSelection()

        viewModelScope.launch {
            _operationEvents.emit(OperationEvent.ShareStarted(taskId))
        }

        val job = viewModelScope.launch(Dispatchers.IO) {
            val tempFiles = mutableListOf<File>()
            try {
                val startTime = System.currentTimeMillis()

                // ═══════════════════════════════════════════════════
                // PHASE 1: Parallel Decryption (same for both paths)
                // ═══════════════════════════════════════════════════

                val decryptPct = if (shouldZip) 70 else 100  // Reserve 30% for ZIP phase
                val perFilePct = decryptPct.toDouble() / toShare.size
                val fileProgressArray = IntArray(toShare.size) { 0 }

                updateShareTask(taskId) { t ->
                    t.copy(status = "Decrypting ${toShare.size} file(s)…")
                }

                val deferred = toShare.mapIndexed { index, media ->
                    async(shareDispatcher) {
                        ensureActive()

                        val shareHelper = SecureShareHelper(application)
                        val vaultFolder = shareHelper.resolveVaultFolder(media)

                        val safeName = media.fileName
                            .replace(Regex("[^a-zA-Z0-9._ -]"), "_")
                            .trim().take(100)
                        val ext = getFileExtension(media)
                        val outFile = File(
                            getShareDir(),
                            "${safeName}_${System.currentTimeMillis()}.$ext"
                        )

                        shareHelper.decryptToFile(vaultFolder, outFile) { chunkProgress ->
                            fileProgressArray[index] = chunkProgress
                            val overall = fileProgressArray.indices.sumOf { i ->
                                (fileProgressArray[i] * perFilePct / 100.0).toInt()
                            }.coerceIn(0, decryptPct - 1)

                            updateShareTask(taskId) { t ->
                                t.copy(
                                    currentFileIndex = index,
                                    currentFileProgress = chunkProgress,
                                    overallProgress = overall,
                                    status = "Decrypting ${index + 1}/${toShare.size}…"
                                )
                            }
                        }

                        outFile
                    }
                }

                // Check cancellation
                if (_activeShareTasks.value.find { it.id == taskId }?.isCancelled == true) {
                    deferred.forEach { it.cancel() }
                    return@launch
                }

                val shareFiles = deferred.awaitAll()
                tempFiles.addAll(shareFiles)

                val validFiles = shareFiles.filter { it.exists() && it.length() > 0 }
                if (validFiles.isEmpty()) {
                    updateShareTask(taskId) { t ->
                        t.copy(error = "No files could be decrypted", isCompleted = true)
                    }
                    return@launch
                }

                val decryptTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Decryption phase: ${validFiles.size} files in ${decryptTime}ms")

                // ═══════════════════════════════════════════════════
                // PHASE 2: Share Strategy — ZIP or Direct
                // ═══════════════════════════════════════════════════

                val uris: ArrayList<Uri>

                if (shouldZip && validFiles.size >= ZipShareHelper.AUTO_ZIP_THRESHOLD) {
                    // ── ZIP PATH ──────────────────────────────────
                    updateShareTask(taskId) { t ->
                        t.copy(
                            overallProgress = 70,
                            status = "Creating ZIP archive…"
                        )
                    }

                    val displayNames = toShare.map { it.fileName }
                    val zipFile = ZipShareHelper.createZip(
                        files = validFiles,
                        names = displayNames,
                        outputDir = getShareDir()
                    ) { zipProgress ->
                        val overall = 70 + (zipProgress * 30 / 100)
                        updateShareTask(taskId) { t ->
                            t.copy(
                                overallProgress = overall.coerceIn(70, 99),
                                status = "Zipping… $zipProgress%"
                            )
                        }
                    }

                    tempFiles.add(zipFile)

                    val zipUri = FileProvider.getUriForFile(
                        application,
                        "${application.packageName}.fileprovider",
                        zipFile
                    )
                    uris = arrayListOf(zipUri)

                    // Clean up individual decrypted files (ZIP has the data now)
                    validFiles.forEach { file ->
                        try { file.delete() } catch (_: Exception) {}
                    }
                    tempFiles.removeAll(validFiles)

                    val zipTime = System.currentTimeMillis() - startTime - decryptTime
                    Log.d(TAG, "ZIP phase: ${zipFile.length()} bytes in ${zipTime}ms")

                } else {
                    // ── DIRECT PATH (1-2 files) ───────────────────
                    uris = ArrayList()
                    validFiles.forEach { file ->
                        val uri = FileProvider.getUriForFile(
                            application,
                            "${application.packageName}.fileprovider",
                            file
                        )
                        uris.add(uri)
                    }
                }

                // ═══════════════════════════════════════════════════
                // PHASE 3: Launch Share Intent
                // ═══════════════════════════════════════════════════

                updateShareTask(taskId) { t ->
                    t.copy(
                        overallProgress = 100,
                        currentFileProgress = 100,
                        status = "Launching share…",
                        isCompleted = true
                    )
                }

                viewModelScope.launch {
                    _operationEvents.emit(OperationEvent.TaskCompleted)
                }

                val totalTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "✅ Share ready in ${totalTime}ms " +
                        "(${validFiles.size} files, zip=$shouldZip, " +
                        "${uris.size} URI(s))")

                withContext(Dispatchers.Main) {
                    launchShareIntent(uris, toShare.size, shouldZip)
                }

                withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.delay(3000)
                    removeShareTask(taskId)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    Log.d(TAG, "Share task $taskId cancelled — cleaning up")
                    cleanupTempFiles(tempFiles)
                    removeShareTask(taskId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Share task $taskId failed: ${e.message}", e)
                updateShareTask(taskId) { t ->
                    t.copy(error = e.message ?: "Unknown error")
                }
                withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.delay(5000)
                    removeShareTask(taskId)
                }
            }
        }

        shareJobs[taskId] = job
        return taskId
    }

    private fun getFileExtension(media: MediaEntity): String {
        val ext = media.fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 5) return ext

        return when {
            media.mimeType.contains("mp4") -> "mp4"
            media.mimeType.contains("mkv") || media.mimeType.contains("matroska") -> "mkv"
            media.mimeType.contains("webm") -> "webm"
            media.mimeType.contains("mp3") || media.mimeType.contains("mpeg") -> "mp3"
            media.mimeType.contains("m4a") -> "m4a"
            media.mimeType.contains("jpeg") || media.mimeType.contains("jpg") -> "jpg"
            media.mimeType.contains("png") -> "png"
            media.mimeType.contains("pdf") -> "pdf"
            media.fileType == "video" -> "mp4"
            media.fileType == "audio" -> "mp3"
            media.fileType == "image" -> "jpg"
            else -> "bin"
        }
    }

    private fun getShareDir(): File =
        File(application.filesDir, "vault_share").also { it.mkdirs() }

    fun cancelShare(taskId: String) {
        Log.d(TAG, "Cancelling share: $taskId")
        updateShareTask(taskId) { it.copy(isCancelled = true, status = "Cancelling…") }
        shareJobs[taskId]?.cancel()
        shareJobs.remove(taskId)
    }

    fun cancelAllShares() {
        Log.d(TAG, "Cancelling all shares")
        shareJobs.forEach { (_, job) -> job.cancel() }
        shareJobs.clear()
        _activeShareTasks.value = emptyList()
    }

    private fun updateShareTask(taskId: String, transform: (ShareTask) -> ShareTask) {
        _activeShareTasks.update { tasks ->
            tasks.map { if (it.id == taskId) transform(it) else it }
        }
    }

    private fun removeShareTask(taskId: String) {
        _activeShareTasks.update { tasks ->
            tasks.filter { it.id != taskId }
        }
        shareJobs.remove(taskId)
    }

    private fun cleanupTempFiles(files: List<File>) {
        for (file in files) {
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
    }

    private fun launchShareIntent(
        uris: ArrayList<Uri>,
        fileCount: Int,
        isZipped: Boolean = false
    ) {
        try {
            // ✅ Pre-cache all URI metadata to prevent FileProvider query storms
            uris.forEach { uri ->
                try {
                    val file = File(
                        application.filesDir,
                        "vault_share/${uri.lastPathSegment}"
                    )
                    if (file.exists()) {
                        val mimeType = if (isZipped) "application/zip"
                        else getMimeFromFileName(file.name)

                        CachingFileProvider.preCache(
                            uri = uri,
                            name = file.name,
                            size = file.length(),
                            mimeType = mimeType
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-cache failed for $uri: ${e.message}")
                }
            }

            val shareIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = if (isZipped) "application/zip" else "*/*"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (isZipped) {
                        putExtra(
                            Intent.EXTRA_SUBJECT,
                            "NightLibrary — $fileCount files"
                        )
                    }
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            val chooser = Intent.createChooser(
                shareIntent,
                if (isZipped) "Share $fileCount files (ZIP)"
                else "Share $fileCount file(s)"
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            (application as? NightLibraryApp)?.isIgnoringNextLock = true
            application.startActivity(chooser)

        } catch (e: Exception) {
            Log.e(TAG, "Share intent failed: ${e.message}", e)
            (application as? NightLibraryApp)?.isIgnoringNextLock = false
        } finally {
            // ✅ Schedule cache cleanup after share completes
            viewModelScope.launch {
                kotlinx.coroutines.delay(60_000) // 1 minute
                CachingFileProvider.clearCache()
            }
        }
    }

    private fun getMimeFromFileName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    // ════════════════════════════════════════════════════════════════
    // ✅ NEW: TEXT SHARE HELPER — Used by Contact & Password fragments
    // ════════════════════════════════════════════════════════════════

    fun launchTextShare(text: String, title: String = "Share") {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (application as? NightLibraryApp)?.isIgnoringNextLock = true
            application.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "text share failed: ${e.message}", e)
            (application as? NightLibraryApp)?.isIgnoringNextLock = false
        }
    }

    // ════════════════════════════════════════════════════════════════
    // MEDIA — Delete Operations (unchanged)
    // ════════════════════════════════════════════════════════════════

    fun permanentDelete(media: MediaEntity) {
        viewModelScope.launch {
            mediaRepository.permanentlyDelete(media)
            _operationEvents.emit(OperationEvent.TaskCompleted)

            launch(Dispatchers.IO) {
                try {
                    SecureWipe.wipeVaultFolder(File(media.vaultFolder))
                    VaultFileManager(application).deleteFile(media.filePath)
                } catch (_: Exception) {}
            }
        }
    }

    fun deleteSelectedMedia() {
        val idsToDelete = selectedItems.value
        if (idsToDelete.isEmpty()) return

        val allMedia = mediaCompleted.value
        val toDelete = allMedia.filter { idsToDelete.contains(it.id) }
        if (toDelete.isEmpty()) return

        selectedItems.value = emptySet()

        viewModelScope.launch {
            try {
                toDelete.forEach { media ->
                    mediaRepository.delete(media)
                }
                _operationEvents.emit(OperationEvent.TaskCompleted)

                launch(Dispatchers.IO) {
                    toDelete.forEach { media ->
                        try {
                            SecureWipe.wipeVaultFolder(File(media.vaultFolder))
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Multi-delete failed: ${e.message}")
            }
        }
    }

    fun cancelDownload(media: MediaEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                workManager.cancelAllWorkByTag(media.fileName)

                val file = File(media.filePath ?: "")
                if (file.exists()) file.delete()

                mediaRepository.delete(media)
            } catch (e: Exception) {
                Log.e(TAG, "Cancel download failed: ${e.message}")
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // HELPERS (unchanged)
    // ════════════════════════════════════════════════════════════════

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}