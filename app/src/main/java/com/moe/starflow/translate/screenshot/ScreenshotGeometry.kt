package com.moe.starflow.translate.screenshot

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Point

/** 截图属于一次确定的 display 配置；180° 翻转同样会使旧坐标失效。 */
data class DisplayGeometry(val width: Int, val height: Int, val rotation: Int, val densityDpi: Int)

/** 不持有可变 RectF，后续框选变化不能重新解释已经识别出的坐标。 */
data class ScreenshotMapping(
    val display: DisplayGeometry,
    val left: Int, val top: Int, val right: Int, val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun matchesBitmap(width: Int, height: Int): Boolean = this.width == width && this.height == height

    fun matchesWindow(width: Int, height: Int, x: Int, y: Int): Boolean =
        matchesBitmap(width, height) && left == x && top == y

    fun rect(): Rect = Rect(left, top, right, bottom)

    fun toDisplayRect(local: Rect): Rect? {
        val clipped = Rect(local)
        if (!clipped.intersect(0, 0, width, height)) return null
        clipped.offset(left, top)
        return clipped
    }

    companion object {
        /** 感知哈希相等不能证明文字仍在相同位置，只有相同源像素才能复用旧坐标。 */
        fun canReuseCoordinates(current: android.graphics.Bitmap, cached: android.graphics.Bitmap,
            cachedCrop: Rect?, requestedCrop: Rect): Boolean =
            cachedCrop == requestedCrop && !current.isRecycled && !cached.isRecycled && current.width == cached.width &&
                current.height == cached.height && current.sameAs(cached)

        fun create(display: DisplayGeometry, crop: RectF?, offset: Point = Point()): ScreenshotMapping? {
            val bounds = if (crop == null) Rect(0, 0, display.width, display.height)
                else exactCropBounds(display.width, display.height, crop, offset) ?: return null
            if (bounds.width() <= 0 || bounds.height() <= 0) return null
            return ScreenshotMapping(display, bounds.left, bounds.top, bounds.right, bounds.bottom)
        }

        /** 越界或退化框必须拒绝，不能夹到边缘后把缩小的图片拉回原框。 */
        fun exactCropBounds(width: Int, height: Int, crop: RectF, offset: Point): Rect? {
            if (!crop.left.isFinite() || !crop.top.isFinite() || !crop.right.isFinite() || !crop.bottom.isFinite()) return null
            val rect = Rect(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt())
            rect.offset(offset.x, offset.y)
            return rect.takeIf {
                it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height &&
                    it.width() > 0 && it.height() > 0
            }
        }
    }
}

data class CapturedScreen(val bitmap: android.graphics.Bitmap, val display: DisplayGeometry)
