package com.moe.starflow.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词拼装回归守卫（原 HyMt2PromptTest 迁移 + 扩充）。
 * 纯函数部分（Hy-MT2 通道的模板替换 / 前缀切分、通用通道的 ChatML 兜底）在 JVM 上直接测；
 * 走模型自带 Jinja 模板的路径需要 native 句柄，放真机验证。
 */
class LlamaCppPromptTest {

    @Test
    fun hyMt2Channel_replacesPlaceholders() {
        val t = "将以下文本翻译为 {target_lang}，只输出结果：\n\n{source_text}"
        assertEquals(
            "将以下文本翻译为 日语，只输出结果：\n\nこんにちは",
            LlamaCppPrompt.buildHy(t, "日语", "こんにちは"),
        )
    }

    @Test
    fun hyMt2Channel_prefixIsEverythingBeforeSourceText() {
        val t = "T:{target_lang} S:{source_text}"
        // 前缀用于前缀 KV 缓存：必须恰好是 {source_text} 之前、{target_lang} 替换后的部分
        assertEquals("T:英语 S:", LlamaCppPrompt.buildHyPrefix(t, "英语"))
    }

    @Test
    fun hyMt2Channel_prefixOfTemplateWithoutPlaceholderIsWholeTemplate() {
        val t = "只翻译，不要解释。"
        assertEquals(t, LlamaCppPrompt.buildHyPrefix(t, "英语"))
    }

    @Test
    fun chatmlFallback_containsRolesAndGenerationPrompt() {
        val s = LlamaCppPrompt.chatmlFallback(
            systemPrompt = "你是翻译",
            roles = intArrayOf(0, 1, 0),
            contents = arrayOf("a", "b", "c"),
            enableThinking = false,
        )
        assertTrue(s.startsWith("<|im_start|>system\n你是翻译<|im_end|>\n"))
        assertTrue(s.contains("<|im_start|>user\na<|im_end|>\n"))
        assertTrue(s.contains("<|im_start|>assistant\nb<|im_end|>\n"))
        assertTrue(s.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun chatmlFallback_thinkingOnOmitsEmptyThinkBlock() {
        val s = LlamaCppPrompt.chatmlFallback("", intArrayOf(0), arrayOf("hi"), enableThinking = true)
        assertTrue(s.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun chatmlFallback_omitsSystemWhenBlank() {
        val s = LlamaCppPrompt.chatmlFallback("  ", intArrayOf(0), arrayOf("hi"), enableThinking = true)
        assertTrue(!s.contains("<|im_start|>system"))
    }
}
