package com.moe.starflow.novel.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「换了模型必须让缓存的引擎失效」的键守卫。
 *
 * 起因是用户报的 bug：**切换模型后必须退出阅读器才生效**。
 * 根因是队列把引擎按值缓存住了，而设置页只写 prefs、没有任何通知。
 * 阅读器现在监听默认 prefs，[NovelEngineConfig] 决定哪些键算「引擎配置」。
 *
 * ⚠️ 两个方向都要卡住：
 * - 漏一个键 → 那个模型改了不生效（又回到用户报的现象）
 * - 多算一个键 → 改个字号/语言就白重建一次引擎；本地引擎（NLLB）重建要重载模型
 */
class NovelEngineConfigTest {

    /** 设置页改模型时真正会写的键（见 `APIConfig.changeCustomPreferences` / `CustomStorage`）。 */
    @Test
    fun `模型选择相关的键都要让引擎失效`() {
        listOf(
            "Text_API",
            "Text_AI",
            "OpenAI_Selected_Provider",
            "OpenAI_Providers",
            "Custom_Text_APIs",
            "Custom_Text_API_0",
            "Custom_Text_API_2",
        ).forEach { assertTrue("$it 变了必须重建引擎", NovelEngineConfig.affectsEngine(it)) }
    }

    /** 语言是**每批现读**的（不缓存在引擎里），改它不该重建引擎。 */
    @Test
    fun `语言与排版相关的键不该重建引擎`() {
        listOf(
            "Source_Language",
            "Target_Language",
            "novel_font_size",
            "novel_paragraph_spacing",
            "reader_reader_mode",
            "status_overlay_enabled",
            null,
        ).forEach { assertFalse("$it 不该触发引擎重建", NovelEngineConfig.affectsEngine(it)) }
    }
}
