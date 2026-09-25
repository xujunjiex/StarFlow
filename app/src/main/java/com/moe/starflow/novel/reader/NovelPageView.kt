package com.moe.starflow.novel.reader

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * 分页模式下**一页**的自绘 View。
 *
 * 逐 segment 绘制而不是把整页拼成一个字符串：跨页的段落在每页只画它自己那一段，
 * 而每段的完整显示文本（原文/译文/双语）来自 [ChapterContent.displayOf] —— 与分页用的是
 * 同一份文本，所以字符区间必然对得上。
 */
class NovelPageView(context: Context) : View(context) {

    private var content: ChapterContent? = null
    private var page: NovelPage? = null
    private var style: NovelTextStyle = DEFAULT_STYLE

    fun bind(content: ChapterContent, page: NovelPage, style: NovelTextStyle) {
        this.content = content
        this.page = page
        this.style = style
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = content ?: return
        val p = page ?: return
        val width = (width - 2 * style.paddingPx).toInt().coerceAtLeast(1)

        var y = style.paddingPx
        for (seg in p.segments) {
            val full = c.displayOf(seg.paraIndex)
            if (full.isEmpty()) continue
            val from = seg.charStart.coerceIn(0, full.length)
            val to = seg.charEnd.coerceIn(from, full.length)
            if (to <= from) continue
            y += NovelTextRenderer.draw(
                canvas = canvas,
                text = full.substring(from, to),
                style = style,
                contentWidth = width,
                x = style.paddingPx,
                y = y,
            )
            y += style.paragraphSpacingPx
        }
    }

    companion object {
        val DEFAULT_STYLE = NovelTextStyle(fontSizePx = 42f, lineSpacingMultiplier = 1.5f, paragraphSpacingPx = 18f, paddingPx = 24f)
    }
}
