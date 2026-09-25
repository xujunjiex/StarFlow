package com.moe.starflow.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语言名映射与内置 Hy-MT2 的 38 种白名单（原 HyMt2LanguagesTest 迁移）。 */
class LlamaCppLanguagesTest {

    @Test
    fun targetNames_coverCommonLanguages() {
        assertEquals("日语", LlamaCppLanguages.getTargetName("ja"))
        assertEquals("中文", LlamaCppLanguages.getTargetName("zh"))
        assertEquals("繁体中文", LlamaCppLanguages.getTargetName("zh-TW"))
        assertEquals("繁体中文", LlamaCppLanguages.getTargetName("zh-Hant"))
        assertEquals("英语", LlamaCppLanguages.getTargetName("en"))
        assertEquals("韩语", LlamaCppLanguages.getTargetName("ko"))
        assertEquals("俄语", LlamaCppLanguages.getTargetName("ru"))
    }

    @Test
    fun unknownCode_fallsBackToItself() {
        // 不在白名单内的语言不能抛异常：提示词里原样带上代码，翻译质量不保证但流程要通
        assertEquals("xx", LlamaCppLanguages.getTargetName("xx"))
        assertEquals("abc", LlamaCppLanguages.getTargetName("abc"))
    }

    @Test
    fun whitelistCoversOfficialLanguagesPlusAliases() {
        // 40 个代码 = 官方 38 种目标语言 + 两个别名（zh-Hant 同 zh-TW、tl 同 fil）
        assertEquals(40, LlamaCppLanguages.hyMt2SupportedCodes.size)
        // 不同的语言名只有 38 个：繁体中文被 zh-TW/zh-Hant 共用、菲律宾语被 fil/tl 共用
        assertEquals(38, LlamaCppLanguages.supportedNames.toSet().size)
    }

    @Test
    fun whitelistExcludesLanguagesHyMt2DoesNotSupport() {
        // 30 种 UI 语言池里 Hy-MT2 不支持的那几个，必须不在白名单内（否则会被错误放行）
        listOf("sv", "da", "no", "fi", "hu", "ro", "ne", "ca", "af").forEach {
            assertTrue("$it 不应在内置 Hy-MT2 白名单里", it !in LlamaCppLanguages.hyMt2SupportedCodes)
        }
        assertTrue("zh-TW" in LlamaCppLanguages.hyMt2SupportedCodes)
    }
}
