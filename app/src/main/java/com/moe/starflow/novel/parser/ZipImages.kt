package com.moe.starflow.novel.parser

import com.moe.starflow.utils.LogCollector
import java.io.File
import java.util.zip.ZipFile

/**
 * ZIP/EPUB 包内图片的解析与读取。**EPUB 与 ZIP 小说包共用这一份**。
 *
 * 抽出来的理由与 `NovelParsers` 同一套：两个解析器各写一遍「相对地址解析 + SVG 包装再往里找一层 +
 * 百分号解码 + 单章额度」，迟早在其中一处漏掉一条 —— 表现是「同一本书换个导入方式插图就没了」。
 *
 * ⚠️ 无状态（[patternCache] 除外，它是纯函数缓存），可被多个导入/阅读任务并发调用。
 */
internal object ZipImages {

    private const val TAG = "ZipImages"

    /**
     * 包内某个条目的**目录**（相对地址的基准）。
     *
     * ⚠️ **必须带结尾的 `/`**：直接拿 `substringBeforeLast('/')` 当基准会拼出
     * `OEBPS/Text../Images/a.png` 这种鬼路径 —— `getEntry` 找不到，于是**这本书的插图一张都读不出来**
     * （而且不报错，只是静默回落占位文本）。踩过。
     */
    fun baseDirOf(path: String): String {
        val dir = path.substringBeforeLast('/', "")
        return if (dir.isEmpty()) "" else "$dir/"
    }

    /**
     * 把相对 href 解析成 zip 内全路径，处理 `../`、`./`、绝对路径与 `#` 片段。
     *
     * ⚠️ **不解百分号转义**：章节路径要用的就是这一份（`EpubParser` 的 OPF/NCX 走它）。
     * URI 形式的地址走 [resolveUri]。
     */
    fun normalize(baseDir: String, href: String): String {
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

    /**
     * 图片地址（URI 引用）→ zip 内全路径。
     *
     * ⚠️ 与 [normalize] 的唯一差别是**先解百分号转义**：zip 里的条目名是字面量，而 xhtml 里的
     * 地址是 URI —— `Images/封面 1.jpg` 在文档里写作 `Images/%E5%B0%81%E9%9D%A2%201.jpg`，
     * 不解码就永远 `getEntry` 不到（表现是"这本书的插图都显示不出来"，而别的书正常）。
     */
    fun resolveUri(baseDir: String, href: String): String = normalize(baseDir, percentDecode(href))

    /**
     * 解 `%XX`（UTF-8）。**不用 `URLDecoder`**：它把 `+` 当空格，而文件名里的 `+` 是**字面量**
     * （`a+b.jpg` 会被它解成 `a b.jpg`，于是那张图 lookup 不到）。
     */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
                continue
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /**
     * 按顺序读出 [sources] 每一张图（读不到 / 超出本章额度的那张为 null）。
     *
     * @param baseDir 引用者（xhtml）在包内的目录，相对地址以它为基准
     * @param sources [HtmlTextExtractor] 抽出的图片地址（已按文档顺序、与正文标记一一对应）
     */
    fun read(
        zip: ZipFile,
        baseDir: String,
        sources: List<String>,
        budget: NovelReadLimits.ZipBudget,
    ): List<ByteArray?> {
        if (sources.isEmpty()) return emptyList()
        var spent = 0
        return sources.map { src ->
            if (src.isEmpty() || spent >= NovelReadLimits.MAX_CHAPTER_IMAGE_BYTES) return@map null
            val bytes = readOne(zip, baseDir, src, budget) ?: return@map null
            spent += bytes.size
            bytes
        }
    }

    /** 读一张图；路径指向 SVG 时**再往里找一层真正的位图**（EPUB3 封面常见写法）。 */
    fun readOne(
        zip: ZipFile,
        baseDir: String,
        src: String,
        budget: NovelReadLimits.ZipBudget,
    ): ByteArray? = try {
        if (src.startsWith("data:", ignoreCase = true)) {
            decodeDataUri(src)
        } else {
            val path = resolveUri(baseDir, src)
            val bytes = entryBytes(zip, path, budget)
            if (bytes != null && looksLikeMarkup(bytes)) {
                // `<svg><image xlink:href="cover.jpg"/></svg>`：SVG 本身画不出来（BitmapFactory
                // 不认识矢量图），里面那张位图才是要显示的。只往里找一层，不做递归。
                val inner = HtmlTextExtractor.firstImageSource(TextEncoding.decode(bytes))
                val innerPath = inner?.let { resolveUri(baseDirOf(path), it) }
                innerPath?.let { entryBytes(zip, it, budget) }
            } else {
                bytes
            }
        }
    } catch (e: NovelReadLimits.TooLargeException) {
        // 单张超限只丢这一张（走占位文本）。上限本身不撤，理由见 [NovelReadLimits]
        LogCollector.w(TAG, "插图超过读取上限，已跳过: $src")
        null
    } catch (e: Exception) {
        // 单张图读不出来（条目损坏 / 路径不对）不该拖垮整章正文
        null
    }

    /**
     * 读一张**本地文件**图片（单 HTML / 文件夹格式；包内格式走 [readOne]）。
     *
     * ⚠️ 解析出来的路径必须落在 [baseDir] 内（路径穿越守卫）：不然一份"小说"里写
     * `../../../../data/data/.../x.jpg` 就能让阅读器去读 app 私有目录里的任意文件。
     */
    fun readLocalOne(baseDir: File, src: String, bookRoot: File = baseDir): ByteArray? {
        return try {
            if (src.startsWith("data:", ignoreCase = true)) {
                decodeDataUri(src)
            } else {
                val f = resolveLocal(baseDir, src, bookRoot)?.let { com.moe.starflow.library.LibrarySources.file(it) }
                if (f == null || !f.isFile) {
                    null
                } else {
                    val bytes = NovelReadLimits.readFile(f, NovelReadLimits.MAX_ZIP_ENTRY_BYTES)
                    if (looksLikeMarkup(bytes)) {
                        val inner = HtmlTextExtractor.firstImageSource(TextEncoding.decode(bytes))
                        val innerFile = inner?.let { resolveLocal(f.parentFile ?: baseDir, it, bookRoot)?.let { at -> com.moe.starflow.library.LibrarySources.file(at) } }
                        innerFile?.takeIf { it.isFile }?.let {
                            NovelReadLimits.readFile(it, NovelReadLimits.MAX_ZIP_ENTRY_BYTES)
                        }
                    } else {
                        bytes
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 本地文件版的地址解析：解百分号转义 + 去 `#` 片段 + **夹在 [baseDir] 内**（越界返回 null）。 */
    fun resolveLocal(baseDir: File, href: String, bookRoot: File = baseDir): File? {
        val path = percentDecode(href).substringBefore('#').trim()
        if (path.isEmpty() || path.startsWith("//") || path.contains("://")) return null
        val base = bookRoot.canonicalFile
        val f = File(baseDir, path).canonicalFile
        return f.takeIf { it.path == base.path || it.path.startsWith(base.path + File.separator) }
    }

    /**
     * `data:` 内嵌图片（base64）。
     *
     * ⚠️ 用 MIME 解码器：data URI 里常见每 76 字符一个换行，严格解码器会直接抛
     * `IllegalArgumentException`。非 base64（`data:image/png,%89PNG...`）的写法极少见，不认。
     */
    private fun decodeDataUri(src: String): ByteArray? {
        val comma = src.indexOf(',')
        if (comma < 0) return null
        val meta = src.substring(5, comma)
        if (!meta.contains("base64", ignoreCase = true)) return null
        return try {
            java.util.Base64.getMimeDecoder().decode(src.substring(comma + 1))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun entryBytes(zip: ZipFile, path: String, budget: NovelReadLimits.ZipBudget): ByteArray? {
        if (path.isEmpty()) return null
        val e = zip.getEntry(path) ?: return null
        return budget.readEntry(zip, e)
    }

    /** 按内容（而不是扩展名）判"这是一段标记语言"：SVG 与伪装成图片的标记都靠它认出来。 */
    fun looksLikeMarkup(bytes: ByteArray): Boolean {
        for (b in bytes) {
            when {
                b == '<'.code.toByte() -> return true
                // 前导空白 / BOM 跳过；一遇到真实数据字节就说明不是文本标记
                b == ' '.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() ||
                    b == '\t'.code.toByte() || b == 0xEF.toByte() || b == 0xBB.toByte() ||
                    b == 0xBF.toByte() -> Unit
                else -> return false
            }
        }
        return false
    }
}
