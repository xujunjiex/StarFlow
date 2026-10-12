package com.moe.starflow.data

import android.graphics.Rect
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TranslatedBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationCacheUtilsTest {

    @Test
    fun countInfoBits_sumsBitCounts() {
        assertEquals(0, TranslationCacheUtils.countInfoBits(0L, 0L, 0L, 0L))
        assertEquals(4, TranslationCacheUtils.countInfoBits(1L, 1L, 1L, 1L))
        assertEquals(64, TranslationCacheUtils.countInfoBits(0xFFFFL, 0xFFFFL, 0xFFFFL, 0xFFFFL))  // 每段 16 bits × 4
    }

    @Test
    fun countInfoBits_longArray() {
        assertEquals(3, TranslationCacheUtils.countInfoBits(longArrayOf(1L, 1L, 1L, 0L)))
    }

    @Test
    fun isSparseHash_lowInfoBits_returnsTrue() {
        // 全 0 → infoBits=0 < 16 → 稀疏
        assertTrue(TranslationCacheUtils.isSparseHash(0L, 0L, 0L, 0L))
        // 每段 3 bits → 12 < 16 → 稀疏
        assertTrue(TranslationCacheUtils.isSparseHash(7L, 7L, 7L, 7L))
    }

    @Test
    fun isSparseHash_normalInfoBits_returnsFalse() {
        // 每段 8 bits → 32 >= 16 → 非稀疏
        assertFalse(TranslationCacheUtils.isSparseHash(0xFFL, 0xFFL, 0xFFL, 0xFFL))
    }

    @Test
    fun parseIndexedTextList_parsesNumberedLines() {
        val result = TranslationCacheUtils.parseIndexedTextList("[1] 你好\n[2] 世界")
        assertEquals(listOf("你好", "世界"), result)
    }

    @Test
    fun parseIndexedTextList_emptyOrInvalid() {
        assertEquals(emptyList<String>(), TranslationCacheUtils.parseIndexedTextList(null))
        assertEquals(emptyList<String>(), TranslationCacheUtils.parseIndexedTextList(""))
        assertEquals(emptyList<String>(), TranslationCacheUtils.parseIndexedTextList("无编号文本"))
    }

    @Test
    fun serializeAndParseBubbleRects_roundTrip() {
        val bubbles = listOf(
            TranslatedBubble(
                rect = Rect(10, 20, 110, 220),
                originalText = "こんにちは",
                translatedText = "你好",
                fontSize = 16f,
                direction = TextDirection.VERTICAL_RL,
                backgroundColor = 0,
                angle = -7.5f,
                centerX = 55f,
                centerY = 115f
            )
        )
        val json = TranslationCacheUtils.serializeBubbleRects(bubbles)
        val entries = TranslationCacheUtils.parseBubbleEntriesJson(json, 12f)
        assertEquals(1, entries.size)
        assertEquals(Rect(10, 20, 110, 220), entries[0].rect)
        assertEquals(16f, entries[0].fontSize, 0.01f)
        assertEquals(TextDirection.VERTICAL_RL, entries[0].direction)
        assertEquals(-7.5f, entries[0].angle, 0.01f)
        assertEquals(55f, entries[0].centerX, 0.01f)
        assertEquals(115f, entries[0].centerY, 0.01f)
    }

    @Test
    fun rebuildBubblesFromCache_reconstructs() {
        val bubbles = TranslationCacheUtils.rebuildBubblesFromCache(
            originals = listOf("abc"),
            translations = listOf("xyz"),
            bubbleRectsJson = "[{\"l\":0,\"t\":0,\"r\":10,\"b\":20,\"fs\":14,\"dir\":\"HORIZONTAL\"}]",
            defaultFontSize = 12f,
            bgColor = 0
        )
        assertEquals(1, bubbles.size)
        assertEquals("abc", bubbles[0].originalText)
        assertEquals("xyz", bubbles[0].translatedText)
        assertEquals(14f, bubbles[0].fontSize, 0.01f)
        assertEquals(TextDirection.HORIZONTAL, bubbles[0].direction)
        assertEquals(0f, bubbles[0].angle, 0f) // legacy rows did not persist tilt
    }
}
