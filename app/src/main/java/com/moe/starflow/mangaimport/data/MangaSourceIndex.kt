package com.moe.starflow.mangaimport.data

import com.moe.starflow.library.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Portable page identities contain no local database id or provider document id. */
data class MangaPageRef(val path: String, val kind: String, val entry: String, val chapter: String,
    val bytes: Long, val missing: Boolean = false, val modifiedAt: Long = 0, val sourceMissing: Boolean = false) {
    val key: String get() = JSONArray(listOf(path, kind, entry)).toString()
    fun json() = JSONObject().put("path", path).put("kind", kind).put("entry", entry)
        .put("chapter", chapter).put("bytes", bytes).put("missing", missing).put("modifiedAt", modifiedAt).put("sourceMissing", sourceMissing)
}

data class MangaSourceIndex(val pages: List<MangaPageRef>, val problems: List<String> = emptyList(), val standalone: Boolean = false) {
    val chapters: List<MangaChapter> get() {
        if (standalone) return if (pages.firstOrNull()?.kind == "PDF") listOf(MangaChapter(1, "", 0, pages.size))
            else MangaChapterSplitter.split(pages.map { it.entry }).chapters
        val groups = pages.groupBy { it.chapter }
        var start = 0
        return groups.entries.mapIndexed { i, (key, refs) ->
            MangaChapter(if (groups.containsKey("") && groups.size > 1) i else i + 1,
                if (key.isEmpty()) "" else if (refs.any { it.path == key && supported(key) }) File(key).nameWithoutExtension else File(key).name, start, refs.size).also { start += refs.size }
        }
    }
    fun json() = JSONObject().put("version", VERSION).put("pages", JSONArray(pages.map { it.json() }))
        .put("problems", JSONArray(problems)).put("standalone", standalone)
    fun store(session: LibrarySession, uuid: String): String {
        val path = ".starflow/books/$uuid/source-index/${UUID.randomUUID()}.json"
        val bytes = json().toString().toByteArray(Charsets.UTF_8)
        // The authoritative book records must retain page identities even if the source directory is cleared.
        session.recordArchive(path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        session.local(path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return path
    }
    companion object {
        const val VERSION = 1
        fun parse(text: String): MangaSourceIndex {
            val json = JSONObject(text)
            require(json.getInt("version") == VERSION) { "Unsupported manga source index" }
            val rows = json.getJSONArray("pages")
            return MangaSourceIndex((0 until rows.length()).map { i -> rows.getJSONObject(i).let {
                MangaPageRef(safeLibraryPath(it.getString("path")), it.getString("kind"), it.getString("entry"),
                    it.getString("chapter"), it.optLong("bytes"), it.optBoolean("missing"), it.optLong("modifiedAt"), it.optBoolean("sourceMissing", it.optBoolean("missing")))
            } }, json.optJSONArray("problems")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(), json.optBoolean("standalone"))
        }
        fun load(session: LibrarySession, path: String): MangaSourceIndex? = path.takeIf { it.isNotBlank() }?.let {
            require(safeLibraryPath(it).startsWith(".starflow/books/"))
            val retained = session.recordArchive(it)
            parse((if (retained.isFile) retained else LibrarySources.file(session.local(it))).readText(Charsets.UTF_8))
        }
        fun forRoot(root: File): MangaSourceIndex? {
            val (session, path) = LibrarySessions.findFile(root) ?: return null
            val book = session.books("manga").firstOrNull { it.source == path } ?: return null
            return load(session, JSONObject(book.body).optString("sourceIndexPath"))
        }
        fun supported(name: String) = name.substringAfterLast('.').lowercase() in setOf("pdf", "zip", "cbz", "rar", "cbr", "7z")

        /** Enumerate each directory once. Unchanged containers reuse their immutable page table. */
        fun build(root: File, previous: MangaSourceIndex? = null, onlyPath: String? = null, sourceNode: LibraryNode? = null): MangaSourceIndex {
            val located = LibrarySessions.findFile(root)
            val prefix = located?.second.orEmpty()
            val staged = if (onlyPath == null && located?.first?.books("manga")?.none { it.source == prefix } != false)
                root.walkTopDown().onFail { _, error -> throw error }.filter { it.isFile }.map { Triple(it.relativeTo(root).invariantSeparatorsPath, it.length(), it.lastModified()) }.toList() else emptyList()
            val nodes = if (onlyPath != null) {
                val backend = located?.first?.storage
                if (sourceNode != null) listOf(Triple(onlyPath, sourceNode.size, sourceNode.modifiedAt))
                else if (backend != null && backend.exists(prefix)) backend.list(prefix).filter { it.path.substringAfterLast('/') == onlyPath }
                    .map { Triple(onlyPath, it.size, it.modifiedAt) }.ifEmpty {
                        listOf(File(root, onlyPath)).filter { it.isFile }.map { Triple(onlyPath, it.length(), it.lastModified()) }
                    }
                else listOf(File(root, onlyPath)).filter { it.isFile }.map { Triple(onlyPath, it.length(), it.lastModified()) }
            } else if (staged.isNotEmpty()) staged else LibrarySources.listFiles(root)?.filter { !it.directory }?.map {
                Triple(it.path.removePrefix("$prefix/"), it.size, it.modifiedAt)
            } ?: root.walkTopDown().onFail { _, error -> throw error }.filter { it.isFile && !it.isHidden }.map {
                Triple(it.relativeTo(root).invariantSeparatorsPath, it.length(), it.lastModified())
            }.toList()
            val old = previous?.pages.orEmpty().groupBy { it.path }
            val pages = mutableListOf<MangaPageRef>()
            val problems = mutableListOf<String>()
            val present = hashSetOf<String>()
            nodes.forEach { (path, bytes, modifiedAt) ->
                if (path.split('/').any { it.startsWith('.') } || (!ArchivedMangaReader.isImageFile(path) && !supported(path))) return@forEach
                safeLibraryPath(path)
                require(File(root, path).canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Source escapes book directory" }
                present += path
                val chapter = if ('/' in path) path.substringBefore('/') else if (supported(path)) path else ""
                val cached = old[path]
                if (cached != null && bytes >= 0 && modifiedAt > 0 && cached.all { !it.sourceMissing && it.bytes == bytes && it.modifiedAt == modifiedAt }) {
                    pages += cached; return@forEach
                }
                val file = File(root, path)
                if (ArchivedMangaReader.isImageFile(path)) {
                    pages += MangaPageRef(path, "IMAGE", "", chapter, bytes, modifiedAt = modifiedAt)
                }
                else {
                    val local = LibrarySources.file(file, forceReload = previous != null)
                    when (val kind = ArchiveTypes.detect(local)) {
                        ArchiveKind.PDF -> PdfPageRenderer(local).use { pdf ->
                            val count = pdf.pageCount
                            if (count <= 0) throw IOException("PDF 没有可读页面：$path")
                            if (cached != null && cached.size != count) {
                                problems += path
                                pages += cached.map { it.copy(missing = false, sourceMissing = false) }
                            } else repeat(count) { pages += MangaPageRef(path, "PDF", it.toString(), chapter, bytes, modifiedAt = modifiedAt) }
                        }
                        ArchiveKind.ZIP, ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP -> {
                            if (kind == ArchiveKind.ZIP && java.util.zip.ZipFile(local).use { it.getEntry("META-INF/container.xml") != null }) return@forEach
                            val raw = if (kind == ArchiveKind.ZIP) java.util.zip.ZipFile(local).use { zip ->
                                zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
                            } else LibraryArchiveReader.acquire(local).use { it.reader.entries.toList() }
                            val entries = MangaChapterSplitter.split(raw.filter(ArchivedMangaReader::isImageFile)).keys
                            val entrySet = entries.toHashSet()
                            entries.forEach { entry ->
                                pages += MangaPageRef(path, kind.name, entry, chapter, bytes, modifiedAt = modifiedAt)
                            }
                            cached?.filter { it.entry !in entrySet }?.forEach { pages += it.copy(missing = true, sourceMissing = false, bytes = bytes, modifiedAt = modifiedAt) }
                        }
                        else -> throw IOException("不支持的漫画内容：$path")
                    }
                }
            }
            old.filterKeys { it !in present }.values.flatten().forEach { pages += it.copy(missing = true, sourceMissing = true) }
            val natural = ArchivedMangaReader.naturalComparator()
            if (onlyPath != null) {
                val ordered = if (pages.firstOrNull()?.kind == "PDF") pages else {
                    val byEntry = pages.associateBy { it.entry }
                    MangaChapterSplitter.split(byEntry.keys.toList()).keys.map(byEntry::getValue)
                }
                return MangaSourceIndex(ordered, problems, standalone = true)
            }
            val ordered = pages.groupBy { it.chapter }.toSortedMap(natural).values.flatMap { chapterPages ->
                // Containers keep their authoritative page order (PDF numbers are not filename strings).
                chapterPages.groupBy { it.path }.toSortedMap(natural).values.flatMap { sourcePages ->
                    if (sourcePages.first().kind in setOf("ZIP", "RAR", "SEVEN_ZIP")) {
                        val byEntry = sourcePages.associateBy { it.entry }
                        MangaChapterSplitter.split(byEntry.keys.toList()).keys.map(byEntry::getValue)
                    } else sourcePages
                }
            }
            return MangaSourceIndex(ordered, problems)
        }
    }
}
