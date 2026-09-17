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
     * 判定统一收敛到 [BubbleOrientation.textVerticalOfBlock]（强信号 quad 优先 → AABB 兜底）。
     */
    fun inferredVertical(): Boolean =
        BubbleOrientation.textVerticalOfBlock(cornerPoints, boundingBox, isVertical)
}
