package com.moe.starflow.library

import com.moe.starflow.mangaimport.data.ArchiveKind
import com.moe.starflow.mangaimport.data.ArchiveTypes
import com.moe.starflow.mangaimport.data.ComicMetadata
import com.moe.starflow.mangaimport.data.ComicMetadataParser
import com.moe.starflow.mangaimport.data.LibraryArchiveReader
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.data.MangaChapterSplitter
import com.moe.starflow.mangaimport.data.MangaSourceIndex
import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.mangaimport.data.MangaImporter
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.io.InputStream

/** Bounded metadata reads from the actual backend. Callers choose whether failures are optional. */
object BookMetadataReader {
    const val VERSION = 9
    private val BOOK_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr", "7z", "pdf", "epub", "txt", "html", "htm", "xhtml")

    /** 与来源同名的侧车元数据后缀：`book.meta.json` / `book.ComicInfo.xml` / `book.comet.xml` … */
    private val SIDECAR_SUFFIXES = listOf("meta.json", "ComicInfo.xml", "comet.xml", "ComicBookInfo.json")

    /** Reuse an existing library grant; a single-file grant never grants arbitrary siblings. */
    fun copyAuthorizedSidecars(context: android.content.Context, document: android.net.Uri, destination: File, kind: String) {
        optional(document.toString()) {
            val session = LibrarySessions.current(context)
            val source = if (document.scheme == "file") document.path?.let(::File)
                else (session.storage as? SafLibraryStorage)?.pathForDocument(document, "${kind}_import")?.let(session::local)
            if (source != null) (sidecarTexts(source) + ownSidecarText(source)).forEach { (name, text) ->
                val target = File(destination, name).canonicalFile
                require(target.path.startsWith(destination.canonicalPath + File.separator))
                if (!target.exists()) { target.parentFile!!.mkdirs(); target.writeText(text, Charsets.UTF_8) }
            }
        }
    }

    private fun ownSidecarText(source: File): List<Pair<String, String>> {
        val file = File(source.path + ".starflow.json")
        val located = LibrarySessions.findFile(file)
        if (located != null && !located.first.storage.exists(located.second) && !file.isFile) return emptyList()
        if (located == null && !file.isFile) return emptyList()
        return optional(file.path) {
            LibrarySources.open(file).use { readText(it) }?.let { listOf(file.name to it) }
        }.orEmpty()
    }

    internal fun namedSidecar(sourceName: String, name: String): Boolean {
        val stem = sourceName.substringBeforeLast('.', sourceName)
        val lower = name.lowercase()
        return lower == "$sourceName.starflow.json".lowercase() || SIDECAR_SUFFIXES.any { suffix ->
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
        val located = LibrarySessions.findFile(directory)
        if (located != null && located.first.storage !is FileLibraryStorage && located.first.storage.exists(located.second)) {
            val (session, root) = located
            return (nodes ?: LibrarySources.listFiles(directory).orEmpty()).filter {
                ComicMetadataParser.isMetadataEntry(it.path) && it.size <= ComicMetadataParser.MAX_METADATA_BYTES
            }.mapNotNull { node ->
                session.storage.read(node.path).use { readText(it, node.size) }
                    ?.let { node.path.removePrefix("$root/") to it }
            }
        }
        return localDirectoryTexts(directory)
    }

    private fun localDirectoryTexts(directory: File): List<Pair<String, String>> {
        if (!directory.isDirectory) return emptyList()
        val root = directory.canonicalPath + File.separator
        return directory.walkTopDown().filter {
            it.isFile && it.canonicalPath.startsWith(root) && ComicMetadataParser.isMetadataEntry(it.name)
        }.mapNotNull { file ->
            file.inputStream().use { readText(it, file.length()) }
                ?.let { file.relativeTo(directory).invariantSeparatorsPath to it }
        }.toList()
    }

    /** Direct work metadata, after removing common archive wrapper directories. */
    fun bookRootTexts(source: File): List<Pair<String, String>> {
        if (isDirectory(source)) {
            val located = LibrarySessions.findFile(source)
            if (located != null && located.first.storage !is FileLibraryStorage && located.first.storage.exists(located.second)) {
                val (session, root) = located
                return session.storage.list(root).filter {
                    !it.directory && ComicMetadataParser.isMetadataEntry(it.path)
                }.mapNotNull { node ->
                    node.path.substringAfterLast('/') to session.storage.read(node.path).use { readText(it, node.size).orEmpty() }
                }
            }
            return (source.listFiles() ?: throw java.io.IOException("Cannot list metadata source: $source"))
                .filter { it.isFile && ComicMetadataParser.isMetadataEntry(it.name) }
                .map { file -> file.name to file.inputStream().use { readText(it, file.length()).orEmpty() } }
        }
        val local = LibrarySources.file(source)
        if (!local.isFile) throw java.io.IOException("Metadata source is unavailable: $source")
        val names = archiveNames(local) ?: return emptyList()
        val wrapper = commonWrapperPath(names)
        val candidates = names.filter { name ->
            val relative = name.removePrefix(wrapper)
            relative.isNotEmpty() && '/' !in relative && ComicMetadataParser.isMetadataEntry(relative)
        }
        return readArchiveEntries(local, candidates) + zipComment(local)
    }

    /** Multi-chapter books own only root metadata; a single chapter owns its inner metadata too. */
    fun bookMetadata(source: File, chapters: List<MangaChapter>, index: MangaSourceIndex? = null): ComicMetadata? {
        val ownedChapters = index?.chapters ?: chapters
        val root = ComicMetadataParser.parse(bookRootTexts(source) + sidecarTexts(source))
        if (ownedChapters.size > 1) return root
        val inner = if (!isDirectory(source)) ComicMetadataParser.parse(sourceTexts(source))
            else ownedChapters.singleOrNull()?.let { chapterMetadata(source, it, index) }
                ?: shallowMetadata(directoryTexts(source))
        return if (root != null && inner != null) ComicMetadataParser.merge(root, inner) else root ?: inner
    }

    /** Resolve ownership from the retained page index, never from archive order or a display title. */
    fun chapterSource(source: File, chapter: MangaChapter, index: MangaSourceIndex?): File {
        if (index != null) {
            if (index.standalone) return source
            val page = index.pages.getOrNull(chapter.startPage)
                ?: throw java.io.IOException("Chapter has no source page: ${chapter.number}")
            return if (page.chapter.isEmpty()) source else File(source, safeLibraryPath(page.chapter))
        }
        if (!isDirectory(source) || chapter.title.isBlank()) return source
        // Legacy directory books without an index have direct child names as chapter titles.
        val located = LibrarySessions.findFile(source)
        val names = if (located != null && located.first.storage !is FileLibraryStorage)
            located.first.storage.list(located.second).map { it.path.substringAfterLast('/') }
        else source.listFiles()?.map { it.name }
            ?: throw java.io.IOException("Cannot list chapter source: $source")
        val name = names.firstOrNull { it == chapter.title }
            ?: names.singleOrNull { it.substringBeforeLast('.') == chapter.title }
            ?: throw java.io.IOException("Chapter source not found: ${chapter.title}")
        return File(source, name)
    }

    /** Read just the requested chapter. The caller holds the book's source lease. */
    fun chapterMetadata(source: File, chapter: MangaChapter, index: MangaSourceIndex?): ComicMetadata? {
        val target = chapterSource(source, chapter, index)
        if (isDirectory(target)) {
            if (target == source && index != null && index.chapters.size > 1)
                return ComicMetadataParser.parse(bookRootTexts(target))
            val metadata = shallowMetadata(directoryTexts(target))
            if (metadata != null) return metadata
            val pages = index?.pages?.drop(chapter.startPage)?.take(chapter.pageCount).orEmpty()
            val container = pages.map { it.path }.distinct().singleOrNull()
            if (container != null && pages.all { it.kind != "IMAGE" } && container.isNotEmpty()) {
                val file = LibrarySources.file(File(source, safeLibraryPath(container)))
                val names = archiveNames(file).orEmpty().filter(ComicMetadataParser::isMetadataEntry)
                return ComicMetadataParser.parse(readArchiveEntries(file, names) + zipComment(file))
            }
            return null
        }
        val local = LibrarySources.file(target)
        if (!local.isFile) throw java.io.IOException("Chapter source is unavailable: $target")
        // A container used as one chapter owns all metadata inside that container.
        if (target != source || index?.standalone == false) {
            val names = archiveNames(local).orEmpty().filter(ComicMetadataParser::isMetadataEntry)
            return ComicMetadataParser.parse(readArchiveEntries(local, names) + zipComment(local) + sidecarTexts(target))
        }
        val names = archiveNames(local) ?: return ComicMetadataParser.parse(sidecarTexts(target))
        val pages = index?.pages?.map { it.entry.replace('\\', '/') } ?: MangaChapterSplitter.split(
            names.filter(ArchivedMangaReader::isImageFile)
        ).keys
        val wrapper = commonWrapperPath(pages)
        val first = pages.getOrNull(chapter.startPage) ?: return null
        val relative = first.removePrefix(wrapper)
        val directory = wrapper + (relative.substringBefore('/', "").takeIf { '/' in relative }
            ?.let { "$it/" }.orEmpty())
        val candidates = names.filter { name ->
            name.startsWith(directory) && ComicMetadataParser.isMetadataEntry(name) &&
                ('/' in relative || '/' !in name.removePrefix(directory))
        }
        return shallowMetadata(readArchiveEntries(local, candidates))
    }

    private fun shallowMetadata(texts: List<Pair<String, String>>): ComicMetadata? {
        val depth = texts.minOfOrNull { it.first.count { c -> c == '/' || c == '\\' } } ?: return null
        return ComicMetadataParser.parse(texts.filter { it.first.count { c -> c == '/' || c == '\\' } == depth })
    }

    private fun isDirectory(source: File): Boolean {
        val located = LibrarySessions.findFile(source)
        if (located != null && located.first.storage !is FileLibraryStorage && located.first.storage.exists(located.second)) {
            val (session, path) = located
            val node = session.storage.list(path.substringBeforeLast('/', "")).firstOrNull { it.path == path }
            if (node != null) return node.directory
        }
        return source.isDirectory
    }

    /** A ZIP comment describes the whole container, including when there is no metadata entry. */
    private fun zipComment(source: File): List<Pair<String, String>> {
        return if (ArchiveTypes.detect(source) != ArchiveKind.ZIP) emptyList() else java.util.zip.ZipFile(source).use { zip ->
            zip.comment?.let(MangaImporter::comicBookInfoComment)?.let { listOf("ComicBookInfo" to it) }.orEmpty()
        }
    }

    private fun commonWrapperPath(names: List<String>): String {
        if (names.isEmpty()) return ""
        var wrapper = ""
        var remaining = names
        while (true) {
            val next = commonWrapper(remaining) ?: return wrapper
            wrapper += "$next/"
            remaining = remaining.map { it.removePrefix("$next/") }
        }
    }

    /** 归档里所有条目名（zip / rar / 7z）；目录或打不开时返回 null。 */
    private fun archiveNames(source: File): List<String>? {
        val inspection = ArchiveTypes.inspect(source)
        inspection.readFailure?.let { throw it }
        return when (inspection.kind) {
            ArchiveKind.ZIP -> java.util.zip.ZipFile(source).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.map { it.name.replace('\\', '/') }.toList()
            }
            ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP -> LibraryArchiveReader.acquire(source).use { lease ->
                lease.reader.entries.filter { !it.endsWith("/") }.map { it.replace('\\', '/') }
            }
            ArchiveKind.UNKNOWN -> {
                if (source.extension.lowercase() in setOf("zip", "cbz", "rar", "cbr", "7z"))
                    throw java.io.IOException("Metadata archive cannot be read: $source")
                null
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

    /** 按名读取归档里的元数据条目（带上限）。 */
    private fun readArchiveEntries(source: File, names: List<String>): List<Pair<String, String>> {
        return when (ArchiveTypes.detect(source)) {
            ArchiveKind.ZIP -> java.util.zip.ZipFile(source).use { zip ->
                names.mapNotNull { name ->
                    val entry = zip.getEntry(name) ?: zip.entries().asSequence().firstOrNull { it.name.replace('\\', '/') == name } ?: return@mapNotNull null
                    name to zip.getInputStream(entry).use { readText(it, entry.size).orEmpty() }
                }
            }
            ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP -> LibraryArchiveReader.acquire(source).use { lease ->
                names.mapNotNull { name ->
                    lease.reader.openEntry(name, ComicMetadataParser.MAX_METADATA_BYTES).use { readText(it)?.let { text -> name to text } }
                }
            }
            else -> emptyList()
        }
    }
    fun sourceTexts(source: File): List<Pair<String, String>> {
        if (isDirectory(source)) return directoryTexts(source)
        val local = LibrarySources.file(source)
        val names = archiveNames(local).orEmpty().filter(ComicMetadataParser::isMetadataEntry)
        return readArchiveEntries(local, names) + zipComment(local) + sidecarTexts(source)
    }

    fun sidecarTexts(source: File): List<Pair<String, String>> {
        val parent = source.parentFile ?: return emptyList()
        val children = parent.listFiles() ?: if (parent.exists()) throw java.io.IOException("Cannot list metadata sidecars: $parent") else emptyArray()
        val localNodes = children.map { LibraryNode(it.invariantSeparatorsPath, it.isDirectory, it.length()) }
        val local = sidecarNodes(localNodes, source.invariantSeparatorsPath, parent.name !in setOf("manga_import", "novel_import")).filter { !it.path.endsWith(".starflow.json", true) }
            .map { node -> node.path.substringAfterLast('/') to
                File(node.path).inputStream().use { readText(it, node.size).orEmpty() } }
        if (local.isNotEmpty()) return local // A SAF import has not published its staged source yet.
        val located = LibrarySessions.findFile(source)
        if (located == null || located.first.storage is FileLibraryStorage) return local
        val (session, sourcePath) = located
        val nodes = session.storage.list(sourcePath.substringBeforeLast('/', ""))
        val allowGeneric = sourcePath.substringBeforeLast('/', "") !in setOf("manga_import", "novel_import", "")
        return sidecarNodes(nodes, sourcePath, allowGeneric).filter { !it.path.endsWith(".starflow.json", true) }
            .map { node -> node.path.substringAfterLast('/') to
                session.storage.read(node.path).use { readText(it, node.size).orEmpty() } }
    }

    /** Filesystem facts only: unchanged chapter archives need not be opened again for migration. */
    internal fun reviewFingerprint(source: File): String {
        val located = LibrarySessions.findFile(source)
        val nodes = if (isDirectory(source)) LibrarySources.listFiles(source) ?: source.walkTopDown()
            .filter(File::isFile).map { LibraryNode(it.relativeTo(source).invariantSeparatorsPath, false, it.length(), it.lastModified()) }.toList()
        else if (located != null) located.first.storage.list(located.second.substringBeforeLast('/', ""))
        else source.parentFile?.listFiles()?.map { LibraryNode(it.name, it.isDirectory, it.length(), it.lastModified()) }.orEmpty()
        return nodes.filter { !it.directory && (ComicMetadataParser.isMetadataEntry(it.path) ||
            it.path.substringAfterLast('.').lowercase() in BOOK_EXTENSIONS) }
            .sortedBy { it.path }.joinToString { "${it.path}:${it.size}:${it.modifiedAt}" }
    }

    private inline fun <T> optional(path: String, read: () -> T): T? = try { read() }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) {
        LogCollector.w("BookMetadata", "读取元数据失败：$path；${e.message}")
        null
    }
}
