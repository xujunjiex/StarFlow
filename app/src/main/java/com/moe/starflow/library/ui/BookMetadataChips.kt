package com.moe.starflow.library.ui

import android.content.Context
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.moe.starflow.R
import com.moe.starflow.mangaimport.data.ComicMetadata

/**
 * 元数据的 chips 排布：`字段名：` 后面跟一串小胶囊，放不下自动换行，**长按**胶囊复制该值。
 *
 * 排布对齐用户给的参考图：标签名与 chips 同一行起排，chips 之间 6dp，整块可换行铺满。
 *
 * ⚠️ **复制是长按，不是点一下**（用户口径 2026-11）：卡片上的标签本来就是"看一眼"的东西，
 * 短按就复制太容易误触（想点卡片却复制了标签）。短按改为转发给容器动作 —— 书架行是进作品数据页，
 * 作品数据页没有容器动作就什么都不做；复制只留给"按住"。
 */
object BookMetadataChips {

    /** 把一份元数据按「系列 / 标签 / 作者 / 社团 / 语言 / 类型 / 页数 / 发布日期」铺开（空值整行省略）。 */
    fun addRows(parent: LinearLayout, meta: ComicMetadata, onCopy: (String) -> Unit) {
        val context = parent.context
        meta.title?.takeIf(String::isNotBlank)?.let { title ->
            parent.addView(TextView(context).apply {
                text = context.getString(R.string.book_meta_title, title)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextIsSelectable(true)
                com.moe.starflow.utils.FontSync.apply(this)
            })
        }
        meta.summary?.takeIf(String::isNotBlank)?.let { summary ->
            parent.addView(TextView(context).apply {
                text = android.text.Html.fromHtml(summary, android.text.Html.FROM_HTML_MODE_LEGACY)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextIsSelectable(true)
                com.moe.starflow.utils.FontSync.apply(this)
            })
        }
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

    /** 按住时缩到多大（与阅读器按钮的按下反馈同一手感）。 */
    private const val PRESSED_SCALE = 0.92f

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

    /**
     * 标签胶囊：**长按**复制该值（见类注释）。短按转发给容器动作，不复制。
     *
     * 长按反馈分两拍：按下缩到 [PRESSED_SCALE]、抬起弹回；长按真的命中时换成一次「弹一下」的
     * 确认动画（复制本身不会改变画面，没有这层动画用户看不出到底按到了没有）。
     */
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
        isLongClickable = true
        // 短按 = 这一块原本的动作（书架行 tag_flow = 进作品数据页）；宿主没动作时静默
        setOnClickListener { (parent as? View)?.performClick() }
        var longPressed = false
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    view.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE).setDuration(80L).start()
                }
                // 长按命中后由下面那次「弹一下」收尾，这里再弹回会和它打架
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (!longPressed) view.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }
            false
        }
        setOnLongClickListener { view ->
            longPressed = true
            view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90L).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }.start()
            onCopy(value)
            true
        }
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
