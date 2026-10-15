package com.moe.starflow.library

import com.moe.starflow.mangaimport.data.MangaSourceIndex
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Repair the old scanner's file/containing-directory duplicates, never same-title books. */
internal object MangaDuplicateRepair {
    fun repair(session: LibrarySession, failures: MutableList<String>) {
        val catalog = session.database.catalog()
        val books = session.books("manga").filter { !it.deleting }
        for (child in books) {
            val childBody = JSONObject(child.body)
            if (!childBody.optBoolean("isArchive") && !childBody.optBoolean("isPdf")) continue
            val parent = books.singleOrNull { it.source == child.source.substringBeforeLast('/', "") } ?: continue
            val refs = listOf(child, parent).map { BookRef(session.locationId, "manga", it.uuid) }
            if (!BookRecordLocks.tryExclusive(refs[0])) continue
            try {
                if (!BookRecordLocks.tryExclusive(refs[1])) continue
                try {
                    if (catalog.byUuid(child.uuid) != child || catalog.byUuid(parent.uuid) != parent ||
                        LibrarySources.hasReaders(session.local(child.source)) || LibrarySources.hasReaders(session.local(parent.source))) continue
                    // Publishing or cleaning either identity must finish before it can be retired.
                    if (catalog.pending().any { it.body.contains(child.uuid) || it.body.contains(parent.uuid) }) continue
                    val childIndex = MangaSourceIndex.load(session, childBody.optString("sourceIndexPath")) ?: continue
                    val parentIndex = MangaSourceIndex.load(session, JSONObject(parent.body).optString("sourceIndexPath")) ?: continue
                    fun physicalPages(book: LibraryBook, index: MangaSourceIndex): List<String> {
                        val body = JSONObject(book.body)
                        val root = if (body.optBoolean("isArchive") || body.optBoolean("isPdf"))
                            book.source.substringBeforeLast('/') else book.source
                        return index.pages.map { page ->
                            JSONArray(listOf(safeLibraryPath("$root/${page.path}"), page.kind, page.entry)).toString()
                        }
                    }
                    val pages = physicalPages(child, childIndex)
                    if (pages.isEmpty() || pages != physicalPages(parent, parentIndex)) continue
                    fun hasRecords(book: LibraryBook): Boolean {
                        val key = BookRef(session.locationId, "manga", book.uuid).key
                        return catalog.translationCount(book.id, key) > 0 || catalog.resolutionCount(book.id, key) > 0
                    }
                    val childRecorded = hasRecords(child)
                    val parentRecorded = hasRecords(parent)
                    if (childRecorded && parentRecorded) {
                        failures += "${child.source}：文件与外层目录重复收录，但两份书目均有处理记录，已保留两份记录；可在作品数据中分别导出"
                        continue
                    }
                    // Keep the processed identity (including its stable page keys and SR paths).
                    // With no processing records, retain the original file identity.
                    val owner = if (parentRecorded) parent else child
                    val duplicate = if (owner == child) parent else child
                    val body = JSONObject(owner.body)
                    val duplicateBody = JSONObject(duplicate.body)
                    val named = listOf(child, parent).filter {
                        JSONObject(it.body).optString("titleOrigin", "user") == "user"
                    }.minByOrNull { JSONObject(it.body).optLong("addedAt") }
                    named?.let {
                        body.put("title", JSONObject(it.body).getString("title")).put("titleOrigin", "user")
                    }
                    if (body.optString("description").isBlank()) body.put("description", duplicateBody.optString("description"))
                    // Identical ordered physical pages permit keeping a read position from the other card.
                    if (body.optInt("lastReadPage") == 0) body.put("lastReadPage", duplicateBody.optInt("lastReadPage"))
                    val archive = session.recordArchive(".starflow/books/${owner.uuid}/reconciled/${duplicate.uuid}.json")
                    archive.parentFile!!.let { if (!it.isDirectory && !it.mkdirs() && !it.isDirectory) error("无法保存重复书目归档") }
                    val temporary = java.io.File(archive.parentFile, "${archive.name}.migrating")
                    temporary.writeText(JSONObject().put("formatVersion", 1).put("reason", "duplicate-containing-directory")
                        .put("retainedUuid", owner.uuid).put("book", duplicateBody)
                        .put("sourceIndex", (if (duplicate == child) childIndex else parentIndex).json()).toString(), Charsets.UTF_8)
                    check(temporary.renameTo(archive)) { "无法保存重复书目归档" }
                    session.database.runInTransaction {
                        check(catalog.byUuid(child.uuid) == child && catalog.byUuid(parent.uuid) == parent) { "修复期间书目已变化，请重新扫描" }
                        check(!hasRecords(duplicate)) { "重复书目新增了处理记录，已停止修复" }
                        session.putBook("manga", owner.id, body)
                        // Row-only retirement: never invoke the source/artifact deletion pipeline.
                        catalog.meta(LibraryMeta("removed:${duplicate.uuid}", "1"))
                        catalog.meta(LibraryMeta("duplicateOf:${duplicate.uuid}", owner.uuid))
                        catalog.remove("manga", duplicate.id)
                    }
                    session.sourcePresence.remove(duplicate.source)
                    LibrarySessions.changes.value += 1
                } finally { BookRecordLocks.endExclusive(refs[1]) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures += "${child.source}：[修复重复书目] ${e.message}" }
            finally { BookRecordLocks.endExclusive(refs[0]) }
        }
    }
}
