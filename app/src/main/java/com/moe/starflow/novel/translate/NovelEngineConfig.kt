package com.moe.starflow.novel.translate

/**
 * 「翻译模型」这类配置在 prefs 里的键。
 *
 * ### 为什么需要它
 * 小说阅读器把引擎缓存在 [NovelTranslationQueue] 里（本地引擎重建一次要重载模型，
 * 不能像漫画那样每页现造）。于是**必须知道模型什么时候被改过** ——
 * 「切换模型要退出阅读器才生效」的根因就是：设置页只写了 prefs，
 * 没有任何东西通知阅读器，缓存的旧引擎就一直用下去。
 *
 * ⚠️ 以后在 `APIConfig` / `CustomStorage` 里新增引擎相关的键，要加到这里，
 * 否则又会出现"某个模型改了不生效"。
 */
object NovelEngineConfig {

    /** 选中的引擎（`Text_API`）与它下面的子选项。 */
    private val KEYS = setOf(
        "Text_API",
        "Text_AI",
        "OpenAI_Selected_Provider",
        "OpenAI_Providers",
        "Custom_Text_APIs",
    )

    /** 自定义 API 按序号存（`Custom_Text_API_0/1/2`），前缀匹配。 */
    private const val CUSTOM_PREFIX = "Custom_Text_API"

    fun affectsEngine(key: String?): Boolean =
        key != null && (key in KEYS || key.startsWith(CUSTOM_PREFIX))
}
