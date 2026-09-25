package com.moe.starflow.llamacpp

/**
 * 目标语言名与内置 Hy-MT2 的语言白名单（原 `translationapi.hymt2translation.HyMt2Languages`
 * 迁移过来：那个包已随老引擎删除）。
 *
 * - [getTargetName]：ISO 639 代码 → 提示词里用的中文语言名（两条 prompt 通道都用它）；
 * - [hyMt2SupportedCodes]：内置 Hy-MT2 官方支持的 **38 种**目标语言，用于目标语言白名单置灰；
 *   用户导入的任意 GGUF **不套用**这个白名单（模型能力未知，交给用户自己选）。
 */
object LlamaCppLanguages {

    private val TARGET_NAMES: Map<String, String> = mapOf(
        "zh" to "中文",
        "zh-TW" to "繁体中文",
        "zh-Hant" to "繁体中文",  // 官方 README 用 zh-Hant，UI 语言池可能用任一 code
        "en" to "英语",
        "ja" to "日语",
        "ko" to "韩语",
        "ru" to "俄语",
        "fr" to "法语",
        "pt" to "葡萄牙语",
        "es" to "西班牙语",
        "tr" to "土耳其语",
        "ar" to "阿拉伯语",
        "th" to "泰语",
        "it" to "意大利语",
        "de" to "德语",
        "vi" to "越南语",
        "ms" to "马来语",
        "id" to "印尼语",
        "fil" to "菲律宾语",
        "tl" to "菲律宾语",
        "hi" to "印地语",
        "pl" to "波兰语",
        "cs" to "捷克语",
        "nl" to "荷兰语",
        "km" to "高棉语",
        "my" to "缅甸语",
        "fa" to "波斯语",
        "gu" to "古吉拉特语",
        "ur" to "乌尔都语",
        "te" to "泰卢固语",
        "mr" to "马拉地语",
        "he" to "希伯来语",
        "bn" to "孟加拉语",
        "ta" to "泰米尔语",
        "uk" to "乌克兰语",
        "bo" to "藏语",
        "kk" to "哈萨克语",
        "mn" to "蒙古语",
        "ug" to "维吾尔语",
        "yue" to "粤语",
    )

    val supportedNames: Collection<String>
        get() = TARGET_NAMES.values

    /**
     * Hy-MT2 官方支持的 38 种目标语言（官方 README「支持的语种」表，与 TARGET_NAMES 一一对应）。
     * 用于目标语言选择白名单：**内置 Hy-MT2** 下不在集合内的语言一律置灰（模型不支持，翻了也是垃圾）。
     * 注意 UI 语言池（nllb_text_support_languages.xml 68 种）远大于此集合，必须用白名单而非黑名单。
     */
    val hyMt2SupportedCodes: Set<String>
        get() = TARGET_NAMES.keys

    /** 取目标语言中文名；不在 38 种内时回退原代码（翻译质量不保证，接受）。 */
    fun getTargetName(code: String): String = TARGET_NAMES[code] ?: code
}
