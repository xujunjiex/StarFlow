package com.moe.starflow.manga.types
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.graphics.Rect

/** 游戏/漫画共用 ML Kit 识别结果的一个文本块。 */
data class TextBlockInfo(
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: Array<android.graphics.Point>?,
    val isVertical: Boolean? = null,  // 显式值优先；null → 用 inferredVertical() 从几何推断
    val angle: Float = 0f,
    val centerX: Float = -1f,
    val centerY: Float = -1f
) {
    /**
     * 推断文字方向（true=竖排 / false=横排）。
     * 优先级：isVertical 显式值 → cornerPoints 顺序无关的主轴判定（抗旋转、抗角点顺序差异）→ AABB 宽高比。
     */
    fun inferredVertical(): Boolean {
        isVertical?.let { return it }
        cornerPoints?.let { pts ->
            quadVertical(pts)?.let { return it }
        }
        return boundingBox?.let { it.height() > it.width() } ?: false
    }

    /**
     * 四边形方向判定（**顺序无关**、抗旋转）：较长对边对的方向即主轴，|y|>|x| → 竖排。
     * 长/短比 < 1.5（近方形/退化）返回 null，交 AABB 兜底。
     * ⚠️ 不能用固定 TL,TR,BR,BL 下标——ML Kit 的 cornerPoints 顺序实测与文档不一致，
     * 按文档顺序会把竖排方块（高 197 宽 28）的 pt0→pt1 当成宽 → 误判横排。
     */
    private fun quadVertical(pts: Array<android.graphics.Point>): Boolean? {
        if (pts.size < 4) return null
        fun sideX(i: Int) = (pts[(i + 1) % 4].x - pts[i].x).toFloat()
        fun sideY(i: Int) = (pts[(i + 1) % 4].y - pts[i].y).toFloat()
        fun len(i: Int) = kotlin.math.hypot(sideX(i), sideY(i))
        val l01 = len(0); val l12 = len(1); val l23 = len(2); val l30 = len(3)
        val pairA = l01 + l23   // 对边 (0-1, 2-3)
        val pairB = l12 + l30   // 对边 (1-2, 3-0)
        val long = maxOf(pairA, pairB)
        val short = minOf(pairA, pairB)
        if (short <= 0f || long / short < 1.5f) return null   // 近方形/退化 → 不定
        // 主轴取较长对边中第一条的方向
        val sx = if (pairA >= pairB) sideX(0) else sideX(1)
        val sy = if (pairA >= pairB) sideY(0) else sideY(1)
        return kotlin.math.abs(sy) > kotlin.math.abs(sx)
    }
}
