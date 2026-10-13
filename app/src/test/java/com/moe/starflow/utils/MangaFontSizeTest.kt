package com.moe.starflow.utils

import android.graphics.Rect
import com.moe.starflow.manga.types.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MangaFontSizeTest {
    @Test
    fun missingOcrSizeUsesTextLengthAndRegionGeometry() {
        assertEquals(
            25f,
            MangaFontSize.estimateAutoSizeCap(Rect(0, 0, 140, 250), "1234567890", TextDirection.VERTICAL_RL)!!,
            0f
        )
        assertEquals(
            30f,
            MangaFontSize.estimateAutoSizeCap(Rect(0, 0, 300, 120), "1234567890", TextDirection.HORIZONTAL)!!,
            0f
        )
    }

    @Test
    fun invalidRegionDoesNotInventFixedFontSize() {
        assertNull(MangaFontSize.estimateAutoSizeCap(Rect(0, 0, 0, 250), "文字", TextDirection.VERTICAL_RL))
        assertNull(MangaFontSize.estimateAutoSizeCap(Rect(0, 0, 100, 100), "\n", TextDirection.VERTICAL_RL))
    }
}
