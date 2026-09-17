package com.moe.starflow.manga.types

import android.graphics.Point
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 文字方向判定的**唯一入口**。
 *
 * 背景：判定曾散在 6 处、用了强弱不同的信号，排查「某页方向判反了」时定位不到是哪一层干的。
 * 这里把判定收敛成两个**函数名自带信号强度**的函数，并在日志里打出用了哪档信号
 * （`signal=quad` / `signal=aabb`），grep 一行即可对账。
 *
 * ⚠️ 两档信号**不合并**：输入语义不同（一个是文字行 quad，一个是气泡矩形），
 * 合并会让「强信号」被「弱信号」的假设污染。
 */
object BubbleOrientation {

    /**
     * **强信号**：由文字行的四边形角点判定（顺序无关、抗旋转）。
     * 长/短边比 < [QUAD_ASPECT_MIN] 视为近方形/退化 → 返回 null 交上层兜底。
     *
     * ⚠️ 不能用固定 TL,TR,BR,BL 下标取边 —— ML Kit 的 cornerPoints 顺序实测与文档不一致，
     * 按文档顺序会把竖排方块（高 197 宽 28）的 pt0→pt1 当成宽 → 误判横排。
     */
    fun textVerticalFromQuad(cornerPoints: Array<Point>?): Boolean? {
        val pts = cornerPoints ?: return null
        if (pts.size < 4) return null
        fun sideX(i: Int) = (pts[(i + 1) % 4].x - pts[i].x).toFloat()
        fun sideY(i: Int) = (pts[(i + 1) % 4].y - pts[i].y).toFloat()
        fun len(i: Int) = hypot(sideX(i), sideY(i))
        val pairA = len(0) + len(2)   // 对边 (0-1, 2-3)
        val pairB = len(1) + len(3)   // 对边 (1-2, 3-0)
        val long = maxOf(pairA, pairB)
        val short = minOf(pairA, pairB)
        if (short <= 0f || long / short < QUAD_ASPECT_MIN) return null
        val sx = if (pairA >= pairB) sideX(0) else sideX(1)
        val sy = if (pairA >= pairB) sideY(0) else sideY(1)
        return abs(sy) > abs(sx)
    }

    /**
     * 文字行级判定：强信号优先，退化时回落 AABB 宽高比。
     * 供 ML Kit / PP-OCR 等**拿得到文字行角点**的路径使用。
     */
    fun textVerticalOfBlock(
        cornerPoints: Array<Point>?,
        boundingBox: Rect?,
        explicit: Boolean? = null
    ): Boolean {
        val vertical = explicit
            ?: textVerticalFromQuad(cornerPoints)
            ?: (boundingBox?.let { it.height() > it.width() } ?: false)
        log("block", cornerPoints != null, boundingBox, vertical)
        return vertical
    }

    /**
     * **弱信号**：只有气泡矩形可用时（RT-DETR / MangaOcr 路径只回传气泡框，拿不到文字行角点），
     * 只能按「气泡高 > 宽 ⇒ 文字竖排」这个启发式猜。
     *
     * ⚠️ 这是启发式，不是判定。收在这里是为了「只有一处」，而非因为它准确 ——
     * 同一页换 OCR 引擎会切换判定路径，强弱信号可能给出不同结论。
     */
    fun textVerticalFromBubbleAabb(rect: Rect): Boolean {
        val vertical = rect.height() > rect.width()
        log("bubble", false, rect, vertical)
        return vertical
    }

    /** 阅读顺序排序用：整页多数文字块是竖排则按竖排排。弱信号，按行 AABB 多数投票。 */
    fun isPageVertical(blocks: List<TextBlockInfo>): Boolean =
        blocks.count { it.boundingBox?.let { r -> r.height() > r.width() } ?: false } > blocks.size / 2

    private fun log(signal: String, strong: Boolean, rect: Rect?, vertical: Boolean) {
        com.moe.starflow.utils.LogCollector.d(
            "BubbleOrientation",
            "signal=${if (strong) "quad" else "aabb"} kind=$signal rect=$rect → vertical=$vertical"
        )
    }

    /** 四边形长/短边比下限：低于此值视为近方形/退化，判定不可靠。 */
    private const val QUAD_ASPECT_MIN = 1.5f
}
