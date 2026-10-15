package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.mangaimport.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/** Directory additions never change the meaning of an in-flight page number. */
object MangaDirectoryUpdates {
    private val pending = ConcurrentHashMap<String, Job>()
    fun refresh(context: Context, session: LibrarySession, books: List<LibraryBook>, failures: MutableList<String>, listings: MutableMap<String, List<LibraryNode>> = mutableMapOf()) {
        books.filter { !it.deleting }.forEach { book ->
            val body = JSONObject(book.body)
            val rootPresent = session.sourcePresence[book.source] ?: return@forEach
            val root = session.local(book.source)
            if (LibrarySources.hasReaders(root) || BookRecordLocks.busy(BookRef(session.locationId, "manga", book.uuid))) {
                val key = "${session.locationId}:${book.uuid}"
                pending.computeIfAbsent(key) {
                    LibrarySessions.launch(context) {
                        try {
                            while (LibrarySources.hasReaders(root) || BookRecordLocks.busy(BookRef(session.locationId, "manga", book.uuid))) delay(100)
                            val errors = mutableListOf<String>()
                            refresh(context, session, listOfNotNull(session.database.catalog().byUuid(book.uuid)), errors)
                            errors.forEach { com.moe.starflow.utils.LogCollector.w("MangaDirectory", it) }
                        } finally { pending.remove(key) }
                    }
                }
                return@forEach
            }
            val ref = BookRef(session.locationId, "manga", book.uuid)
            if (!BookRecordLocks.tryExclusive(ref)) return@forEach
            try {
                if (LibrarySources.hasReaders(root)) return@forEach
                val old = MangaSourceIndex.load(session, body.optString("sourceIndexPath"))
                if (!rootPresent && old == null) return@forEach
                val container = body.optBoolean("isArchive") || body.optBoolean("isPdf")
                val updated = if (!rootPresent) old!!.copy(pages = old.pages.map { it.copy(missing = true, sourceMissing = true) })
                else if (container) {
                    val parent = book.source.substringBeforeLast('/', "")
                    val node = listings.getOrPut(parent) { session.storage.list(parent) }.first { it.path == book.source }
                    MangaSourceIndex.build(root.parentFile!!, old, root.name, node)
                } else MangaSourceIndex.build(root, old)
                session.sourcePresence[book.source] = rootPresent // Book presence and per-page availability are separate.
                if (updated.problems.isNotEmpty()) session.sourceIssues[book.source] = "来源分页结构发生变化：${updated.problems.joinToString()}"
                else session.sourceIssues.remove(book.source)
                val missing = updated.pages.count { it.missing }
                val changed = updated.pages.count { !it.missing && it.path in updated.problems }
                val structureChanged = old != null && (
                    old.pages.map { Triple(it.key, it.chapter, it.missing) } != updated.pages.map { Triple(it.key, it.chapter, it.missing) } ||
                    old.problems != updated.problems)
                fun keys(value: String): Set<String> = JSONArray(value).let { a -> (0 until a.length()).map(a::getString).toSet() }
                val ignored = keys(body.optString("ignoredMissingRecordKeys", "[]"))
                val missingKeys = updated.pages.filter { it.missing }.map { it.key }.toSet()
                val catalog = session.database.catalog()
                val recordedPages = if (missingKeys.isEmpty()) emptySet() else catalog.recordedMangaPages(book.id, ref.key).toSet()
                val unmatched = recordedPages.mapNotNull { old?.pages?.getOrNull(it)?.key }
                    .filter { it in missingKeys && it !in ignored }.toSet()
                val priorUnmatched = keys(body.optString("missingRecordKeys", "[]"))
                if (old?.json().toString() == updated.json().toString() &&
                    body.optInt("missingPages") == missing && body.optInt("changedPages") == changed &&
                    unmatched == priorUnmatched) return@forEach
                val newPositions = updated.pages.withIndex().associate { it.value.key to it.index }
                val positions = old?.pages?.map { newPositions.getValue(it.key) }?.toIntArray() ?: IntArray(0)
                val mapping = if (positions.indices.all { positions[it] == it }) IntArray(0) else positions
                val path = updated.store(session, book.uuid)
                body.put("sourceIndexPath", path).put("pageCount", updated.pages.size)
                    .put("chapters", MangaChapter.listToJson(updated.chapters))
                    .put("missingPages", missing).put("changedPages", changed)
                    .put("missingRecordKeys", JSONArray(unmatched.toList()).toString())
                if (structureChanged) body.put("lastReadPage", 0).put("lastReadAt", 0)
                if (old != null) {
                    val oldByKey = old.pages.associateBy { it.key }
                    val added = updated.pages.count { it.key !in oldByKey }
                    val restored = updated.pages.count { !it.missing && oldByKey[it.key]?.missing == true }
                    // Unrecorded missing pages need no warning. Ignored stable keys stay ignored.
                    if (structureChanged && (added > 0 || restored > 0 || changed > 0) || (unmatched - priorUnmatched).isNotEmpty()) {
                        body.put("sourceNoticeId", java.util.UUID.randomUUID().toString())
                            .put("sourceNotice", context.getString(com.moe.starflow.R.string.manga_source_change_summary,
                                added, missing, restored, changed))
                    } else if (structureChanged) {
                        // A silent deletion must not leave a stale warning from an earlier layout.
                        body.put("sourceNoticeSeen", body.optString("sourceNoticeId")).put("sourceNotice", "")
                    }
                }
                if (body.optString("titleOrigin", "user") != "user") {
                    val copied = book.source == "manga_import/${book.uuid}"
                    body.put("title", if (copied) body.optString("sourceName").ifBlank { body.optString("title") } else if (container) root.nameWithoutExtension else root.name)
                }
                runBlocking { session.updateMangaLayout(book, body, mapping) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += "${book.source}：${e.message}" }
            finally { BookRecordLocks.endExclusive(ref) }
        }
    }
}

/** An export holds both session and artifact leases; destructive operations wait for it. */
object BookRecordLocks {
    private val counts = ConcurrentHashMap<String, Int>()
    private val sources = ConcurrentHashMap<String, Int>()
    private val exclusive = hashSetOf<String>()
    @Synchronized fun exporting(ref: BookRef) = (counts[ref.key] ?: 0) > 0
    @Synchronized fun busy(ref: BookRef) = exporting(ref) || (sources[ref.key] ?: 0) > 0 || ref.key in exclusive
    @Synchronized fun tryExclusive(ref: BookRef, includeSources: Boolean = true): Boolean {
        if (exporting(ref) || includeSources && (sources[ref.key] ?: 0) > 0 || ref.key in exclusive) return false
        exclusive += ref.key
        return true
    }
    @Synchronized fun endExclusive(ref: BookRef) { exclusive.remove(ref.key); (this as java.lang.Object).notifyAll() }
    @Synchronized fun acquire(ref: BookRef, source: Boolean = false): java.io.Closeable {
        while (ref.key in exclusive) (this as java.lang.Object).wait(100)
        val readers = if (source) sources else counts
        readers[ref.key] = (readers[ref.key] ?: 0) + 1
        return java.io.Closeable { synchronized(this) {
            val remaining = (readers[ref.key] ?: 1) - 1
            if (remaining <= 0) readers.remove(ref.key) else readers[ref.key] = remaining
            (this as java.lang.Object).notifyAll()
        } }
    }
}
