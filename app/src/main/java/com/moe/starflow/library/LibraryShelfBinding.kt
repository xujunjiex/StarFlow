package com.moe.starflow.library

import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flatMapLatest

object LibraryShelfBinding {
    private fun renderStatus(status: TextView, message: CharSequence, visible: Boolean, scanning: Boolean) {
        val row = status.parent as? ViewGroup
        row?.visibility = if (visible) View.VISIBLE else View.GONE
        status.visibility = if (visible) View.VISIBLE else View.GONE
        status.text = message
        row?.findViewById<ProgressBar>(R.id.library_scan_spinner)?.visibility =
            if (visible && scanning) View.VISIBLE else View.GONE
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(fragment: Fragment, kind: String, status: TextView,
        refresh: () -> Unit, failures: (List<String>) -> Unit, rootChanged: () -> Unit = {}) {
        val scope = fragment.viewLifecycleOwner.lifecycleScope
        val pinned = LibrarySessions.pin(fragment.requireContext())
        LibrarySessions.launch(pinned) {
            runCatching { LibrarySessions.prepare(pinned); LibrarySessions.refreshSources(pinned) }
            LibrarySessions.changes.value += 1
        }
        scope.launch {
            var location = LibrarySessions.current(fragment.requireContext()).locationId
            LibrarySessions.changes.collect {
                val next = LibrarySessions.current(fragment.requireContext()).locationId
                if (next != location) { location = next; rootChanged() }
                refresh()
            }
        }
        scope.launch {
            LibrarySessions.changes.flatMapLatest { LibrarySessions.current(fragment.requireContext()).saveState }
                .collect { state ->
                    val scan = LibraryScanner.state.value
                    val scanning = scan.running && scan.kind == kind
                    val message = when {
                        scanning -> fragment.getString(R.string.library_scan_progress, scan.done, scan.total)
                        state.error != null -> fragment.getString(R.string.library_unsaved) + "：" + state.error
                        else -> fragment.getString(R.string.library_saving)
                    }
                    renderStatus(status, message, state.error != null || state.saving || scanning, scanning)
                }
        }
        scope.launch {
            LibraryScanner.state.collect { scan ->
                if (scan.kind != kind || scan.location != LibrarySessions.current(fragment.requireContext()).locationId) return@collect
                if (scan.running) {
                    renderStatus(status, fragment.getString(R.string.library_scan_progress, scan.done, scan.total), true, true)
                } else {
                    refresh()
                    val saved = LibrarySessions.current(fragment.requireContext()).saveState.value
                    renderStatus(status, saved.error ?: fragment.getString(R.string.library_saving),
                        saved.error != null || saved.saving, false)
                    LibraryScanner.consumeFailures()?.let(failures)
                }
            }
        }
        scope.launch { LibraryDeletion.state.collect { state ->
            if (state.kind == kind && !state.running && state.location == LibrarySessions.current(fragment.requireContext()).locationId) {
                refresh()
                LibraryDeletion.consumeFailures()?.let(failures)
            }
        } }
    }
}
