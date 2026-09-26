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
        // 方括号标题（部分网文用【】包裹）
        Regex("""^\s*[\[【](.{1,30})[\]】]\s*$"""),
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
        return null
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
