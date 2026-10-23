package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.mangaimport.translate.ReaderTranslationHub
import com.moe.starflow.novel.translate.NovelTranslationHub
import com.moe.starflow.sr.SrProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.UUID

data class LibraryDeleteState(val location: String = "", val kind: String = "",
    val running: Boolean = false, val failures: List<String> = emptyList(), val runId: String = "", val activeUuids: Set<String> = emptySet())

/** Deleting remains a durable catalog state until every destructive step succeeds. */
object LibraryDeletion {
    val state = MutableStateFlow(LibraryDeleteState())
    private var consumed = ""

    fun isDeleting(location: String, kind: String, uuid: String): Boolean = state.value.let {
        it.running && it.location == location && it.kind == kind && uuid in it.activeUuids
    }

    @Synchronized fun consumeFailures(): List<String>? {
        val value = state.value
        if (value.running || value.runId == consumed) return null
        consumed = value.runId
        return value.failures.takeIf { it.isNotEmpty() }
    }

    fun start(context: Context, kind: String, ids: List<Long>, expectedUuids: Map<Long, String> = emptyMap()) {
        val pinned = LibrarySessions.pin(context)
        val session = LibrarySessions.current(pinned)
        LibrarySessions.launch(pinned) {
            LibraryWork.sourceMutations.withLock {
                val run = UUID.randomUUID().toString()
                state.value = LibraryDeleteState(session.locationId, kind, true, runId = run)
                val failures = mutableListOf<String>()
                try {
                    session.prepare()
                    ids.forEach { id ->
                        session.storage.invalidateLookups() // External removal may invalidate cached SAF document handles.
                        val book = session.database.catalog().book(kind, id) ?: return@forEach
                        if (expectedUuids[id]?.let { it != book.uuid } == true) {
                            failures += "作品引用已失效：$id"
                            return@forEach
                        }
                        val deletingBody = JSONObject(book.body)
                        val ref = BookRef(session.locationId, kind, book.uuid)
                        while (!BookRecordLocks.tryExclusive(ref, includeSources = false)) kotlinx.coroutines.delay(100)
                        state.value = state.value.copy(activeUuids = setOf(book.uuid))
                        try {
                            validateSource(session, book)
                            planSourceCleanup(session, book, deletingBody)
                            session.putBook(kind, id, deletingBody, deleting = true)
                            session.flush() // Never delete sources before the intent reaches the root.
                            LibrarySessions.changes.value += 1
                            val key = BookRef(session.locationId, kind, book.uuid).key
                            if (kind == "manga") {
                                ReaderTranslationHub.shutdownBook(key)
                                SrProcessor.stopManga(id, key)
                                session.importedPageTranslationDao().deleteMangaScoped(id, key)
                                session.importedPageSrDao().deleteMangaScoped(id, key)
                            } else {
                                NovelTranslationHub.shutdownBook(key)
                                session.novelParagraphTranslationDao().deleteForNovelScoped(id, key)
                            }
                            session.flush()
                            val sourceRoot = deletingBody.getString("deleteSourceRoot")
                            while (LibrarySources.hasReaders(session.local(sourceRoot))) kotlinx.coroutines.delay(50)
                            deleteSource(session, book, deletingBody)
                            session.storage.delete(".starflow/books/${book.uuid}")
                            if (session.storage !is FileLibraryStorage) {
                                session.local(sourceRoot).deleteRecursively()
                                session.local(".starflow/books/${book.uuid}").deleteRecursively()
                            }
                            if (session.storage.exists(".starflow/legacy/${book.uuid}")) session.storage.delete(".starflow/legacy/${book.uuid}")
                            listOf(".starflow/books/${book.uuid}", ".starflow/legacy/${book.uuid}").forEach { archive ->
                                val file = session.recordArchive(archive)
                                if (file.exists() && !file.deleteRecursively()) throw IOException("作品归档记录清理失败")
                            }
                            session.finishRemoval(kind, id)
                            session.compact()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            val detail = "${session.storage.displayPath}/${book.source}：${e.message ?: e.javaClass.simpleName}"
                            failures += detail
                            // Keep the placeholder even when this error itself cannot be written back.
                            // Compaction may fail after removal committed. Never recreate a removed book.
                            if (session.database.catalog().book(kind, id)?.uuid == book.uuid)
                                runCatching { session.putBook(kind, id, deletingBody, deleting = true, error = detail) }
                        } finally { BookRecordLocks.endExclusive(ref) }
                        LibrarySessions.changes.value += 1
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failures += e.message ?: e.javaClass.simpleName
                } finally {
                    state.value = LibraryDeleteState(session.locationId, kind, false, failures, run)
                    LibrarySessions.changes.value += 1
                }
            }
        }
    }

    /** Persist ownership before deleting, so a retry still knows about the adjacent metadata. */
    internal fun planSourceCleanup(session: LibrarySession, book: LibraryBook, body: JSONObject) {
        if (!body.has("deleteSourceRoot")) {
            val copiedRoot = "${book.kind}_import/${book.uuid}"
            val parent = book.source.substringBeforeLast('/', "")
            val ownsCopy = book.source == copiedRoot || book.source.startsWith("$copiedRoot/")
            val sidecars = if (!ownsCopy && session.storage.exists(book.source)) {
                val nodes = session.storage.list(parent)
                if (nodes.singleOrNull { it.path == book.source }?.directory == false)
                    BookMetadataReader.sidecarNodes(nodes, book.source, parent != "${book.kind}_import") else emptyList()
            } else emptyList()
            body.put("deleteSourceRoot", if (ownsCopy) copiedRoot else book.source)
            body.put("deleteSidecars", JSONArray(sidecars.map { it.path }))
        }
        val root = body.getString("deleteSourceRoot")
        // An old app-copy wrapper can also be the source of a duplicate catalog entry.
        // Once our original is gone, removing its records must not require deleting that wrapper.
        if (root != book.source && !session.storage.exists(book.source) && conflictingSource(session, book, root) != null)
            body.put("deleteSourceRoot", book.source)
        validateCleanup(session, book, body)
    }

    private fun validateCleanup(session: LibrarySession, book: LibraryBook, body: JSONObject) {
        val root = safeLibraryPath(body.getString("deleteSourceRoot"))
        val copied = "${book.kind}_import/${book.uuid}"
        if (root != book.source && !(root == copied && book.source.startsWith("$copied/")))
            throw IOException("删除目录不属于此作品，已停止删除")
        validateSource(session, book.copy(source = root))
        val parent = book.source.substringBeforeLast('/', "")
        val sidecars = body.optJSONArray("deleteSidecars") ?: JSONArray()
        for (i in 0 until sidecars.length()) {
            val path = safeLibraryPath(sidecars.getString(i))
            val name = path.substringAfterLast('/')
            val related = BookMetadataReader.namedSidecar(book.source.substringAfterLast('/'), name) ||
                parent != "${book.kind}_import" && com.moe.starflow.mangaimport.data.ComicMetadataParser.isMetadataEntry(name)
            if (path.substringBeforeLast('/', "") != parent || !related) throw IOException("删除元数据不属于此作品，已停止删除")
            validateSource(session, book.copy(source = path))
        }
    }

    internal fun deleteSource(session: LibrarySession, book: LibraryBook, body: JSONObject) {
        validateCleanup(session, book, body)
        val sourceRoot = body.getString("deleteSourceRoot")
        if (session.storage.exists(sourceRoot)) session.storage.delete(sourceRoot)
        val sidecars = body.optJSONArray("deleteSidecars") ?: JSONArray()
        for (i in 0 until sidecars.length()) {
            val path = sidecars.getString(i)
            if (session.storage.exists(path)) session.storage.delete(path)
        }
        val parent = book.source.substringBeforeLast('/', "")
        // Empty dedicated wrappers are removed; a user's shared group or import root stays.
        if (parent.startsWith("${book.kind}_import/") && session.storage.exists(parent) && session.storage.list(parent).isEmpty() &&
            conflictingSource(session, book, parent) == null)
            session.storage.delete(parent)
    }

    internal fun validateSource(session: LibrarySession, book: LibraryBook) {
        val path = safeLibraryPath(book.source)
        if (!path.startsWith("${book.kind}_import/")) throw IOException("来源不属于作品存储目录，已停止删除")
        if (session.storage is FileLibraryStorage) {
            val root = session.storage.root.canonicalPath + java.io.File.separator
            val source = java.io.File(session.storage.root, path)
            if (java.nio.file.Files.isSymbolicLink(source.toPath()) || !source.canonicalPath.startsWith(root))
                throw IOException("作品来源越出书库或为符号链接，已停止删除")
        }
        // Missing sources cannot delete another book. Catalog paths alone are not live ownership.
        val conflict = conflictingSource(session, book, path)
        if (conflict != null) {
            val title = JSONObject(conflict.body).optString("title").ifBlank { conflict.uuid }
            throw IOException("来源仍被另一书目使用，已停止删除以免影响该书目：$title\n来源：${conflict.source}\nUUID：${conflict.uuid}")
        }
    }

    private fun conflictingSource(session: LibrarySession, book: LibraryBook, path: String): LibraryBook? {
        if (!session.storage.exists(path)) return null
        val others = listOf("manga", "novel").flatMap(session::books).filter { it.uuid != book.uuid }
        val overlap = others.firstOrNull {
            (it.source == path || it.source.startsWith("$path/") || path.startsWith("${it.source}/")) && session.storage.exists(it.source)
        }
        if (overlap != null) return overlap
        val sourceUri = session.storage.uri(path)
        val files = session.storage as? FileLibraryStorage
        if (files != null) {
            // File URIs are determined by their path. Reject different locators before doing
            // filesystem IO: probing every peer for every validation makes a batch quadratic.
            return others.firstOrNull {
                android.net.Uri.fromFile(java.io.File(files.root, safeLibraryPath(it.source))) == sourceUri &&
                    files.exists(it.source)
            }
        }
        return others.firstOrNull { session.storage.exists(it.source) && session.storage.uri(it.source) == sourceUri }
    }
}
