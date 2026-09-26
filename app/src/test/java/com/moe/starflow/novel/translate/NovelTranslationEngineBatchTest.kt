package com.moe.starflow.novel.translate

import com.moe.starflow.translate.TranslationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「一次一批」的守卫。
 *
 * 队列靠「一批一批地要」实现「点一次翻一批」与「增量配额 x 批」；
 * 如果引擎在这里偷偷把整章翻完，这两个功能就都没法计数了 —— 所以必须钉住
 * **一次 translateBatch = 一次请求**。
 */
class NovelTranslationEngineBatchTest {

    private fun para(index: Int, text: String) = NovelParagraph(index, NovelParagraphType.TEXT, text)

    /** 假引擎：把请求里的每个 `[n]` 都回成「译n」，并记下请求了几次。 */
    private class FakeTranslator : NovelTextTranslator {
        val prompts = mutableListOf<String>()

        override fun translate(
            prompt: String,
            sourceLang: String,
            targetLang: String,
            callback: (TranslationResult) -> Unit,
        ) {
            prompts += prompt
            val reply = Regex("""\[(\d+)]""").findAll(prompt)
                .joinToString("\n") { "[${it.groupValues[1]}] 译${it.groupValues[1]}" }
            callback(TranslationResult.Success(reply))
        }
    }

    @Test
    fun `翻一批只发一次请求且按段号回填`() = runBlocking {
        val fake = FakeTranslator()
        val engine = NovelTranslationEngine(fake)
        val paras = listOf(para(0, "a"), para(1, "b"), para(2, "c"))

        val got = engine.translateBatch(paras, listOf(1, 2), "en", "zh")

        assertEquals(mapOf(1 to "译1", 2 to "译2"), got)
        assertEquals("一批只发一次请求", 1, fake.prompts.size)
    }

    /** 空批不发请求（否则白烧一次额度，用户看不到任何变化）。 */
    @Test
    fun `空批不发请求`() = runBlocking {
        val fake = FakeTranslator()
        val engine = NovelTranslationEngine(fake)

        assertEquals(emptyMap<Int, String>(), engine.translateBatch(emptyList(), emptyList(), "en", "zh"))
        assertEquals(0, fake.prompts.size)
    }

    /** 请求里带的段号必须**正好是这一批**，不能把整章塞进去。 */
    @Test
    fun `请求里只带这一批的段号`() = runBlocking {
        val fake = FakeTranslator()
        val engine = NovelTranslationEngine(fake)
        val paras = (0 until 20).map { para(it, "text$it") }

        engine.translateBatch(paras, listOf(5, 6, 7), "en", "zh")

        val prompt = fake.prompts.single()
        assertEquals(true, prompt.contains("[5]"))
        assertEquals(true, prompt.contains("[7]"))
        assertEquals("第 4 段不该在请求里", false, prompt.contains("[4]"))
        assertEquals("第 8 段不该在请求里", false, prompt.contains("[8]"))
    }

    /** 整批失败（模型回空）→ 降级逐段重试一次，能把没被审查卡住的段救回来。 */
    @Test
    fun `整批失败降级逐段重试一次`() = runBlocking {
        val calls = mutableListOf<String>()
        val flaky = object : NovelTextTranslator {
            override fun translate(
                prompt: String,
                sourceLang: String,
                targetLang: String,
                callback: (TranslationResult) -> Unit,
            ) {
                calls += prompt
                // 整批（>1 段）一律回空；单段才回译文
                val ids = Regex("""\[(\d+)]""").findAll(prompt).map { it.groupValues[1] }.toList()
                val reply = if (ids.size > 1) "" else "[${ids.first()}] 译${ids.first()}"
                callback(TranslationResult.Success(reply))
            }
        }

        val got = NovelTranslationEngine(flaky).translateBatch(
            listOf(para(0, "a"), para(1, "b")), listOf(0, 1), "en", "zh",
        )

        assertEquals(mapOf(0 to "译0", 1 to "译1"), got)
        assertEquals("整批 1 次 + 逐段 2 次", 3, calls.size)
    }
}
