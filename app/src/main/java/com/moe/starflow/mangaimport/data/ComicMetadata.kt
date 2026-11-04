package com.moe.starflow.mangaimport.data

import android.content.Context
import android.util.Xml
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * 漫画包里的**元数据**（只用来自动填简介）。
 *
 * 认的是公开格式，**不看文件名、按内容嗅探**（工具把 `comet.xml` 命名成 `ComicInfo.xml`、
 * 把 ComicBookInfo 塞进 zip 注释，都能认出来）：
 *
 * | 格式 | 形状 | 谁写它 |
 * |---|---|---|
 * | ComicInfo.xml | XML，根 `<ComicInfo>` | ComicRack 的事实标准（ComicTagger / Kavita / Komga / Mylar） |
 * | CoMet | XML，根 `<comet>` | 较早的通用标准，字段少但跨工具 |
 * | ComicBookInfo | JSON，根键 `ComicBookInfo/1.0` | ComicRack 的 JSON 导出，常写在 **zip 注释**里 |
 * | 通用 JSON | 任意对象 | 各家下载器自拍的键名，按通用别名 + booru 角色标签读 |
 *
 * 优先级（同名字段谁赢）：ComicInfo > CoMet > ComicBookInfo > 通用 JSON。
 * 合并是**逐字段**的：高优先级为空的字段由低优先级补上（见 [merge]）。
 *
 * ⚠️ **来源不参与任何环节**：模型里没有 `web`/`id` 字段，简介里也没有「来源」行。
 * 曾经拿 `id` 拼第三方站点 URL —— 那个字面量会跟着 dex 进安装包，安装器/商店按域名黑名单
 * 静态扫描就报「含色情内容」（2026-11 真机安装提示即此事）。Mihon 的做法是 `Web` 解析了也不给
 * 用户看；这里更彻底：字段不保留，就没有地方能把它接回来。
 *
 * ⚠️ **只填简介，不做别的**（用户口径）：不改书名（书名仍取文件名）、不改阅读方向、
 * 不往书架行加字段。简介写进 `ImportedManga.description`，用户可以照旧在「编辑简介」里手改
 * —— 我们**只在导入那一次**生成它，之后不再覆盖任何东西。
 *
 * ⚠️ **别把标签拼成几百字的简介**：标签数量没有上限，简介行只有两行可见，
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
    val pageCount: Int? = null,
    val year: Int? = null,
    val summary: String? = null,
    val episode: String? = null,
    val publishedAt: String? = null,
)

object ComicMetadataParser {

    private const val TAG = "ComicMetadata"

    /**
     * 元数据条目名白名单（只比 basename，忽略大小写与所在目录）。
     *
     * ⚠️ 这里只决定「**要不要读**」，格式由内容决定 —— 白名单多列一个名字不会把别的格式读歪。
     */
    private val METADATA_NAMES = setOf(
        "comicinfo.xml",
        "comet.xml",
        "comicbookinfo.json",
        "comicbookinfo",
        "meta.json",
    )

    /** 格式优先级，数字小的先合并（见类注释）。 */
    private const val RANK_COMIC_INFO = 0
    private const val RANK_COMET = 1
    private const val RANK_COMIC_BOOK_INFO = 2
    private const val RANK_GENERIC = 3

    /** ComicRack 的 JSON 导出把内容挂在根键下。 */
    private const val COMIC_BOOK_INFO = "ComicBookInfo/1.0"

    /** 元数据条目上限：遇到被改名成 meta.json 的大文件不读（防内存里塞几十 MB）。 */
    const val MAX_METADATA_BYTES = 256 * 1024L

    /** 简介里标签串的长度上限（超出部分截断加省略号）。 */
    private const val MAX_TAGS_CHARS = 160

    /** 是不是我们要读的元数据条目（忽略大小写与所在目录）。 */
    fun isMetadataEntry(entryName: String): Boolean = basename(entryName) in METADATA_NAMES

    fun basename(entryName: String): String =
        entryName.replace('\\', '/').substringAfterLast('/').trim().lowercase(Locale.ROOT)

    /**
     * 解析一组「条目名 → 文本内容」。
     *
     * 每个条目先按**内容**定格式，再按「格式优先级 → 目录深度」排序后逐字段合并。
     * ⚠️ 排序不能只看目录深度：多种格式常常都在根目录（深度都是 0），只比深度就成了
     * 「谁在列表里靠前谁说了算」——调用方换个遍历顺序，作者/系列就换了个来源。
     *
     * @return 全都解析不出来时返回 null（调用方据此不填简介）
     */
    fun parse(texts: List<Pair<String, String>>): ComicMetadata? {
        val ordered = texts.mapNotNull { (name, text) ->
            typed(text)?.let { Triple(it.first, depth(name), it.second) }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        var merged: ComicMetadata? = null
        ordered.forEach { (_, _, meta) -> merged = merged?.let { merge(it, meta) } ?: meta }
        return merged
    }

    /** 按内容嗅探解析一段文本（不看文件名）；解析不出来返回 null。 */
    fun parseAny(text: String): ComicMetadata? = typed(text)?.second

    private fun depth(name: String): Int = name.count { it == '/' || it == '\\' }

    /**
     * 嗅探 + 解析，返回「格式优先级 to 元数据」。
     *
     * 坏数据一律**返回 null 而不是抛**：元数据缺失只是少填一段简介，
     * 绝不能让整本书导入失败（导入路径上抛异常 = 用户看到「导入失败」）。
     */
    private fun typed(text: String): Pair<Int, ComicMetadata>? = try {
        val body = stripBom(text).trimStart()
        when {
            body.startsWith("<") -> xmlTyped(body)
            body.startsWith("{") -> jsonTyped(body)
            else -> null
        }
    } catch (e: Exception) {
        LogCollector.w(TAG, "元数据解析失败（忽略）: ${e.message}")
        null
    }

    // ===== XML：ComicInfo.xml / CoMet =====

    private fun xmlTyped(xml: String): Pair<Int, ComicMetadata>? {
        val parsed = xmlFields(xml) ?: return null
        val (rank, meta) = when (parsed.first) {
            "comicinfo" -> RANK_COMIC_INFO to comicInfoMeta(parsed.second)
            "comet" -> RANK_COMET to cometMeta(parsed.second)
            else -> return null
        }
        return if (meta.isMeaningful()) rank to meta else null
    }

    /** 解析 ComicInfo.xml（根元素必须是 `<ComicInfo>`）。 */
    fun parseComicInfo(xml: String): ComicMetadata? = try {
        val parsed = xmlFields(xml)
        if (parsed != null && parsed.first == "comicinfo") {
            comicInfoMeta(parsed.second).takeIf { it.isMeaningful() }
        } else {
            null
        }
    } catch (e: Exception) {
        LogCollector.w(TAG, "ComicInfo.xml 解析失败（忽略元数据）: ${e.message}")
        null
    }

    /** 解析 CoMet（根元素必须是 `<comet>`）。 */
    fun parseCoMet(xml: String): ComicMetadata? = try {
        val parsed = xmlFields(xml)
        if (parsed != null && parsed.first == "comet") {
            cometMeta(parsed.second).takeIf { it.isMeaningful() }
        } else {
            null
        }
    } catch (e: Exception) {
        LogCollector.w(TAG, "CoMet 解析失败（忽略元数据）: ${e.message}")
        null
    }

    /**
     * 把 XML 的**叶子标签**读成「小写标签名 → 文本」。
     *
     * 两种 XML 格式共用这一份读取（根标签由调用方判断），不引 `DocumentBuilderFactory`：
     * 多一份 XML 实现，Android 上还多一次 DTD 处理的坑。
     *
     * @return 根标签小写名 to 字段表；连根标签都读不出来时返回 null
     */
    private fun xmlFields(xml: String): Pair<String, Map<String, String>>? {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            // ⚠️ BOM 不剥掉的话 XmlPullParser 会在第一个事件就抛（有些工具写出的 XML 带 BOM）
            setInput(ByteArrayInputStream(stripBom(xml).toByteArray(Charsets.UTF_8)), null)
        }
        val fields = LinkedHashMap<String, String>()
        var root: String? = null
        var capture: String? = null
        val buf = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (root == null) root = parser.name.lowercase(Locale.ROOT)
                    capture = parser.name.lowercase(Locale.ROOT)
                    buf.setLength(0)
                }
                XmlPullParser.TEXT -> if (capture != null) buf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val key = capture
                    val value = buf.toString().trim()
                    if (key != null && value.isNotEmpty()) fields.putIfAbsent(key, value)
                    capture = null
                    buf.setLength(0)
                }
            }
            event = parser.next()
        }
        return root?.let { it to fields }
    }

    private fun comicInfoMeta(f: Map<String, String>) = ComicMetadata(
        title = f["title"],
        series = f["series"],
        // 漫画只有一个「作者」展示位：Writer 优先，没有再退到画师/上色/嵌字/封面作者
        writer = firstNonBlank(f, "writer", "penciller", "colorist", "letterer", "coverartist"),
        translator = f["translator"],
        format = f["format"],
        tags = splitTags(f["tags"].orEmpty()),
        languageIso = f["languageiso"],
        pageCount = f["pagecount"]?.toIntOrNull(),
        year = f["year"]?.toIntOrNull(),
        summary = f["summary"] ?: f["description"],
        publishedAt = dateOf(f["year"], f["month"], f["day"]),
    )

    /**
     * CoMet 的字段名（CoMet 规范）：`title` / `series` / `writer` / `genre` / `language` /
     * `synopsis` / `notes` / `pages` / `year` / `month`。没有来源字段，也不去猜一个。
     */
    private fun cometMeta(f: Map<String, String>) = ComicMetadata(
        title = f["title"],
        series = f["series"],
        writer = firstNonBlank(f, "writer", "penciller", "colorist", "letterer"),
        translator = f["translator"],
        format = f["genre"],
        tags = splitTags(f["genre"].orEmpty()),
        languageIso = f["language"],
        pageCount = (f["pages"] ?: f["pagecount"])?.toIntOrNull(),
        year = f["year"]?.toIntOrNull(),
        summary = f["synopsis"] ?: f["notes"] ?: f["description"] ?: f["summary"],
        publishedAt = dateOf(f["year"], f["month"], null),
    )

    // ===== JSON：ComicBookInfo / 通用 =====

    private fun jsonTyped(json: String): Pair<Int, ComicMetadata>? {
        val root = JSONObject(json)
        // ComicBookInfo：内容挂在 "ComicBookInfo/1.0" 下；也有工具去掉外壳直接写对象
        val book = root.optJSONObject(COMIC_BOOK_INFO)
            ?: root.takeIf { it.has("credits") && (lookup(it, "publicationYear") != null || it.has("issue")) }
        if (book != null) {
            val meta = comicBookInfoMeta(book)
            if (meta.isMeaningful()) return RANK_COMIC_BOOK_INFO to meta
        }
        // ⚠️ 这里没有任何站点语义：不读 id、不拼 URL、不按来源猜测
        val generic = genericJsonMeta(root)
        return if (generic.isMeaningful()) RANK_GENERIC to generic else null
    }

    /** 解析 ComicBookInfo（整段 JSON，或直接是 `ComicBookInfo/1.0` 那层对象）。 */
    fun parseComicBookInfo(json: String): ComicMetadata? = try {
        val root = JSONObject(json)
        val book = root.optJSONObject(COMIC_BOOK_INFO)
            ?: root.takeIf { it.has("credits") && (lookup(it, "publicationYear") != null || it.has("issue")) }
            ?: root
        comicBookInfoMeta(book).takeIf { it.isMeaningful() }
    } catch (e: Exception) {
        LogCollector.w(TAG, "ComicBookInfo 解析失败（忽略元数据）: ${e.message}")
        null
    }

    private fun comicBookInfoMeta(c: JSONObject): ComicMetadata {
        val credits = c.optJSONArray("credits")
        val tags = names(c.optJSONArray("tags")) + splitTags(text(c, "genre").orEmpty())
        val year = int(c, "publicationyear", "year")
        val month = int(c, "publicationmonth")
        return ComicMetadata(
            title = text(c, "title"),
            series = text(c, "series"),
            // 作者位只放「写」的人；没有 Writer 才退到画师（不能把画师混进作者那一行）
            writer = creditNames(credits, "writer", "author", "plot", "script")
                ?: creditNames(credits, "penciller")
                ?: text(c, "writer", "author"),
            translator = creditNames(credits, "translator", "translation", "scanlator"),
            format = text(c, "genre"),
            tags = tags.distinct(),
            // ⚠️ ComicBookInfo 的 language 是 "English" 这种名字，不是 ISO 码
            languageIso = text(c, "language")?.takeIf { it.length in 2..3 },
            pageCount = int(c, "pages", "pagecount", "numberofpages", "numpages"),
            year = year,
            summary = text(c, "comments", "summary", "description", "synopsis"),
            publishedAt = dateOf(year?.toString(), month?.toString(), null),
        )
    }

    /**
     * 通用 JSON：各家下载器/工具自己拍的键名。
     *
     * ⚠️ 没有任何站点语义：不认站点名、不拼 URL、不按 id 推导来源；认不出的键一律忽略。
     */
    private fun genericJsonMeta(o: JSONObject): ComicMetadata {
        val roles = roleTags(o.optJSONArray("tags"))
        val tags = (roles.tags + splitTags(text(o, "genre", "genres").orEmpty())).distinct()
        val year = int(o, "year", "publicationyear")
        return ComicMetadata(
            title = text(o, "title", "book_title", "name"),
            series = text(o, "series", "series_title", "alternate_series") ?: roles.series,
            writer = text(o, "writer", "author", "authors", "artist", "artists", "creator") ?: roles.writer,
            translator = text(o, "translator", "group", "circle", "scanlator", "team") ?: roles.translator,
            format = text(o, "category", "format") ?: roles.format,
            tags = tags,
            // ⚠️ 不能把 "language": "japanese" 这种**名字**塞进 languageIso（它要的是 ISO 码）
            languageIso = text(o, "language_iso", "languageiso"),
            pageCount = int(o, "num_pages", "page_count", "pages", "pagecount", "numberofpages"),
            year = year,
            summary = text(
                o, "summary", "description", "intro", "introduction",
                "synopsis", "note", "notes", "comments", "comment",
            ),
            episode = text(o, "episode_label", "episode"),
            publishedAt = text(o, "published_at", "published", "date") ?: dateOf(year?.toString(), null, null),
        )
    }

    /** `tags: [{type, name}]` 里按**角色名**分出来的字段（booru 风格的通用约定，不是某家专有）。 */
    private class RoleTags {
        var writer: String? = null
        var translator: String? = null
        var series: String? = null
        var format: String? = null
        val tags = mutableListOf<String>()
    }

    /**
     * 读 `tags` 数组：纯字符串就是标签；带 `type` 的对象按角色分拣。
     *
     * 角色词表是通用约定（artist/author → 作者，group/circle/scanlator → 社团，
     * parody/series → 系列，category/genre → 类型），`language` 这类**名字不是 ISO 码**的丢掉。
     */
    private fun roleTags(arr: JSONArray?): RoleTags {
        val out = RoleTags()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            when (val value = arr.opt(i)) {
                is String -> value.trim().takeIf { it.isNotEmpty() }?.let(out.tags::add)
                is JSONObject -> {
                    val name = value.optString("name").trim().takeIf { it.isNotEmpty() } ?: continue
                    when (value.optString("type").lowercase(Locale.ROOT)) {
                        "artist", "author", "writer", "creator" -> out.writer = join(out.writer, name)
                        "group", "circle", "translator", "scanlator" -> out.translator = join(out.translator, name)
                        "parody", "series", "franchise" -> out.series = join(out.series, name)
                        "category", "genre", "format" -> out.format = out.format ?: name
                        "language" -> Unit
                        else -> out.tags += name
                    }
                }
            }
        }
        return out
    }

    private fun join(current: String?, value: String): String =
        if (current == null) value else "$current, $value"

    /**
     * 按**忽略大小写**取键值。
     *
     * ⚠️ 各家 JSON 的键大小写不统一（ComicInfo 系的 `publicationYear` / `pageCount`，
     * 下载器系的 `page_count` / `num_pages`），`JSONObject.opt` 是大小写敏感的，
     * 只写一种拼法就会「字段永远读不到」——表现是年份/页数莫名其妙为空。
     */
    private fun lookup(obj: JSONObject, key: String): Any? {
        if (obj.has(key)) return obj.opt(key)
        val lower = key.lowercase(Locale.ROOT)
        val iterator = obj.keys()
        while (iterator.hasNext()) {
            val actual = iterator.next()
            if (actual.lowercase(Locale.ROOT) == lower) return obj.opt(actual)
        }
        return null
    }

    /** 取第一个非空文本；对象按常见语言键降级，数组按逗号拼。 */
    private fun text(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val raw = lookup(obj, key) ?: continue
            val value = when (raw) {
                is String -> raw.trim()
                is Number -> raw.toString()
                is JSONObject -> localizedText(raw)
                is JSONArray -> names(raw).joinToString(", ")
                else -> ""
            }
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }

    private fun localizedText(obj: JSONObject): String {
        for (key in listOf("english", "en", "japanese", "ja", "chinese", "zh", "name", "title", "default")) {
            val value = obj.optString(key).trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    /** 取第一个正整数；字符串数字也认。 */
    private fun int(obj: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            val raw = lookup(obj, key) ?: continue
            when (raw) {
                is Number -> return raw.toInt().takeIf { it > 0 }
                is String -> raw.trim().toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            }
        }
        return null
    }

    private fun names(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            when (val value = arr.opt(i)) {
                is String -> value.trim().takeIf { it.isNotEmpty() }?.let(out::add)
                is JSONObject -> value.optString("name").trim().takeIf { it.isNotEmpty() }?.let(out::add)
            }
        }
        return out
    }

    /** ComicBookInfo 的 `credits: [{person, role}]`，按 role 关键字取人（多个人用逗号拼）。 */
    private fun creditNames(credits: JSONArray?, vararg roles: String): String? {
        if (credits == null) return null
        val wanted = roles.map { it.lowercase(Locale.ROOT) }
        val people = mutableListOf<String>()
        for (i in 0 until credits.length()) {
            val credit = credits.optJSONObject(i) ?: continue
            val role = credit.optString("role").lowercase(Locale.ROOT)
            if (wanted.none { role.contains(it) }) continue
            credit.optString("person").trim().takeIf { it.isNotEmpty() }?.let(people::add)
        }
        return people.distinct().joinToString(", ").takeIf { it.isNotEmpty() }
    }

    private fun firstNonBlank(f: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { f[it]?.takeIf(String::isNotBlank) }

    /** 有几位拼到几位：`2026` / `2026-09` / `2026-09-27`；年份缺失返回 null。 */
    private fun dateOf(year: String?, month: String?, day: String?): String? {
        val y = year?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val m = month?.trim()?.toIntOrNull()?.takeIf { it in 1..12 } ?: return y.toString()
        val d = day?.trim()?.toIntOrNull()?.takeIf { it in 1..31 }
            ?: return String.format(Locale.ROOT, "%04d-%02d", y, m)
        return String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d)
    }

    // ===== 合并 =====

    /** [primary] 里为空的字段用 [secondary] 补上（`tags` 视为整体，主表非空就不动）。 */
    fun merge(primary: ComicMetadata, secondary: ComicMetadata): ComicMetadata = ComicMetadata(
        title = primary.title ?: secondary.title,
        series = primary.series ?: secondary.series,
        writer = primary.writer ?: secondary.writer,
        translator = primary.translator ?: secondary.translator,
        format = primary.format ?: secondary.format,
        tags = primary.tags.ifEmpty { secondary.tags },
        languageIso = primary.languageIso ?: secondary.languageIso,
        pageCount = primary.pageCount ?: secondary.pageCount,
        year = primary.year ?: secondary.year,
        summary = primary.summary ?: secondary.summary,
        episode = primary.episode ?: secondary.episode,
        publishedAt = primary.publishedAt ?: secondary.publishedAt,
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
            !languageIso.isNullOrBlank() || pageCount != null || year != null ||
            !summary.isNullOrBlank() || !episode.isNullOrBlank() || !publishedAt.isNullOrBlank()

    // ===== 简介文案 =====

    /**
     * 生成简介（导入时写进 `ImportedManga.description`，用户之后可自行编辑）。
     *
     * 列出可用的简介正文、标题、章节、作者、系列、页数、发布日期和标签；空字段不占行。
     * ⚠️ **没有「来源」行**（来源字段已从模型里删除，见类注释）。
     * 标签按 [MAX_TAGS_CHARS] 截断 —— 简介行只有两行可见，几十个标签拼进去等于什么都看不见。
     */
    fun buildDescription(context: Context, meta: ComicMetadata): String {
        val lines = mutableListOf<String>()
        meta.summary?.takeIf(String::isNotBlank)?.let {
            lines += android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }
        meta.title?.takeIf(String::isNotBlank)?.let { lines += context.getString(R.string.book_meta_title, it) }
        meta.episode?.takeIf(String::isNotBlank)?.let { lines += context.getString(R.string.book_meta_episode, it) }
        meta.writer?.takeIf { it.isNotBlank() }
            ?.let { lines += context.getString(R.string.comic_meta_author, it) }
        meta.translator?.takeIf { it.isNotBlank() }
            ?.let { lines += context.getString(R.string.comic_meta_circle, it) }
        meta.series?.takeIf { it.isNotBlank() }
            ?.let { lines += context.getString(R.string.comic_meta_series, it) }
        meta.pageCount?.let { lines += context.getString(R.string.book_meta_pages, it) }
        meta.publishedAt?.takeIf(String::isNotBlank)?.let { lines += context.getString(R.string.book_meta_published, it) }
        if (meta.tags.isNotEmpty()) {
            val joined = meta.tags.joinToString(", ")
            val text = if (joined.length > MAX_TAGS_CHARS) {
                joined.take(MAX_TAGS_CHARS).trimEnd().trimEnd(',') + "…"
            } else {
                joined
            }
            lines += context.getString(R.string.comic_meta_tags, text)
        }
        return lines.joinToString("\n")
    }

    /** 解析压缩包/目录里的元数据条目并生成简介；没有元数据返回空串（简介留空）。 */
    fun descriptionOf(context: Context, texts: List<Pair<String, String>>): String {
        val meta = parse(texts) ?: return ""
        return buildDescription(context, meta)
    }

    /**
     * 清掉历史简介里的「来源」行（老版本导入的书库里还留着这些行）。
     *
     * 判据是**本地化前缀**（`R.string.comic_meta_source` 格式化空串的结果），不是域名关键字 ——
     * ⚠️ 用域名匹配会把刚删掉的字面量重新写回 dex，等于把事故装回去。
     *
     * @param prefix 当前语言的来源前缀，例如「来源：」/「Source:」
     * @return 清理后的文本；没有任何一行命中时原样返回（调用方据此判断要不要写库）
     */
    fun stripSourceLines(description: String, prefix: String): String {
        if (description.isEmpty()) return description
        val keys = listOf(prefix, "来源：", "Source:").map { it.trim() }.filter { it.isNotEmpty() }
        val kept = description.lines().filterNot { line ->
            val trimmed = line.trimStart()
            keys.any { trimmed.startsWith(it) }
        }
        return kept.joinToString("\n")
    }

    // ===== 工具 =====

    private fun splitTags(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun stripBom(text: String): String =
        text.removePrefix("\uFEFF").trimStart('\uFEFF', '\u0000')
}
