package com.moe.starflow.novel.reader

import android.text.StaticLayout
import android.text.TextPaint
import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 章文本分页。
 *
 * ### 架构：纯核心 + Android 外壳
 * [paginateByLines] 是**纯函数**（输入「每段的行起点数组」，输出页表），全部分页逻辑都在这里，
 * 可普通单测。 [paginate] 只是用 `StaticLayout` 求出每段的行起点再调它。
 *
 * ⚠️ 这么分是被 Robolectric 逼的：它的 `Paint.breakText` **不按宽度换行**，排出来的
 * `StaticLayout` 行数与真机不符 —— 依赖真实换行的断言在 Robolectric 下是**假绿**。
 * 所以换行信息由调用方/测试显式提供，分页逻辑本身与文本引擎解耦。
 *
 * ### 硬约束
 * 对**每一段**，它的全部 segment 的 `[charStart, charEnd)` 按顺序拼接必须完整覆盖
 * `[0, 文本长度)`、无重叠、无丢字。这是「用户不会丢字」的唯一保证，有守卫测试逐段验证。
 */
object NovelPaginator {

    /** 浮点比较容差（行高与页高都来自 px 换算，直接 `<=` 会因精度丢一行）。 */
    private const val EPSILON = 0.01f

    /**
     * @param lineStarts 与 `paragraphs` 中**可见段**一一对应：`lineStarts[i]` 是第 i 个可见段
     *   每行的段内起始偏移（升序，首元素应为 0）。空数组按「整段一行」处理。
     */
    internal fun paginateByLines(
        paragraphs: List<NovelParagraph>,
        lineStarts: List<IntArray>,
        lineHeightPx: Float,
        paragraphSpacingPx: Float,
        pageHeightPx: Float,
    ): List<NovelPage> {
        val visible = paragraphs.filter { it.type != NovelParagraphType.SKIP }
        if (visible.isEmpty()) return emptyList()

        val lineHeight = lineHeightPx.coerceAtLeast(1f)
        // 每页能放几行。页高不足一行时按 1 行算 —— 否则会切出空页/死循环
        val capacity = floor(pageHeightPx / lineHeight + EPSILON).toInt().coerceAtLeast(1)
        // 段间距折算成整行单位：行数是离散的，用浮点高度做累加会因精度在临界处多放/少放一行
        val spacingUnits = if (paragraphSpacingPx > 0f) {
            (paragraphSpacingPx / lineHeight).roundToInt().coerceAtLeast(1)
        } else 0

        val pages = mutableListOf<NovelPage>()
        var current = mutableListOf<PageSegment>()
        var used = 0

        for ((vi, para) in visible.withIndex()) {
            val starts = lineStarts.getOrNull(vi)?.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
            val textLen = para.originalText.length
            val lineCount = starts.size
            var lineIdx = 0
            var firstChunkOfPara = true

            while (lineIdx < lineCount) {
                // 段间距只在「段落的第一块 + 本页已有内容」时消耗
                val spacing = if (firstChunkOfPara && current.isNotEmpty()) spacingUnits else 0
                if (used + spacing + 1 > capacity) {
                    // 连一行都放不下 → 换页后重来（新页为空，spacing 自然变 0，不会死循环）
                    if (current.isNotEmpty()) {
                        pages.add(NovelPage(current))
                        current = mutableListOf()
                        used = 0
                        continue
                    }
                    // 空页仍放不下（capacity 至少为 1，理论不可达）：硬放一行，避免死循环
                }
                used += spacing
                val room = (capacity - used).coerceAtLeast(1)
                val take = (lineCount - lineIdx).coerceAtMost(room)
                val charStart = starts[lineIdx]
                val lastIdx = lineIdx + take - 1
                val charEnd = if (lastIdx + 1 < lineCount) starts[lastIdx + 1] else textLen
                current.add(
                    PageSegment(
                        paraIndex = para.index,
                        charStart = charStart.coerceIn(0, textLen),
                        charEnd = charEnd.coerceIn(charStart.coerceIn(0, textLen), textLen),
                    )
                )
                used += take
                lineIdx += take
                firstChunkOfPara = false
                if (lineIdx < lineCount) {
                    pages.add(NovelPage(current))
                    current = mutableListOf()
                    used = 0
                }
            }
        }
        if (current.isNotEmpty()) pages.add(NovelPage(current))
        return pages
    }

    /**
     * 生产入口：用 `StaticLayout` 求出每段的行起点，再交给纯核心切页。
     *
     * 宽高都要扣掉两侧内边距（渲染时文字就是画在这个内框里的）。
     */
    fun paginate(
        paragraphs: List<NovelParagraph>,
        style: NovelTextStyle,
        widthPx: Int,
        heightPx: Int,
    ): List<NovelPage> {
        val visible = paragraphs.filter { it.type != NovelParagraphType.SKIP }
        if (visible.isEmpty() || widthPx <= 0 || heightPx <= 0) return emptyList()

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = style.fontSizePx }
        val contentWidth = (widthPx - 2 * style.paddingPx).toInt().coerceAtLeast(1)
        val contentHeight = (heightPx - 2 * style.paddingPx).toFloat().coerceAtLeast(1f)
        val lineHeight = style.fontSizePx * style.lineSpacingMultiplier

        val lineStarts = visible.map { p ->
            val layout = StaticLayout.Builder
                .obtain(p.originalText, 0, p.originalText.length, paint, contentWidth)
                .setLineSpacing(0f, style.lineSpacingMultiplier)
                .setIncludePad(false)
                .build()
            IntArray(layout.lineCount) { layout.getLineStart(it) }
        }

        return paginateByLines(visible, lineStarts, lineHeight, style.paragraphSpacingPx, contentHeight)
    }
}
