package com.moe.starflow.library.ui

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.moe.starflow.R
import com.moe.starflow.mangaimport.data.ComicMetadata

/**
 * 元数据的 chips 排布：`字段名：` 后面跟一串小胶囊，放不下自动换行，点胶囊复制该值。
 *
 * 排布对齐用户给的参考图：标签名与 chips 同一行起排，chips 之间 6dp，整块可换行铺满。
 */
object BookMetadataChips {

    /** 把一份元数据按「系列 / 标签 / 作者 / 社团 / 语言 / 类型 / 页数 / 发布日期」铺开（空值整行省略）。 */
    fun addRows(parent: LinearLayout, meta: ComicMetadata, onCopy: (String) -> Unit) {
        val context = parent.context
        addRow(parent, context.getString(R.string.book_meta_series_label), listOfNotNull(meta.series), onCopy)
        addRow(parent, context.getString(R.string.book_meta_tags_label), meta.tags, onCopy)
        addRow(parent, context.getString(R.string.book_meta_author_label), listOfNotNull(meta.writer), onCopy)
        addRow(parent, context.getString(R.string.book_meta_circle_label), listOfNotNull(meta.translator), onCopy)
        addRow(parent, context.getString(R.string.book_meta_language_label), listOfNotNull(meta.languageIso), onCopy)
        addRow(parent, context.getString(R.string.book_meta_category_label), listOfNotNull(meta.format), onCopy)
        addRow(parent, context.getString(R.string.book_meta_pages_label), listOfNotNull(meta.pageCount?.toString()), onCopy)
        addRow(parent, context.getString(R.string.book_meta_date_label), listOfNotNull(meta.publishedAt), onCopy)
    }

    /** 单行：标签名 + chips（值全为空时不占位）。 */
    fun addRow(parent: LinearLayout, label: String, values: List<String>, onCopy: (String) -> Unit) {
        val clean = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return
        val context = parent.context
        val row = WrapLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 6) }
        }
        fill(row, label, clean, onCopy)
        parent.addView(row)
    }

    /** 往一个已有容器里塞「标签名 + chips」（书架行用）。 */
    fun fill(container: ViewGroup, label: String, values: List<String>, onCopy: (String) -> Unit) {
        val clean = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return
        container.removeAllViews()
        val context = container.context
        container.addView(labelView(context, label))
        clean.forEach { value -> container.addView(chipView(context, value, onCopy, maxChars = null)) }
    }

    /** 只有 chips、没有字段名的版本（书架行空间紧）。 */
    fun fillChips(container: ViewGroup, values: List<String>, onCopy: (String) -> Unit) {
        container.removeAllViews()
        val context = container.context
        values.map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { value -> container.addView(chipView(context, value, onCopy)) }
    }

    /** 单个标签在卡片上的最大字符数：超了截断（完整内容去作品数据页看）。 */
    const val MAX_TAG_CHARS = 12

    /** 卡片用的截断版 chips：只显示前 [limit] 个，末尾给「+N」按钮。 */
    fun fillFoldedChips(
        container: ViewGroup,
        values: List<String>,
        expanded: Boolean,
        limit: Int,
        onCopy: (String) -> Unit,
        onToggle: () -> Unit,
    ) {
        container.removeAllViews()
        val context = container.context
        val clean = values.map { it.trim() }.filter { it.isNotEmpty() }
        val shown = if (expanded) clean else clean.take(limit)
        shown.forEach { value -> container.addView(chipView(context, value, onCopy)) }
        if (clean.size <= limit) return
        val label = if (expanded) container.context.getString(R.string.book_tags_collapse)
        else container.context.getString(R.string.book_tags_more, clean.size - limit)
        container.addView(actionChip(context, label, onToggle))
    }

    private fun labelView(context: Context, label: String) = TextView(context).apply {
        text = label
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(context, 4) }
    }

    private fun actionChip(context: Context, label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.primary))
        setBackgroundResource(R.drawable.bg_metadata_chip)
        setPadding(dp(context, 6), dp(context, 3), dp(context, 6), dp(context, 3))
        isClickable = true
        setOnClickListener { action() }
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = dp(context, 6)
            topMargin = dp(context, 4)
        }
    }

    private fun chipView(
        context: Context,
        value: String,
        onCopy: (String) -> Unit,
        maxChars: Int? = MAX_TAG_CHARS,
    ) = TextView(context).apply {
        text = if (maxChars != null && value.length > maxChars) value.take(maxChars).trimEnd() + "…" else value
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.chip_text))
        setBackgroundResource(R.drawable.bg_metadata_chip)
        setPadding(dp(context, 6), dp(context, 3), dp(context, 6), dp(context, 3))
        isClickable = true
        setOnClickListener { onCopy(value) }
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = dp(context, 6)
            topMargin = dp(context, 4)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
