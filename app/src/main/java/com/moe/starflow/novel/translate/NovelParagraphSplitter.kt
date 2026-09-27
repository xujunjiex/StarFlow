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
     * ⚠️ 译文表的主键是 `(novelId, novelKey, chapterIndex, paraIndex)`，**不含 splitVersion** ——
     * 旧版本的行会占着同一批主键，新版本的行 `insertIgnore` 静默写不进去（"补齐分母"与
     * "标记翻译中"全废），而旧行没有任何清理路径（表只增不减）。
     *
     * 所以 +1 时**必须同时确认清理还在**：`NovelChapterTranslator.resetStale`
     * （阅读器进入时跑）会 `deleteOtherVersions` 把非当前版本的行删掉。
     * 另一条路是把 `splitVersion` 并进主键（需要一次重建表的迁移），当前没做。
     *
     * 历史：1 → 2 是因为 TXT 改成**一行一段**（见 [linesToParagraphs]），段号映射整体变了。
     */
    const val SPLIT_VERSION = 2

    /** 短于此长度的段落不翻译（短对白、分隔符、"……"）。 */
    const val MIN_TEXT_LENGTH = 4

    /**
     * 图片占位标记（由 `HtmlTextExtractor` 产出）。
     * 与 `HtmlTextExtractor.IMAGE_MARK` 是同一个字符串，这里用正则做前缀匹配以容忍空白差异。
     */
    private val IMAGE_PREFIX = Regex("""^📷\s*\[图片]""")

    /**
     * **TXT 正文的段落规范：一行就是一段**（把单换行提升成段落分隔）。
     *
     * ⚠️ 不做这一步，TXT 小说会被整章切成**一个**段落：网文/公版 txt 几乎都是"一行一段、
     * 行间不留空行"，而 [split] 只认空行分段。后果是一串连锁的：
     * 阅读器里是一堵墙、翻译按整章发一个请求（远超模型上下文）、章行分母恒为 1、
     * 长按多选只能选到整章、锚点定位全落在同一段上。
     * 实测公版《三国演义》txt：只按空行切是 **240 块**（其中 120 块 >500 字、最大一块 7295 字），
     * 一行一段是 **1703 段**。
     *
     * ⚠️ 只对 **TXT / 文件夹里的 txt** 用（`TxtParser` / `FolderNovelParser`）：HTML/EPUB 那边
     * 单个换行是**段内**换行（`<br>`），`HtmlTextExtractor` 已经用空行分隔段落了，再归一化会把
     * 一整段按 `<br>` 切碎。
     *
     * ⚠️ 代价：**硬换行**的 txt（一行几十字、句子被折断）会被切碎。中文小说几乎不这么排，
     * 而"一行一段"是压倒性的常态 —— 取舍明确选它。
     */
    fun linesToParagraphs(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

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
