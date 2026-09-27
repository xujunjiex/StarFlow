package com.moe.starflow.novel.reader

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.translate.NovelChapterTranslator
import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
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

    private const val TAG = "NovelExport"

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

    /** 导出文件名（**不含目录**）：`书名-模式.txt`。非法字符见 [sanitize]。 */
    fun displayName(book: ImportedNovel, kind: Kind): String =
        "${sanitize(book.title)}-${suffix(kind)}.txt"

    /**
     * 导出**整本**（按章顺序拼一个 txt），写进手机的 **Download** 目录。
     *
     * ⚠️ 落点必须是 `MediaStore.Downloads`（= 文件管理器里的 **Download**），**不是**
     * `getExternalFilesDir()`：后者在 `/Android/data/<包名>/files/` 下，Android 11+ 的文件
     * 管理器根本进不去、用户拿不到导出的书（而且卸载即删）。API 29+ 往 Download 写自己的文件
     * **不需要任何存储权限**。与漫画的导出（`MangaReaderActivity.writeToDownloads`）同一个落点。
     *
     * @return 成功时给**实际落盘的文件名**（重名时 MediaStore 会自动加 " (1)"，所以回读一次）；
     *   失败给 `Result.failure`（原因带在异常里，同时记进日志）
     */
    suspend fun exportBook(
        context: Context,
        book: ImportedNovel,
        repository: NovelChapterRepository,
        translator: NovelChapterTranslator,
        kind: Kind,
    ): Result<String> = exportBook(context, book, repository, kind) { i ->
        translator.loadTranslations(book, i)
    }

    /**
     * 导出主体。取译文的方式可注入：单测不必造出 Room DAO + 翻译引擎（那是
     * [NovelChapterTranslator] 的构造依赖），只要给一个 `章号 → 译文` 的 lambda。
     */
    suspend fun exportBook(
        context: Context,
        book: ImportedNovel,
        repository: NovelChapterRepository,
        kind: Kind,
        loadTranslations: suspend (Int) -> Map<Int, String>,
    ): Result<String> = runCatching {
        // ⚠️ 必须切 IO：调用方是 `lifecycleScope.launch`（= Main.immediate），不切的话
        // 整本书的取章 + 拼接 + 写盘全在主线程上，几百章的书直接把 UI 卡住
        withContext(Dispatchers.IO) {
            val display = displayName(book, kind)
            writeToDownloads(context, display) { w ->
                writeBook(w, book, repository, kind, loadTranslations)
            }
        }
    }

    /**
     * 整本拼进任意 [Writer]（**与落盘解耦**）。
     *
     * ⚠️ 分出来是为了能单测：MediaStore 在 Robolectric 下没有 provider，`insert` 直接返回 null，
     * 「落盘那一层」在单测里验证不了 —— 但「拼出来的字节对不对」必须能验证，那是导出的全部内容。
     *
     * ⚠️ **流式写**（一章一写）而不是先攒一个 `StringBuilder` 再 `toString()`：
     * 后者对 300 章的书要把全文在内存里复制两份（builder 的 char[] + toString 再一份）。
     */
    internal suspend fun writeBook(
        w: Writer,
        book: ImportedNovel,
        repository: NovelChapterRepository,
        kind: Kind,
        loadTranslations: suspend (Int) -> Map<Int, String>,
    ) {
        val chapters = repository.chaptersOf(book)
        for (i in chapters.indices) {
            val paragraphs = repository.paragraphsOf(book, i)
            if (paragraphs.isEmpty()) continue
            val translations = runCatching { loadTranslations(i) }.getOrDefault(emptyMap())
            writeChapter(w, chapters[i].title, buildChapterText(paragraphs, translations, kind))
        }
    }

    /**
     * 写进手机 Download，返回实际落盘的文件名。
     *
     * ⚠️ `insert` 必须在 try 里：MediaProvider 不可用（存储未挂载/受限）会抛
     * SecurityException / IllegalArgumentException —— 本方法跑在协程里，抛出去就是未捕获异常。
     * ⚠️ 写失败要把那一行删掉，否则 Download 里留一个 0 字节的「已导出」幽灵文件。
     */
    private suspend fun writeToDownloads(
        context: Context,
        display: String,
        body: suspend (Writer) -> Unit,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, display)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore 拒绝创建导出文件：$display")
            val out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("无法打开导出文件输出流：$display")
            out.use { BufferedWriter(OutputStreamWriter(it, Charsets.UTF_8)).use { w -> body(w) } }
            return actualDisplayName(resolver, uri) ?: display
        } catch (e: Exception) {
            LogCollector.e(TAG, "导出到 Download 失败 name=$display", e)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            throw e
        }
    }

    /** 回读实际文件名：重名时 MediaStore 会写成 `名字 (1).txt`，提示用户时要给真的那个。 */
    private fun actualDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** 写一章：标题行 + 空行 + 正文 + 空行（与旧实现拼接出的字节完全一致）。 */
    internal fun writeChapter(w: Writer, title: String, body: String) {
        w.append(title).append('\n').append('\n')
        w.append(body).append('\n')
    }
}
