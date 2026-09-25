package com.moe.starflow.novel.parser

import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelFormat
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 一种文本格式的解析器。实现必须是**无状态 object**（不持有 file 引用）。
 *
 * 失败用异常表达（`IOException` / `IllegalStateException`），由调用方归类成
 * `NovelImportFailureReason`。返回空章节列表 = 文件里没有可读文本。
 *
 * ⚠️ [parse] / [loadChapter] 都会做阻塞的文件 / 压缩包 I/O，**调用方必须在主线程之外调用**。
 */
interface NovelParser {
    /** 解析结构（目录 + 元数据），不读正文。 */
    suspend fun parse(file: File): NovelBook

    /**
     * 读取一章的纯文本。
     *
     * [locator] 语义：
     * - EPUB / ZIP = zip entry 名
     * - TXT = `"<起始字符>,<结束字符>"`
     * - 单 HTML = 空串（整篇即一章）
     * - FOLDER = `"<夹内相对路径>"`；夹内 epub 的某一章是 `"<相对路径>|<epub 内 locator>"`
     */
    suspend fun loadChapter(file: File, locator: String): String
}

/**
 * 格式判定。**先验内容、后看扩展名** —— 扩展名会被用户改，而压缩包结构不会说谎。
 *
 * ⚠️ [detect] 做阻塞的压缩包 I/O，**调用方必须在主线程之外调用**。
 */
object NovelFormatDetector {

    private val HTML_EXT = setOf("html", "htm", "xhtml")
    private val TXT_EXT = setOf("txt")

    /** null = 不是受支持的文本格式（可能是漫画包，或完全不支持的文件）。 */
    fun detect(file: File): NovelFormat? {
        // 目录：文件夹子（整个夹 = 一部小说）
        if (file.isDirectory) return NovelFormat.FOLDER
        zipKind(file)?.let { return it }
        val ext = file.extension.lowercase(Locale.ROOT)
        return when {
            ext in TXT_EXT -> NovelFormat.TXT
            ext in HTML_EXT -> NovelFormat.HTML
            else -> null
        }
    }

    /**
     * 压缩包判定：能打开才按内容判，打不开当普通文件继续走扩展名。
     *
     * ⚠️ **文本优先于图片**：用户是从**小说**导入入口走到这里来的，意图已经声明过。反过来
     * 判错的代价不对称 —— 把带附图的漫画包误判成小说，用户得到一个肉眼可见、随手可删的
     * 「1 章的书」；把带插图的文本包（Kototoro 的 `chapter_{id}.html` + `images/{id}/` 就是
     * 这个形状）误判成漫画，则是**硬拦**用户，他什么都导不进来。
     */
    private fun zipKind(file: File): NovelFormat? {
        val names = try {
            ZipFile(file).use { zip ->
                val out = mutableListOf<String>()
                val e = zip.entries()
                while (e.hasMoreElements()) {
                    val entry = e.nextElement()
                    if (!entry.isDirectory) out.add(entry.name)
                }
                out
            }
        } catch (e: Exception) {
            // 打不开一律返回 null = 「不是压缩包」，与扩展名分支合流。
            // `ZipException` 确实是「不是压缩包」；其余异常（权限 / IO）也返回 null 是**有意为之**：
            // 走到这里时源文件已经在复制那一步成功读过了，落到本函数的是复制后的目标文件，
            // 它读不了不值得单开一个失败模式（`detect()` 被调用方依赖为「绝不抛异常」）。
            return null
        }
        if (names.isEmpty()) return null
        // EPUB 标记：mimetype + container.xml，或单独 container.xml（前者是规范的完整形态，
        // 后者覆盖只有 container.xml 的包 —— 故只需判后者一条）
        if (names.any { it == "META-INF/container.xml" }) return NovelFormat.EPUB
        // 有 html/txt → 小说包（先于图片检查，见上方 KDoc 的理由）
        if (names.any { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in HTML_EXT || it.endsWith(".txt", true) }) {
            return NovelFormat.ZIP_HTML
        }
        // 有图片 → 漫画，本链路不接
        if (names.any { ArchivedMangaReader.isImageFile(it) }) return null
        return null
    }
}
