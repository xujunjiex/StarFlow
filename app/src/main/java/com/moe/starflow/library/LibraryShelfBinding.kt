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
    private fun renderStatus(status: TextView, message: CharSequence, visible: Boolean, scanning: Boolean, error: Boolean = false) {
        val row = status.parent as? ViewGroup
        row?.visibility = if (visible) View.VISIBLE else View.GONE
        status.visibility = if (visible) View.VISIBLE else View.GONE
        status.text = message
        status.setTextColor(ContextCompat.getColor(status.context, if (error) R.color.red else R.color.text_secondary))
        status.setOnClickListener { if (error) AppNotice.show(status.context, message.toString(), AppNotice.Style.ERROR) }
        row?.findViewById<ProgressBar>(R.id.library_scan_spinner)?.visibility =
            if (visible && scanning) View.VISIBLE else View.GONE
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(fragment: Fragment, kind: String, status: TextView,
        refresh: () -> Unit, failures: (List<String>) -> Unit, rootChanged: () -> Unit = {}, isActive: () -> Boolean = { true },
        cancelImport: (Long) -> Unit = {}) {
        val scope = fragment.viewLifecycleOwner.lifecycleScope
        fun scanReport(report: LibraryScanState) {
            if (report.needsRecovery) {
                if (!LibraryStorageDialog.offerRecovery(fragment, report.failures.joinToString("\n"))) LibraryScanner.deferReport(report)
            }
            else failures(report.failures)
        }
        scope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (isActive()) {
                    LibrarySessions.current(fragment.requireContext()).saveState.value.error?.let {
                        LibraryStorageDialog.offerRecovery(fragment, it)
                    }
                    LibraryScanner.consumeReport(LibrarySessions.current(fragment.requireContext()).locationId, kind)?.let(::scanReport)
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
                if (isActive() && fragment.isResumed) LibraryScanner.consumeReport(next, kind)?.let(::scanReport)
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
                    val task = tasks.firstOrNull()
                    val message = when {
                        saved.error != null -> fragment.getString(R.string.library_unsaved) + "：" + saved.error
                        task != null -> fragment.getString(R.string.library_import_active, tasks.size, taskLabel(fragment, task))
                        scanning -> fragment.getString(R.string.library_scan_progress, scan.done, scan.total)
                        deleting -> fragment.getString(R.string.library_deleting)
                        else -> fragment.getString(R.string.library_saving)
                    }
                    renderStatus(status, message, saved.error != null || saved.saving || scanning || deleting || tasks.isNotEmpty(),
                        scanning || deleting || tasks.isNotEmpty(), saved.error != null)
                    if (saved.error != null) {
                        status.setOnClickListener { LibraryStorageDialog.offerRecovery(fragment, saved.error, automatic = false) }
                        if (isActive()) LibraryStorageDialog.offerRecovery(fragment, saved.error)
                    }
                    val cancel = (status.parent as? ViewGroup)?.findViewById<View>(R.id.library_import_cancel)
                    cancel?.visibility = if (tasks.isNotEmpty()) View.VISIBLE else View.GONE
                    cancel?.setOnClickListener {
                        if (tasks.size == 1) cancelImport(tasks.single().id)
                        else showImportTasks(fragment, tasks, cancelImport)
                    }
                    if (saved.error == null && tasks.isNotEmpty()) status.setOnClickListener { showImportTasks(fragment, tasks, cancelImport) }
                }
        }
        scope.launch {
            LibraryScanner.state.collect { scan ->
                if (scan.kind != kind || scan.location != LibrarySessions.current(fragment.requireContext()).locationId) return@collect
                if (!scan.running) {
                    refresh()
                    if (scan.failures.isEmpty()) LibraryStorageDialog.recoveryCompleted(scan.location)
                    if (isActive() && fragment.isResumed) LibraryScanner.consumeReport(scan.location, kind)?.let(::scanReport)
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
