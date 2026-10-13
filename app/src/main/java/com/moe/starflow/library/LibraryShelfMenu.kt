package com.moe.starflow.library

import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import com.moe.starflow.R

/** Shared overflow actions for the manga and novel shelves. */
object LibraryShelfMenu {
    private const val DISPLAY = 1
    private const val SCAN = 2
    private const val STORAGE = 3

    fun show(fragment: Fragment, anchor: View, onDisplay: () -> Unit, onScan: () -> Unit) {
        PopupMenu(fragment.requireContext(), anchor).apply {
            menu.add(Menu.NONE, DISPLAY, 0, R.string.display_options)
            menu.add(Menu.NONE, SCAN, 1, R.string.library_scan)
            menu.add(Menu.NONE, STORAGE, 2, R.string.library_storage_title)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    DISPLAY -> onDisplay()
                    SCAN -> onScan()
                    STORAGE -> LibraryStorageDialog().show(fragment.parentFragmentManager, "library-storage")
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }
}
