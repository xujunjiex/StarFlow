package com.moe.starflow.mangaimport.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.moe.starflow.R
import com.moe.starflow.data.TranslationCacheUtils

/** 每页翻译详情：逐条 原文(淡) + 译文(主)，文本可选中复制，可复制全部/重翻本页/关闭。 */
object ReaderPageTranslateDetailDialog {

    fun show(
        context: Context,
        pageIndex: Int,
        sourceText: String?,
        translatedText: String?,
        onRetry: () -> Unit,
    ) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_reader_translate_detail, null, false)
        val originals = TranslationCacheUtils.parseIndexedTextList(sourceText)
        val translations = TranslationCacheUtils.parseIndexedTextList(translatedText)

        val sb = SpannableStringBuilder()
        for (i in originals.indices) {
            val o = originals[i]
            val t = translations.getOrNull(i).orEmpty()
            sb.append("[${i + 1}] ").append(o).append("\n")
            sb.append("[${i + 1}] ", ForegroundColorSpan(0xFF666666.toInt()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                .append(t, ForegroundColorSpan(0xFF222222.toInt()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n\n")
        }
        view.findViewById<TextView>(R.id.tv_detail_title).text =
            context.getString(R.string.reader_translate_detail_title, pageIndex + 1)
        view.findViewById<TextView>(R.id.tv_detail_content).text = if (sb.isEmpty()) {
            context.getString(R.string.reader_translate_detail_empty)
        } else sb

        view.findViewById<Button>(R.id.btn_detail_copy).setText(context.getString(R.string.reader_translate_copy_all))
        view.findViewById<Button>(R.id.btn_detail_retry).setText(context.getString(R.string.reader_translate_retry_this_page))
        view.findViewById<Button>(R.id.btn_detail_close).setText(context.getString(R.string.reader_translate_close))

        val dialog = AlertDialog.Builder(context).setView(view).create()
        view.findViewById<Button>(R.id.btn_detail_copy).setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("translation", translatedText.orEmpty()))
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btn_detail_retry).setOnClickListener {
            dialog.dismiss(); onRetry()
        }
        view.findViewById<Button>(R.id.btn_detail_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }
}