package com.moe.starflow.library

import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flatMapLatest

object LibraryShelfBinding {
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
                    status.visibility = if (state.error != null || state.saving || scanning) View.VISIBLE else View.GONE
                    status.text = when {
                        scanning -> fragment.getString(R.string.library_scan_progress, scan.done, scan.total)
                        state.error != null -> fragment.getString(R.string.library_unsaved) + "：" + state.error
                        else -> fragment.getString(R.string.library_saving)
                    }
                }
        }
        scope.launch {
            LibraryScanner.state.collect { scan ->
                if (scan.kind != kind || scan.location != LibrarySessions.current(fragment.requireContext()).locationId) return@collect
                if (scan.running) {
                    status.visibility = View.VISIBLE
                    status.text = fragment.getString(R.string.library_scan_progress, scan.done, scan.total)
                } else {
                    refresh()
                    val saved = LibrarySessions.current(fragment.requireContext()).saveState.value
                    status.visibility = if (saved.error != null || saved.saving) View.VISIBLE else View.GONE
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
