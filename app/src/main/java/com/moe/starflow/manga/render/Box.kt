package com.moe.starflow.manga.render

import android.graphics.Rect

/**
 * 排版区域的几何信息。
 *
 * ⚠️ **刻意不用 `android.graphics.Rect`**：单测开了 `unitTests.returnDefaultValues`，
 * 纯 JVM 测试里 `Rect` 是桩类（`width()` 返回 0），内核会直接早退成空排版 ——
 * 那样这个「纯函数内核」就根本测不了。用值对象则可以在无 Robolectric 的情况下覆盖全部不变量。
 */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f

    companion object {
        fun from(rect: Rect): Box =
            Box(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    }
}
