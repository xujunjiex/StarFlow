package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.mangaimport.data.*
import com.moe.starflow.novel.data.*
import com.moe.starflow.novel.model.NovelFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

data class LibraryScanState(val location: String = "", val kind: String = "", val running: Boolean = false,
    val done: Int = 0, val total: Int = 0, val added: Int = 0, val failures: List<String> = emptyList(),
    val runId: String = java.util.UUID.randomUUID().toString(), val needsRecovery: Boolean = false)

object LibraryScanner {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(LibraryScanState())
    private val reports = ArrayDeque<LibraryScanState>()
    private val requests = hashSetOf<String>()
    @Synchronized fun consumeFailures(location: String = state.value.location, kind: String = state.value.kind): List<String>? {
        return consumeReport(location, kind)?.failures
    }
    @Synchronized fun consumeReport(location: String, kind: String): LibraryScanState? {
        val report = reports.firstOrNull { it.location == location && it.kind == kind } ?: return null
        reports.remove(report)
        return report
    }
    @Synchronized private fun completed(result: LibraryScanState): LibraryScanState {
        if (result.failures.isNotEmpty()) reports.addLast(result)
        state.value = result
        LibrarySessions.changes.value += 1
        return result
    }
    @Synchronized internal fun discardReports(location: String) { reports.removeAll { it.location == location } }
    @Synchronized internal fun deferReport(report: LibraryScanState) {
        if (reports.none { it.runId == report.runId }) reports.addFirst(report)
    }
    @Synchronized fun start(context: Context, kind: String) {
        require(kind == "manga" || kind == "novel")
        val pinned = LibrarySessions.pin(context)
        val session = LibrarySessions.current(pinned)
        val key = "${session.locationId}:${session.generation}:$kind"
        if (!requests.add(key)) return
        LibrarySessions.launch(pinned, scope) {
            scan(pinned, kind)
        }.invokeOnCompletion { synchronized(this) { requests.remove(key) } }
    }
    suspend fun scan(context: Context, kind: String): LibraryScanState = mutex.withLock {
        withContext(Dispatchers.IO) {
            val pinned = LibrarySessions.pin(context)
            val session = LibrarySessions.current(pinned)
            val lease = LibrarySessions.use()
            val failures = mutableListOf<String>()
            val informational = hashSetOf<String>()
            var added = 0
            try {
                session.storage.invalidateLookups()
                state.value = LibraryScanState(session.locationId, kind, running = true)
                LibrarySessions.prepare(pinned)
                val root = if (kind == "manga") "manga_import" else "novel_import"
                if (kind == "manga") MangaDuplicateRepair.repair(session, failures)
                val known = session.books(kind)
                // Overall index and candidate enumeration precede per-book inspection.
                val listings = mutableMapOf(root to session.storage.list(root))
                val members = mutableMapOf(root to listings.getValue(root).mapTo(hashSetOf()) { it.path })
                fun sourceExists(path: String): Boolean {
                    val parent = path.substringBeforeLast('/', "")
                    if (parent != root && parent.isNotEmpty() && !sourceExists(parent)) return false
                    val children = members.getOrPut(parent) {
                        listings.getOrPut(parent) { session.storage.list(parent) }.mapTo(hashSetOf()) { it.path }
                    }
                    return path in children
                }
                known.forEach { book ->
                    try { session.sourcePresence[book.source] = sourceExists(book.source) }
                    catch (e: Exception) { failures += "${session.storage.displayPath}/${book.source}：[检查来源] ${e.message}" }
                }
                // Repair descriptions missed by the previous importer once; never replace edits.
                val repairs = known.filter {
                    val body = org.json.JSONObject(it.body)
                    !it.deleting && !(kind == "novel" && body.optInt("chapterCount") == 0) && session.sourcePresence[it.source] == true && body.optString("description").isBlank() && body.optInt("metadataVersion") < BookMetadataReader.VERSION
                }
                if (kind == "manga") MangaDirectoryUpdates.refresh(pinned, session, known, failures, listings)
                else refreshEmptyNovels(pinned, session, known, failures)
                val candidates = discover(session, root, known, kind, failures, listings)
                val total = repairs.size + candidates.size
                state.value = state.value.copy(total = total)
                repairs.forEachIndexed { index, book ->
                    coroutineContext.ensureActive()
                    try {
                        backfillDescription(pinned, session, book)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failures += "${session.storage.displayPath}/${book.source}：[读取元数据] ${e.message}" }
                    state.value = state.value.copy(done = index + 1, failures = failures.toList())
                }
                candidates.forEachIndexed { index, node ->
                    val id = if (kind == "manga") ImportManager.reserveScanId(pinned, null)
                        else NovelImportManager.reserveScanId(pinned, null)
                    if (id == null) return@forEachIndexed
                    val source = session.local(node.path)
                    var localLease: java.io.Closeable? = null
                    var stage = "读取来源"
                    var emptyDirectory = false
                    try {
                        if (node.directory) source.mkdirs()
                        localLease = LibrarySources.acquire(source)
                        if (!node.directory) LibrarySources.file(source)
                        val hintedUuid = source.name.takeIf { LibraryCodec.UUID_PATTERN.matches(it) }
                        val identityPath = hintedUuid?.let { ".starflow/books/$it/source.json" }
                        val identity = identityPath?.takeIf(session.storage::exists)?.let {
                            org.json.JSONObject(session.storage.read(it).bufferedReader().use { reader -> reader.readText() })
                        }?.takeIf {
                            val original = safeLibraryPath(it.optString("source"))
                            it.optInt("format") == 1 && it.optString("kind") == kind && it.optString("uuid") == hintedUuid &&
                                (original == node.path || original.startsWith("${node.path}/"))
                        }
                        val indexedUuid = identity?.getString("uuid")
                        if (indexedUuid != null && session.database.catalog().byUuid(indexedUuid) == null)
                            session.database.catalog().meta(LibraryMeta("uuid:$kind:$id", indexedUuid))
                        val title = identity?.optString("sourceName")?.takeIf { it.isNotBlank() }
                            ?: if (node.directory) source.name else source.nameWithoutExtension.ifBlank { source.name }
                        stage = "识别作品"
                        if (kind == "manga") {
                            if (!node.directory && ArchiveTypes.detect(source) == ArchiveKind.ZIP &&
                                ZipFile(source).use { it.getEntry("META-INF/container.xml") != null }) return@forEachIndexed
                            val manga = MangaImporter.inspectLocalSource(pinned, source, id, title, sourceNode = node)
                            if (manga.pageCount <= 0 && !node.directory) throw java.io.IOException("来源中没有可读漫画图片")
                            emptyDirectory = node.directory && manga.pageCount == 0
                            added += ImportedMangaStore.mergeScanned(pinned, listOf(manga))
                        } else {
                            if (node.directory) LibrarySources.file(source, folderTexts = true)
                            val novel = NovelImporter.inspectLocalSource(pinned, source, id, title, allowEmptyDirectory = node.directory)
                            emptyDirectory = node.directory && novel.chapterCount == 0
                            added += NovelStore.mergeScanned(pinned, listOf(novel))
                        }
                        session.sourcePresence[node.path] = true
                        stage = "提交书库"
                        session.flush()
                        if (session.saveState.value.pending != 0) throw java.io.IOException("未保存到书库：${session.saveState.value.error}")
                        if (emptyDirectory) {
                            val notice = pinned.getString(com.moe.starflow.R.string.library_empty_directory_added, node.path)
                            informational += notice; failures += notice
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e !is IllegalStateException || e.message != NovelImporter.ERROR_NO_TEXT_CHAPTER)
                            failures += "${session.storage.displayPath}/${node.path}：[$stage] ${e.javaClass.simpleName}：${e.message}"
                    } finally {
                        localLease?.close()
                        if (kind == "manga") ImportManager.releaseScanIds(listOf(id)) else NovelImportManager.releaseScanIds(listOf(id))
                        state.value = state.value.copy(done = repairs.size + index + 1, added = added, failures = failures.toList())
                    }
                }
                session.flush()
                completed(state.value.copy(running = false, done = total, added = added, failures = failures.toList(),
                    needsRecovery = failures.any { it !in informational }))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                com.moe.starflow.utils.LogCollector.e("LibraryScanner", "扫描书库失败：${session.storage.displayPath}", e)
                completed(state.value.copy(running = false, failures = failures + "${session.storage.displayPath}：${e.javaClass.simpleName}：${e.message}", needsRecovery = true))
            } finally {
                state.value = state.value.copy(running = false)
                lease.close()
            }
        }
    }
    /** Empty directories already own their paths; discover their first text chapters without making a new book. */
    private suspend fun refreshEmptyNovels(context: Context, session: LibrarySession, known: List<LibraryBook>, failures: MutableList<String>) {
        known.filter { !it.deleting && session.sourcePresence[it.source] == true }.forEach { book ->
            val body = org.json.JSONObject(book.body)
            if (body.optString("format") != NovelFormat.FOLDER.name || body.optInt("chapterCount") != 0) return@forEach
            val source = session.local(book.source)
            try {
                LibrarySources.acquire(source).use {
                    LibrarySources.file(source, folderTexts = true)
                    val parsed = NovelImporter.inspectLocalSource(context, source, book.id, body.optString("title"))
                    val previous = NovelStore.load(context).firstOrNull { it.bookUuid == book.uuid } ?: return@use
                    NovelStore.patch(context, previous) { latest -> latest.copy(chapterCount = parsed.chapterCount,
                        sizeBytes = parsed.sizeBytes, author = latest.author ?: parsed.author,
                        description = latest.description.ifBlank { parsed.description },
                        metadataVersion = parsed.metadataVersion, coverPath = latest.coverPath ?: parsed.coverPath) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e !is IllegalStateException || e.message != NovelImporter.ERROR_NO_TEXT_CHAPTER)
                    failures += "${session.storage.displayPath}/${book.source}: ${e.message}"
            }
        }
    }

    private suspend fun backfillDescription(context: Context, session: LibrarySession, book: LibraryBook) {
        val source = session.local(book.source)
        val body = org.json.JSONObject(book.body)
        val directory = if (book.kind == "manga") !body.optBoolean("isArchive") && !body.optBoolean("isPdf")
            else body.optString("format") == NovelFormat.FOLDER.name
        if (directory && session.storage !is FileLibraryStorage) source.mkdirs()
        LibrarySources.acquire(source).use {
            if (!directory) LibrarySources.file(source)
            val texts = BookMetadataReader.sourceTexts(source)
            val metadata = ComicMetadataParser.parse(texts)
            if (book.kind == "manga") {
                val description = metadata?.let { ComicMetadataParser.buildDescription(context, it) }.orEmpty()
                ImportedMangaStore.load(context).firstOrNull { it.bookUuid == book.uuid }?.let { value ->
                    ImportedMangaStore.patch(context, value) { latest ->
                        latest.copy(description = latest.description.ifBlank { description }, metadataVersion = BookMetadataReader.VERSION)
                    }
                }
            } else {
                val value = NovelStore.load(context).firstOrNull { it.bookUuid == book.uuid } ?: return
                val embedded = if (value.format != NovelFormat.TXT) {
                    if (directory) LibrarySources.file(source, folderTexts = true)
                    val parsed = com.moe.starflow.novel.parser.NovelParsers.forFormat(value.format).parse(source)
                    ComicMetadata(writer = parsed.author, summary = parsed.description.takeIf(String::isNotBlank))
                } else ComicMetadata(writer = value.author)
                val merged = metadata?.let { ComicMetadataParser.merge(it, embedded) } ?: embedded
                val description = ComicMetadataParser.buildDescription(context, merged)
                NovelStore.patch(context, value) { latest ->
                    latest.copy(description = latest.description.ifBlank { description }, author = latest.author ?: merged.writer, metadataVersion = BookMetadataReader.VERSION)
                }
            }
        }
    }
    private fun discover(session: LibrarySession, root: String, known: List<LibraryBook>, kind: String, failures: MutableList<String>, listings: MutableMap<String, List<LibraryNode>>): List<LibraryNode> {
        val out = mutableListOf<LibraryNode>()
        val retired = session.database.catalog().allMeta().filter { it.name.startsWith("legacySource:") && it.value == "1" }
            .map { it.name.removePrefix("legacySource:") }
        val ownedSources = (known.map { it.source } + retired).toHashSet()
        val copiedRoots = known.mapTo(hashSetOf()) { "$root/${it.uuid}" }
        val ownedParents = hashSetOf<String>()
        for (source in ownedSources) {
            var parent = source.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) { ownedParents += parent; parent = parent.substringBeforeLast('/', "") }
        }
        fun owned(path: String): Boolean {
            var ancestor = path
            while (ancestor.isNotEmpty()) {
                if (ancestor in ownedSources) return true
                ancestor = ancestor.substringBeforeLast('/', "")
            }
            return false
        }
        val active = if (kind == "manga") ImportManager.activeIdsForScanner() else NovelImportManager.activeIdsForScanner()
        val importing = active.map { "$root/${session.uuid(kind, it)}" }.toSet() +
            session.database.catalog().allMeta().filter { it.name.startsWith("importing:$kind:") && it.value.isNotEmpty() }.map { it.value }
        fun supported(node: LibraryNode): Boolean = node.directory ||
            node.path.substringAfterLast('.').lowercase() in if (kind == "manga")
                setOf("zip", "cbz", "rar", "cbr", "7z", "pdf") else setOf("txt", "epub", "zip", "html", "htm", "xhtml")
        listings.getValue(root).forEach { node ->
            val name = node.path.substringAfterLast('/')
            if (name.startsWith('.') || (name == "sr_ncnn_benchmark.txt" || name == "sr_bench_out") || node.path in importing) return@forEach
            if (owned(node.path)) return@forEach
            // A previously catalogued nested source already owns this wrapper. Do not create
            // a second manga for its parent while preserving the original identity and records.
            if (node.path in copiedRoots || node.path in ownedParents) return@forEach
            if (supported(node)) out += node
        }
        return out.distinctBy { it.path }
    }
}
