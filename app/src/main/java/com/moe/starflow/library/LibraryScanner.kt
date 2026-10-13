package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.mangaimport.data.*
import com.moe.starflow.novel.data.*
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.novel.parser.NovelFormatDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.zip.ZipFile

data class LibraryScanState(val location: String = "", val kind: String = "", val running: Boolean = false,
    val done: Int = 0, val total: Int = 0, val added: Int = 0, val failures: List<String> = emptyList(),
    val runId: String = java.util.UUID.randomUUID().toString())

object LibraryScanner {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(LibraryScanState())
    private var reported: String? = null
    @Synchronized fun consumeFailures(): List<String>? {
        val value = state.value
        if (value.running || reported == value.runId || value.failures.isEmpty()) return null
        reported = value.runId
        return value.failures
    }
    fun start(context: Context, kind: String) {
        if (state.value.running) return
        val pinned = LibrarySessions.pin(context)
        val starting = LibraryScanState(LibrarySessions.current(pinned).locationId, kind, running = true)
        state.value = starting
        LibrarySessions.launch(pinned, scope) {
            try { scan(pinned, kind) }
            catch (e: Exception) { state.value = state.value.copy(running = false, failures = listOf(e.message ?: e.javaClass.simpleName)) }
        }.invokeOnCompletion { if (state.value.runId == starting.runId) state.value = state.value.copy(running = false) }
    }
    suspend fun scan(context: Context, kind: String): LibraryScanState = mutex.withLock {
        withContext(Dispatchers.IO) {
            val pinned = LibrarySessions.pin(context)
            val session = LibrarySessions.current(pinned)
            val lease = LibrarySessions.use()
            val failures = mutableListOf<String>()
            var added = 0
            try {
                LibrarySessions.prepare(pinned)
                val root = if (kind == "manga") "manga_import" else "novel_import"
                val known = session.books(kind)
                // Overall index and candidate enumeration precede per-book inspection.
                session.storage.list(root)
                known.forEach { book ->
                    try { session.sourcePresence[book.source] = session.storage.exists(book.source) }
                    catch (e: Exception) { failures += "${session.storage.displayPath}/${book.source}：[检查来源] ${e.message}" }
                }
                val candidates = discover(session, root, known, kind, failures)
                state.value = LibraryScanState(session.locationId, kind, true, total = candidates.size)
                candidates.forEachIndexed { index, node ->
                    val id = if (kind == "manga") ImportManager.reserveScanId(pinned, null)
                        else NovelImportManager.reserveScanId(pinned, null)
                    if (id == null) return@forEachIndexed
                    val source = session.local(node.path)
                    var localLease: java.io.Closeable? = null
                    var stage = "读取来源"
                    try {
                        if (node.directory) source.mkdirs()
                        localLease = LibrarySources.acquire(source)
                        if (!node.directory) LibrarySources.file(source)
                        val title = source.nameWithoutExtension.ifBlank { source.name }
                        stage = "识别作品"
                        if (kind == "manga") {
                            if (!node.directory && ArchiveTypes.detect(source) == ArchiveKind.ZIP &&
                                ZipFile(source).use { it.getEntry("META-INF/container.xml") != null }) return@forEachIndexed
                            val manga = MangaImporter.inspectLocalSource(pinned, source, id, title)
                            if (manga.pageCount <= 0) throw java.io.IOException("来源中没有可读漫画图片")
                            added += ImportedMangaStore.mergeScanned(pinned, listOf(manga))
                        } else {
                            if (node.directory) LibrarySources.file(source, folderTexts = true)
                            val novel = NovelImporter.inspectLocalSource(pinned, source, id, title)
                            added += NovelStore.mergeScanned(pinned, listOf(novel))
                        }
                        session.sourcePresence[node.path] = true
                        stage = "提交书库"
                        session.flush()
                        if (session.saveState.value.pending != 0) throw java.io.IOException("未保存到书库：${session.saveState.value.error}")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e !is IllegalStateException || e.message != NovelImporter.ERROR_NO_TEXT_CHAPTER)
                            failures += "${session.storage.displayPath}/${node.path}：[$stage] ${e.javaClass.simpleName}：${e.message}"
                    } finally {
                        localLease?.close()
                        if (kind == "manga") ImportManager.releaseScanIds(listOf(id)) else NovelImportManager.releaseScanIds(listOf(id))
                        state.value = state.value.copy(done = index + 1, added = added, failures = failures.toList())
                    }
                }
                LibrarySessions.changes.value += 1
                LibraryScanState(session.locationId, kind, false, candidates.size, candidates.size, added, failures).also { state.value = it }
            } finally {
                state.value = state.value.copy(running = false)
                lease.close()
            }
        }
    }
    private fun discover(session: LibrarySession, root: String, known: List<LibraryBook>, kind: String, failures: MutableList<String>): List<LibraryNode> {
        val out = mutableListOf<LibraryNode>()
        val retired = session.database.catalog().allMeta().filter { it.name.startsWith("legacySource:") && it.value == "1" }
            .map { it.name.removePrefix("legacySource:") }
        val ownedSources = known.map { it.source } + retired
        fun owned(path: String) = ownedSources.any {
            it == path || path.startsWith("$it/") || it.startsWith("$path/")
        }
        val active = if (kind == "manga") ImportManager.activeIdsForScanner() else NovelImportManager.activeIdsForScanner()
        val importing = active.map { "$root/${session.uuid(kind, it)}" }.toSet() +
            session.database.catalog().allMeta().filter { it.name.startsWith("importing:$kind:") && it.value.isNotEmpty() }.map { it.value }
        fun supported(node: LibraryNode): Boolean = node.directory ||
            node.path.substringAfterLast('.').lowercase() in if (kind == "manga")
                setOf("zip", "cbz", "rar", "cbr", "7z", "pdf") else setOf("txt", "epub", "zip", "html", "htm", "xhtml")
        session.storage.list(root).forEach { node ->
            val name = node.path.substringAfterLast('/')
            if (name.startsWith('.') || (name == "sr_ncnn_benchmark.txt" || name == "sr_bench_out") || node.path in importing) return@forEach
            if (owned(node.path)) return@forEach
            if (node.directory && kind == "manga") {
                val children = try { session.storage.list(node.path) }
                    catch (e: Exception) { failures += "${session.storage.displayPath}/${node.path}：[枚举候选] ${e.message}"; return@forEach }
                val archives = children.filter { !it.directory && supported(it) }
                if (children.any { ArchivedMangaReader.isImageFile(it.path) || it.directory }) out += node
                else archives.filterNot { owned(it.path) }.forEach(out::add)
            } else if (supported(node)) out += node
        }
        return out.distinctBy { it.path }
    }
}
