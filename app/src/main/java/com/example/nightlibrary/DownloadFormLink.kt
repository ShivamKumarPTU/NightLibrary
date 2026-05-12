package com.example.nightlibrary

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.nightlibrary.database.VaultDatabase
import com.example.nightlibrary.databinding.FragmentDownloadFormLinkBinding
import com.example.nightlibrary.manager.DownloadQueueManager
import com.example.nightlibrary.preferences.SecurityPreferenceManager  // 🔇 NEW import
import com.example.nightlibrary.repository.ContactRepository
import com.example.nightlibrary.repository.MediaRepository
import com.example.nightlibrary.repository.PasswordRepository
import com.example.nightlibrary.viewmodel.VaultViewModel
import com.example.nightlibrary.viewmodel.VaultViewModelFactory
import com.example.nightlibrary.worker.ExtractResult
import com.example.nightlibrary.worker.MediaExtractor
import com.example.nightlibrary.worker.VideoInfo
import androidx.work.WorkManager
import com.example.nightlibrary.core.security.PasswordCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
// ADD these imports at the top:
import com.example.nightlibrary.model.FormatInfo
import com.example.nightlibrary.util.DeviceCapabilityUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
class DownloadFormLink : Fragment() {
    // Volatile flag: animation loop exits instantly when real % arrives
    @Volatile private var realProgressReceived = false
    private var preWarmJob: Job? = null
    companion object {
        private const val TAG = "DownloadFormLink"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_INCOGNITO = "incognito"
        private const val KEY_SILENT = "silent"             // 🔇 NEW key

        private val ALLOWED_SCHEMES = setOf("http", "https")

        private val BLOCKED_PATTERNS = listOf(
            "javascript:", "data:", "file://", "content://",
            "blob:", "about:", "chrome://", "intent://"
        )

        private const val MAX_URL_LENGTH = 2048
    }

    private var _binding: FragmentDownloadFormLinkBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: VaultViewModel

    private var extractedInfo: VideoInfo? = null
    private var lastUrl: String? = null
    private var extractionJob: Job? = null
    // Animates the loader percentage while waiting for WebView/scrape extraction.
    // Cancelled as soon as real progress arrives (YtDlp) or the job finishes.
    private var progressAnimJob: Job? = null

    // 🔇 NEW: Lazy reference to preference manager
    private val securityPrefs by lazy {
        SecurityPreferenceManager(requireContext())
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadFormLinkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val database = VaultDatabase.getDatabase(requireContext())
        val contactRepository = ContactRepository(database.contactDao())
        val passwordRepository = PasswordRepository(database.passwordDao(), PasswordCryptoManager())
        val mediaRepository = MediaRepository(database.mediaDao())
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        val factory = VaultViewModelFactory(
            application = requireActivity().application,
            contactRepository,
            passwordRepository,
            mediaRepository,
            workManager
        )
        viewModel = ViewModelProvider(requireActivity(), factory)[VaultViewModel::class.java]

        savedInstanceState?.let {
            lastUrl = it.getString(KEY_LAST_URL)
            binding.switchIncognito.isChecked = it.getBoolean(KEY_INCOGNITO, false)
            binding.switchSilent.isChecked = it.getBoolean(KEY_SILENT, false) // 🔇 NEW
            lastUrl?.let { url -> binding.editLink.setText(url) }
        }

        setupToolbar()
        setupUrlInput()
        setupButtons()
        setupQualitySelector()
        setupSilentMode()       // 🔇 NEW

        binding.cardMediaInfo.visibility = View.GONE
        binding.labelQuality.visibility = View.VISIBLE
        binding.cardQualitySelector.visibility = View.VISIBLE
        updateFetchButtonState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            outState.putString(KEY_LAST_URL, binding.editLink.text.toString())
            outState.putBoolean(KEY_INCOGNITO, binding.switchIncognito.isChecked)
            outState.putBoolean(KEY_SILENT, binding.switchSilent.isChecked) // 🔇 NEW
        }
    }

    override fun onDestroyView() {
        extractionJob?.cancel()
        preWarmJob?.cancel()
        progressAnimJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    // ─── Setup ────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbarAddContent.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 🔇 SILENT MODE — Wire up switch_silent to SecurityPreferenceManager
    // ═══════════════════════════════════════════════════════════════════

    private fun setupSilentMode() {
        // Initialize toggle from persisted preference
        binding.switchSilent.isChecked = securityPrefs.isSilentMode

        // Persist changes immediately so workers & resumed downloads pick it up
        binding.switchSilent.setOnCheckedChangeListener { _, isChecked ->
            securityPrefs.isSilentMode = isChecked
        }

        // Private imports default to minimal notifications.
        binding.switchIncognito.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !binding.switchSilent.isChecked) {
                binding.switchSilent.isChecked = true
                toast("Minimal notifications enabled for private import")
            }
        }
    }

    private fun setupUrlInput() {
        binding.editLink.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                updateFetchButtonState()

                if (text != lastUrl) {
                    extractedInfo = null
                    updateQualityLabel(null)
                }

                if (text.length > 10 && text != lastUrl && isValidUrl(text)) {
                    binding.buttonFetchMedia.text = "Fetch Media"
                    // Pre-warm: silently start extraction while user reads the URL
                    preWarmJob?.cancel()
                    preWarmJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(600)
                        if (isActive && text == binding.editLink.text.toString().trim()) {
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                try { MediaExtractor.getVideoInfo(text) }
                                catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        })
    }

    private fun setupButtons() {
        binding.buttonFetchMedia.setOnClickListener {
            val url = getAndValidateUrl() ?: return@setOnClickListener
            performExtraction(url, toastOnSuccess = true)
        }

        binding.layoutLoader.btnCancelLoading.setOnClickListener {
            extractionJob?.cancel()
            progressAnimJob?.cancel()
            showLoading(false)
            toast("Extraction cancelled")
        }
    }

    private fun setupQualitySelector() {
        binding.cardQualitySelector.setOnClickListener {
            val url = getAndValidateUrl() ?: return@setOnClickListener

            if (extractedInfo != null && lastUrl == url) {
                loadQualitiesAndShowSheet()
            } else {
                performExtraction(url, toastOnSuccess = false)
            }
        }
    }

    // ─── URL Validation ───────────────────────────────────────────────────

    private fun getAndValidateUrl(): String? {
        val raw = binding.editLink.text.toString().trim()

        if (raw.isEmpty()) {
            showInputError("Paste a video link to get started")
            return null
        }

        if (raw.length > MAX_URL_LENGTH) {
            showInputError("URL is too long (max $MAX_URL_LENGTH characters)")
            return null
        }

        val lower = raw.lowercase()
        for (pattern in BLOCKED_PATTERNS) {
            if (lower.startsWith(pattern) || lower.contains(pattern)) {
                showInputError("Invalid URL scheme")
                return null
            }
        }

        val uri = try {
            android.net.Uri.parse(raw)
        } catch (e: Exception) {
            showInputError("Could not parse this URL")
            return null
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_SCHEMES) {
            showInputError("Only HTTP/HTTPS links are supported")
            return null
        }

        val host = uri.host
        if (host.isNullOrBlank() || !host.contains(".")) {
            showInputError("Invalid URL — no valid domain found")
            return null
        }

        if (isPrivateHost(host)) {
            showInputError("Local/private URLs are not allowed")
            return null
        }

        return raw
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            scheme in ALLOWED_SCHEMES && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "localhost" ||
                lower.startsWith("127.") ||
                lower.startsWith("10.") ||
                lower.startsWith("192.168.") ||
                lower.startsWith("172.16.") ||
                lower.startsWith("172.17.") ||
                lower.startsWith("172.18.") ||
                lower.startsWith("172.19.") ||
                lower.startsWith("172.2") ||
                lower.startsWith("172.3") ||
                lower == "0.0.0.0" ||
                lower.endsWith(".local") ||
                lower.endsWith(".internal")
    }

    private fun showInputError(message: String) {
        binding.layoutLink.error = message
        binding.layoutLink.isErrorEnabled = true
        binding.editLink.postDelayed({
            if (_binding != null) {
                binding.layoutLink.isErrorEnabled = false
            }
        }, 3000)
    }

    private fun performExtraction(url: String, toastOnSuccess: Boolean) {
        extractionJob?.cancel()
        progressAnimJob?.cancel()
        preWarmJob?.cancel()
        realProgressReceived = false

        hideKeyboard()
        lastUrl = url
        showLoading(true, "Connecting…")

        val startTime = System.currentTimeMillis()

        // ✅ FIXED: Smooth time-based animation — never gets stuck
        progressAnimJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            binding.layoutLoader.loaderProgress.visibility = View.VISIBLE
            var displayed = 0

            while (isActive && displayed < 95) {
                delay(120)
                if (!isActive) break

                val elapsed = System.currentTimeMillis() - startTime

                // Smooth easing curve — fast start, gradual slow-down
                val target = when {
                    elapsed < 800   -> (elapsed * 18 / 800).toInt()              // 0→18% in 0.8s
                    elapsed < 2500  -> 18 + ((elapsed - 800) * 27 / 1700).toInt()  // 18→45% in 1.7s
                    elapsed < 5000  -> 45 + ((elapsed - 2500) * 20 / 2500).toInt() // 45→65% in 2.5s
                    elapsed < 8000  -> 65 + ((elapsed - 5000) * 15 / 3000).toInt() // 65→80% in 3s
                    elapsed < 15000 -> 80 + ((elapsed - 8000) * 10 / 7000).toInt() // 80→90% in 7s
                    else            -> 90 + ((elapsed - 15000) * 4 / 15000).toInt() // 90→94% crawl
                }.coerceIn(0, 94)

                if (target > displayed) {
                    displayed = target
                    if (_binding != null) {
                        binding.layoutLoader.loaderProgress.text = "$displayed%"

                        // ✅ Phase-based status messages
                        binding.layoutLoader.loaderText.text = when {
                            displayed < 15 -> "Connecting…"
                            displayed < 30 -> "Analyzing page…"
                            displayed < 50 -> "Extracting media info…"
                            displayed < 70 -> "Processing metadata…"
                            displayed < 85 -> "Almost ready…"
                            else -> "Finishing up…"
                        }
                    }
                }
            }
        }

        extractionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val info = MediaExtractor.getVideoInfo(url) { progress ->
                    // ✅ FIXED: Only take over when real progress is meaningfully ahead
                    // yt-dlp mapped progress arrives in 15-88 range
                    // Only transition at 80%+ to avoid killing smooth animation
                    if (progress >= 80) {
                        realProgressReceived = true
                        progressAnimJob?.cancel()
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (_binding != null) {
                                binding.layoutLoader.loaderProgress.text = "${progress.toInt()}%"
                                binding.layoutLoader.loaderText.text = "Almost done…"
                            }
                        }
                    }
                }

                progressAnimJob?.cancel()
                if (!isAdded || _binding == null) return@launch

                // ✅ Smooth completion animation
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        binding.layoutLoader.loaderProgress.text = "100%"
                        binding.layoutLoader.loaderText.text =
                            if (info != null) "✓ Found!" else "Not found"
                    }
                }
                delay(400)

                if (!isAdded || _binding == null) return@launch
                showLoading(false)

                if (info == null) {
                    showInputError("Could not find a video at this link")
                    return@launch
                }

                extractedInfo = info
                updateQualityLabel(info)

                if (toastOnSuccess) {
                    toast("✓ Video found! Tap 'Video Quality' to pick format")
                } else {
                    loadQualitiesAndShowSheet()
                }
            } catch (e: Exception) {
                progressAnimJob?.cancel()
                if (!isAdded || _binding == null) return@launch
                showLoading(false)
                showInputError("Analysis failed: ${e.message?.take(60) ?: "Unknown error"}")
            }
        }
    }
    // ─── Quality Selection ────────────────────────────────────────────────

    private fun loadQualitiesAndShowSheet() {
        val info = extractedInfo ?: return
        val url = lastUrl ?: info.pageUrl
        val incognito = binding.switchIncognito.isChecked

        progressAnimJob?.cancel()
        realProgressReceived = false
        showLoading(true, "Fetching quality options…")

        val startTime = System.currentTimeMillis()

        // ✅ FIXED: Same smooth time-based animation
        progressAnimJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            binding.layoutLoader.loaderProgress.visibility = View.VISIBLE
            var displayed = 0

            while (isActive && displayed < 95) {
                delay(120)
                if (!isActive) break

                val elapsed = System.currentTimeMillis() - startTime

                val target = when {
                    elapsed < 500   -> (elapsed * 15 / 500).toInt()
                    elapsed < 2000  -> 15 + ((elapsed - 500) * 30 / 1500).toInt()
                    elapsed < 4000  -> 45 + ((elapsed - 2000) * 20 / 2000).toInt()
                    elapsed < 7000  -> 65 + ((elapsed - 4000) * 15 / 3000).toInt()
                    elapsed < 12000 -> 80 + ((elapsed - 7000) * 10 / 5000).toInt()
                    else            -> 90 + ((elapsed - 12000) * 4 / 10000).toInt()
                }.coerceIn(0, 94)

                if (target > displayed) {
                    displayed = target
                    if (_binding != null) {
                        binding.layoutLoader.loaderProgress.text = "$displayed%"

                        binding.layoutLoader.loaderText.text = when {
                            displayed < 20 -> "Fetching formats…"
                            displayed < 45 -> "Analyzing qualities…"
                            displayed < 70 -> "Processing format list…"
                            displayed < 85 -> "Almost ready…"
                            else -> "Finishing up…"
                        }
                    }
                }
            }
        }

        extractionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = MediaExtractor.getFormats(url) { progress ->
                    if (progress >= 80) {
                        realProgressReceived = true
                        progressAnimJob?.cancel()
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (_binding != null) {
                                binding.layoutLoader.loaderProgress.text = "${progress.toInt()}%"
                                binding.layoutLoader.loaderText.text = "Almost done…"
                            }
                        }
                    }
                }

                progressAnimJob?.cancel()
                if (!isAdded || _binding == null) return@launch

                // ✅ Completion animation
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        binding.layoutLoader.loaderProgress.text = "100%"
                        binding.layoutLoader.loaderText.text = "✓ Ready!"
                    }
                }
                delay(350)

                if (!isAdded || _binding == null) return@launch
                showLoading(false)

                when (result) {
                    is ExtractResult.Success -> showQualitySheet(result.json, info, incognito)
                    is ExtractResult.Error -> warnAndEnqueue(info, incognito)
                }
            } catch (e: Exception) {
                progressAnimJob?.cancel()
                if (!isAdded || _binding == null) return@launch
                showLoading(false)
                warnAndEnqueue(info, incognito)
            }
        }
    }

    private fun showQualitySheet(json: String, info: VideoInfo, incognito: Boolean) {
        val sheet = VideoQualityBottomSheet.newInstance(json)
        sheet.setOnQualitySelectedListener { selectedUrl, _, formatId, headers ->
            enqueueAndNavigate(selectedUrl, info, incognito, headers, formatId)
        }
        sheet.show(parentFragmentManager, "quality_sheet")
    }

    private fun warnAndEnqueue(info: VideoInfo, incognito: Boolean) {
        toast("Could not load quality list — downloading best available")
        enqueueAndNavigate(info.streamUrl, info, incognito, null, null)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 🔇🔒 ENQUEUE — Now passes both incognito AND silent flags
    // ═══════════════════════════════════════════════════════════════════

    private fun enqueueAndNavigate(
        streamUrl: String,
        info: VideoInfo,
        incognito: Boolean,
        headers: Map<String, String>?,
        formatId: String?
    ) {
        if (!isNetworkAvailable()) {
            toast("No network connection")
            return
        }

        if (isCellularOnly()) {
            toast("⚠ Downloading over cellular data")
        }

        // 🔇 NEW: Read silent state from the toggle
        val silent = binding.switchSilent.isChecked

        val timestamp = System.currentTimeMillis()
        val extension = if (info.fileType == "audio") ".mp3" else ".mp4"
        val prefix = if (info.fileType == "audio") "AUD_" else "VID_"
        val fileName = "$prefix$timestamp$extension"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                
                // Ensure pageUrl is never null or empty; fall back to streamUrl
                val pageUrl = if (!info.pageUrl.isNullOrEmpty()) info.pageUrl else streamUrl

                DownloadQueueManager(ctx).enqueueDownload(
                    downloadUrl = streamUrl,
                    fileName = fileName,
                    incognito = incognito,
                    silent = silent,
                    mimeType = if (info.fileType == "audio") "audio/mpeg" else "video/mp4",
                    headers = headers ?: info.headers.ifEmpty { null },
                    originalUrl = pageUrl,                            // ✅ CORRECT PASS
                    formatId = formatId ?: info.formatId,
                    useYtDlp = info.useYtDlp,
                    isHls = info.isHls,
                    duration = info.duration,
                    fileType = info.fileType,
                    streamUrl = if (streamUrl != pageUrl) streamUrl else null   // ← ADD THIS
                )

                viewModel.notifyDownloadStarted()

                if (!isAdded || _binding == null) return@launch

                // 🔒 NEW: Incognito-aware confirmation
                val message = when {
                    incognito -> "Private import started"
                    silent    -> "Download started with minimal notifications"
                    else      -> "✓ Added to vault queue"
                }
                toast(message)

                findNavController().navigate(
                    R.id.secretVaultFragment,
                    Bundle().apply { putInt("start_tab", 0) },
                    NavOptions.Builder()
                        .setPopUpTo(R.id.secretVaultFragment, true)
                        .build()
                )
            } catch (e: Exception) {
                if (!isAdded || _binding == null) return@launch
                toast("Failed to queue download: ${e.message?.take(50)}")
            }
        }
    }

    // ─── Network Checks ───────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isCellularOnly(): Boolean {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // ─── UI Helpers ───────────────────────────────────────────────────────

    private fun updateFetchButtonState() {
        val hasText = binding.editLink.text.toString().trim().isNotEmpty()
        binding.buttonFetchMedia.isEnabled = hasText
        binding.buttonFetchMedia.alpha = if (hasText) 1.0f else 0.5f
    }

    private fun updateQualityLabel(info: VideoInfo?) {
        if (_binding == null) return
        if (info != null) {
            binding.textSelectedQuality.text = when {
                info.isHls -> "HLS Stream detected — tap to select"
                info.useYtDlp -> "Multiple formats available — tap to select"
                else -> "Direct stream — tap to select"
            }
        } else {
            binding.textSelectedQuality.text = "Tap to select quality"
        }
    }

    private fun showLoading(show: Boolean, message: String = "") {
        if (_binding == null) return
        binding.layoutLoader.overlayLoader.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            if (message.isNotEmpty()) {
                binding.layoutLoader.loaderText.text = message
            }
            binding.layoutLoader.loaderProgress.visibility = View.VISIBLE
        } else {
            binding.layoutLoader.loaderProgress.visibility = View.GONE
        }
        binding.progressBar.visibility = View.GONE
    }

    private fun toast(msg: String) {
        if (isAdded) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun hideKeyboard() {
        val v = activity?.currentFocus ?: return
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }
}
