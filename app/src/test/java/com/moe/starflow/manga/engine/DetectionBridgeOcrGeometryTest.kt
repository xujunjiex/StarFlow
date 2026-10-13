package com.moe.starflow.manga.engine

import android.graphics.Rect
import com.moe.starflow.manga.types.TextBlockInfo
import com.moe.starflow.manga.types.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetectionBridgeOcrGeometryTest {
    @Test
    fun ocrToBubbleRegionsKeepsDetectedCharacterSizeForMergedVerticalColumns() {
        val block = TextBlockInfo(
            text = "今日晴朗\n天空湛蓝",
            boundingBox = Rect(20, 10, 160, 260),
            cornerPoints = null,
            isVertical = true,
            fontSize = 24f
        )

        val bubble = DetectionBridge.ocrToBubbleRegions(listOf(block), TextDirection.VERTICAL_LR).single()

        assertEquals(24f, bubble.fontSize, 0f)
        assertEquals(TextDirection.VERTICAL_LR, bubble.direction)
    }

    @Test
    fun missingCharacterSizeUsesDynamicGeometryEstimateInsteadOfMergedBounds() {
        val block = TextBlockInfo(
            text = "两列文字",
            boundingBox = Rect(20, 10, 160, 260),
            cornerPoints = null,
            isVertical = true,
            fontSize = null
        )

        val bubble = DetectionBridge.ocrToBubbleRegions(listOf(block), TextDirection.VERTICAL_LR).single()

        assertEquals(62.5f, bubble.fontSize, 0f)
    }
}
