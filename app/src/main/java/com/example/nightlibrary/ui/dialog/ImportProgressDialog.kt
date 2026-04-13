package com.example.nightlibrary.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.nightlibrary.R
import com.google.android.material.button.MaterialButton

class ImportProgressDialog : DialogFragment() {

    companion object {
        private const val ARG_TOTAL_FILES = "total_files"

        fun newInstance(totalFiles: Int = 1): ImportProgressDialog {
            return ImportProgressDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TOTAL_FILES, totalFiles)
                }
            }
        }
    }

    private var tvTitle: TextView? = null
    private var tvFileCounter: TextView? = null
    private var tvCurrentFileName: TextView? = null
    private var tvCurrentStatus: TextView? = null
    private var progressCurrentFile: ProgressBar? = null
    private var tvCurrentPercent: TextView? = null
    private var dividerOverall: View? = null
    private var tvOverallLabel: TextView? = null
    private var progressOverall: ProgressBar? = null
    private var tvOverallPercent: TextView? = null
    private var btnCancel: MaterialButton? = null

    private var totalFiles = 1

    var onCancelClicked: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.FullScreenDialogTheme)
        isCancelable = false
        totalFiles = arguments?.getInt(ARG_TOTAL_FILES, 1) ?: 1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(
            R.layout.dialog_multi_import_progress,
            container,
            false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTitle = view.findViewById(R.id.tvImportTitle)
        tvFileCounter = view.findViewById(R.id.tvFileCounter)
        tvCurrentFileName = view.findViewById(R.id.tvCurrentFileName)
        tvCurrentStatus = view.findViewById(R.id.tvCurrentStatus)
        progressCurrentFile = view.findViewById(R.id.progressCurrentFile)
        tvCurrentPercent = view.findViewById(R.id.tvCurrentPercent)
        dividerOverall = view.findViewById(R.id.dividerOverall)
        tvOverallLabel = view.findViewById(R.id.tvOverallLabel)
        progressOverall = view.findViewById(R.id.progressOverall)
        tvOverallPercent = view.findViewById(R.id.tvOverallPercent)
        btnCancel = view.findViewById(R.id.btnCancelImport)

        btnCancel?.setOnClickListener {
            onCancelClicked?.invoke()
        }

        // For single file imports, hide overall section
        if (totalFiles <= 1) {
            dividerOverall?.visibility = View.GONE
            tvOverallLabel?.visibility = View.GONE
            progressOverall?.visibility = View.GONE
            tvOverallPercent?.visibility = View.GONE
            tvFileCounter?.visibility = View.GONE
        }
    }

    /**
     * Update for current file being processed.
     *
     * @param fileIndex     1-based index of current file
     * @param fileName      Name of current file being imported
     * @param status        Status text ("Generating thumbnail…", "Encrypting…")
     * @param filePercent   Progress of current file (0-100)
     * @param overallPercent Overall progress across all files (0-100)
     */
    fun updateBatchProgress(
        fileIndex: Int,
        fileName: String,
        status: String,
        filePercent: Int,
        overallPercent: Int
    ) {
        if (!isAdded) return

        if (totalFiles > 1) {
            tvFileCounter?.text = "File $fileIndex of $totalFiles"
        }

        tvCurrentFileName?.text = fileName
        tvCurrentStatus?.text = status
        progressCurrentFile?.progress = filePercent
        tvCurrentPercent?.text = "$filePercent%"

        if (totalFiles > 1) {
            progressOverall?.progress = overallPercent
            tvOverallPercent?.text = "$overallPercent%"
        }
    }

    /**
     * Simple single-file progress update (backward compatible).
     */
    fun updateProgress(message: String, percent: Int) {
        if (!isAdded) return
        tvCurrentStatus?.text = message
        progressCurrentFile?.progress = percent
        tvCurrentPercent?.text = "$percent%"
    }

    fun dismissProgress() {
        if (isAdded) {
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        tvTitle = null
        tvFileCounter = null
        tvCurrentFileName = null
        tvCurrentStatus = null
        progressCurrentFile = null
        tvCurrentPercent = null
        dividerOverall = null
        tvOverallLabel = null
        progressOverall = null
        tvOverallPercent = null
        btnCancel = null
        super.onDestroyView()
    }
}