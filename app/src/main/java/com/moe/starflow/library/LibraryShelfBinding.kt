package com.moe.starflow.library

import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import com.moe.starflow.R
import com.moe.starflow.utils.AppNotice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import com.moe.starflow.mangaimport.data.ImportManager
import com.moe.starflow.mangaimport.data.ImportTask
import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.novel.data.NovelImportManager

object LibraryShelfBinding {
    private fun renderStatus(
        status: TextView,
        toolbarSpinner: ProgressBar,
        inlineScanStatus: TextView,
        message: CharSequence,
        visible: Boolean,
        busy: Boolean,
        showScanInline: Boolean,
        error: Boolean = false,
    ) {
        val row = status.parent as? ViewGroup
        row?.visibility = if (visible && !showScanInline) View.VISIBLE else View.GONE
        status.visibility = if (visible && !showScanInline) View.VISIBLE else View.GONE
        status.text = message
        status.setTextColor(ContextCompat.getColor(status.context, if (error) R.color.red else R.color.text_secondary))
        status.setOnClickListener { if (error) AppNotice.show(status.context, message.toString(), AppNotice.Style.ERROR) }
        toolbarSpinner.visibility = if (visible && busy) View.VISIBLE else View.GONE
        inlineScanStatus.text = message
        inlineScanStatus.visibility = if (showScanInline) View.VISIBLE else View.GONE
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(fragment: Fragment, kind: String, status: TextView,
        toolbarSpinner: ProgressBar, inlineScanStatus: TextView, toolbarCancel: View,
        refresh: () -> Unit, failures: (List<String>) -> Unit, rootChanged: () -> Unit = {}, isActive: () -> Boolean = { true },
        cancelImport: (Long) -> Unit = {}) {
        val scope = fragment.viewLifecycleOwner.lifecycleScope
        fun scanReport(report: LibraryScanState) {
            // Resume callbacks can run inside a FragmentManager transaction. Keep the report
            // in the scanner until the posted dialog has actually been shown.
            fragment.view?.post {
                if (!fragment.isAdded || !isActive() || !fragment.isResumed) return@post
                val current = LibrarySessions.current(fragment.requireContext())
                if (current.locationId != report.location ||
                    LibraryScanner.peekReport(report.location, kind)?.runId != report.runId) return@post
                if (LibraryReportDialog.show(fragment, report.details(fragment.requireContext())))
                    LibraryScanner.acknowledgeReport(report.runId)
            }
        }
        scope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (isActive()) {
                    LibraryScanner.peekReport(LibrarySessions.current(fragment.requireContext()).locationId, kind)?.let(::scanReport)
                    LibraryScanner.start(fragment.requireContext(), kind)
                }
                awaitCancellation()
            }
        }
        scope.launch {
            var session = LibrarySessions.current(fragment.requireContext())
            LibrarySessions.changes.collect {
                val current = LibrarySessions.current(fragment.requireContext())
                val next = current.locationId
                if (current !== session) {
                    session = current; rootChanged()
                    if (isActive() && fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) LibraryScanner.start(fragment.requireContext(), kind)
                }
                refresh()
                if (isActive() && fragment.isResumed) LibraryScanner.peekReport(next, kind)?.let(::scanReport)
            }
        }
        scope.launch {
            val importTasks = if (kind == "manga") ImportManager.tasks else NovelImportManager.tasks
            combine(LibrarySessions.changes.flatMapLatest { LibrarySessions.current(fragment.requireContext()).saveState },
                LibraryScanner.state, importTasks, LibraryDeletion.state) { saved, scan, tasks, deletion ->
                ShelfStatus(saved, scan, tasks, deletion)
            }.collect { current ->
                    val location = LibrarySessions.current(fragment.requireContext()).locationId
                    val saved = current.saved
                    val scan = current.scan
                    val scanning = scan.running && scan.kind == kind && scan.location == location
                    val deleting = current.deletion.running && current.deletion.kind == kind && current.deletion.location == location
                    val tasks = current.imports
                    val task = tasks.firstOrNull { it.progress.phase != ImportPhase.QUEUED } ?: tasks.firstOrNull()
                    val importScanning = task?.progress?.phase == ImportPhase.SCANNING
                    val message = when {
                        saved.error != null -> fragment.getString(if (saved.errorKind == LibraryErrorKind.FILES)
                            R.string.library_unsaved else R.string.library_unavailable) + "：" + saved.error
                        task != null -> fragment.getString(R.string.library_import_active, tasks.size, taskLabel(fragment, task))
                        scanning -> fragment.getString(R.string.library_scan_progress, scan.done, scan.total)
                        deleting -> fragment.getString(R.string.library_deleting)
                        else -> fragment.getString(R.string.library_saving)
                    }
                    val showScanInline = saved.error == null && (scanning && task == null || importScanning)
                    renderStatus(status, toolbarSpinner, inlineScanStatus, message,
                        saved.error != null || saved.saving || scanning || deleting || tasks.isNotEmpty(),
                        scanning || deleting || tasks.isNotEmpty(), showScanInline, saved.error != null)
                    if (saved.error != null) {
                        status.setOnClickListener { LibraryReportDialog.show(fragment,
                            libraryRecoveryDetails(fragment.requireContext(), saved.error, saved.errorKind)) }
                    }
                    toolbarCancel.visibility = if (tasks.isNotEmpty()) View.VISIBLE else View.GONE
                    toolbarCancel.setOnClickListener {
                        if (tasks.size == 1) cancelImport(tasks.single().id)
                        else showImportTasks(fragment, tasks, cancelImport)
                    }
                    if (saved.error == null && tasks.isNotEmpty()) {
                        status.setOnClickListener { showImportTasks(fragment, tasks, cancelImport) }
                        inlineScanStatus.setOnClickListener { showImportTasks(fragment, tasks, cancelImport) }
                    } else {
                        inlineScanStatus.setOnClickListener(null)
                    }
                }
        }
        scope.launch {
            LibraryScanner.state.collect { scan ->
                if (scan.kind != kind || scan.location != LibrarySessions.current(fragment.requireContext()).locationId) return@collect
                if (!scan.running) {
                    refresh()
                    if (isActive() && fragment.isResumed) LibraryScanner.peekReport(scan.location, kind)?.let(::scanReport)
                }
            }
        }
        scope.launch { LibraryDeletion.state.collect { state ->
            if (state.kind == kind && state.location == LibrarySessions.current(fragment.requireContext()).locationId) {
                refresh()
                if (!state.running) LibraryDeletion.consumeFailures()?.let(failures)
            }
        } }
    }

    private data class ShelfStatus(val saved: LibrarySaveState, val scan: LibraryScanState,
        val imports: List<ImportTask>, val deletion: LibraryDeleteState)

    private fun taskLabel(fragment: Fragment, task: ImportTask): String {
        val progress = when {
            task.progress.phase == ImportPhase.QUEUED -> fragment.getString(R.string.import_task_queued)
            task.progress.phase == ImportPhase.SCANNING -> fragment.getString(R.string.import_task_scanning)
            task.percent >= 0 -> fragment.getString(R.string.import_task_copying_percent, task.percent)
            else -> fragment.getString(R.string.import_task_copying)
        }
        return "${task.title} · $progress"
    }

    private fun showImportTasks(fragment: Fragment, tasks: List<ImportTask>, cancel: (Long) -> Unit) {
        android.app.AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.library_import_tasks)
            .setItems(tasks.map { taskLabel(fragment, it) }.toTypedArray()) { _, which -> cancel(tasks[which].id) }
            .setNegativeButton(R.string.cancel, null).create().also {
                it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            }
    }
}
