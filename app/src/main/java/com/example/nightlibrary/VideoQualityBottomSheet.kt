package com.example.nightlibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nightlibrary.databinding.BottomSheetVideoQualityBinding
import com.example.nightlibrary.model.FormatInfo
import com.example.nightlibrary.util.DeviceCapabilityUtil
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONObject

/**
 * VideoQualityBottomSheet — FULL REWRITE
 *
 * Solves:
 *   Problem 1: Device capability warning banner
 *   Problem 2: Clear 🔊/🔇/🎵 audio indicators
 *   Problem 7: HLS audio detection via FormatInfo
 *   Feature A: Audio-only format support
 *   Feature C: 3-section UI (Muxed / Video-Only / Audio-Only)
 *
 * Configuration-change safe via newInstance() + setter pattern.
 */
class VideoQualityBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_JSON = "formats_json"

        fun newInstance(json: String): VideoQualityBottomSheet {
            return VideoQualityBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_JSON, json)
                }
            }
        }
    }

    private var _binding: BottomSheetVideoQualityBinding? = null
    private val binding get() = _binding!!

    /**
     * Callback delivers the selected FormatInfo to DownloadFormLink.
     * Single formatId — NO merge, user's exact choice.
     */
    private var onFormatSelected: ((FormatInfo) -> Unit)? = null

    /**
     * Legacy callback for backward compatibility with existing code.
     * Will be removed once DownloadFormLink is fully migrated.
     */
    private var onQualitySelected: ((url: String, quality: String, formatId: String?, headers: Map<String, String>?) -> Unit)? = null

    fun setOnFormatSelectedListener(listener: (FormatInfo) -> Unit) {
        onFormatSelected = listener
    }

    fun setOnQualitySelectedListener(
        listener: (url: String, quality: String, formatId: String?, headers: Map<String, String>?) -> Unit
    ) {
        onQualitySelected = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetVideoQualityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // ✅ FIX: Apply Security Flag to prevent screenshots of the format list
        dialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val json = arguments?.getString(ARG_JSON) ?: run {
            dismiss()
            return
        }

        val rootJson = try { JSONObject(json) } catch (_: Exception) {
            binding.labelNoFormats.visibility = View.VISIBLE
            binding.labelNoFormats.text = "Invalid format data"
            return
        }

        // Parse all formats using FormatInfo (Problem 2, 7, Feature A)
        val allFormats = FormatInfo.parseAll(rootJson)

        if (allFormats.isEmpty()) {
            binding.labelNoFormats.visibility = View.VISIBLE
            binding.labelNoFormats.text = "No downloadable formats found.\nTry another link or check if the video is available."
            binding.recyclerQualities.visibility = View.GONE
            return
        }

        // Problem 1: Device capability warning
        if (DeviceCapabilityUtil.shouldShowWarning(allFormats)) {
            binding.warningBanner.visibility = View.VISIBLE
            binding.tvWarningText.text = DeviceCapabilityUtil.getWarningText()
        } else {
            binding.warningBanner.visibility = View.GONE
        }

        // Feature C: Group by category for 3-section UI
        val grouped = FormatInfo.groupByCategory(allFormats)
        val sectionItems = buildSectionedList(grouped)

        binding.tvFormatCount.text = "${allFormats.size} formats available"

        // Setup RecyclerView
        val adapter = QualitySectionAdapter(sectionItems) { selectedFormat ->
            // Deliver via new FormatInfo callback
            onFormatSelected?.invoke(selectedFormat)

            // Also deliver via legacy callback for backward compat
            onQualitySelected?.invoke(
                selectedFormat.url,
                selectedFormat.displayLabel,
                selectedFormat.formatId,
                selectedFormat.headers.ifEmpty { null }
            )

            dismiss()
        }

        binding.recyclerQualities.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerQualities.adapter = adapter
        binding.recyclerQualities.itemAnimator = null // Prevent flicker on selection change
        binding.recyclerQualities.isNestedScrollingEnabled = true
    }

    /**
     * Build a flat list with section headers + format items for RecyclerView.
     */
    private fun buildSectionedList(
        grouped: LinkedHashMap<FormatInfo.Category, List<FormatInfo>>
    ): List<SectionItem> {
        val items = mutableListOf<SectionItem>()

        grouped.forEach { (category, formats) ->
            val headerTitle = when (category) {
                FormatInfo.Category.MUXED -> "📹 VIDEO + AUDIO (Ready to play)"
                FormatInfo.Category.VIDEO_ONLY -> "📹 VIDEO ONLY (No sound)"
                FormatInfo.Category.AUDIO_ONLY -> "🎵 AUDIO ONLY"
                FormatInfo.Category.UNKNOWN -> "❓ OTHER"
            }
            items.add(SectionItem.Header(headerTitle))
            formats.forEach { format ->
                items.add(SectionItem.Format(format))
            }
        }

        return items
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ═══════════════════════════════════════════════════════════════
    // SECTION ITEM MODEL
    // ═══════════════════════════════════════════════════════════════

    sealed class SectionItem {
        data class Header(val title: String) : SectionItem()
        data class Format(val info: FormatInfo) : SectionItem()
    }

    // ═══════════════════════════════════════════════════════════════
    // SECTIONED ADAPTER — Problem 1, 2, Feature A, C
    // ═══════════════════════════════════════════════════════════════

    class QualitySectionAdapter(
        private val items: List<SectionItem>,
        private val onSelect: (FormatInfo) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_FORMAT = 1
        }

        private var selectedPosition: Int = -1

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is SectionItem.Header -> TYPE_HEADER
                is SectionItem.Format -> TYPE_FORMAT
            }
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(
                    inflater.inflate(R.layout.item_quality_section_header, parent, false)
                )
                else -> FormatVH(
                    inflater.inflate(R.layout.item_quality_format, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SectionItem.Header -> (holder as HeaderVH).bind(item)
                is SectionItem.Format -> (holder as FormatVH).bind(item, position)
            }
        }

        inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvTitle: TextView = view.findViewById(R.id.tvSectionTitle)
            fun bind(item: SectionItem.Header) {
                tvTitle.text = item.title
            }
        }

        inner class FormatVH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvAudioIndicator: TextView = view.findViewById(R.id.tvAudioIndicator)
            private val tvFormatLabel: TextView = view.findViewById(R.id.tvFormatLabel)
            private val tvCodecInfo: TextView = view.findViewById(R.id.tvCodecInfo)
            private val tvFormatWarning: TextView = view.findViewById(R.id.tvFormatWarning)
            private val tvFileSize: TextView = view.findViewById(R.id.tvFileSize)
            private val radioSelect: RadioButton = view.findViewById(R.id.radioSelect)

            fun bind(item: SectionItem.Format, position: Int) {
                val f = item.info

                // Problem 2: Clear audio indicators
                tvAudioIndicator.text = f.audioIndicator
                tvFormatLabel.text = f.displayLabel
                tvCodecInfo.text = f.codecLabel
                tvFileSize.text = f.estimatedSizeLabel

                // Problem 1: Per-format device warning
                val warning = if (f.hasVideo && f.height > 0) {
                    DeviceCapabilityUtil.getFormatWarning(f.height)
                } else null

                if (warning != null) {
                    tvFormatWarning.visibility = View.VISIBLE
                    tvFormatWarning.text = warning
                } else {
                    tvFormatWarning.visibility = View.GONE
                }

                // Selection state
                radioSelect.isChecked = position == selectedPosition

                // Click handler — single selection
                itemView.setOnClickListener {
                    val oldPos = selectedPosition
                    selectedPosition = holder_position(position)

                    if (oldPos != -1) notifyItemChanged(oldPos)
                    notifyItemChanged(selectedPosition)

                    onSelect(f)
                }

                radioSelect.setOnClickListener {
                    itemView.performClick()
                }
            }

            private fun holder_position(fallback: Int): Int {
                val pos = bindingAdapterPosition
                return if (pos != RecyclerView.NO_POSITION) pos else fallback
            }
        }
    }
}