package com.moe.starflow.manga

import android.graphics.Rect
import com.moe.starflow.manga.types.BubbleRegion
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.CustomPreference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「不需要翻译的气泡」短路：纯符号 + **纯数字（页码）**。
 *
 * 纯数字段落除了白花一次请求，更是 2026-09-25 那批「整批译文前移一位 + 末条空白」的触发源：
 * 模型对纯数字条目的译文就是原样一个数字行（`330`），而按行降级解析里这种行最容易被当成
 * 编号前缀整行吃掉。这里锁住两点：判据本身，以及**它真的没被送进翻译请求**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateUtilsPassthroughTest {

    // ===== 判据 =====

    @Test
    fun numericOnly_acceptsPageNumbers() {
        listOf("330", "331", "12", "0", "2026", "３３０", "1,000", "3.5", "12-13", "2026/09/25", "1 : 2").forEach {
            assertTrue("'$it' 应判为纯数字", TranslateUtils.isNumericOnlyText(it))
        }
    }

    @Test
    fun numericOnly_rejectsThingsThatNeedTranslation() {
        listOf(
            "100%", "$100", "3F", "第3话", "SHOCK!", "...", "", "   ", "CH 3", "330 号",
            "1人", "2、3人", "3.5倍",
        ).forEach {
            assertFalse("'$it' 不该判为纯数字", TranslateUtils.isNumericOnlyText(it))
        }
    }

    /** 纯符号仍然走原来的符号短路（两条判据不能互相吞并）。 */
    @Test
    fun symbolOnly_stillSeparateFromNumeric() {
        assertTrue(TranslateUtils.isSymbolOnlyText("!!!"))
        assertFalse(TranslateUtils.isNumericOnlyText("!!!"))
        assertTrue(TranslateUtils.isNumericOnlyText("330"))
        assertFalse(TranslateUtils.isSymbolOnlyText("330"))
    }

    // ===== 行为：纯数字气泡根本不进翻译请求 =====

    /** 记录收到的每段原文，用于断言「哪些气泡被送去翻译了」。 */
    private class RecordingTranslator : TranslationTextAPI {
        val received = mutableListOf<String>()

        override fun getTranslation(
            text: String, sourceLanguage: String, targetLanguage: String,
            callback: (TranslationResult) -> Unit,
        ) {
            received += text
            callback(TranslationResult.Success("译:$text"))
        }

        override fun cancelTranslation() {}
        override fun release() {}
    }

    private fun bubble(text: String, top: Int): BubbleRegion = BubbleRegion(
        rect = Rect(0, top, 100, top + 40),
        texts = listOf(text),
        fontSize = 20f,
        direction = TextDirection.HORIZONTAL,
    )

    @Test
    fun numericBubble_isNotSentToTranslator_andKeepsOriginalText() = runBlocking {
        val translator = RecordingTranslator()
        val bubbles = listOf(
            bubble("330", top = 0),          // 页码：不该送出去
            bubble("HELLO", top = 100),
            bubble("!!", top = 200),         // 纯符号：同样不送
            bubble("WORLD", top = 300),
        )

        val out = TranslateUtils.translateBubbles(
            translator = translator,
            bubbles = bubbles,
            sourceLang = "en",
            targetLang = "zh",
            prefs = CustomPreference.getInstance(RuntimeEnvironment.getApplication()),
        )

        assertEquals("只有正文两句该被送去翻译", listOf("HELLO", "WORLD"), translator.received)
        assertEquals(4, out.size)
        // ⚠️ 顺序沿用既有实现：短路气泡（纯符号/纯数字）整体排在译文前面，不与原页顺序交错。
        // 渲染按各自 rect 落位、存库三列（sourceText/translatedText/bubbleRects）平行写入，顺序无影响。
        assertEquals("330", out[0].translatedText)   // 页码原文回填
        assertEquals("!!", out[1].translatedText)
        assertEquals("译:HELLO", out[2].translatedText)
        assertEquals("译:WORLD", out[3].translatedText)
    }

    /** 整页只有纯数字/纯符号时，不该发任何请求，也不能抛错。 */
    @Test
    fun onlyNumericBubbles_makesNoRequest() = runBlocking {
        val translator = RecordingTranslator()
        val out = TranslateUtils.translateBubbles(
            translator = translator,
            bubbles = listOf(bubble("330", top = 0), bubble("...", top = 100)),
            sourceLang = "en",
            targetLang = "zh",
            prefs = CustomPreference.getInstance(RuntimeEnvironment.getApplication()),
        )

        assertTrue(translator.received.isEmpty())
        assertEquals(listOf("330", "..."), out.map { it.translatedText })
    }
}
