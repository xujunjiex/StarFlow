package com.moe.starflow.novel.parser

/**
 * 一章在原文中的位置。[charStart], [charEnd] 是**字符**下标（前闭后开）。
 *
 * 用字符下标而不是字节下标：编码探测之后手上已经是 `String`，再折算回字节偏移还得按
 * 探测结果重新编码对齐，而换行归一化（CRLF→LF）会让两者的对应关系更难维持。
 */
data class TxtChapter(val title: String, val charStart: Int, val charEnd: Int)

/**
 * TXT 分章。
 *
 * ⚠️ 网文标题格式极其发散，这里是**启发式**：命中即切，命中不了按字数兜底。
 *
 * 两条容易写错的地方：
 * 1. **正则必须锚定行首**（`^\s*第…`）。不锚定的话正文里「他在第 3 章看到过这个说法」
 *    这种句子会被当成章节标题，一本书瞬间变成几百个碎片章。
 * 2. **首个标记之前的内容不能丢**。很多 txt 开头有作者的话/简介/免责声明，若只按标记切，
 *    这段会被静默丢掉 —— 与「章节区间完整覆盖正文」的硬约束冲突。
 */
object TxtChapterSplitter {

    /** 章节标题最长字符数。超出视为正文里偶然出现的「第N章」表述，不当作标题。 */
    private const val MAX_TITLE_LEN = 40

    /** 首个章节标记之前的内容若达到这个字数，单独成节（标题为 [PREFACE_TITLE]）。 */
    private const val PREFACE_TITLE = "前言"

    private val PATTERNS: List<Regex> = listOf(
        // 第X章 / 第X节 / 第X话 / 第X回 / 第X卷 / 第X篇，可带分隔符与标题
        Regex("""^\s*(第\s*[零一二三四五六七八九十百千万两0-9]+\s*[章节話话回卷篇])(\s*[：:、.．\-—]?\s*.{0,30})$"""),
        // 特殊章名
        Regex("""^\s*(序章|序言|序|楔子|引子|前言|后记|尾声|终章|结局|番外)(\s*[：:、.．\-—]?\s*.{0,30})?$"""),
        // 英文 Chapter N
        Regex("""^\s*(Chapter\s+\d+)(\s*[：:、.．\-—]?\s*.{0,30})$""", RegexOption.IGNORE_CASE),
    )

    /** 整行被方括号包住（【】/[]）的形态。内容与判据见 [matchBracketTitle]。 */
    private val BRACKET_LINE = Regex("""^\s*([\[【])(.{1,30})([\]】])\s*$""")

    /**
     * 方括号里的内容必须**像卷/章标签**才算标题：含「卷 / 章 / 节 / 回 / 话 / 篇」这类量词
     * （中文 + 韩文 장/절/권/화/회/편 + 英文 Chapter），或本身就是序章/楔子/番外/后记这类特殊章名。
     *
     * ⚠️ 这条判据是修 bug 加的：旧规则只要求「整行带【】且 ≤30 字」，于是一句被括起来的正文
     * （`【他心想】`）也被当成标题 —— 目录被这种短行切成一堆碎片章。
     * 真正的 `【卷一】` / `【第三章】` / `【卷之二】` / `[제1장 등불]` 照旧命中
     * （韩文那条是 `novel-fixtures/long-ko.txt` 的真实形态，不加就会让整本韩文书塌成一章）。
     *
     * ⚠️ 关键词是**白名单**：没列到的语言里「括号包着的章名」会漏判 —— 这一侧的代价是
     * 「那一行留在正文里」（内容不丢），比把目录打碎轻得多，所以宁可漏。
     */
    private val BRACKET_LABEL_HINT = Regex(
        """[卷章节節回話话篇部]|序|楔|番外|后记|後記|尾声|终章|終章|结局|結局|引子|前言""" +
            """|장|절|권|화|회|편|[Cc]hapter"""
    )

    /**
     * 句子标点：括号里出现这些说明括的是一句**话**，不是标题。
     *
     * ⚠️ 与 [BRACKET_LABEL_HINT] 是两道**独立**的闸门：`【他说，你好】` 靠这一条拦下，
     * `【他心想】` 靠上一条拦下。只留一道都会漏。
     * 破折号 / 波浪号（`——`、`～`）**不算**句读：它们在「第1～5章」这类真标题里也会出现。
     */
    private val BRACKET_PROSE_PUNCT = setOf(
        '。', '！', '？', '，', '、', '；', '：', '…',
        '.', ',', '!', '?', ';', ':',
    )

    fun split(text: String): List<TxtChapter> {
        if (text.isBlank()) return emptyList()
        val starts = findChapterStarts(text)
        return if (starts.isEmpty()) wholeBook(text) else buildFromStarts(text, starts)
    }

    /**
     * 找出所有章节起始位置（行首下标 + 标题）。
     * 独立成函数是为了让 [TxtParser] 之类需要单独复用同一份判定的地方不另写一套正则而漂移。
     */
    fun findChapterStarts(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) text.length else nl
            val line = text.substring(lineStart, lineEnd).trim()
            if (line.isNotEmpty() && line.length <= MAX_TITLE_LEN) {
                matchTitle(line)?.let { out.add(lineStart to it) }
            }
            if (nl < 0) break
            lineStart = nl + 1
        }
        return out
    }

    private fun matchTitle(line: String): String? {
        for (p in PATTERNS) {
            val m = p.find(line) ?: continue
            val title = m.value.trim().replace(Regex("""[ \t]+"""), " ")
            if (title.isNotEmpty()) return title
        }
        return matchBracketTitle(line)
    }

    /**
     * 方括号标题：括号必须**配对**（`【…】` / `[…]`，混着来的不算），
     * 内容不含句读标点、且看着像卷/章标签（见 [BRACKET_LABEL_HINT]）。
     *
     * 返回的是**整行（含括号）**：目录显示的就是原文那一行的样子，去掉括号反而要额外解释规则。
     */
    private fun matchBracketTitle(line: String): String? {
        val m = BRACKET_LINE.find(line) ?: return null
        val open = m.groupValues[1]
        val inner = m.groupValues[2]
        val close = m.groupValues[3]
        val paired = (open == "[" && close == "]") || (open == "【" && close == "】")
        if (!paired) return null
        if (inner.any { it in BRACKET_PROSE_PUNCT }) return null
        if (!BRACKET_LABEL_HINT.containsMatchIn(inner)) return null
        return line.trim().replace(Regex("""[ \t]+"""), " ")
    }

    private fun buildFromStarts(text: String, starts: List<Pair<Int, String>>): List<TxtChapter> {
        val out = mutableListOf<TxtChapter>()
        // 首个标记之前的内容单独成节，否则会被整段丢掉（见类注释第 2 条）
        val firstStart = starts[0].first
        if (firstStart > 0 && text.substring(0, firstStart).isNotBlank()) {
            out.add(TxtChapter(PREFACE_TITLE, 0, firstStart))
        }
        for (i in starts.indices) {
            val (start, title) = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1].first else text.length
            out.add(TxtChapter(title, start, end))
        }
        return out
    }

    /**
     * 无章节标记：**整本作为一章**（标题留空，UI 会显示「第1章」）。
     *
     * ⚠️ 曾经按每 8000 字切一节、起名「第1节」「第2节」…（用户明确否掉）：
     * 一本书没有章标记就按一章算；8000 字一切只会让目录变成一串"第N节"，
     * 和真正的"章"混在一起更莫名其妙，而且会把「续读定位」切碎成十几段。
     */
    private fun wholeBook(text: String): List<TxtChapter> = listOf(TxtChapter("", 0, text.length))
}
