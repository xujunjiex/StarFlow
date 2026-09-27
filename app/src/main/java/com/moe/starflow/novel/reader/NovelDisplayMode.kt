package com.moe.starflow.novel.reader

/**
 * 正文显示三态。
 *
 * 比参考实现（只有「译文」/「双语」两态）多一个「原文」：用户想对照原书、
 * 或者觉得某段翻译别扭时，需要一个不看译文的开关。
 *
 * ⚠️ **未翻译的段落在任何模式下都显示原文** —— 译文是分批增量回写的，逐批渐进替换；
 * 显示空白会让用户以为内容丢了。
 */
enum class NovelDisplayMode { TRANSLATED, ORIGINAL, BILINGUAL }

/** prefs 编解码。存字符串而不是枚举序号：序号会随枚举顺序变化而错位。 */
object NovelDisplayModeCodec {

    fun fromPref(v: String?): NovelDisplayMode = when (v) {
        "1" -> NovelDisplayMode.ORIGINAL
        "2" -> NovelDisplayMode.BILINGUAL
        else -> NovelDisplayMode.TRANSLATED
    }

    fun toPref(m: NovelDisplayMode): String = when (m) {
        NovelDisplayMode.TRANSLATED -> "0"
        NovelDisplayMode.ORIGINAL -> "1"
        NovelDisplayMode.BILINGUAL -> "2"
    }

    /** 循环切换：译文 → 原文 → 双语 → 译文。 */
    fun next(m: NovelDisplayMode): NovelDisplayMode = when (m) {
        NovelDisplayMode.TRANSLATED -> NovelDisplayMode.ORIGINAL
        NovelDisplayMode.ORIGINAL -> NovelDisplayMode.BILINGUAL
        NovelDisplayMode.BILINGUAL -> NovelDisplayMode.TRANSLATED
    }
}

/**
 * 决定每段**当前要显示什么文本**。
 *
 * ### 这是分页与绘制的唯一共同来源
 * 这个函数有**两个**消费方，而且它们必须永远一致：
 * - `NovelChapterRepository` 用它拼出「显示文本」再去分页
 * - `NovelPageView` / `NovelScrollAdapter` 用它拿到同一份文本再按字符区间切片绘制
 *
 * ⚠️ 两者用不同的文本就会出真问题：
 * - 分页用原文、绘制用译文 → **译文比原文长，页面溢出、最后一截被裁掉**
 * - 双语模式下用原文的字符区间去切译文 → **切出来的是错位的**（两串长度不同）
 *
 * 所以：**分页与绘制都只认 [displayText] 的输出**，字符区间永远在同一个字符串上。
 *
 * ### 双语模式的呈现取舍
 * 双语返回「原文 + 换行 + 译文」这样一个**单一字符串**，因此两行共用同一套字号与颜色，
 * 不能把原文画成灰色小字。这是为了「一套字符区间走到底」而付出的代价：
 * 要让原文小一号就得为每段维护两套排版与两套区间，分页逻辑会复杂一倍且更易错。
 */
object NovelPageBilingual {

    /** 双语模式下原文与译文之间的分隔。 */
    const val BILINGUAL_SEPARATOR = "\n"

    fun displayText(original: String, translated: String?, mode: NovelDisplayMode): String = when {
        translated.isNullOrEmpty() -> original
        mode == NovelDisplayMode.ORIGINAL -> original
        mode == NovelDisplayMode.BILINGUAL -> "$original$BILINGUAL_SEPARATOR$translated"
        else -> translated
    }
}
