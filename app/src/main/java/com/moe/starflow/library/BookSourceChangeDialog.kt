package com.moe.starflow.library

import android.app.AlertDialog
import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import java.lang.ref.WeakReference
import org.json.JSONArray

/** Warnings are revision-scoped; explicitly ignored missing page keys persist with the book. */
object BookSourceChangeDialog {
    private var visible = WeakReference<AlertDialog>(null)
    fun show(context: Context, book: ImportedManga,
        onRead: (() -> Unit)? = null, onDismiss: () -> Unit = {}): Boolean {
        val pending = book.sourceNoticeId.isNotBlank() && book.sourceNoticeId != book.sourceNoticeSeen
        if (!pending) return false
        if (visible.get()?.isShowing == true) return true
        val ref = BookRef(book.libraryLocationId, "manga", book.bookUuid)
        fun keys(value: String) = JSONArray(value).let { a -> (0 until a.length()).map(a::getString).toSet() }
        val missingRecords = keys(book.missingRecordKeys) - keys(book.ignoredMissingRecordKeys)
        val message = book.title + "\n\n" + book.sourceNotice.ifBlank {
            context.getString(R.string.manga_source_partial_summary, book.missingPages, book.changedPages)
        }
        val warning = if (missingRecords.isEmpty()) "" else "\n\n" + context.getString(R.string.manga_source_missing_records, missingRecords.size)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.manga_source_change_title)
            .setMessage(message + warning)
            .setPositiveButton(if (onRead == null) android.R.string.ok else R.string.manga_source_continue) { _, _ -> onRead?.invoke() }
            .setNegativeButton(R.string.book_data_title) { _, _ -> BookDataActivity.open(context, ref) }
            .setNeutralButton(if (missingRecords.isEmpty()) R.string.book_recovery_faq else R.string.manga_source_ignore_records) { _, _ ->
                if (missingRecords.isEmpty()) BookRecoveryDialog.faq(context)
                else try {
                    ImportedMangaStore.patch(context, book) { latest ->
                        latest.copy(ignoredMissingRecordKeys = JSONArray((keys(latest.ignoredMissingRecordKeys) + missingRecords).toList()).toString())
                    }
                    onRead?.invoke()
                } catch (e: Exception) {
                    com.moe.starflow.utils.AppNotice.show(context, e.message.orEmpty(), com.moe.starflow.utils.AppNotice.Style.ERROR)
                }
            }
            .create()
        visible = WeakReference(dialog)
        dialog.setOnDismissListener { if (visible.get() === dialog) visible.clear(); onDismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
        if (pending) try {
            ImportedMangaStore.patch(context, book) { latest ->
                if (latest.sourceNoticeId == book.sourceNoticeId) latest.copy(sourceNoticeSeen = book.sourceNoticeId) else latest
            }
        } catch (e: Exception) {
            com.moe.starflow.utils.AppNotice.show(context, e.message.orEmpty(), com.moe.starflow.utils.AppNotice.Style.ERROR)
        }
        return true
    }
}
