package com.moe.starflow.novel.parser

import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelFormat
import java.io.File
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * 一种文本格式的解析器。实现必须是**无状态 object**（不持有 file 引用）。
 *
 * 失败用异常表达（`IOException` / `IllegalStateException`），由调用方归类成
 * `NovelImportFailureReason`。返回空章节列表 = 文件里没有可读文本。
 */
interface NovelParser {
    /** 解析结构（目录 + 元数据），不读正文。 */
    suspend fun parse(file: File): NovelBook

    /**
     * 读取一章的纯文本。
     *
     * [locator] 语义：EPUB / ZIP = zip entry 名；TXT = `"<起始字节>,<结束字节>"`；
     * 单 HTML = 空串（整篇即一章）。
     */
    suspend fun loadChapter(file: File, locator: String): String
}

/**
 * 格式判定。**先验内容、后看扩展名** —— 扩展名会被用户改，而压缩包结构不会说谎。
 */
object NovelFormatDetector {

    private val HTML_EXT = setOf("html", "htm", "xhtml")
    private val TXT_EXT = setOf("txt")

    /** null = 不是受支持的文本格式（可能是漫画包，或完全不支持的文件）。 */
    fun detect(file: File): NovelFormat? {
        zipKind(file)?.let { return it }
        val ext = file.extension.lowercase(Locale.ROOT)
        return when {
            ext in TXT_EXT -> NovelFormat.TXT
            ext in HTML_EXT -> NovelFormat.HTML
            else -> null
        }
    }

    /** 压缩包判定：能打开才按内容判，打不开当普通文件继续走扩展名。 */
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
        } catch (e: ZipException) {
            return null      // 不是压缩包
        } catch (e: Exception) {
            return null      // 读不了（权限/IO）→ 交给上层报 UNREADABLE
        }
        if (names.isEmpty()) return null
        // EPUB 标记：优先 mimetype 条目，其次 container.xml
        if (names.any { it == "mimetype" } && names.any { it == "META-INF/container.xml" }) {
            return NovelFormat.EPUB
        }
        if (names.any { it == "META-INF/container.xml" }) return NovelFormat.EPUB
        // 有图片 → 漫画，本链路不接
        if (names.any { isImageEntry(it) }) return null
        // 有 html/txt → 小说包
        if (names.any { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in HTML_EXT || it.endsWith(".txt", true) }) {
            return NovelFormat.ZIP_HTML
        }
        return null
    }

    private fun isImageEntry(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "avif")
}
