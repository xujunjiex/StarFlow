package com.moe.starflow.novel.reader

import android.content.Context
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.translate.NovelChapterTranslator
import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import java.io.File

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
    ): File {
        val chapters = repository.chaptersOf(book)
        val sb = StringBuilder()
        for (i in chapters.indices) {
            val paragraphs = repository.paragraphsOf(book, i)
            if (paragraphs.isEmpty()) continue
            val translations = runCatching { translator.loadTranslations(book, i) }.getOrDefault(emptyMap())
            sb.append(chapters[i].title).append('\n').append('\n')
            sb.append(buildChapterText(paragraphs, translations, kind)).append('\n')
        }
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "${sanitize(book.title)}-${suffix(kind)}.txt")
        file.writeText(sb.toString())
        return file
    }
}
