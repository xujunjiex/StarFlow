package com.moe.starflow.library

import com.moe.starflow.mangaimport.data.ArchiveKind
import com.moe.starflow.mangaimport.data.ArchiveTypes
import com.moe.starflow.mangaimport.data.ComicMetadata
import com.moe.starflow.mangaimport.data.ComicMetadataParser
import com.moe.starflow.mangaimport.data.LibraryArchiveReader
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.data.MangaImporter
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.io.InputStream

/** Bounded optional metadata reads. Directory sources are read from their actual backend. */
object BookMetadataReader {
    const val VERSION = 5
    private val BOOK_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr", "7z", "pdf", "epub", "txt", "html", "htm", "xhtml")

    /** 与来源同名的侧车元数据后缀：`book.meta.json` / `book.ComicInfo.xml` / `book.comet.xml` … */
    private val SIDECAR_SUFFIXES = listOf("meta.json", "ComicInfo.xml", "comet.xml", "ComicBookInfo.json")

    /** Reuse an existing library grant; a single-file grant never grants arbitrary siblings. */
    fun copyAuthorizedSidecars(context: android.content.Context, document: android.net.Uri, destination: File, kind: String) {
        optional(document.toString()) {
            val session = LibrarySessions.current(context)
            val source = if (document.scheme == "file") document.path?.let(::File)
                else (session.storage as? SafLibraryStorage)?.pathForDocument(document, "${kind}_import")?.let(session::local)
            if (source != null) sidecarTexts(source).forEach { (name, text) ->
                val target = File(destination, name).canonicalFile
                require(target.path.startsWith(destination.canonicalPath + File.separator))
                if (!target.exists()) { target.parentFile!!.mkdirs(); target.writeText(text, Charsets.UTF_8) }
            }
        }
    }

    internal fun namedSidecar(sourceName: String, name: String): Boolean {
        val stem = sourceName.substringBeforeLast('.', sourceName)
        val lower = name.lowercase()
        return SIDECAR_SUFFIXES.any { suffix ->
            val tail = suffix.lowercase()
            lower == "$stem.$tail".lowercase() || lower == "$sourceName.$tail".lowercase()
        }
    }

    internal fun sidecarNodes(nodes: List<LibraryNode>, source: String, allowGeneric: Boolean): List<LibraryNode> {
        val sourceName = source.replace('\\', '/').substringAfterLast('/')
        val books = nodes.filter { !it.directory && it.path.substringAfterLast('.').lowercase() in BOOK_EXTENSIONS }
        val dedicated = allowGeneric && nodes.none { it.directory } && books.size == 1 && books.single().path == source
        return nodes.filter { !it.directory && (namedSidecar(sourceName, it.path.substringAfterLast('/')) ||
            dedicated && ComicMetadataParser.isMetadataEntry(it.path)) }
            .sortedBy { if (namedSidecar(sourceName, it.path.substringAfterLast('/'))) 0 else 1 }
    }
    fun readText(input: InputStream, size: Long = -1): String? {
        val limit = ComicMetadataParser.MAX_METADATA_BYTES
        if (size > limit) return null
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size().toLong() + count > limit) return null
            bytes.write(buffer, 0, count)
        }
        return bytes.toString("UTF-8")
    }

    fun directoryTexts(directory: File, nodes: List<LibraryNode>? = null): List<Pair<String, String>> {
        val staged = localDirectoryTexts(directory)
        if (staged.isNotEmpty()) return staged
        val located = LibrarySessions.findFile(directory)
        if (located != null && located.first.storage !is FileLibraryStorage) {
            val (session, root) = located
            return (nodes ?: optional(directory.path) { LibrarySources.listFiles(directory) }.orEmpty()).filter {
                ComicMetadataParser.isMetadataEntry(it.path) && it.size <= ComicMetadataParser.MAX_METADATA_BYTES
            }.mapNotNull { node -> optional(node.path) {
                session.storage.read(node.path).use { readText(it, node.size) }
                    ?.let { node.path.removePrefix("$root/") to it }
            } }
        }
        return staged
    }

    private fun localDirectoryTexts(directory: File): List<Pair<String, String>> {
        if (!directory.isDirectory) return emptyList()
        val root = directory.canonicalPath + File.separator
        return directory.walkTopDown().filter {
            it.isFile && it.canonicalPath.startsWith(root) && ComicMetadataParser.isMetadataEntry(it.name)
        }.mapNotNull { file -> optional(file.path) {
            file.inputStream().use { readText(it, file.length()) }
                ?.let { file.relativeTo(directory).invariantSeparatorsPath to it }
        } }.toList()
    }

    /**
     * 按**已知章标题**把来源里的元数据分成「书级 / 章级」。
     *
     * 章标题来自 `MangaChapter.title`（= 章节子目录名）。路径里出现某个章标题目录段 → 该章，
     * 否则算书级（包括「包了一层书名目录」的 `Book/ComicInfo.xml`）。
     *
     * ⚠️ 书级只取这一份 —— 章节的 json **不能**并进书简介，否则多章书永远只显示最浅那份。
     */
    fun splitMetadata(
        source: File,
        chapterTitles: Set<String>,
    ): Pair<List<Pair<String, String>>, Map<String, List<Pair<String, String>>>> =
        ComicMetadataParser.splitByChapter(sourceTexts(source), chapterTitles)

    /** 章标题 → 解析后的元数据（作品数据页按需调用；源不可读时返回空表）。 */
    fun chapterMetadata(source: File, chapters: List<MangaChapter>): Map<String, ComicMetadata> {
        val titles = chapters.mapNotNull { it.title.takeIf(String::isNotBlank) }.toSet()
        if (titles.isEmpty()) return emptyMap()
        return optional(source.path) {
            ComicMetadataParser.splitByChapter(sourceTexts(source), titles).second
                .mapNotNull { (title, texts) -> ComicMetadataParser.parse(texts)?.let { title to it } }
                .toMap()
        }.orEmpty()
    }

    fun sourceTexts(source: File): List<Pair<String, String>> {
        if (source.isDirectory) return directoryTexts(source)
        val embedded = optional(source.path) {
            when (ArchiveTypes.detect(source)) {
                ArchiveKind.ZIP -> MangaImporter.readZipMetadataTexts(source)
                ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP -> LibraryArchiveReader.acquire(source).use { lease ->
                    lease.reader.entries.filter(ComicMetadataParser::isMetadataEntry).mapNotNull { name ->
                        optional(name) { lease.reader.openEntry(name, ComicMetadataParser.MAX_METADATA_BYTES).use { readText(it) }?.let { name to it } }
                    }
                }
                else -> emptyList()
            }
        }.orEmpty()
        return embedded + sidecarTexts(source)
    }

    fun sidecarTexts(source: File): List<Pair<String, String>> {
        val parent = source.parentFile ?: return emptyList()
        val localNodes = parent.listFiles().orEmpty().map { LibraryNode(it.invariantSeparatorsPath, it.isDirectory, it.length()) }
        val local = sidecarNodes(localNodes, source.invariantSeparatorsPath, parent.name !in setOf("manga_import", "novel_import"))
            .mapNotNull { node -> optional(node.path) {
                File(node.path).inputStream().use { readText(it, node.size) }?.let {
                    ComicMetadataParser.basename(node.path) to it
                }
            } }
        if (local.isNotEmpty()) return local // A SAF import has not published its staged source yet.
        val located = LibrarySessions.findFile(source)
        if (located == null || located.first.storage is FileLibraryStorage) return local
        val sourcePath = located?.second ?: source.invariantSeparatorsPath
        val nodes = if (located != null) optional(parent.path) {
            located.first.storage.list(located.second.substringBeforeLast('/', ""))
        }.orEmpty() else parent.listFiles().orEmpty().map { LibraryNode(it.invariantSeparatorsPath, it.isDirectory, it.length()) }
        val allowGeneric = if (located != null) located.second.substringBeforeLast('/', "") !in setOf("manga_import", "novel_import", "")
            else parent.name !in setOf("manga_import", "novel_import")
        return sidecarNodes(nodes, sourcePath, allowGeneric)
            .mapNotNull { node -> optional(node.path) {
                val input = if (located != null) located.first.storage.read(node.path) else File(node.path).inputStream()
                input.use { readText(it, node.size) }?.let {
                    ComicMetadataParser.basename(node.path) to it
                }
            } }
    }

    private inline fun <T> optional(path: String, read: () -> T): T? = try { read() }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) {
        LogCollector.w("BookMetadata", "读取元数据失败：$path；${e.message}")
        null
    }
}
