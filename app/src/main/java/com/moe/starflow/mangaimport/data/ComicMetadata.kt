package com.moe.starflow.mangaimport.data

import android.content.Context
import android.util.Xml
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * 漫画压缩包里的**元数据**（只用来自动填简介）。
 *
 * 两种格式都认（现实里两种常常同时存在，见下面的样例）：
 * - **`ComicInfo.xml`** —— ComicRack / ComicTagger 的行业标准，字段最全
 * - **`meta.json`** —— nhentai 等下载器写的元数据（中日文标题 / artist / group / tags）
 *
 * 样例（真实文件 `nhentai-684364 - …cbz` 里就有这两个）：
 * ```xml
 * <ComicInfo><Title>…</Title><Series>original</Series><Writer>tamura-chan</Writer>
 *   <Translator>chikyuujin</Translator><Tags>sole female, lolicon…</Tags>
 *   <LanguageISO>ja</LanguageISO><Web>https://nhentai.net/g/684364/</Web></ComicInfo>
 * ```
 * ```json
 * {"id":684364,"title":{"english":"…","japanese":"…"},"tags":[{"type":"artist","name":"tamura-chan"},…]}
 * ```
 *
 * ⚠️ **只填简介，不做别的**（用户口径）：不改书名（书名仍取文件名）、不改阅读方向、
 * 不往书架行加字段。简介写进 `ImportedManga.description`，用户可以照旧在「编辑简介」里手改
 * —— 我们**只在导入那一次**生成它，之后不再覆盖任何东西。
 *
 * ⚠️ **别把标签拼成几百字的简介**：标签数量没有上限（有的本子几十个），简介行只有两行可见，
 * 这里对标签做了长度截断（[MAX_TAGS_CHARS]）。
 */
data class ComicMetadata(
    val title: String? = null,
    val series: String? = null,
    val writer: String? = null,
    val translator: String? = null,
    val format: String? = null,
    val tags: List<String> = emptyList(),
    val languageIso: String? = null,
    val web: String? = null,
    val pageCount: Int? = null,
    val year: Int? = null
)

object ComicMetadataParser {

    private const val TAG = "ComicMetadata"

    /** 只认这两个文件名（比较时统一小写、只看 basename）。 */
    private const val COMIC_INFO = "comicinfo.xml"
    private const val META_JSON = "meta.json"

    /** 元数据条目上限：遇到被改名成 meta.json 的大文件不读（防内存里塞几十 MB）。 */
    const val MAX_METADATA_BYTES = 256 * 1024L

    /** 简介里标签串的长度上限（超出部分截断加省略号）。 */
    private const val MAX_TAGS_CHARS = 160

    /** 是不是我们要读的元数据条目（忽略大小写与所在目录）。 */
    fun isMetadataEntry(entryName: String): Boolean = basename(entryName) in setOf(COMIC_INFO, META_JSON)

    fun basename(entryName: String): String =
        entryName.replace('\\', '/').substringAfterLast('/').trim().lowercase()

    /**
     * 解析一组「条目名 → 文本内容」。
     *
     * 两种格式都在时：**`ComicInfo.xml` 优先，`meta.json` 只补它缺的字段** ——
     * ComicInfo 是人工/工具标注过的，更可信（nhentai 的 json 里 `parody`/`category` 之类
     * 是网站的分类名，不如 ComicInfo 的 `Series` 直白）。
     *
     * @return 两种都解析不出来时返回 null（调用方据此不填简介）
     */
    fun parse(texts: List<Pair<String, String>>): ComicMetadata? {
        // ⚠️ 排序规则**不能只按目录深度**：ComicInfo 与 meta.json 常常都在根目录（深度都是 0），
        // 只比深度就成了「谁在列表里靠前谁说了算」——调用方换个遍历顺序，作者/系列就换了个来源。
        // 必须**先按格式定优先级**（ComicInfo 是人工/工具标注的，比 nhentai 的分类名更可信），
        // 再在同一格式内让浅层目录优先。
        val ordered = texts.sortedWith(
            compareBy(
                { if (basename(it.first) == COMIC_INFO) 0 else 1 },
                { it.first.count { c -> c == '/' || c == '\\' } },
            )
        )
        var merged: ComicMetadata? = null
        ordered.forEach { (name, text) ->
            val parsed = when (basename(name)) {
                COMIC_INFO -> parseComicInfo(text)
                META_JSON -> parseNhentaiJson(text)
                else -> null
            } ?: return@forEach
            merged = merged?.let { merge(it, parsed) } ?: parsed
        }
        return merged
    }

    /** [primary] 里为空的字段用 [secondary] 补上（`tags` 视为整体，主表非空就不动）。 */
    fun merge(primary: ComicMetadata, secondary: ComicMetadata): ComicMetadata = ComicMetadata(
        title = primary.title ?: secondary.title,
        series = primary.series ?: secondary.series,
        writer = primary.writer ?: secondary.writer,
        translator = primary.translator ?: secondary.translator,
        format = primary.format ?: secondary.format,
        tags = primary.tags.ifEmpty { secondary.tags },
        languageIso = primary.languageIso ?: secondary.languageIso,
        web = primary.web ?: secondary.web,
        pageCount = primary.pageCount ?: secondary.pageCount,
        year = primary.year ?: secondary.year,
    )

    /**
     * 有没有解析出**任何**字段。
     *
     * ⚠️ 这不只是洁癖：被截断/乱码的 XML（`<ComicInfo><Title>oops`）在 KXmlParser 下**不一定抛异常**，
     * 它会走完事件循环、留下一个「全是 null 的 ComicMetadata」——那种东西会让调用方以为
     * 「有元数据」而填出一段空简介。所以解析结果一律要过这一关。
     */
    private fun ComicMetadata.isMeaningful(): Boolean =
        !title.isNullOrBlank() || !series.isNullOrBlank() || !writer.isNullOrBlank() ||
            !translator.isNullOrBlank() || !format.isNullOrBlank() || tags.isNotEmpty() ||
            !languageIso.isNullOrBlank() || !web.isNullOrBlank() || pageCount != null || year != null

    // ===== ComicInfo.xml =====

    /**
     * 解析 ComicInfo.xml。用 `Xml.newPullParser`（与小说 EPUB 解析同一套，见 `EpubParser`）——
     * 别引 `DocumentBuilderFactory`：多一份 XML 实现，Android 上还多一次 DTD 处理的坑。
     *
     * 坏 XML 一律**返回 null 而不是抛**：元数据缺失只是少填一段简介，
     * 绝不能让整本书导入失败（导入路径上抛异常 = 用户看到「导入失败」）。
     */
    fun parseComicInfo(xml: String): ComicMetadata? = try {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            // ⚠️ BOM 不剥掉的话 XmlPullParser 会在第一个事件就抛（有些工具写出的 XML 带 BOM）
            setInput(ByteArrayInputStream(stripBom(xml).toByteArray(Charsets.UTF_8)), null)
        }
        var title: String? = null
        var series: String? = null
        var writer: String? = null
        var translator: String? = null
        var format: String? = null
        var tags: List<String> = emptyList()
        var language: String? = null
        var web: String? = null
        var pageCount: Int? = null
        var year: Int? = null

        var capture: String? = null
        val buf = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    capture = parser.name
                    buf.setLength(0)
                }
                XmlPullParser.TEXT -> if (capture != null) buf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val value = buf.toString().trim().takeIf { it.isNotEmpty() }
                    when (capture?.lowercase()) {
                        "title" -> title = value
                        "series" -> series = value
                        "writer", "penciller", "artist" -> if (writer == null) writer = value
                        "translator" -> translator = value
                        "format" -> format = value
                        "tags" -> if (value != null) tags = splitTags(value)
                        "languageiso" -> language = value
                        "web" -> web = value
                        "pagecount" -> pageCount = value?.toIntOrNull()
                        "year" -> year = value?.toIntOrNull()
                    }
                    capture = null
                }
            }
            event = parser.next()
        }
        ComicMetadata(
            title = title, series = series, writer = writer, translator = translator,
            format = format, tags = tags, languageIso = language, web = web,
            pageCount = pageCount, year = year
        ).takeIf { it.isMeaningful() }
    } catch (e: Exception) {
        LogCollector.w(TAG, "ComicInfo.xml 解析失败（忽略元数据）: ${e.message}")
        null
    }

    // ===== meta.json（nhentai 等下载器） =====

    fun parseNhentaiJson(json: String): ComicMetadata? = try {
        val o = JSONObject(stripBom(json))
        val titleObj = o.optJSONObject("title")
        val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() }
            ?: titleObj?.optString("japanese")?.takeIf { it.isNotBlank() }
            ?: o.optString("title").takeIf { it.isNotBlank() && it != "null" }

        var artist: String? = null
        var group: String? = null
        var parody: String? = null
        var category: String? = null
        val tags = mutableListOf<String>()
        o.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val name = t.optString("name").takeIf { it.isNotBlank() } ?: continue
                when (t.optString("type").lowercase()) {
                    "artist" -> artist = if (artist == null) name else "$artist, $name"
                    "group" -> group = if (group == null) name else "$group, $name"
                    "parody" -> parody = if (parody == null) name else "$parody, $name"
                    "category" -> category = name
                    // 语言/分类之外的都是用户认知里的「标签」
                    "tag" -> tags += name
                }
            }
        }
        val id = o.optInt("id", -1)
        ComicMetadata(
            title = title,
            // nhentai 的 parody ≈ 作品系列，category ≈ 类型
            series = parody,
            writer = artist,
            translator = group,
            format = category,
            tags = tags,
            // ⚠️ language 标签是 "japanese" 这种名字、不是 ISO 码，不能塞进 languageIso
            // （ComicInfo 那边给的是 ja，缺了也不影响简介）
            languageIso = null,
            // 这份 json 按定义就是 nhentai 下载器写的 → 没有 Web 字段时用 id 拼出来
            web = if (id > 0) "https://nhentai.net/g/$id/" else null,
            pageCount = o.optInt("num_pages", -1).takeIf { it > 0 },
            year = null
        ).takeIf { it.isMeaningful() }
    } catch (e: Exception) {
        LogCollector.w(TAG, "meta.json 解析失败（忽略元数据）: ${e.message}")
        null
    }

    // ===== 简介文案 =====

    /**
     * 生成简介（导入时写进 `ImportedManga.description`，用户之后可自行编辑）。
     *
     * 只列用户要的四类：**作者 / 社团·译者 / 系列 / 标签 / 来源**（空的一律不占行）。
     * 标签按 [MAX_TAGS_CHARS] 截断 —— 简介行只有两行可见，几十个标签拼进去等于什么都看不见。
     */
    fun buildDescription(context: Context, meta: ComicMetadata): String {
        val lines = mutableListOf<String>()
        meta.writer?.takeIf { it.isNotBlank() }
            ?.let { lines += context.getString(R.string.comic_meta_author, it) }
        meta.translator?.takeIf { it.isNotBlank() }
            ?.let { lines += context.getString(R.string.comic_meta_circle, it) }
        meta.series?.takeIf { it.isNotBlank() }
            ?.let { lines += context.getString(R.string.comic_meta_series, it) }
        if (meta.tags.isNotEmpty()) {
            val joined = meta.tags.joinToString(", ")
            val text = if (joined.length > MAX_TAGS_CHARS) {
                joined.take(MAX_TAGS_CHARS).trimEnd().trimEnd(',') + "…"
            } else {
                joined
            }
            lines += context.getString(R.string.comic_meta_tags, text)
        }
        meta.web?.takeIf { it.isNotBlank() }
            ?.let { lines += context.getString(R.string.comic_meta_source, it) }
        return lines.joinToString("\n")
    }

    /** 解析压缩包/目录里的元数据条目并生成简介；没有元数据返回空串（简介留空）。 */
    fun descriptionOf(context: Context, texts: List<Pair<String, String>>): String {
        val meta = parse(texts) ?: return ""
        return buildDescription(context, meta)
    }

    // ===== 工具 =====

    private fun splitTags(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun stripBom(text: String): String =
        text.removePrefix("\uFEFF").trimStart('\uFEFF', '\u0000')
}
