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
 *
 * ⚠️ 例外：`<script>` / `<style>` / `<head>` 的整段剥除与「删掉所有标签」是**手写扫描**，
 * 不是正则 —— 理由与容错无关，是复杂度（见下方「剥标签」那一节的注释）。
 */
object HtmlTextExtractor {

    private val BLOCK_TAGS = Regex(
        """</?(p|div|h[1-6]|li|tr|blockquote|section|article|dd|dt|pre)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    /**
     * 图片标签。
     *
     * ⚠️ 不只有 `<img>`：EPUB3 的封面页标准写法是 `<svg><image xlink:href="cover.jpg"/></svg>`
     * （SVG 当容器、真正的位图挂在 `<image>` 上），只认 `<img>` 的话**封面永远显示不出来** ——
     * 而那正是用户报的「封面、插图打不开」里最典型的一张。
     */
    private val IMG = Regex("""<(?:img|image)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex(
        """<title\b[^>]*>(.*?)</title>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BLANK_LINES = Regex("""\n{3,}""")
    private val INLINE_SPACE = Regex("""[ \t]+""")
    private val LINE_PADDING = Regex("""[ \t]*\n[ \t]*""")

    /** 图片占位标记。`NovelParagraphSplitter` 靠它认出「这段是图，别翻译」。 */
    const val IMAGE_MARK = "📷 [图片]"

    /**
     * 一段标记语言抽出来的东西：正文 + 本章引用到的图片。
     *
     * @param text 纯文本正文（图片在文里是 [IMAGE_MARK]，**每张图单独成段**）
     * @param imageSources 图片地址（`src` / `xlink:href` / `href` 的**原样**值，未做相对路径解析），
     *   **按文档顺序**，与 [text] 里的图片标记**一一对应**：第 i 个标记就是第 i 项。
     *   标签上没写地址的那张也占一个位置（空串），这样两边的下标永远对得上 ——
     *   少一项就会让后面所有插图**整体错位一张**。
     */
    class Extracted(val text: String, val imageSources: List<String>)

    fun extract(html: String): String = extractWithImages(html).text

    /**
     * 正文 + 图片地址（见 [Extracted]）。
     *
     * ⚠️ 图片标记**前后各留一个空行**：这样它必定单独成段（`NovelParagraphSplitter` 只按空行
     * 分段）。写成单换行的话，`<p>文字<img/></p>` 会得到「文字\n📷 [图片]」**一段**，
     * 而它被判成 IMAGE 段 → **那半句文字凭空消失**（既没显示成文字，也没显示成图）。
     */
    fun extractWithImages(html: String): Extracted {
        if (html.isBlank()) return Extracted("", emptyList())
        var s = html
        // 顺序与旧的正则版一致：先 script/style，再 head
        s = stripClosedBlock(s, "script")
        s = stripClosedBlock(s, "style")
        // ⚠️ head 必须整段剥掉：里面的 `<title>` 是**元数据**，不剥就会当成正文的第一个段落 ——
        // EPUB 每章 XHTML 的 head 都有 title，后果是**每一章开头都多出一行书名/章名**，而那一行
        // 还会被当成正文送去翻译。只剥闭合的 `<head>…</head>`：畸形文档（没闭合）时宁可不剥，
        // 也好过把整个正文吃掉。
        s = stripClosedBlock(s, "head")
        s = BR.replace(s, "\n")
        val sources = ArrayList<String>()
        s = IMG.replace(s) { m ->
            // 没写地址的图也占一格（空串）：下标对齐比"少一项"重要得多
            sources.add(imageSourceOf(m.value))
            "\n\n$IMAGE_MARK\n\n"
        }
        s = BLOCK_TAGS.replace(s, "\n\n")
        s = stripTags(s)
        s = decodeEntities(s)
        s = INLINE_SPACE.replace(s, " ")
        s = LINE_PADDING.replace(s, "\n")
        s = BLANK_LINES.replace(s, "\n\n")
        return Extracted(s.trim(), sources)
    }

    /**
     * 取**第一个**图片标签的地址（SVG 包装用：`<img src="cover.svg">` 里那层
     * `<image xlink:href="cover.jpg">` 才是能画的位图）。没有返回 null。
     */
    fun firstImageSource(markup: String): String? =
        IMG.find(markup)?.let { m -> imageSourceOf(m.value).takeIf { it.isNotEmpty() } }

    /**
     * 图片标签上的地址：`src`（`<img>`）→ `xlink:href`（SVG `<image>`）→ `href`。
     *
     * ⚠️ 顺序不能反：`href` 会**命中 `xlink:href` 里那一截**（前面是 `:`，属于"词边界"），
     * 于是 `<image xlink:href="a.jpg">` 被解析成……其实是同一个值，但
     * `<image xlink:href="a.jpg" href="b.jpg">` 这种写法就会取错。
     */
    private fun imageSourceOf(tag: String): String {
        for (attr in IMAGE_ATTRS) {
            attrValue(tag, attr)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private val IMAGE_ATTRS = listOf("src", "xlink:href", "href")

    /**
     * ⚠️ `ConcurrentHashMap` 而不是 `HashMap`：本 object 会被多个导入/阅读任务并发调用
     * （类注释里那条「无状态」约束说的是解析状态，这里是纯函数的缓存），并发写 `HashMap` 会坏表。
     */
    private val attrPatterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * 取属性值（双引号 / 单引号 / 裸值三种写法）。
     *
     * ⚠️ 属性名前的边界必须是 `(?<![\w-])` 而不是 `\b`：`data-src` 里的 `src`
     * （懒加载图片的标准写法）在 `\b` 下**会命中**，于是拿到的是占位符而不是真图。
     */
    private fun attrValue(tag: String, name: String): String? {
        val re = attrPatterns.getOrPut(name) {
            Regex(
                """(?<![\w-])${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
                RegexOption.IGNORE_CASE,
            )
        }
        val m = re.find(tag) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }
    }

    fun titleOf(html: String): String? =
        TITLE.find(html)?.groupValues?.getOrNull(1)
            ?.let { decodeEntities(it).trim() }
            ?.takeIf { it.isNotEmpty() }

    // ===== 剥标签：手写线性扫描 =====

    // 这几件事**不能交给正则**。
    //
    // 旧实现是 `<(script|style)\b[^>]*>.*?</\1>` + `<[^>]+>`：`\1` 是反向引用，引擎没法为它
    // 预扫，每个 `<script` / `<style` 起始位置都要把后面整篇重扫一遍去找闭合标签；`<[^>]+>`
    // 同理 —— 没有「后面必须有个 `>`」的预扫，每个 `<` 都要往后扫穿再回溯。于是**一段堆了
    // 5 万个未闭合 `<script ` 的畸形 xhtml 就是 O(n²)**：实测旧实现在 400KB 输入上要 21 秒，
    // 导入 / 翻页线程被钉死，用户看到的就是「卡住」。
    //
    // 手写扫描每个位置只往后看一次。刻意不依赖正则引擎的优化：Android 上是 ICU 的引擎、
    // 桌面 JVM 上另有一套，两边的复杂度优化都不一样（实测桌面 JVM 对那个反向引用反而不慢），
    // 「快不快」不能靠某个引擎碰巧做了预扫。

    /**
     * 删掉 [tag] 的**闭合块**（`<tag …>…</tag>`）。
     *
     * 找不到闭合标签时**什么都不删**（畸形文档宁可不剥，也不能把整篇正文吃掉）。
     *
     * 与旧正则唯一的差异：闭合标签多一个空格（`</script >`）现在也算闭合 —— 旧的 `<head\s*>`
     * 本来就宽容（所以 `</head >` 一直能剥），这里只是把 script/style 统一过来；完本文档
     * （`</script>`）两边行为完全一致。
     */
    internal fun stripClosedBlock(html: String, tag: String): String {
        val open = "<$tag"
        val close = "</$tag"
        var from = 0
        var search = 0
        var out: StringBuilder? = null
        while (true) {
            val openAt = indexOfTag(html, open, search)
            if (openAt < 0) break
            val openEnd = html.indexOf('>', openAt + open.length)
            // 开标签自己都没结束（后面连 `>` 都没有）→ 不可能再有合法的闭合块
            if (openEnd < 0) break
            val closeAt = indexOfTag(html, close, openEnd + 1)
            if (closeAt < 0) break
            val closeEnd = html.indexOf('>', closeAt + close.length)
            if (closeEnd < 0) break
            // 第一次真要删才建 builder：多数章节压根没有 script / head，别白拷一整篇
            val sb = out ?: StringBuilder(html.length).also { out = it }
            sb.append(html, from, openAt)
            from = closeEnd + 1
            search = from
        }
        val sb = out ?: return html
        sb.append(html, from, html.length)
        return sb.toString()
    }

    /**
     * 删掉所有 `<…>` 标签（等价于原来的 `<[^>]+>`）。
     *
     * `<>`（尖括号里一个字符都没有）按原正则**不算**标签，原样保留。
     */
    private fun stripTags(html: String): String {
        var lt = html.indexOf('<')
        if (lt < 0) return html
        val out = StringBuilder(html.length)
        var from = 0
        while (lt >= 0) {
            val gt = html.indexOf('>', lt + 1)
            if (gt < 0) break
            if (gt == lt + 1) {
                lt = html.indexOf('<', lt + 1)
                continue
            }
            out.append(html, from, lt)
            from = gt + 1
            lt = html.indexOf('<', from)
        }
        out.append(html, from, html.length)
        return out.toString()
    }

    /**
     * 找不区分大小写的 [needle]，且其后一个字符不是 `\w` —— 等价于正则里标签名后的 `\b`，
     * 免得把 `<scriptx>` 当成 `<script`。
     */
    private fun indexOfTag(html: String, needle: String, from: Int): Int {
        var i = from
        while (true) {
            i = html.indexOf(needle, i, ignoreCase = true)
            if (i < 0) return -1
            val after = i + needle.length
            if (after >= html.length || !isWordChar(html[after])) return i
            i++
        }
    }

    /** 等价于 Java 正则默认的 `\w`（ASCII）：字母 / 数字 / 下划线。 */
    private fun isWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

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
    internal fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
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
