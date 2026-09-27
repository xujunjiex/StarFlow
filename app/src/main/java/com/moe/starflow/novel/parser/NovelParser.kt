package com.moe.starflow.novel.parser

import com.moe.starflow.novel.model.NovelBook
import com.moe.starflow.novel.model.NovelFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
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
     * （末尾原本还有一条「有图片 → 判为漫画」的分支，它两个出口都是 `return null`，是死代码，
     * 已删；文本优先这条规则本身没变 —— 图片包仍然走到最后的 `return null`。）
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
        // 有 html/txt → 小说包（文本优先于图片，判错的代价不对称，见上方 KDoc）
        if (names.any { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in HTML_EXT || it.endsWith(".txt", true) }) {
            return NovelFormat.ZIP_HTML
        }
        // 其余（含纯图片包）：本链路不接
        return null
    }
}

/**
 * 小说解析的**读取上限策略**：一处定义，所有解析器共用。
 *
 * ### 为什么必须封顶
 * 解析器直接吃用户丢进来的文件，而 `file.readBytes()` / `zip.getInputStream(e).readBytes()`
 * 都是「有多少读多少」：一个几百 MB 的 txt，或一个压缩比 1000:1 的 zip 炸弹（声明 1KB、
 * 解压 2GB），会在导入线程上把堆吃爆 —— 用户看到的是「导入卡住不动」，或整个进程被 OOM
 * 杀掉，且**没有任何错误提示**。封面也在导入路径上（`EpubParser.readCover`），不需要用户
 * 做任何操作就会踩到。所以每条读路径都必须有上限。
 *
 * ### 数字怎么来的
 * - 单条目 8MB：正常章节 xhtml 几十 KB，内嵌插图/封面几百 KB ~ 2MB，8MB 已远超合理值。
 * - 单本 200MB：300 章 × 每章 100KB ≈ 30MB，加上插图也远用不到。
 * - 单包 20000 条：正常书几百章；这条只用来挡「构造出海量条目」的包。
 * - 单文件 100MB：txt 小说几 MB 是常态，100MB 已是极端值。
 *
 * ### 超限报什么
 * 抛 [TooLargeException]（`ZipException` 的子类，为了不破坏「格式损坏」那条既有路径的归类）。
 * `NovelImportManager.classify` **先判这个子类**再判 `ZipException`，映射到
 * `NovelImportFailureReason.TOO_LARGE`（「文件太大，无法导入」）。
 * ⚠️ 那个先后顺序不能反：反了就被 `ZipException → NOT_ARCHIVE` 吃掉，用户看到的是
 * 「格式不支持或文件损坏」—— 文件其实好得很，只是太大，按那句话去查永远查不出结果。
 *
 * ⚠️ 本 object 无可变状态，可安全并发调用。
 */
object NovelReadLimits {

    const val MAX_ZIP_ENTRY_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_DECOMPRESSED_BYTES = 200L * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 20_000
    const val MAX_FILE_BYTES = 100 * 1024 * 1024

    /** 超过读取上限。类型是 `ZipException`，为的是复用既有的失败归类（见 object KDoc）。 */
    class TooLargeException(message: String) : ZipException(message)

    /**
     * 读满 [limit] 字节就停：**多出来的不读，也不报错**。
     * 用于「只读文件开头」这类故意截断的场景（`EpubParser.headTitleOf` 找章标题）。
     */
    fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val buf = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val n = input.read(buf, read, limit - read)
            if (n < 0) break
            read += n
        }
        return if (read == limit) buf else buf.copyOf(read)
    }

    /**
     * 读完整条流，**超过 [limit] 立刻抛** [TooLargeException]（最多多读一个缓冲块就停）。
     * [what] 只进异常文案（条目名 / 文件名），方便从日志看出是哪一条撑爆的。
     */
    fun readFullyAtMost(input: InputStream, limit: Int, what: String): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw TooLargeException("$what 超过 ${limit / (1024 * 1024)}MB 读取上限")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 读一个 zip 条目（按解压后的字节数算上限，不看压缩包里的声明值 —— 那个值可以撒谎）。 */
    fun readZipEntry(zip: ZipFile, entry: ZipEntry, limit: Int = MAX_ZIP_ENTRY_BYTES): ByteArray =
        zip.getInputStream(entry).use { readFullyAtMost(it, limit, entry.name) }

    /**
     * 整体读一个文件。
     *
     * **先看 [File.length] 再读**：超限时连一次大分配都不做 —— 读之前就拒绝，才叫防 OOM
     * （而不是读到一半才发现太大，那时内存已经花出去了）。长度也可能撒谎（流式/被并发改写），
     * 所以读取本身照样带上限。
     */
    fun readFile(file: File, limit: Int = MAX_FILE_BYTES): ByteArray {
        if (file.length() > limit) {
            throw TooLargeException("${file.name} 超过 ${limit / (1024 * 1024)}MB 读取上限")
        }
        return file.inputStream().use { readFullyAtMost(it, limit, file.name) }
    }

    /** 条目数超限的包直接拒掉：光是把条目名列出来就已经在吃内存了。 */
    fun checkEntryCount(zip: ZipFile, limit: Int = MAX_ZIP_ENTRIES) {
        if (zip.size() > limit) throw TooLargeException("压缩包条目数 ${zip.size()} 超过上限 $limit")
    }

    /**
     * 一本书的解压配额：单条目上限 + 单本累计上限。
     *
     * ⚠️ **每次 parse 新建一个**，绝不能做成 object 级字段：解析器会被多个导入任务并发调用，
     * 共享计数器必然串数据（与 `EpubParser` 类注释里那条「无状态」约束同因）。
     */
    class ZipBudget {
        private var spent = 0L

        /** 读一个条目并记进总配额。 */
        fun readEntry(zip: ZipFile, entry: ZipEntry): ByteArray {
            val bytes = readZipEntry(zip, entry)
            spend(entry.name, bytes.size)
            return bytes
        }

        /** 记一笔已经读出来的字节（给「只读开头」那种截断读用）。 */
        fun spend(what: String, bytes: Int) {
            spent += bytes
            if (spent > MAX_TOTAL_DECOMPRESSED_BYTES) {
                throw TooLargeException(
                    "解压总量超过 ${MAX_TOTAL_DECOMPRESSED_BYTES / (1024 * 1024)}MB（读到 $what 时）"
                )
            }
        }
    }
}
