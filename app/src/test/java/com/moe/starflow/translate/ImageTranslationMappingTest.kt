package com.moe.starflow.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTranslationMappingTest {

    @Test
    fun `scaledToOriginal maps rect and font size`() {
        val payload = ImageTranslationPayload(
            blocks = listOf(
                ImageTextBlock(10, 20, 110, 60, "a", "甲", vertical = false, lineCount = 1, fontSize = 20f),
            ),
            text = "甲",
        )

        val mapped = payload.scaledToOriginal(2f)

        val block = mapped.blocks.single()
        assertEquals(20, block.left)
        assertEquals(40, block.top)
        assertEquals(220, block.right)
        assertEquals(120, block.bottom)
        assertEquals(40f, block.fontSize, 0.01f)
        assertEquals("甲", block.translatedText)
    }

    @Test
    fun `scale one returns the same payload`() {
        val payload = ImageTranslationPayload(emptyList(), "x")
        assertEquals(payload, payload.scaledToOriginal(1f))
    }
}
