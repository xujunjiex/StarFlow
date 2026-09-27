package com.moe.starflow.novel.parser

import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TXT 解析器。
 *
 * ⚠️ 正文**不缓存**：每次 [loadChapter] 都重新读文件再按字符区间切。看似浪费，但 txt 小说
 * 通常几 MB，读一次几十毫秒；把整本字符串缓存进内存（几 MB 文本 → 十几 MB 的 String 对象）
 * 对 300 章的书不划算。上层 `NovelChapterRepository` 有章级 LRU，实际重复读很少。
 *
 * ⚠️ 阻塞 I/O，调用方须在主线程之外调用（[parse] / [loadChapter] 内部已切到 IO 调度器）。
 */
object TxtParser : NovelParser {

    override suspend fun parse(file: File): NovelBook = withContext(Dispatchers.IO) {
        // 读取上限见 NovelReadLimits：超限抛 ZipException（上层归类为「格式不支持或文件损坏」），
        // 而不是有多少读多少把堆吃爆
        val text = TextEncoding.decode(NovelReadLimits.readFile(file))
        val chapters = TxtChapterSplitter.split(text).mapIndexed { i, c ->
            NovelChapterMeta(index = i, title = c.title, locator = locatorOf(c))
        }
        NovelBook(
            title = file.nameWithoutExtension.ifBlank { "Untitled" },
            author = null,
            chapters = chapters,
        )
    }

    override suspend fun loadChapter(file: File, locator: String): String = withContext(Dispatchers.IO) {
        val (start, end) = parseLocator(locator)
        val text = TextEncoding.decode(NovelReadLimits.readFile(file))
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        // ⚠️ **一行一段**：txt 小说几乎都是一行一段、行间不留空行，而段落切分只认空行 ——
        // 不归一化的话整章会被当成一个段落（见 NovelParagraphSplitter.linesToParagraphs）。
        NovelParagraphSplitter.linesToParagraphs(text.substring(from, to))
    }

    private fun locatorOf(c: TxtChapter): String = "${c.charStart},${c.charEnd}"

    /** locator 是 `"<start>,<end>"`（字符下标）。坏值退化成「整篇」，而不是抛异常中断阅读。 */
    private fun parseLocator(locator: String): Pair<Int, Int> {
        val parts = locator.split(',')
        val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val end = parts.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
        return start to end
    }
}
