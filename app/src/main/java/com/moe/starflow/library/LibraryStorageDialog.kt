package com.moe.starflow.library

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.ImageView
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
        if (tree != null) change(tree) else syncLocationChoice()
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_library_storage, null, false)
        content = view
        val session = LibrarySessions.current(requireContext())
        view.findViewById<TextView>(R.id.library_path).text = session.storage.displayPath
        syncLocationChoice()
        view.findViewById<View>(R.id.library_choose).setOnClickListener { picker.launch(null) }
        view.findViewById<View>(R.id.library_default).setOnClickListener { change(null) }
        view.findViewById<View>(R.id.library_close).setOnClickListener { dismiss() }
        view.findViewById<TextView>(R.id.library_retry).visibility =
            if (session.saveState.value.error != null || session.saveState.value.pending > 0) View.VISIBLE else View.GONE
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { session.storage.availableBytes() }
            view.findViewById<TextView>(R.id.library_space).text = getString(R.string.library_space,
                bytes?.let { Formatter.formatFileSize(requireContext(), it) } ?: getString(R.string.library_space_unknown))
        }
        view.findViewById<TextView>(R.id.library_retry).setOnClickListener {
            lifecycleScope.launch {
                try { LibrarySessions.prepare(requireContext()); dismiss() }
                catch (e: Exception) { AppNotice.show(requireContext(), e.message ?: getString(R.string.library_unsaved)) }
            }
        }
        return Dialog(requireContext()).also { dialog ->
            dialog.setContentView(view)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.setCanceledOnTouchOutside(true)
        }
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        val margin = (32 * metrics.density).toInt()
        val maxWidth = (460 * metrics.density).toInt()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((metrics.widthPixels - margin).coerceAtMost(maxWidth), WindowManager.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.42f }
        }
    }
    private fun syncLocationChoice() {
        val view = content ?: return
        val storage = LibrarySessions.current(requireContext()).storage
        val useCustom = storage !is FileLibraryStorage
        view.findViewById<TextView>(R.id.library_default_path).text = LibrarySessions.defaultDisplayPath(requireContext())
        view.findViewById<TextView>(R.id.library_custom_path).text =
            if (useCustom) storage.displayPath else getString(R.string.library_custom_folder_hint)
        view.findViewById<View>(R.id.library_default).setBackgroundResource(
            if (useCustom) R.drawable.bg_library_location_card else R.drawable.bg_library_location_selected)
        view.findViewById<View>(R.id.library_choose).setBackgroundResource(
            if (useCustom) R.drawable.bg_library_location_selected else R.drawable.bg_library_location_card)
        view.findViewById<ImageView>(R.id.library_default_selected).visibility = if (useCustom) View.GONE else View.VISIBLE
        view.findViewById<ImageView>(R.id.library_custom_selected).visibility = if (useCustom) View.VISIBLE else View.GONE
    }
    private fun change(tree: android.net.Uri?) {
        val controls = listOf(R.id.library_choose, R.id.library_default, R.id.library_retry, R.id.library_close)
            .mapNotNull { content?.findViewById<View>(it) }
        val oldSpace = content?.findViewById<TextView>(R.id.library_space)?.text
        controls.forEach { it.isEnabled = false }
        content?.findViewById<TextView>(R.id.library_space)?.setText(R.string.library_switch_wait)
        isCancelable = false
        lifecycleScope.launch {
            try { LibrarySessions.select(requireContext(), tree); dismiss() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (isAdded) {
                    syncLocationChoice()
                    AppNotice.show(requireContext(), e.message ?: getString(R.string.library_unsaved))
                }
            }
            finally {
                controls.forEach { it.isEnabled = true }
                content?.findViewById<TextView>(R.id.library_space)?.text = oldSpace
                isCancelable = true
            }
        }
    }
}
