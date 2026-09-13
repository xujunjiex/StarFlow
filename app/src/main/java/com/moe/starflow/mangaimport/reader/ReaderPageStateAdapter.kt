package com.moe.starflow.mangaimport.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.TranslationCacheUtils
import com.moe.starflow.utils.UiUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 翻译面板每页一行：P{n} + 状态徽章 + 失败原因；点「详情/收起」在当前行内联展开
 * （像屏幕翻译历史那样：元数据 + 逐条原文/译文 + 复制全部），配色随面板主题（[dark]）。
 */
class ReaderPageStateAdapter(
    private val onJump: (Int) -> Unit,
) : RecyclerView.Adapter<ReaderPageStateAdapter.VH>() {

    var rows: List<ImportedPageTranslation> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    /** 面板深浅（随阅读背景切换），行内文字配色跟随。 */
    var dark = false
        set(value) { field = value; notifyDataSetChanged() }

    private var expandedIndex: Int? = null

    class VH(item: View) : RecyclerView.ViewHolder(item)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_translate_page_state, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = holder.itemView
        val row = rows[position]

        val labelColor = if (dark) 0xFFE2E2E4.toInt() else 0xFF333333.toInt()
        val subColor = if (dark) 0xFF9A9A9F.toInt() else 0xFF888888.toInt()
        val accent = 0xFF55AEEA.toInt()

        item.findViewById<TextView>(R.id.tv_page_label).apply {
            text = "P${row.pageIndex + 1}"
            setTextColor(labelColor)
        }
        val badge = item.findViewById<TextView>(R.id.tv_state_badge)
        badge.setText(contextString(item.context, when (row.state) {
            ImportedPageTranslation.STATE_TRANSLATING -> R.string.reader_translate_state_translating
            ImportedPageTranslation.STATE_SUCCESS -> R.string.reader_translate_state_success
            ImportedPageTranslation.STATE_FAILED -> R.string.reader_translate_state_failed
            else -> R.string.reader_translate_state_idle
        }))
        badge.setBackgroundResource(
            when (row.state) {
                ImportedPageTranslation.STATE_SUCCESS -> R.drawable.bg_state_success
                ImportedPageTranslation.STATE_FAILED -> R.drawable.bg_state_failed
                ImportedPageTranslation.STATE_TRANSLATING -> R.drawable.bg_state_translating
                else -> R.drawable.bg_state_idle
            }
        )

        item.findViewById<TextView>(R.id.tv_fail_message).apply {
            text = row.failMessage
            visibility = if (!row.failMessage.isNullOrBlank()) View.VISIBLE else View.GONE
        }

        val expanded = position == expandedIndex
        val btnDetail = item.findViewById<TextView>(R.id.btn_row_detail)
        btnDetail.setTextColor(accent)
        btnDetail.text = contextString(item.context,
            if (expanded) R.string.reader_translate_collapse else R.string.reader_translate_row_detail)

        val detail = item.findViewById<View>(R.id.detail_panel)
        if (expanded) {
            fillDetail(item, row, labelColor, subColor, accent)
            detail.visibility = View.VISIBLE
        } else {
            detail.visibility = View.GONE
        }

        // 点整行 = 跳页；展开态点详情按钮 = 收起
        item.setOnClickListener { if (!expanded) onJump(row.pageIndex) }
        btnDetail.setOnClickListener {
            val prev = expandedIndex
            expandedIndex = if (expanded) null else position
            if (prev != null) notifyItemChanged(prev)
            notifyItemChanged(position)
        }
    }

    /** 像屏幕翻译历史那样：元数据（翻译器/语言/时间）+ 逐条 原文(淡)+译文(主) + 复制全部。 */
    private fun fillDetail(item: View, row: ImportedPageTranslation, labelColor: Int, subColor: Int, accent: Int) {
        val meta = buildString {
            row.translatorName?.takeIf { it.isNotBlank() }?.let { append(it).append("\n") }
            val langs = listOfNotNull(row.sourceLang, row.targetLang)
            if (langs.isNotEmpty()) append(langs.joinToString(" → ")).append(" · ")
            if (row.updatedAtMs > 0L) {
                append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(row.updatedAtMs)))
            }
        }
        item.findViewById<TextView>(R.id.tv_detail_meta).apply {
            text = meta.trim()
            setTextColor(subColor)
        }

        val originals = TranslationCacheUtils.parseIndexedTextList(row.sourceText)
        val translations = TranslationCacheUtils.parseIndexedTextList(row.translatedText)
        val sb = SpannableStringBuilder()
        for (i in originals.indices) {
            sb.append("[${i + 1}] ")
            sb.append(originals[i], ForegroundColorSpan(subColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n[${i + 1}] ")
            sb.append(translations.getOrNull(i).orEmpty(), ForegroundColorSpan(labelColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i < originals.size - 1) sb.append("\n\n")
        }
        item.findViewById<TextView>(R.id.tv_detail_list).text = sb
        item.findViewById<TextView>(R.id.tv_detail_list).setTextColor(labelColor)

        item.findViewById<TextView>(R.id.btn_copy_all).apply {
            text = contextString(item.context, R.string.reader_translate_copy_all)
            setTextColor(accent)
            setOnClickListener {
                if (!row.translatedText.isNullOrBlank()) {
                    val cm = item.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("translation", row.translatedText))
                    UiUtils.showToast(item.context, contextString(item.context, R.string.reader_translate_copied))
                }
            }
        }
    }

    private fun contextString(context: Context, res: Int): String = context.getString(res)
}