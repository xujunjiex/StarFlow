package com.moe.starflow.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplatesTest {

    @Test
    fun `七套模板定义完整`() {
        assertEquals(7, ChatTemplates.all.size)
        val ids = ChatTemplates.all.map { it.id }
        assertEquals(
            listOf("default", "terminology", "style", "personalization", "delimiters", "structured1", "structured2"),
            ids
        )
        ChatTemplates.all.forEach { t ->
            assertTrue("${t.id} zh 非空", t.zh.isNotBlank())
            assertTrue("${t.id} en 非空", t.en.isNotBlank())
            assertTrue("${t.id} 含 {source_text}", t.zh.contains("{source_text}"))
            assertTrue("${t.id} variableHints 非空", t.variableHints.isNotEmpty())
        }
    }

    @Test
    fun `默认翻译模板含目标语言占位符`() {
        val default = ChatTemplates.all.first { it.id == "default" }
        assertTrue(default.zh.contains("{target_lang}"))
        assertTrue(default.variableHints.any { it.first == "{target_lang}" })
    }

    @Test
    fun `默认系统提示非空`() {
        assertTrue(ChatTemplates.DEFAULT_SYSTEM.isNotBlank())
    }
}
