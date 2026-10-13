package com.moe.starflow.library

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.format.Formatter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.utils.AppNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryStorageDialog : DialogFragment() {
    private var content: android.view.View? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) change(tree)
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_library_storage, null, false)
        content = view
        val session = LibrarySessions.current(requireContext())
        view.findViewById<TextView>(R.id.library_path).text = session.storage.displayPath
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { session.storage.availableBytes() }
            view.findViewById<TextView>(R.id.library_space).text = getString(R.string.library_space,
                bytes?.let { Formatter.formatFileSize(requireContext(), it) } ?: getString(R.string.library_space_unknown))
        }
        view.findViewById<TextView>(R.id.library_choose).setOnClickListener { picker.launch(null) }
        view.findViewById<TextView>(R.id.library_default).setOnClickListener { change(null) }
        view.findViewById<TextView>(R.id.library_retry).setOnClickListener {
            lifecycleScope.launch {
                try { LibrarySessions.prepare(requireContext()); dismiss() }
                catch (e: Exception) { AppNotice.show(requireContext(), e.message ?: getString(R.string.library_unsaved)) }
            }
        }
        return AlertDialog.Builder(requireContext()).setTitle(R.string.library_storage_title).setView(view)
            .setNegativeButton(R.string.cancel, null).create().also { dialog ->
                dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
            }
    }
    private fun change(tree: android.net.Uri?) {
        val controls = listOf(R.id.library_choose, R.id.library_default, R.id.library_retry).mapNotNull { content?.findViewById<TextView>(it) }
        val oldSpace = content?.findViewById<TextView>(R.id.library_space)?.text
        controls.forEach { it.isEnabled = false }
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        content?.findViewById<TextView>(R.id.library_space)?.setText(R.string.library_switch_wait)
        isCancelable = false
        lifecycleScope.launch {
            try { LibrarySessions.select(requireContext(), tree); dismiss() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (isAdded) AppNotice.show(requireContext(), e.message ?: getString(R.string.library_unsaved)) }
            finally {
                controls.forEach { it.isEnabled = true }
                content?.findViewById<TextView>(R.id.library_space)?.text = oldSpace
                (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
                isCancelable = true
            }
        }
    }
}
