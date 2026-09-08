package com.moe.starflow.mangaimport.reader

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * 阅读器颜色矫正（复刻 Kototoro/Koto 的 ReaderColorFilter）。
 *
 * 预设 + 自定义（亮度/对比度），最终生成 ColorMatrixColorFilter 应用在页码 ImageView 上。
 * 只影响屏幕显示，不修改原始图片文件。
 */
data class ReaderColorFilter(
    val brightness: Float,
    val contrast: Float,
    val isInverted: Boolean,
    val isGrayscale: Boolean,
    val isBookBackground: Boolean,
) {

    val isEmpty: Boolean
        get() = !isGrayscale && !isInverted && !isBookBackground && brightness == 0f && contrast == 0f

    fun toColorFilter(): ColorMatrixColorFilter = ColorMatrixColorFilter(toColorMatrix())

    fun toColorMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        if (isGrayscale) cm.setSaturation(0f)
        if (isInverted) cm.postConcat(ColorMatrix(invertMatrix))
        if (isBookBackground) cm.postConcat(ColorMatrix(bookMatrix))
        if (brightness != 0f) cm.postConcat(brightnessMatrix(brightness))
        if (contrast != 0f) cm.postConcat(contrastMatrix(contrast))
        return cm
    }

    private fun brightnessMatrix(brightness: Float): ColorMatrix {
        val scale = brightness + 1f
        val m = ColorMatrix()
        m.setScale(scale, scale, scale, 1f)
        return m
    }

    private fun contrastMatrix(contrast: Float): ColorMatrix {
        val scale = contrast + 1f
        val translate = (-0.5f * scale + 0.5f) * 255f
        return ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    companion object {
        private const val BOOK_BLUE_FACTOR = 0.92f

        private val invertMatrix = floatArrayOf(
            -1f, 0f, 0f, 0f, 1f,
            0f, -1f, 0f, 0f, 1f,
            0f, 0f, -1f, 0f, 1f,
            0f, 0f, 0f, 1f, 0f,
        )

        // 护眼：降低蓝色分量，暖黄底
        private val bookMatrix = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, BOOK_BLUE_FACTOR, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

        val EMPTY = ReaderColorFilter(0f, 0f, false, false, false)
        val INVERTED = ReaderColorFilter(0f, 0f, isInverted = true, false, false)
        val GRAYSCALE = ReaderColorFilter(0f, 0f, false, isGrayscale = true, false)
        val BOOK = ReaderColorFilter(0f, 0f, false, false, isBookBackground = true)

        /** 把「预设名 + 自定义亮度/对比度」装成滤镜：（预设使用其固定效果，自定义叠加亮度/对比度）。 */
        fun custom(brightness: Float, contrast: Float, inverted: Boolean, grayscale: Boolean, book: Boolean) =
            ReaderColorFilter(brightness, contrast, inverted, grayscale, book)
    }
}