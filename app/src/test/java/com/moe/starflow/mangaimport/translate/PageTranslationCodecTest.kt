package com.moe.starflow.mangaimport.translate

import android.graphics.Color
import android.graphics.Rect
import com.moe.starflow.manga.types.TranslatedBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslationCodecTest {

    private fun bubble(text: String, x: Int) = TranslatedBubble(
        rect = Rect(x, 10, x + 100, 90),
        originalText = "orig-$text",
        translatedText = "trans-$text",
        backgroundColor = Color.TRANSPARENT,
    )

    @Test
    fun numberedTextsKeepOrderAndNumbers() {
        val bubbles = listOf(bubble("A", 0), bubble("B", 50))
        assertEquals("[1] orig-A\n[2] orig-B", PageTranslationCodec.sourceText(bubbles))
        assertEquals("[1] trans-A\n[2] trans-B", PageTranslationCodec.translatedText(bubbles))
    }

    @Test
    fun emptyListYieldsBlank() {
        assertTrue(PageTranslationCodec.sourceText(emptyList()).isBlank())
        assertEquals("", PageTranslationCodec.translatedText(emptyList()))
    }
}