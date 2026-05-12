package com.example.nightlibrary.securefileactivity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.example.nightlibrary.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class CameraChoiceBottomSheet : BottomSheetDialogFragment() {

    // ✅ FIX: Use companion object factory + callbacks set from parent
    //   Old: Lambdas in constructor → lost on config change → crash
    //   New: Callbacks set via setter → parent reattaches in onViewCreated

    private var videoLimitText: String = ""
    private var onPhotoSelected: (() -> Unit)? = null
    private var onVideoSelected: (() -> Unit)? = null

    companion object {
        private const val ARG_VIDEO_LIMIT = "video_limit"

        fun newInstance(
            videoLimitText: String,
            onPhoto: () -> Unit,
            onVideo: () -> Unit
        ): CameraChoiceBottomSheet {
            return CameraChoiceBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_VIDEO_LIMIT, videoLimitText)
                }
                this.onPhotoSelected = onPhoto
                this.onVideoSelected = onVideo
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoLimitText = arguments?.getString(ARG_VIDEO_LIMIT) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        dialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )


        return inflater.inflate(R.layout.dialog_camera_choice, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tvVideoLimit).text = videoLimitText

        view.findViewById<MaterialButton>(R.id.cardTakePhoto).setOnClickListener {
            dismiss()
            onPhotoSelected?.invoke()
        }

        view.findViewById<MaterialButton>(R.id.cardRecordVideo).setOnClickListener {
            dismiss()
            onVideoSelected?.invoke()
        }

        view.findViewById<MaterialButton>(R.id.button_cancel_camera).setOnClickListener {
            dismiss()
        }
    }
}