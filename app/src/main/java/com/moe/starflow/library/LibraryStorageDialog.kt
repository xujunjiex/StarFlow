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
    private var targetSet = false
    private var targetTree: android.net.Uri? = null
    private var targetPath: String? = null
    private var targetError: String? = null
    private var targetKind = LibraryErrorKind.ACCESS
    private var recoveryDialog: android.app.AlertDialog? = null
    private var content: android.view.View? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) change(tree) else syncLocationChoice()
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        targetSet = savedInstanceState?.getBoolean("targetSet") ?: false
        targetTree = savedInstanceState?.getString("targetTree")?.let(android.net.Uri::parse)
        targetPath = savedInstanceState?.getString("targetPath")
        targetError = savedInstanceState?.getString("targetError")
        targetKind = savedInstanceState?.getString("targetKind")?.let(LibraryErrorKind::valueOf) ?: LibraryErrorKind.ACCESS
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_library_storage, null, false)
        content = view
        val session = LibrarySessions.current(requireContext())
        view.findViewById<TextView>(R.id.library_path).text = session.storage.displayPath
        syncLocationChoice()
        view.findViewById<View>(R.id.library_choose).setOnClickListener { picker.launch(null) }
        view.findViewById<View>(R.id.library_default).setOnClickListener { change(null) }
        view.findViewById<View>(R.id.library_close).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.library_advanced).setOnClickListener {
            view.findViewById<View>(R.id.library_rebuild).apply {
                visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        view.findViewById<View>(R.id.library_rebuild).setOnClickListener {
            confirmReinitialization(if (targetSet) targetTree else
                (LibrarySessions.current(requireContext()).storage as? SafLibraryStorage)?.tree)
        }
        renderProblem()
        // A pre-update choice may still live in app-specific storage; it remains reachable but must warn.
        val unsafeCurrent = (session.storage as? SafLibraryStorage)
            ?.let { LibraryLocationSafety.unsafeTreeReason(it.tree) } != null
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
                    unsafeCurrent -> {
                        visibility = View.VISIBLE
                        text = getString(R.string.library_current_unsafe)
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
            change(if (targetSet) targetTree else (LibrarySessions.current(requireContext()).storage as? SafLibraryStorage)?.tree)
        }
        return Dialog(requireContext()).also { dialog ->
            dialog.setContentView(view)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.setCanceledOnTouchOutside(true)
        }
    }
    override fun onDestroy() {
        recoveryDialog?.dismiss(); recoveryDialog = null
        content = null
        super.onDestroy()
        android.os.Handler(android.os.Looper.getMainLooper()).post { LibrarySessions.changes.value += 1 }
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
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("targetSet", targetSet)
        outState.putString("targetTree", targetTree?.toString())
        outState.putString("targetPath", targetPath)
        outState.putString("targetError", targetError)
        outState.putString("targetKind", targetKind.name)
    }
    private fun renderProblem() {
        val session = LibrarySessions.current(requireContext())
        val detail = targetError ?: session.saveState.value.error
        content?.findViewById<TextView>(R.id.library_problem)?.apply {
            visibility = if (detail == null) View.GONE else View.VISIBLE
            text = detail?.let { libraryRecoveryDetails(requireContext(), it,
                if (targetSet) targetKind else session.saveState.value.errorKind) }
            setOnClickListener { if (detail != null) showFailureDetails(text.toString()) }
        }
        content?.findViewById<TextView>(R.id.library_rebuild)?.text = getString(R.string.library_rebuild_target,
            targetPath ?: session.storage.displayPath)
        content?.findViewById<View>(R.id.library_retry)?.visibility =
            if (detail != null || session.saveState.value.pending > 0) View.VISIBLE else View.GONE
    }
    private fun showFailureDetails(details: String) {
        recoveryDialog?.dismiss()
        recoveryDialog = com.moe.starflow.utils.AutoScanFailureDialog.builder(requireContext(),
            getString(R.string.library_report_title), details)
            .setPositiveButton(R.string.confirm, null)
            .setNeutralButton(R.string.library_recovery_choose) { _, _ -> picker.launch(targetTree) }
            .create().also { it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background) }
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
    internal fun change(tree: android.net.Uri?, reinitialize: Boolean = false) {
        val controls = listOf(R.id.library_choose, R.id.library_default, R.id.library_retry, R.id.library_close, R.id.library_advanced, R.id.library_rebuild)
            .mapNotNull { content?.findViewById<View>(it) }
        val oldSpace = content?.findViewById<TextView>(R.id.library_space)?.text
        controls.forEach { it.isEnabled = false }
        content?.findViewById<TextView>(R.id.library_space)?.setText(R.string.library_switch_wait)
        isCancelable = false
        lifecycleScope.launch {
            try { LibrarySessions.select(requireContext(), tree, reinitialize); dismiss() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: LibraryLocationUnsafeException) {
                // Use the activity context so the dialog follows the in-app language, not the app context.
                if (isAdded) showUnsafeLocation(getString(R.string.library_location_unsafe_message))
            }
            catch (e: Exception) {
                if (isAdded) {
                    syncLocationChoice()
                    targetSet = true
                    targetTree = tree
                    targetPath = tree?.let { SafLibraryStorage(requireContext(), it).displayPath }
                        ?: LibrarySessions.defaultDisplayPath(requireContext())
                    targetKind = libraryErrorKind(e)
                    targetError = targetPath + "\n" + libraryFailureDetail(e)
                    renderProblem()
                    showFailureDetails(libraryRecoveryDetails(requireContext(), targetError!!, targetKind))
                }
            }
            finally {
                controls.forEach { it.isEnabled = true }
                content?.findViewById<TextView>(R.id.library_space)?.text = oldSpace
                isCancelable = true
            }
        }
    }

    /** The system deletes app-specific roots on uninstall; offer a normal re-pick instead of "覆盖重建". */
    private fun showUnsafeLocation(message: String) {
        recoveryDialog?.dismiss()
        recoveryDialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.library_location_unsafe_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.library_recovery_choose) { _, _ -> picker.launch(null) }
            .create().also {
                it.show()
                it.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            }
    }

    private fun confirmReinitialization(tree: android.net.Uri?) {
        val storage = tree?.let { SafLibraryStorage(requireContext(), it) }
            ?: FileLibraryStorage(java.io.File(LibrarySessions.defaultDisplayPath(requireContext())))
        val path = storage.displayPath
        recoveryDialog?.dismiss()
        recoveryDialog = android.app.AlertDialog.Builder(requireContext()).setTitle(R.string.library_reinitialize_title)
            .setMessage(getString(R.string.library_rebuild_confirmation, path))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.library_recovery_choose) { _, _ -> picker.launch(tree) }
            .setPositiveButton(R.string.library_reinitialize_action) { _, _ -> change(tree, reinitialize = true) }
            .create().also {
                it.show()
                it.findViewById<TextView>(android.R.id.message)?.apply {
                    setTextIsSelectable(true)
                }
                it.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            }
    }
}
