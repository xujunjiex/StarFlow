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
    const val VERSION = 8
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
    /**
     * 章级元数据：章可能是**图片子目录**，也可能是**压缩包文件**（文件夹里一堆 cbz）。
     * 两种都支持；读不到返回空表（调用方据此显示「来源不可读」）。
     */
    fun chapterMetadata(source: File, chapters: List<MangaChapter>): Map<String, ComicMetadata> {
        val titles = chapters.mapNotNull { it.title.takeIf(String::isNotBlank) }.toSet()
        if (titles.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, ComicMetadata>()
        if (source.isDirectory) {
            var files: List<File> = emptyList()
            val listed = optional(source.path) {
                source.walkTopDown().filter { it.isFile && ComicMetadataParser.isMetadataEntry(it.name) }.toList()
            }.orEmpty()
            files = listed
            titles.forEach { title ->
                val inChapter = files.filter { file ->
                    val segments = file.relativeTo(source).invariantSeparatorsPath.split('/')
                    segments.size >= 2 && segments.dropLast(1).contains(title)
                }
                val shallowest = inChapter.minByOrNull { file ->
                    file.relativeTo(source).invariantSeparatorsPath.count { c -> c == '/' }
                }
                if (shallowest != null) {
                    optional(shallowest.path) {
                        shallowest.inputStream().use { readText(it) }?.let { text ->
                            ComicMetadataParser.parse(listOf(shallowest.name to text))
                        }
                    }?.let { result[title] = it }
                }
            }
        } else {
            val names = archiveNames(source).orEmpty()
            val wrapper = commonWrapper(names)
            titles.forEach { title ->
                val inChapter = names.filter { name ->
                    val segments = stripWrapper(name, wrapper).split('/')
                    segments.size >= 2 && segments.dropLast(1).contains(title)
                }
                val shallowest = inChapter.minByOrNull { stripWrapper(it, wrapper).count { c -> c == '/' } }
                if (shallowest != null) {
                    readArchiveEntries(source, listOf(shallowest)).firstOrNull()?.let { (_, text) ->
                        ComicMetadataParser.parse(listOf(shallowest to text))?.let { result[title] = it }
                    }
                }
            }
        }
        // 书 = **单个压缩包**：章是包内子目录。标题对不上目录名时按章序 ↔ 目录顺序对应
        if (!source.isDirectory) {
            val names = archiveNames(source).orEmpty()
            val wrapper = commonWrapper(names)
            val dirs = names.mapNotNull { name ->
                val relative = stripWrapper(name, wrapper)
                val index = relative.indexOf('/')
                if (index <= 0) null else relative.substring(0, index)
            }.distinct()
            if (dirs.size > 1) {
                chapters.sortedBy { it.startPage }.forEachIndexed { index, chapter ->
                    val title = chapter.title.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                    if (result.containsKey(title)) return@forEachIndexed
                    val dir = dirs.getOrNull(index) ?: return@forEachIndexed
                    val candidates = names.filter { name ->
                        val relative = stripWrapper(name, wrapper)
                        relative.startsWith("$dir/") && ComicMetadataParser.isMetadataEntry(relative.substringAfterLast('/'))
                    }.sortedBy { name -> stripWrapper(name, wrapper).count { c -> c == '/' } }
                    if (candidates.isEmpty()) return@forEachIndexed
                    ComicMetadataParser.parse(readArchiveEntries(source, candidates.take(2)))?.let { result[title] = it }
                }
            }
        }        // 章 = 压缩包文件本身：名字对不上就按章序与压缩包列表对应
        val ordered = chapters.sortedBy { it.startPage }
        ordered.forEachIndexed { index, chapter ->
            val title = chapter.title.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            if (result.containsKey(title)) return@forEachIndexed
            val file = chapterFile(source, title) ?: archiveByIndex(source, index) ?: return@forEachIndexed
            // ⚠️ 章是**这一章自己的包**：包内任意深度的元数据都归它（书根才要求 depth 0）。
            // 常见 cbz：`书名/ComicInfo.xml` 与根目录图片混放，用书根规则会永远读不到。
            ComicMetadataParser.parse(sourceTexts(file))?.let { result[title] = it }
        }
        return result
    }

    /** 章标题 → 实际文件：直接子文件优先，其次递归，最后按章序与压缩包列表对应。 */
    private fun chapterFile(source: File, title: String): File? {
        if (!source.isDirectory) return null
        fun isArchive(file: File): Boolean =
            ArchiveTypes.detect(file) == ArchiveKind.ZIP ||
                ArchiveTypes.detect(file) == ArchiveKind.RAR ||
                ArchiveTypes.detect(file) == ArchiveKind.SEVEN_ZIP
        fun pick(files: List<File>): File? {
            val exact = files.firstOrNull { it.name == title }
            val extended = files.firstOrNull { it.nameWithoutExtension == title }
            val loose = files.firstOrNull { it.name.equals(title, ignoreCase = true) }
            return exact ?: extended ?: loose
        }
        pick(source.listFiles().orEmpty().filter { it.isFile })?.let { return it }
        val all = runCatching { source.walkTopDown().filter { it.isFile }.toList() }.getOrDefault(emptyList())
        pick(all)?.let { return it }
        return null
    }

    /** 章序 → 压缩包列表（章标题对不上文件名时的兜底，按文件名自然序）。 */
    private fun archiveByIndex(source: File, index: Int): File? {
        if (!source.isDirectory || index < 0) return null
        val archives = runCatching {
            source.listFiles().orEmpty().filter { it.isFile }
                .filter { name -> name.name.substringAfterLast('.', "").lowercase() in setOf("cbz", "zip", "cbr", "rar", "7z") }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }.getOrDefault(emptyList())
        return archives.getOrNull(index)
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
