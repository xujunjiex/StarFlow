package com.moe.starflow.novel.parser

/**
 * HTML/XHTML → 纯文本。
 *
 * ### 为什么用正则而不是 XML 解析器
 * 电子书的 XHTML **经常不是良构 XML** —— 未闭合标签、裸 `&`、未声明的命名空间实体都很常见。
 * 用 `XmlPullParser` 会直接抛异常、整章读不出来；而这里的任务只是「抽文字」，不需要文档树，
 * 正则的容错性好得多。
 *
 * ### 为什么不用 `Html.fromHtml`
 * 那是 Android 框架 API，在**纯 JVM 单测**里不可用（会抛 "not mocked"），而这些解析器必须
 * 能在普通 unit test 里直接验证。所以实体解码自己写。
 */
object HtmlTextExtractor {

    private val SCRIPT_STYLE = Regex(
        """<(script|style)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /**
     * 整个 `<head>` 段一并去掉。
     *
     * ⚠️ 必须去：`<head>` 里的 `<title>` 是**元数据**，不剥掉就会当成正文的第一个段落 ——
     * EPUB 每章 XHTML 的 head 都有 title，后果是**每一章开头都多出一行书名/章名**，
     * 而那一行还会被当成正文送去翻译。
     *
     * 只匹配闭合的 `<head>…</head>`：畸形文档（没闭合）时宁可不剥，也好过把整个正文吃掉。
     */
    private val HEAD = Regex(
        """<head\b[^>]*>.*?</head\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BLOCK_TAGS = Regex(
        """</?(p|div|h[1-6]|li|tr|blockquote|section|article|dd|dt|pre)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val IMG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("""<[^>]+>""")
    private val TITLE = Regex(
        """<title\b[^>]*>(.*?)</title>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BLANK_LINES = Regex("""\n{3,}""")
    private val INLINE_SPACE = Regex("""[ \t]+""")
    private val LINE_PADDING = Regex("""[ \t]*\n[ \t]*""")

    /** 图片占位标记。`NovelParagraphSplitter` 靠它认出「这段是图，别翻译」。 */
    const val IMAGE_MARK = "📷 [图片]"

    fun extract(html: String): String {
        if (html.isBlank()) return ""
        var s = html
        s = SCRIPT_STYLE.replace(s, "")
        s = HEAD.replace(s, "")
        s = BR.replace(s, "\n")
        s = IMG.replace(s, "\n$IMAGE_MARK\n")
        s = BLOCK_TAGS.replace(s, "\n\n")
        s = ANY_TAG.replace(s, "")
        s = decodeEntities(s)
        s = INLINE_SPACE.replace(s, " ")
        s = LINE_PADDING.replace(s, "\n")
        s = BLANK_LINES.replace(s, "\n\n")
        return s.trim()
    }

    fun titleOf(html: String): String? =
        TITLE.find(html)?.groupValues?.getOrNull(1)
            ?.let { decodeEntities(it).trim() }
            ?.takeIf { it.isNotEmpty() }

    // ===== 实体解码 =====

    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);""")

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "amp" to "&",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
    )

    /**
     * **单趟**解码所有实体。
     *
     * ⚠️ 不要写成「逐个 `replace("&amp;", "&")` 之类串行替换」：那种写法必然**二次解码**
     * —— 原文 `&amp;lt;` 想表达的是字面量 `&lt;`，串行替换会先还原 `&amp;` 得到 `&lt;`，
     * 再被 `&lt;` 规则吃掉变成 `<`，于是正文里的「`&lt;` 这四个字符」凭空变成一个尖括号。
     * 单趟用一次正则扫完，每个实体只解一次，不存在这个顺序陷阱。
     *
     * 认不出的实体原样保留（宁可显示 `&foo;`，也不要静默吞掉）。
     */
    private fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val body = m.groupValues[1]
        if (body.startsWith("#")) {
            val code = if (body.startsWith("#x", ignoreCase = true)) {
                body.drop(2).toIntOrNull(16)
            } else {
                body.drop(1).toIntOrNull()
            }
            code?.takeIf { it in 1..0x10FFFF }
                ?.let { String(Character.toChars(it)) }
                ?: m.value
        } else {
            NAMED_ENTITIES[body.lowercase()] ?: m.value
        }
    }
}
