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
        assertEquals("こんにちは", entries[0].originalText)
        assertEquals("你好", entries[0].translatedText)
    }

    @Test
    fun cacheRoundTrip_keepsAllParagraphsAndBodyNumbersInOneRegion() {
        val source = "original first\noriginal second"
        val translation = "[1] 第一段\n\n[1] 第二段\n第三段含引用 [2] 和 emoji 😀"
        val bubble = TranslatedBubble(Rect(10, 20, 500, 900), source, translation, 0)
        val json = TranslationCacheUtils.serializeBubbleRects(listOf(bubble))
        val (originals, translations) = TranslationCacheUtils.readBubbleTexts(
            "[1] $source", "[1] $translation", json)
        assertEquals(listOf(source), originals)
        assertEquals(listOf(translation), translations)
        val restored = TranslationCacheUtils.rebuildBubblesFromCache(originals, translations, json, 16f, 0)
        assertEquals(1, restored.size)
        assertEquals(bubble.rect, restored.single().rect)
        assertEquals(source, restored.single().originalText)
        assertEquals(translation, restored.single().translatedText)
        assertTrue(restored.single().fromCache)
        assertFalse(restored.single().isInMemoryCache)
    }

    @Test
    fun cacheRoundTrip_keepsTextAttachedToItsOwnRectAcrossMultipleRegions() {
        val expected = listOf(
            TranslatedBubble(Rect(0, 0, 100, 300), "A\n[2] part of A", "A译文\n[2] 仍属A", 0),
            TranslatedBubble(Rect(150, 0, 250, 300), "B", "B译文\n续段", 0)
        )
        val json = TranslationCacheUtils.serializeBubbleRects(expected)
        val (originals, translations) = TranslationCacheUtils.readBubbleTexts(null, null, json)
        assertEquals(expected.map { it.originalText }, originals)
        assertEquals(expected.map { it.translatedText }, translations)
        val restored = TranslationCacheUtils.rebuildBubblesFromCache(emptyList(), emptyList(), json, 16f, 0)
        assertEquals(expected.map { it.rect }, restored.map { it.rect })
        assertEquals(expected.map { it.originalText }, restored.map { it.originalText })
        assertEquals(expected.map { it.translatedText }, restored.map { it.translatedText })
    }

    @Test
    fun ocrOnlyPayload_doesNotPersistDisplayTextAsATranslation() {
        val bubble = TranslatedBubble(Rect(0, 0, 100, 300), "source", "source", 0)
        val json = TranslationCacheUtils.serializeBubbleRects(listOf(bubble), includeTranslations = false)
        val (originals, translations) = TranslationCacheUtils.readBubbleTexts("[1] source", null, json)
        assertEquals(listOf("source"), originals)
        assertEquals(listOf(""), translations)
        val restored = TranslationCacheUtils.rebuildBubblesFromCache(originals, translations, json, 16f, 0)
        assertEquals("source", restored.single().originalText)
        assertEquals("", restored.single().translatedText)
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
