package com.moe.starflow.novel.parser

import android.util.Xml
import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelChapterMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 2/3 解析（自写，不引三方库 —— 项目无外部 maven 仓库配置）。
 *
 * 流程：`META-INF/container.xml` → OPF 路径 → OPF 的 manifest / spine / metadata → 目录。
 *
 * ### 三处容易写错、都按最坏情况处理过的地方
 *
 * 1. **章节顺序的权威是 `spine`**，不是文件名、也不是 manifest 顺序。按文件名排会得到
 *    `ch10` 在 `ch2` 前面（字典序），整本书错乱。
 * 2. **加密判定不能只看「有没有 `encryption.xml`」**。商用 EPUB 普遍对**字体**做混淆，
 *    那个文件里只列字体；一刀切会把大量合法书判成 DRM 而拒之门外。这里解析出被加密条目的
 *    URI，**只有当它指向正文文档（xhtml/html/htm）时**才判 DRM。
 * 3. **章标题不能靠逐个读正文 XHTML 取 h1** —— 300 章的书等于导入时全文解压，与「正文
 *    懒加载」直接冲突。这里优先读 EPUB 自带的目录（EPUB2 的 NCX / EPUB3 的 nav 文档，
 *    都是一两个小文件），都没有才退化成「只读每章开头 4KB 找标题」，最后兜底「第 N 章」。
 *
 * ⚠️ 本 object 无任何可变状态（解析状态全部是函数内的局部变量）：`parse` 会被多个导入任务
 * 并发调用，任何共享字段都会串数据。
 *
 * ⚠️ 阻塞 I/O，调用方须在主线程之外调用。
 */
object EpubParser : NovelParser {

    private const val CONTAINER = "META-INF/container.xml"
    private const val ENCRYPTION = "META-INF/encryption.xml"
    private const val NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
    private const val NS_OPF = "http://www.idpf.org/2007/opf"
    private const val NS_DC = "http://purl.org/dc/elements/1.1/"

    /** 退化路径下每章只读这么多字节找标题（不整章解压）。 */
    private const val HEAD_BYTES = 4096

    private const val MAX_TITLE_LEN = 60

    override suspend fun parse(file: File): NovelBook = withContext(Dispatchers.IO) {
        ZipFile(file).use { zip ->
            rejectIfContentEncrypted(zip)

            val opfPath = zip.getEntry(CONTAINER)?.let { entry ->
                zip.getInputStream(entry).use { parseContainer(it) }
            } ?: throw IllegalStateException("EPUB 缺少 $CONTAINER")

            val opfEntry = zip.getEntry(opfPath) ?: throw IllegalStateException("OPF 不存在: $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = zip.getInputStream(opfEntry).use { parseOpf(it) }

            val base = if (opfDir.isEmpty()) "" else "$opfDir/"
            val idToHref = opf.manifest.associate { it.id to it.href }

            val chapterPaths = opf.spine.mapNotNull { idref ->
                idToHref[idref]?.let { normalize(base, it) }
            }
            if (chapterPaths.isEmpty()) throw IllegalStateException("NO_TEXT_CHAPTER")

            val tocTitles = readTocTitles(zip, opf, base)

            val chapters = chapterPaths.mapIndexed { i, path ->
                NovelChapterMeta(
                    index = i,
                    title = tocTitles[path]
                        ?: headTitleOf(zip, path)
                        ?: defaultTitle(i),
                    locator = path,
                )
            }

            NovelBook(
                title = opf.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                author = opf.creator?.takeIf { it.isNotBlank() },
                chapters = chapters,
                coverBytes = readCover(zip, opf, base, idToHref),
            )
        }
    }

    override suspend fun loadChapter(file: File, locator: String): String = withContext(Dispatchers.IO) {
        val raw = ZipFile(file).use { zip ->
            val entry = zip.getEntry(locator) ?: return@withContext ""
            zip.getInputStream(entry).use { it.readBytes() }
        }
        HtmlTextExtractor.extract(TextEncoding.decode(raw))
    }

    // ===== 加密 =====

    /**
     * 只在**正文文档**被加密时拒绝（见类注释第 2 条）。字体混淆放行。
     */
    private fun rejectIfContentEncrypted(zip: ZipFile) {
        val entry = zip.getEntry(ENCRYPTION) ?: return
        if (entry.size == 0L) return
        val uris = zip.getInputStream(entry).use { parseEncryptionUris(it) }
        val contentEncrypted = uris.any { uri ->
            val path = uri.substringBefore('#').lowercase()
            path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm")
        }
        if (contentEncrypted) throw IllegalStateException("ENCRYPTED")
    }

    private fun parseEncryptionUris(input: InputStream): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val parser = newParser(input)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "CipherReference") {
                    parser.getAttributeValue(null, "URI")?.let { out.add(it) }
                }
                event = parser.next()
            }
        }
        return out
    }

    // ===== container.xml =====

    private fun parseContainer(input: InputStream): String {
        val parser = newParser(input)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                parser.getAttributeValue(null, "full-path")?.let { return it }
            }
            event = parser.next()
        }
        throw IllegalStateException("container.xml 里没有 rootfile full-path")
    }

    // ===== OPF =====

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String?,
        val properties: String?,
    )

    private class Opf(
        val title: String?,
        val creator: String?,
        val manifest: List<ManifestItem>,
        val spine: List<String>,
        val metaCoverId: String?,
        val ncxPath: String?,
        val navPath: String?,
    )

    private fun parseOpf(input: InputStream): Opf {
        val parser = newParser(input)
        val manifest = mutableListOf<ManifestItem>()
        val spine = mutableListOf<String>()
        var title: String? = null
        var creator: String? = null
        var metaCover: String? = null
        var spineTocAttr: String? = null

        // 文本捕获：只在目标元素（dc:title / dc:creator）上开始累积，END_TAG 按深度收口
        var captureName: String? = null
        var captureDepth = 0
        val buf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    val ns = parser.namespace
                    // ⚠️ 只在**目标元素**上开始捕获，不能「遇到任意最外层元素就开始累积」：
                    // dc:title 嵌在 metadata 里，那样写会让 buf 先装 metadata 与 title 之间的
                    // 空白再装标题，且 END_TAG 的深度永远对不上 → 标题恒取不到。
                    if ((name == "title" && (ns == NS_DC || ns == null)) ||
                        (name == "creator" && (ns == NS_DC || ns == null))
                    ) {
                        captureName = name
                        captureDepth = parser.depth
                        buf.setLength(0)
                    }
                    when (name) {
                        // <meta name="cover" content="cover-id"/> 是空元素，在 START_TAG 就取属性
                        "meta" -> if (parser.getAttributeValue(null, "name")?.equals("cover", true) == true) {
                            metaCover = parser.getAttributeValue(null, "content")
                        }
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            if (id != null && href != null) {
                                manifest.add(
                                    ManifestItem(
                                        id = id,
                                        href = href,
                                        mediaType = parser.getAttributeValue(null, "media-type"),
                                        properties = parser.getAttributeValue(null, "properties"),
                                    )
                                )
                            }
                        }
                        "itemref" -> {
                            parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                        }
                        "spine" -> {
                            spineTocAttr = parser.getAttributeValue(null, "toc")
                        }
                    }
                }

                XmlPullParser.TEXT -> if (captureName != null) buf.append(parser.text)

                XmlPullParser.END_TAG -> {
                    if (captureName != null && parser.name == captureName && parser.depth == captureDepth) {
                        val text = buf.toString().trim()
                        when (captureName) {
                            "title" -> title = text
                            "creator" -> creator = text
                        }
                        captureName = null
                        captureDepth = 0
                        buf.setLength(0)
                    }
                }
            }
            event = parser.next()
        }

        // 没有 spine（或 spine 里没有任何 idref）不在这里抛：由调用方在解析出章节路径后
        // 统一按 NO_TEXT_CHAPTER 报错（从用户视角都是「这本书没有可读的正文」）。
        val ncxPath = manifest.firstOrNull {
            it.mediaType == "application/x-dtbncx+xml" || (spineTocAttr != null && it.id == spineTocAttr)
        }?.href

        val navPath = manifest.firstOrNull {
            val props = it.properties ?: ""
            props.split(' ', '\t').any { p -> p.equals("nav", true) }
        }?.href

        return Opf(title, creator, manifest, spine, metaCover, ncxPath, navPath)
    }

    // ===== 目录（NCX / nav） =====

    /**
     * 读 EPUB 自带目录取章标题。返回「zip 内全路径 → 标题」。
     * NCX 与 nav 都读不到时返回空表，由调用方走「读每章开头」的退化路径。
     */
    private fun readTocTitles(zip: ZipFile, opf: Opf, base: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        opf.ncxPath?.let { href ->
            val path = normalize(base, href)
            val ncxBase = path.substringBeforeLast('/', "")
            val ncxBaseDir = if (ncxBase.isEmpty()) "" else "$ncxBase/"
            zip.getEntry(path)?.let { entry ->
                zip.getInputStream(entry).use { parseNcx(it, ncxBaseDir, out) }
            }
        }
        if (out.isEmpty()) {
            opf.navPath?.let { href ->
                val path = normalize(base, href)
                val navBase = path.substringBeforeLast('/')
                val navBaseDir = if (navBase.isEmpty()) "" else "$navBase/"
                zip.getEntry(path)?.let { entry ->
                    zip.getInputStream(entry).use { parseNav(it, navBaseDir, out) }
                }
            }
        }
        return out
    }

    private fun parseNcx(input: InputStream, baseDir: String, out: MutableMap<String, String>) {
        runCatching {
            val parser = newParser(input)
            var depth = 0
            var labelDepth = -1
            val label = StringBuilder()
            var pendingLabel: String? = null
            // NCX 里 <content> 可能出现在 <text> 之前或之后，用一个局部变量兜住两种顺序。
            // ⚠️ 必须是局部变量：本 object 会被多个导入任务并发调用，任何对象级字段都会串数据。
            var pendingContent: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "navPoint" -> depth++
                        "text" -> {
                            labelDepth = parser.depth
                            label.setLength(0)
                        }
                        "content" -> {
                            val src = parser.getAttributeValue(null, "src")
                            if (src != null) {
                                if (pendingLabel != null) {
                                    putTitle(out, baseDir, src, pendingLabel!!)
                                    pendingLabel = null
                                } else {
                                    pendingContent = src
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (labelDepth > 0 && parser.depth == labelDepth) {
                        label.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "text" && labelDepth > 0) {
                        val t = label.toString().trim()
                        labelDepth = -1
                        val src = pendingContent
                        if (src != null) {
                            putTitle(out, baseDir, src, t)
                            pendingContent = null
                        } else {
                            pendingLabel = t
                        }
                    }
                }
                event = parser.next()
            }
        }
    }

    private fun parseNav(input: InputStream, baseDir: String, out: MutableMap<String, String>) {
        runCatching {
            val parser = newParser(input)
            var inLink = false
            var linkDepth = 0
            var href: String? = null
            val label = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> if (parser.name == "a") {
                        inLink = true
                        linkDepth = parser.depth
                        href = parser.getAttributeValue(null, "href")
                        label.setLength(0)
                    }
                    XmlPullParser.TEXT -> if (inLink && parser.depth >= linkDepth) label.append(parser.text)
                    XmlPullParser.END_TAG -> if (parser.name == "a" && inLink) {
                        val title = label.toString().trim()
                        val h = href
                        if (h != null && title.isNotEmpty()) putTitle(out, baseDir, h, title)
                        inLink = false
                        href = null
                    }
                }
                event = parser.next()
            }
        }
    }

    private fun putTitle(out: MutableMap<String, String>, baseDir: String, src: String, title: String) {
        val clean = title.replace(Regex("""\s+"""), " ").trim()
        if (clean.isEmpty() || clean.length > MAX_TITLE_LEN) return
        val path = normalize(baseDir, src)
        // 同一路径只记第一次（目录里同一章可能因分卷出现多次）
        out.putIfAbsent(path, clean)
    }

    // ===== 退化路径：只读每章开头 =====

    /**
     * 没有目录时：只解压每章**开头** [HEAD_BYTES] 字节，取第一行短文本当标题。
     * 不整章解压，避免 300 章的书在导入时把所有正文都过一遍。
     */
    private fun headTitleOf(zip: ZipFile, path: String): String? {
        return try {
            val entry = zip.getEntry(path) ?: return null
            val head = zip.getInputStream(entry).use { readAtMost(it, HEAD_BYTES) }
            val text = HtmlTextExtractor.extract(TextEncoding.decode(head))
            text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it.length <= MAX_TITLE_LEN }
        } catch (e: Exception) {
            null
        }
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val buf = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val n = input.read(buf, read, limit - read)
            if (n < 0) break
            read += n
        }
        return if (read == limit) buf else buf.copyOf(read)
    }

    // ===== 封面 =====

    private fun readCover(
        zip: ZipFile,
        opf: Opf,
        base: String,
        idToHref: Map<String, String>,
    ): ByteArray? {
        val byProperties = opf.manifest.firstOrNull {
            it.properties?.split(' ', '\t')?.any { p -> p.equals("cover-image", true) } == true
        }
        val byMetaId = opf.metaCoverId?.let { idToHref[it] }
        // 兜底：id 或文件名里含 cover 的图片条目（老 EPUB 常见写法）
        val byName = opf.manifest.firstOrNull {
            (it.mediaType?.startsWith("image/") == true) &&
                (it.id.contains("cover", true) || it.href.contains("cover", true))
        }
        val href = byProperties?.href ?: byMetaId ?: byName?.href ?: return null
        return runCatching {
            val path = normalize(base, href)
            zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        }.getOrNull()
    }

    private fun defaultTitle(index: Int) = "第 ${index + 1} 章"

    private fun newParser(input: InputStream): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }

    /** 把相对 href 解析成 zip 内全路径，处理 `../`、`./`、绝对路径与 `#` 片段。 */
    private fun normalize(baseDir: String, href: String): String {
        val decoded = href.substringBefore('#').trim()
        if (decoded.isEmpty()) return ""
        val combined = if (decoded.startsWith('/')) decoded.drop(1) else baseDir + decoded
        val out = mutableListOf<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }
}
