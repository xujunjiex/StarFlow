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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryStorageDialog : DialogFragment() {
    companion object {
        private const val TAG = "library-storage"
        private const val ERROR = "recovery_error"
        private val offered = mutableMapOf<String, MutableSet<String>>()
        private var recoveryOpen = false
        fun offerRecovery(fragment: androidx.fragment.app.Fragment, error: String, automatic: Boolean = true): Boolean {
            if (!fragment.isAdded || !fragment.isResumed || fragment.parentFragmentManager.isStateSaved) return false
            val manager = fragment.parentFragmentManager
            val location = LibrarySessions.current(fragment.requireContext()).locationId
            val seen = offered.getOrPut(location) { mutableSetOf() }
            if (recoveryOpen || manager.findFragmentByTag(TAG) != null) return error in seen
            if (automatic && error in seen) return true
            seen.add(error)
            recoveryOpen = true
            fragment.requireView().post {
                if (!fragment.isAdded || !fragment.isResumed || manager.isStateSaved) { recoveryOpen = false; seen.remove(error) }
                else if (manager.findFragmentByTag(TAG) == null)
                    LibraryStorageDialog().apply { arguments = Bundle().apply { putString(ERROR, error) } }.show(manager, TAG)
            }
            return true
        }
        internal fun recoveryCompleted(location: String) { offered.remove(location) }
    }
    private var recoveryShown = false
    private var recoveryDialog: android.app.AlertDialog? = null
    private var content: android.view.View? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) change(tree) else syncLocationChoice()
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        recoveryOpen = true
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_library_storage, null, false)
        content = view
        val session = LibrarySessions.current(requireContext())
        view.findViewById<TextView>(R.id.library_path).text = session.storage.displayPath
        syncLocationChoice()
        view.findViewById<View>(R.id.library_choose).setOnClickListener { picker.launch(null) }
        view.findViewById<View>(R.id.library_default).setOnClickListener { change(null) }
        view.findViewById<View>(R.id.library_close).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.library_rebuild).setOnClickListener {
            confirmReinitialization((LibrarySessions.current(requireContext()).storage as? SafLibraryStorage)?.tree,
                session.saveState.value.error ?: getString(R.string.library_rebuild_manual))
        }
        view.findViewById<TextView>(R.id.library_retry).visibility =
            if (session.saveState.value.error != null || session.saveState.value.pending > 0) View.VISIBLE else View.GONE
        lifecycleScope.launch {
            val (bytes, blocked, rootMissing) = withContext(Dispatchers.IO) {
                val saf = session.storage as? SafLibraryStorage
                Triple(
                    session.storage.availableBytes(),
                    LibraryImportAccess.blockedDirectories(session),
                    saf != null && !saf.rootAvailable(),
                )
            }
            if (content !== view || !isAdded) return@launch
            view.findViewById<TextView>(R.id.library_space).text = getString(R.string.library_space,
                bytes?.let { Formatter.formatFileSize(requireContext(), it) } ?: getString(R.string.library_space_unknown))
            view.findViewById<TextView>(R.id.library_write_warning).apply {
                when {
                    // 目录被改名/移动/删除：直接说清楚，别等用户去点「覆盖并重建」才看到提供器的原话
                    rootMissing -> {
                        visibility = View.VISIBLE
                        text = getString(R.string.library_root_missing)
                    }
                    blocked.isNotEmpty() -> {
                        visibility = View.VISIBLE
                        text = getString(R.string.library_import_readonly, blocked.joinToString { it.name })
                    }
                    else -> visibility = View.GONE
                }
            }
        }
        view.findViewById<TextView>(R.id.library_retry).setOnClickListener {
            change((LibrarySessions.current(requireContext()).storage as? SafLibraryStorage)?.tree)
        }
        return Dialog(requireContext()).also { dialog ->
            dialog.setContentView(view)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.setCanceledOnTouchOutside(true)
        }
    }
    override fun onDestroy() {
        recoveryDialog?.dismiss(); recoveryDialog = null
        recoveryOpen = false
        content = null
        super.onDestroy()
        LibrarySessions.changes.value += 1
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
        if (!recoveryShown) {
            recoveryShown = true
            val session = LibrarySessions.current(requireContext())
            (arguments?.getString(ERROR) ?: session.saveState.value.error)?.let {
                confirmReinitialization((session.storage as? SafLibraryStorage)?.tree, it)
            }
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
    private fun change(tree: android.net.Uri?, reinitialize: Boolean = false) {
        val controls = listOf(R.id.library_choose, R.id.library_default, R.id.library_retry, R.id.library_close, R.id.library_rebuild)
            .mapNotNull { content?.findViewById<View>(it) }
        val oldSpace = content?.findViewById<TextView>(R.id.library_space)?.text
        controls.forEach { it.isEnabled = false }
        content?.findViewById<TextView>(R.id.library_space)?.setText(R.string.library_switch_wait)
        isCancelable = false
        lifecycleScope.launch {
            try { LibrarySessions.select(requireContext(), tree, reinitialize); dismiss() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (isAdded) {
                    syncLocationChoice()
                    confirmReinitialization(tree, e.message ?: getString(R.string.library_unsaved))
                }
            }
            finally {
                controls.forEach { it.isEnabled = true }
                content?.findViewById<TextView>(R.id.library_space)?.text = oldSpace
                isCancelable = true
            }
        }
    }

    private fun confirmReinitialization(tree: android.net.Uri?, error: String) {
        val storage = tree?.let { SafLibraryStorage(requireContext(), it) }
            ?: FileLibraryStorage(java.io.File(LibrarySessions.defaultDisplayPath(requireContext())))
        val path = storage.displayPath
        offered.getOrPut(storage.locationId) { mutableSetOf() }.add(error)
        recoveryDialog?.dismiss()
        recoveryDialog = android.app.AlertDialog.Builder(requireContext()).setTitle(R.string.library_reinitialize_title)
            .setMessage(getString(R.string.library_reinitialize_message, path, error))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.library_recovery_choose) { _, _ -> picker.launch(tree) }
            .setPositiveButton(R.string.library_reinitialize_action) { _, _ -> change(tree, reinitialize = true) }
            .create().also {
                it.show()
                it.findViewById<TextView>(android.R.id.message)?.apply {
                    setTextIsSelectable(true)
                    val detail = error
                    val start = text.toString().indexOf(detail)
                    if (detail.isNotEmpty() && start >= 0) text = android.text.SpannableString(text).apply {
                        setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.red)),
                            start, start + detail.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                it.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            }
    }
}
