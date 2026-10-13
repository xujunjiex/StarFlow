package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.mangaimport.translate.ReaderTranslationHub
import com.moe.starflow.novel.translate.NovelTranslationHub
import com.moe.starflow.sr.SrProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

data class LibraryDeleteState(val location: String = "", val kind: String = "",
    val running: Boolean = false, val failures: List<String> = emptyList(), val runId: String = "")

/** Deleting remains a durable catalog state until every destructive step succeeds. */
object LibraryDeletion {
    val state = MutableStateFlow(LibraryDeleteState())
    private val lock = Mutex()
    private var consumed = ""

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
            lock.withLock {
                val run = UUID.randomUUID().toString()
                state.value = LibraryDeleteState(session.locationId, kind, true, runId = run)
                val failures = mutableListOf<String>()
                try {
                    session.prepare()
                    ids.forEach { id ->
                        val book = session.database.catalog().book(kind, id) ?: return@forEach
                        if (expectedUuids[id]?.let { it != book.uuid } == true) {
                            failures += "作品引用已失效：$id"
                            return@forEach
                        }
                        try {
                            validateSource(session, book)
                            session.putBook(kind, id, JSONObject(book.body), deleting = true)
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
                            while (LibrarySources.hasReaders(session.local(book.source))) kotlinx.coroutines.delay(50)
                            session.storage.delete(book.source)
                            session.storage.delete(".starflow/books/${book.uuid}")
                            if (session.storage !is FileLibraryStorage) {
                                session.local(book.source).deleteRecursively()
                                session.local(".starflow/books/${book.uuid}").deleteRecursively()
                            }
                            session.finishRemoval(kind, id)
                            session.storage.delete(".starflow/legacy/${book.uuid}")
                            session.compact()
                        } catch (e: Exception) {
                            val detail = "${session.storage.displayPath}/${book.source}：${e.message ?: e.javaClass.simpleName}"
                            failures += detail
                            // Keep the placeholder even when this error itself cannot be written back.
                            runCatching { session.putBook(kind, id, JSONObject(book.body), deleting = true, error = detail) }
                        }
                        LibrarySessions.changes.value += 1
                    }
                } catch (e: Exception) {
                    failures += e.message ?: e.javaClass.simpleName
                } finally {
                    state.value = LibraryDeleteState(session.locationId, kind, false, failures, run)
                    LibrarySessions.changes.value += 1
                }
            }
        }
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
        val overlap = listOf("manga", "novel").flatMap(session::books).any {
            it.uuid != book.uuid && (it.source == path || it.source.startsWith("$path/") || path.startsWith("${it.source}/"))
        }
        if (overlap) throw IOException("作品来源目录重叠，已停止删除")
        if (session.storage.exists(path)) {
            val sourceUri = session.storage.uri(path)
            if (listOf("manga", "novel").flatMap(session::books).any {
                it.uuid != book.uuid && session.storage.exists(it.source) && session.storage.uri(it.source) == sourceUri
            }) throw IOException("作品来源指向同一提供器文档，已停止删除")
        }
    }
}
