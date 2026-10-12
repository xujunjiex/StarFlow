package com.moe.starflow.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moe.starflow.R

/** Compact, scrollable report for automatic library scan failures. */
object AutoScanFailureDialog {

    fun builder(context: Context, title: String, details: String): MaterialAlertDialogBuilder {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_auto_scan_failure, null, false)
        view.findViewById<TextView>(R.id.auto_scan_dialog_title).text = title
        view.findViewById<TextView>(R.id.auto_scan_dialog_detail).text = details
        view.findViewById<android.view.View>(R.id.auto_scan_dialog_copy).setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(title, details))
            UiUtils.showToast(context, context.getString(R.string.import_auto_scan_copied))
        }
        return MaterialAlertDialogBuilder(context).setView(view)
    }
}
