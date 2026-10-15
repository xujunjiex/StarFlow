package com.moe.starflow.novel.parser

import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelChapterMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 单 HTML 文件：整篇 = 一章。 */
object HtmlParser : NovelParser {

    private const val LOCATOR = ""

    override suspend fun parse(file: File): NovelBook = withContext(Dispatchers.IO) {
        // 读取上限见 NovelReadLimits（单文件 100MB）：整个文件都要进内存做正则提取，不封顶
        // 就是给一个超大文件留了 OOM 的路
        val raw = TextEncoding.decode(NovelReadLimits.readFile(file))
        val fallbackTitle = file.nameWithoutExtension.ifBlank { "Untitled" }
        val chapter = NovelChapterMeta(
            index = 0,
            title = HtmlTextExtractor.titleOf(raw) ?: fallbackTitle,
            locator = LOCATOR,
        )
        NovelBook(
            title = HtmlTextExtractor.titleOf(raw) ?: fallbackTitle,
            author = authorOf(raw),
            chapters = listOf(chapter),
            description = descriptionOf(raw),
        )
    }

    internal fun descriptionOf(raw: String): String = metaContent(raw, setOf("description", "og:description", "dc.description")).orEmpty()
    internal fun authorOf(raw: String): String? = metaContent(raw, setOf("author", "dc.creator"))

    private fun metaContent(raw: String, names: Set<String>): String? {
        val meta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
        val attributes = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        return meta.findAll(raw).firstNotNullOfOrNull { tag ->
            val values = attributes.findAll(tag.value).associate { match ->
                match.groupValues[1].lowercase() to match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            }
            if ((values["name"] ?: values["property"])?.lowercase() in names)
                values["content"]?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                    ?.takeIf(String::isNotBlank)
            else null
        }
    }

    override suspend fun loadChapter(file: File, locator: String): String =
        loadChapterSource(file, locator).text

    /**
     * 正文 + 图片。
     *
     * ⚠️ 单 HTML 的图片是**同目录下的兄弟文件**，而导入只复制了这一个 html（SAF 的单文件授权
     * 拿不到兄弟文件）→ 这些地址在导入后**都取不到**，回落成占位文本。这里仍然按规矩解析：
     * 一旦哪天导入改成连图片一起拷（或用户手工把图片放进书目录），这条路径立刻就能用。
     */
    override suspend fun loadChapterSource(file: File, locator: String): ChapterSource =
        loadChapterSourceWithin(file, file.parentFile ?: file)

    suspend fun loadChapterSourceWithin(file: File, bookRoot: File): ChapterSource =
        withContext(Dispatchers.IO) {
            val ex = HtmlTextExtractor.extractWithImages(TextEncoding.decode(NovelReadLimits.readFile(file)))
            val dir = file.parentFile ?: return@withContext ChapterSource(ex.text)
            var spent = 0
            ChapterSource(
                ex.text,
                ex.imageSources.map { src ->
                    if (src.isEmpty() || spent >= NovelReadLimits.MAX_CHAPTER_IMAGE_BYTES) {
                        return@map null
                    }
                    val bytes = ZipImages.readLocalOne(dir, src, bookRoot) ?: return@map null
                    spent += bytes.size
                    bytes
                },
            )
        }
}
