package com.moe.starflow.library

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.moe.starflow.R
import com.moe.starflow.utils.AutoScanFailureDialog

class LibraryReportDialog : DialogFragment() {
    companion object {
        private const val TAG = "library-report"
        fun show(fragment: Fragment, details: String): Boolean {
            if (!fragment.isAdded || !fragment.isResumed || fragment.parentFragmentManager.isStateSaved) return false
            val manager = fragment.parentFragmentManager
            if (manager.findFragmentByTag(TAG) != null || manager.findFragmentByTag("library-storage") != null) return false
            LibraryReportDialog().apply { arguments = Bundle().apply { putString("details", details) } }
                .showNow(manager, TAG)
            return true
        }
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = AutoScanFailureDialog.builder(
        requireContext(), getString(R.string.library_report_title), requireArguments().getString("details").orEmpty())
        .setPositiveButton(R.string.confirm, null)
        .setNeutralButton(R.string.library_storage_title) { _, _ ->
            LibraryStorageDialog().show(parentFragmentManager, "library-storage")
        }.create().also { dialog ->
            dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
        }
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        android.os.Handler(android.os.Looper.getMainLooper()).post { LibrarySessions.changes.value += 1 }
    }
}
