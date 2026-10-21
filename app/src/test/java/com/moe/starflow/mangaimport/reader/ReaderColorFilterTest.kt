package com.moe.starflow.mangaimport.reader

import android.graphics.ColorMatrix
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderColorFilterTest {

    @Test
    fun inversion_swapsBlackAndWhite() {
        assertColor(ReaderColorFilter.INVERTED, floatArrayOf(0f, 0f, 0f, 255f), 255f, 255f, 255f, 255f)
        assertColor(ReaderColorFilter.INVERTED, floatArrayOf(255f, 255f, 255f, 255f), 0f, 0f, 0f, 255f)
    }

    @Test
    fun inversion_preservesGrayLevelsInsteadOfClippingToBlack() {
        for (gray in 0..255) {
            val value = gray.toFloat()
            val expected = 255f - value
            assertColor(ReaderColorFilter.INVERTED, floatArrayOf(value, value, value, 255f),
                expected, expected, expected, 255f)
        }
    }

    @Test
    fun inversion_producesComplementaryColors() {
        assertColor(ReaderColorFilter.INVERTED, floatArrayOf(255f, 0f, 0f, 255f), 0f, 255f, 255f, 255f)
        assertColor(ReaderColorFilter.INVERTED, floatArrayOf(0f, 255f, 0f, 255f), 255f, 0f, 255f, 255f)
        assertColor(ReaderColorFilter.INVERTED, floatArrayOf(0f, 0f, 255f, 255f), 255f, 255f, 0f, 255f)
        assertColor(ReaderColorFilter.INVERTED, floatArrayOf(30f, 80f, 190f, 255f), 225f, 175f, 65f, 255f)
    }

    @Test
    fun inversion_keepsAlphaAndDoesNotUseItToOffsetRgb() {
        for (alpha in listOf(0f, 64f, 128f, 255f)) {
            assertColor(ReaderColorFilter.INVERTED, floatArrayOf(30f, 80f, 190f, alpha),
                225f, 175f, 65f, alpha)
        }
    }

    @Test
    fun inversion_combinesWithGrayscale() {
        val filter = ReaderColorFilter.INVERTED.copy(isGrayscale = true)
        // Android 的灰度权重：0.213 * 30 + 0.715 * 80 + 0.072 * 190 = 77.27。
        assertColor(filter, floatArrayOf(30f, 80f, 190f, 255f), 177.73f, 177.73f, 177.73f, 255f)
    }

    @Test
    fun inversion_combinesWithEyeCareBrightnessAndContrast() {
        val filter = ReaderColorFilter.INVERTED.copy(
            isBookBackground = true, brightness = -0.2f, contrast = 0.5f,
        )
        // RGB 先反色，再护眼、亮度与对比度；只在最终输出时裁剪。
        assertColor(filter, floatArrayOf(30f, 80f, 190f, 128f), 206.25f, 146.25f, 8.01f, 128f)
    }

    @Test
    fun disablingInversion_restoresOriginalColor() {
        val filter = ReaderColorFilter.INVERTED.copy(isInverted = false)
        assertColor(filter, floatArrayOf(30f, 80f, 190f, 128f), 30f, 80f, 190f, 128f)
    }

    private fun assertColor(filter: ReaderColorFilter, input: FloatArray, vararg expected: Float) {
        // 从实际 ColorMatrixColorFilter 取矩阵，按 Android 的 RGBA 变换规则计算输出。
        val matrix = ColorMatrix()
        filter.toColorFilter().getColorMatrix(matrix)
        val values = matrix.array
        val output = FloatArray(4) { row ->
            val offset = row * 5
            (input.indices.sumOf { column -> (values[offset + column] * input[column]).toDouble() }
                .toFloat() + values[offset + 4]).coerceIn(0f, 255f)
        }
        assertArrayEquals("RGBA output", expected, output, 0.001f)
    }
}
