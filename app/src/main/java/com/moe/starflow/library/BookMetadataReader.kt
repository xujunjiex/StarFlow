package com.moe.starflow.library

import com.moe.starflow.mangaimport.data.ArchiveKind
import com.moe.starflow.mangaimport.data.ArchiveTypes
import com.moe.starflow.mangaimport.data.ComicMetadataParser
import com.moe.starflow.mangaimport.data.LibraryArchiveReader
import com.moe.starflow.mangaimport.data.MangaImporter
import com.moe.starflow.utils.LogCollector
import java.io.File
import java.io.InputStream

/** Bounded optional metadata reads. Directory sources are read from their actual backend. */
object BookMetadataReader {
    const val VERSION = 3
    private val BOOK_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr", "7z", "pdf", "epub", "txt", "html", "htm", "xhtml")

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
        return name.lowercase() in setOf("$stem.meta.json", "$sourceName.meta.json", "$stem.ComicInfo.xml", "$sourceName.ComicInfo.xml").map { it.lowercase() }
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
                    (if (node.path.endsWith(".xml", true)) "ComicInfo.xml" else "meta.json") to it
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
                    (if (node.path.endsWith(".xml", true)) "ComicInfo.xml" else "meta.json") to it
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
