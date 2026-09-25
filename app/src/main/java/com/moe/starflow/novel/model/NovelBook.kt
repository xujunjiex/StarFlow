package com.moe.starflow.novel.model

/** 小说文件格式。 */
enum class NovelFormat { TXT, EPUB, ZIP_HTML, HTML }

/**
 * 章节目录项（**不含正文**）。
 *
 * ⚠️ 正文懒加载：300 章的 EPUB 若在导入时全解，会卡几十秒并把整本书的字符串堆在内存里。
 * [locator] 是「怎么取到这一章」的指针，各格式语义见 [NovelParser.loadChapter]。
 */
data class NovelChapterMeta(
    val index: Int,
    val title: String,
    val locator: String,
)

/**
 * 解析出的书结构。
 *
 * @param chapters 已按阅读顺序排好（EPUB 按 spine 序，**不是文件名序**）
 * @param coverBytes 内嵌封面原始字节；没有则 null
 */
data class NovelBook(
    val title: String,
    val author: String?,
    val chapters: List<NovelChapterMeta>,
    val coverBytes: ByteArray? = null,
) {
    // data class 带 ByteArray 必须手写 equals/hashCode，否则数组按引用比较
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NovelBook) return false
        return title == other.title && author == other.author &&
            chapters == other.chapters && (coverBytes contentEquals other.coverBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + (author?.hashCode() ?: 0)
        result = 31 * result + chapters.hashCode()
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        return result
    }
}
