package com.moe.starflow.novel.translate

/**
 * 章节文本 → 段落列表。
 *
 * ### 确定性是硬要求
 * 同一份文本两次切分必须给出完全相同的 `index`。任何非确定性（例如依赖 HashMap 迭代序、
 * locale 相关的比较）都会让译文**随机错位**，而且错位后看不出错 —— 译文还在，只是对错了段。
 * 所以这里只做顺序扫描，不碰任何无序集合。
 *
 * ### [SPLIT_VERSION]
 * 切分规则一旦变更（段界、最短长度、分类判据），旧 `paraIndex` 会整体错位。
 * **改规则必须递增此常量** —— 译文查询按版本过滤，旧译文自动失效重翻，
 * 而不是悄悄显示在错误的段落上。
 */
object NovelParagraphSplitter {

    /**
     * 切分规则版本。改规则就 +1，理由见类注释。
     *
     * ⚠️ **改这个值之前先清旧行**：译文表的主键是 `(novelId, novelKey, chapterIndex, paraIndex)`，
     * **不含 splitVersion** —— 旧版本的行会占着同一批主键，新版本的行 `insertIgnore` 静默写不进去
     * （"补齐分母"和"标记翻译中"全废），而旧行没有任何清理路径（表只增不减）。
     * 当前 `SPLIT_VERSION = 1` 从没变过，所以还没有实际数据踩这一条。
     * 真要 +1 时二选一：① 阅读器进入时按 `(novelId, novelKey)` 删掉非当前版本的行；
     * ② 把 `splitVersion` 并进主键（需要一次重建表的迁移）。
     */
    const val SPLIT_VERSION = 1

    /** 短于此长度的段落不翻译（短对白、分隔符、"……"）。 */
    const val MIN_TEXT_LENGTH = 4

    /**
     * 图片占位标记（由 `HtmlTextExtractor` 产出）。
     * 与 `HtmlTextExtractor.IMAGE_MARK` 是同一个字符串，这里用正则做前缀匹配以容忍空白差异。
     */
    private val IMAGE_PREFIX = Regex("""^📷\s*\[图片]""")

    fun split(content: String): List<NovelParagraph> {
        if (content.isBlank()) return emptyList()
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n[ \t]+"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")

        val out = mutableListOf<NovelParagraph>()
        var index = 0
        for (raw in normalized.split("\n\n")) {
            val trimmed = raw.trim()
            // 空段不占 index：否则连续空行会往 index 里塞一堆无用段，
            // 而译文表每个 index 都要落一行，白白撑大数据库
            if (trimmed.isEmpty()) continue
            val type = when {
                IMAGE_PREFIX.containsMatchIn(trimmed) -> NovelParagraphType.IMAGE
                trimmed.length < MIN_TEXT_LENGTH -> NovelParagraphType.SKIP
                else -> NovelParagraphType.TEXT
            }
            out.add(NovelParagraph(index = index, type = type, originalText = trimmed))
            index++
        }
        return out
    }
}
