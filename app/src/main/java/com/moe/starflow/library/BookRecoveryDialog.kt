package com.moe.starflow.library

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.moe.starflow.R
import com.moe.starflow.me.settings.SettingPageActivity
import com.moe.starflow.utils.AppNotice

object BookRecoveryDialog {
    const val FAQ_CARD = "library-source-recovery"
    fun faq(context: Context) {
        context.startActivity(Intent(context, SettingPageActivity::class.java)
            .putExtra(SettingPageActivity.EXTRA_FRAGMENT_TYPE, SettingPageActivity.TYPE_FRAGMENT_FAQ)
            .putExtra("faq_card", FAQ_CARD))
    }
    fun show(context: Context, ref: BookRef) {
        val (session, book) = BookData.resolve(context, ref)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 12) }
        fun label(value: String) { body.addView(TextView(context).apply { text = value; setTextIsSelectable(true); setPadding(0, 8, 0, 8) }) }
        label(context.getString(R.string.book_recovery_message))
        label(context.getString(R.string.book_data_location, session.storage.displayPath, book.source))
        label("UUID: ${book.uuid}")
        fun action(label: Int, run: () -> Unit) { body.addView(MaterialButton(context).apply { setText(label); setOnClickListener { run() } }) }
        action(R.string.book_recovery_faq) { faq(context) }
        action(R.string.book_data_title) { BookDataActivity.open(context, ref) }
        action(R.string.book_recovery_check) {
            LibraryScanner.start(session.context(context), ref.kind)
            AppNotice.show(context, context.getString(R.string.book_recovery_check_started))
        }
        val missingPaths = TextView(context).apply { setTextIsSelectable(true); setPadding(0, 8, 0, 8) }
        body.addView(missingPaths)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.import_file_lost).setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton(R.string.cancel, null).create().also {
                it.show(); it.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
            }
        (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
            val paths = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val index = runCatching { com.moe.starflow.mangaimport.data.MangaSourceIndex.load(session, org.json.JSONObject(book.body).optString("sourceIndexPath")) }.getOrNull()
                index?.pages.orEmpty().filter { it.missing }.map {
                    if (index?.standalone == true) "${book.source} :: ${it.entry}" else "${book.source}/${it.path}"
                }.distinct()
            }
            if (dialog.isShowing && paths.isNotEmpty()) missingPaths.text = context.getString(R.string.book_recovery_missing_paths, paths.take(100).joinToString("\n"))
        }
    }
}
