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
     * **书级元数据 = 书根**，严格按路径层级判定（不再靠章标题猜）：
     * - 目录：只取 dir 的**直接子文件**；
     * - 压缩包：条目剥掉「所有条目共有的最外层目录」后，仍然**没有子目录**的才算书根。
     *
     * ⚠️ 这条必须严格：以前用「路径里出现章标题才算章」，单章书/标题对不上时，
     * 子目录里的 json 会被当成书级 → 表现就是「根目录没有 json 却显示了子目录/包内的 json」。
     */
    fun bookRootTexts(source: File): List<Pair<String, String>> {
        if (source.isDirectory) {
            return source.listFiles().orEmpty()
                .filter { it.isFile && ComicMetadataParser.isMetadataEntry(it.name) && it.length() <= ComicMetadataParser.MAX_METADATA_BYTES }
                .mapNotNull { file -> optional(file.path) { file.inputStream().use { readText(it, file.length()) }?.let { file.name to it } } }
        }
        val names = archiveNames(source) ?: return emptyList()
        val wrapper = commonWrapper(names)
        val candidates = names.filter { name ->
            val relative = stripWrapper(name, wrapper)
            relative.isNotEmpty() && '/' !in relative && ComicMetadataParser.isMetadataEntry(relative)
        }
        if (candidates.isEmpty()) return emptyList()
        return readArchiveEntries(source, candidates)
    }

    /** 章级元数据：章目录/… 里最浅的那份（按首段目录名分组；根散图章为空名，不参与）。 */
    fun chapterMetadata(source: File, chapters: List<MangaChapter>): Map<String, ComicMetadata> {
        val titles = chapters.mapNotNull { it.title.takeIf(String::isNotBlank) }.toSet()
        if (titles.isEmpty()) return emptyMap()
        val names = if (source.isDirectory) {
            source.walkTopDown().filter { it.isFile && ComicMetadataParser.isMetadataEntry(it.name) }
                .map { it.relativeTo(source).invariantSeparatorsPath }.toList()
        } else archiveNames(source).orEmpty()
        val wrapper = commonWrapper(names)
        val grouped = names.mapNotNull { name ->
            val relative = stripWrapper(name, wrapper)
            val index = relative.indexOf('/')
            if (index <= 0) return@mapNotNull null
            val key = relative.substring(0, index)
            if (key !in titles) return@mapNotNull null
            key to name
        }.groupBy({ it.first }, { it.second })
        return grouped.mapNotNull { (title, entries) ->
            val shallowest = entries.minByOrNull { stripWrapper(it, wrapper).count { c -> c == '/' } } ?: return@mapNotNull null
            val texts = if (source.isDirectory) {
                optional(shallowest) { File(source, shallowest).inputStream().use { readText(it) }?.let { listOf(shallowest to it) } }.orEmpty()
            } else readArchiveEntries(source, listOf(shallowest))
            ComicMetadataParser.parse(texts)?.let { title to it }
        }.toMap()
    }

    /** 归档里所有条目名（zip / rar / 7z）；目录或打不开时返回 null。 */
    private fun archiveNames(source: File): List<String>? = optional(source.path) {
        when (ArchiveTypes.detect(source)) {
            ArchiveKind.ZIP -> java.util.zip.ZipFile(source).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.map { it.name.replace('\\', '/') }.toList()
            }
            ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP -> LibraryArchiveReader.acquire(source).use { lease ->
                lease.reader.entries.filter { !it.endsWith("/") }.map { it.replace('\\', '/') }
            }
            else -> null
        }
    }

    /** 所有条目共有的最外层目录（Book/…），没有则 null。 */
    private fun commonWrapper(names: List<String>): String? {
        if (names.isEmpty()) return null
        val first = names.first().substringBefore('/')
        if (first.isEmpty() || names.any { it.substringBefore('/') != first || '/' !in it }) return null
        return first
    }

    private fun stripWrapper(name: String, wrapper: String?): String =
        if (wrapper != null && name.startsWith("$wrapper/")) name.removePrefix("$wrapper/") else name

    /** 按名读取归档里的元数据条目（带上限）。 */
    private fun readArchiveEntries(source: File, names: List<String>): List<Pair<String, String>> = optional(source.path) {
        when (ArchiveTypes.detect(source)) {
            ArchiveKind.ZIP -> java.util.zip.ZipFile(source).use { zip ->
                names.mapNotNull { name ->
                    val entry = zip.getEntry(name) ?: return@mapNotNull null
                    if (entry.size > ComicMetadataParser.MAX_METADATA_BYTES) return@mapNotNull null
                    zip.getInputStream(entry).use { readText(it, entry.size)?.let { text -> name to text } }
                }
            }
            ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP -> LibraryArchiveReader.acquire(source).use { lease ->
                names.mapNotNull { name ->
                    optional(name) {
                        lease.reader.openEntry(name, ComicMetadataParser.MAX_METADATA_BYTES).use { readText(it)?.let { text -> name to text } }
                    }
                }
            }
            else -> emptyList()
        }
    }.orEmpty()
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
