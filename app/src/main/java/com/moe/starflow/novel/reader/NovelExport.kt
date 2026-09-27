package com.moe.starflow.novel.reader

import android.content.Context
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.translate.NovelChapterTranslator
import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * 导出：**译文 / 原文 / 双语**三种（用户明确要求）。
 *
 * ⚠️ 文本规则**复用 [NovelPageBilingual.displayText]**：导出与阅读必须给出同一份文本，
 * 各写一套的话迟早出现「阅读器里是双语、导出来只有译文」这种对不上的事。
 * 未翻译的段在三种模式下都回落原文（留空会让导出的文件看起来像丢了内容）。
 */
object NovelExport {

    enum class Kind { TRANSLATED, ORIGINAL, BILINGUAL }

    /** 一章的文本（纯函数，可单测）。 */
    fun buildChapterText(
        paragraphs: List<NovelParagraph>,
        translations: Map<Int, String>,
        kind: Kind,
    ): String {
        val mode = when (kind) {
            Kind.TRANSLATED -> NovelDisplayMode.TRANSLATED
            Kind.ORIGINAL -> NovelDisplayMode.ORIGINAL
            Kind.BILINGUAL -> NovelDisplayMode.BILINGUAL
        }
        return buildString {
            for (p in paragraphs) {
                if (p.type == NovelParagraphType.SKIP) continue
                append(NovelPageBilingual.displayText(p.originalText, translations[p.index], mode))
                append('\n')
            }
        }
    }

    /** 文件名里不能出现的字符（书名的来源是用户文件，什么都可能有）。 */
    internal fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "novel" }

    fun suffix(kind: Kind): String = when (kind) {
        Kind.TRANSLATED -> "译文"
        Kind.ORIGINAL -> "原文"
        Kind.BILINGUAL -> "双语"
    }

    /**
     * 导出**整本**（按章顺序拼一个 txt），返回文件。
     *
     * 落在应用专属外部目录（与导入的漫画同一个位置，文件管理器/数据线都能拿到），
     * 不申请任何存储权限。
     */
    suspend fun exportBook(
        context: Context,
        book: ImportedNovel,
        repository: NovelChapterRepository,
        translator: NovelChapterTranslator,
        kind: Kind,
    ): File = exportBook(context, book, repository, kind) { i -> translator.loadTranslations(book, i) }

    /**
     * 导出主体。取译文的方式可注入：单测不必造出 Room DAO + 翻译引擎（那是
     * [NovelChapterTranslator] 的构造依赖），只要给一个 `章号 → 译文` 的 lambda。
     *
     * ⚠️ 整段跑在 [Dispatchers.IO]：调用方是 `lifecycleScope.launch`（= `Main.immediate`），
     * 不切的话整本书的文本拼接 + 落盘全在主线程上，几百章的书直接把 UI 卡住。
     *
     * ⚠️ **流式写**（`BufferedWriter`，一章一写）而不是先攒一个 `StringBuilder` 再 `toString()`：
     * 后者对 300 章的书要把全文在内存里复制两份（builder 的 char[] + toString 再一份），
     * 导出大书时峰值内存翻倍。
     */
    suspend fun exportBook(
        context: Context,
        book: ImportedNovel,
        repository: NovelChapterRepository,
        kind: Kind,
        loadTranslations: suspend (Int) -> Map<Int, String>,
    ): File = withContext(Dispatchers.IO) {
        val chapters = repository.chaptersOf(book)
        val dir = (context.getExternalFilesDir(null) ?: context.filesDir).apply { mkdirs() }
        // ⚠️ 文件名必须带 id + 时间戳：以前只有「书名-模式.txt」，两本同名的书（很常见）
        // 会**互相覆盖**，用户看到的就是「导出来的不是这本书」。时间戳同时顶掉连点两次覆盖的问题。
        val file = File(dir, "${sanitize(book.title)}-${suffix(kind)}-${book.id}_${System.currentTimeMillis()}.txt")
        BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8)).use { w ->
            for (i in chapters.indices) {
                val paragraphs = repository.paragraphsOf(book, i)
                if (paragraphs.isEmpty()) continue
                val translations = runCatching { loadTranslations(i) }.getOrDefault(emptyMap())
                writeChapter(w, chapters[i].title, buildChapterText(paragraphs, translations, kind))
            }
        }
        file
    }

    /** 写一章：标题行 + 空行 + 正文 + 空行（与旧实现拼接出的字节完全一致）。 */
    internal fun writeChapter(w: Writer, title: String, body: String) {
        w.append(title).append('\n').append('\n')
        w.append(body).append('\n')
    }
}
