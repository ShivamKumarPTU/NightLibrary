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
import com.example.nightlibrary.core.security.VaultFileManager
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

    // ✅ FIX: Use SharingStarted.Eagerly for all in-progress flows.
    //
    // WHY: WhileSubscribed(5_000) stops the upstream Room query when the
    // fragment temporarily loses its collector (e.g. during navigation to/from
    // the vault, the In-Progress tab scrolling offscreen, or any brief UI
    // re-composition). During that 5-second grace window the DB can update
    // progress many times but the StateFlow never sees those emissions.
    // When the collector re-subscribes it gets a stale snapshot and shows
    // "frozen" progress bars.
    //
    // Eagerly keeps the Room query alive for the entire ViewModel lifetime
    // (which is tied to the Activity, not individual fragments). The cost is
    // one persistent DB listener while the app is open — negligible compared
    // to missing dozens of progress updates per second during an active download.

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
    // MEDIA SHARE OPERATIONS (unchanged)
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

        val task = ShareTask(
            id = taskId,
            mediaIds = mediaIds,
            fileNames = fileNames,
            totalFiles = toShare.size,
            status = "Preparing ${toShare.size} file(s)…"
        )

        _activeShareTasks.update { current -> current + task }
        clearSelection()

        viewModelScope.launch {
            _operationEvents.emit(OperationEvent.ShareStarted(taskId))
        }

        val job = viewModelScope.launch(Dispatchers.IO) {
            val tempFiles = mutableListOf<File>()
            try {
                //val tempFiles = mutableListOf<File>()

                // ✅ FIX: Decrypt all files in parallel instead of sequentially.
                // For N files each taking ~T seconds, total time drops from N×T → T.
                val perFilePct = 100.0 / toShare.size
                val fileProgressArray = IntArray(toShare.size) { 0 }

                val deferred = toShare.mapIndexed { index, media ->
                    async {
                        val vaultFileManager = VaultFileManager(application)
                        val temp = vaultFileManager.decryptToTempFile(
                            File(media.vaultFolder),
                            media.mimeType.ifEmpty { "application/octet-stream" },
                            media.id
                        ) { chunkProgress ->
                            fileProgressArray[index] = chunkProgress
                            // Overall = average progress across all files
                            val overall = (fileProgressArray.sum() * perFilePct / 100.0)
                                .toInt().coerceIn(0, 99)
                            updateShareTask(taskId) { t ->
                                t.copy(
                                    currentFileIndex = index,
                                    currentFileProgress = chunkProgress,
                                    overallProgress = overall,
                                    status = "Decrypting ${media.fileName.take(20)}…"
                                )
                            }
                        }

                        val safeName = media.fileName
                            .replace(Regex("[^a-zA-Z0-9._ -]"), "_")
                            .trim().take(100)
                        val named = File(temp.parent, safeName)
                        if (named.exists()) named.delete()
                        if (temp.renameTo(named)) named else temp
                    }
                }

                // Check for cancellation before awaiting
                val currentTask = _activeShareTasks.value.find { it.id == taskId }
                if (currentTask?.isCancelled == true || !isActive) {
                    deferred.forEach { it.cancel() }
                    return@launch
                }

                val shareFiles = deferred.awaitAll()
                tempFiles.addAll(shareFiles)

                val uris = ArrayList<Uri>()
                shareFiles.forEach { shareFile ->
                    if (shareFile.exists() && shareFile.length() > 0) {
                        val uri = FileProvider.getUriForFile(
                            application,
                            "${application.packageName}.fileprovider",
                            shareFile
                        )
                        uris.add(uri)
                    }
                }

                if (uris.isEmpty()) {
                    updateShareTask(taskId) { t ->
                        t.copy(
                            error = "No files could be decrypted",
                            isCompleted = true
                        )
                    }
                    return@launch
                }

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
                withContext(Dispatchers.Main) {
                    launchShareIntent(uris, toShare.size)
                }

                withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.delay(3000)
                    removeShareTask(taskId)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // ✅ FIX: delay() inside catch(CancellationException) re-throws immediately
                // because the job is already cancelled — removeShareTask was never reached.
                // withContext(NonCancellable) lets cleanup run even after cancellation.
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

    private fun launchShareIntent(uris: ArrayList<Uri>, fileCount: Int) {
        try {
            val shareIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            val chooser = Intent.createChooser(shareIntent, "Share $fileCount file(s)")
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

            // ✅ FIX: Prevent auth trigger when user returns from the share target
            // (Gmail, WhatsApp, etc.). Must be set BEFORE startActivity() because
            // the system calls MainActivity.onStop() synchronously on the next frame.
            (application as? NightLibraryApp)?.isIgnoringNextLock = true

            application.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Share intent failed: ${e.message}", e)
            (application as? NightLibraryApp)?.isIgnoringNextLock = false
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
            Log.e(TAG, "Text share failed: ${e.message}", e)
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