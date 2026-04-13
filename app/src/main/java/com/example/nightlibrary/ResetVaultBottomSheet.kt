package com.example.nightlibrary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ResetVaultBottomSheet(
    private val onResetConfirmed: () -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_bottom_sheet_confirm_cancel,
            container,
            false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        val resetButton = view.findViewById<Button>(R.id.resetButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)

        resetButton.setOnClickListener {
            dismiss()
            onResetConfirmed.invoke()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }
    }
}