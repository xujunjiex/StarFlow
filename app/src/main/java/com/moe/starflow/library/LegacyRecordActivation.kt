package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.data.ImportedPageSr
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.NovelParagraphTranslation
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaPageOrder
import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.mangaimport.data.MangaChapterSplitter
import com.moe.starflow.mangaimport.data.MangaSourceIndex
import com.moe.starflow.mangaimport.reader.ReaderPageSource
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.utils.LogCollector
import org.json.JSONArray
import org.json.JSONObject

/** Activate verified legacy rows only while the initial import's explicit pending intent is valid. */
internal object LegacyRecordActivation {
    private const val TAG = "LegacyRecordActivation"

    // v0.11.2 also accepts pre-rename title|timestamp keys left by earlier releases.
    internal fun matchesMangaKey(key: String, stamp: String): Boolean =
        key == stamp || key.endsWith("|$stamp")

    internal fun markPending(session: LibrarySession, uuid: String) {
        session.database.catalog().meta(LibraryMeta("legacyActivation:$uuid", "pending"))
        session.database.catalog().meta(LibraryMeta("legacyProgress:$uuid", "pending"))
    }

    internal fun cancelPending(session: LibrarySession, uuid: String) {
        val catalog = session.database.catalog()
        if (catalog.meta("legacyActivation:$uuid") == "pending")
            catalog.meta(LibraryMeta("legacyActivation:$uuid", "cancelled"))
    }

    internal fun cancelProgress(session: LibrarySession, uuid: String) {
        val catalog = session.database.catalog()
        if (catalog.meta("legacyProgress:$uuid") == "pending")
            catalog.meta(LibraryMeta("legacyProgress:$uuid", "cancelled"))
    }

    private fun eligible(session: LibrarySession, book: LibraryBook): Boolean {
        val catalog = session.database.catalog()
        return !book.deleting && catalog.meta("removed:${book.uuid}") != "1" &&
            catalog.meta("legacyActive:${book.uuid}") != "1" &&
            catalog.meta("legacyActivation:${book.uuid}") == "pending"
    }

    fun activate(app: Context, session: LibrarySession) {
        val catalog = session.database.catalog()
        val oldManga = runCatching { ImportedMangaStore.loadLegacy(app) }.getOrElse {
            LogCollector.w(TAG, "旧漫画清单不可读，暂不激活旧页记录", it)
            emptyList()
        }
        for (kind in listOf("manga", "novel")) for (book in catalog.books(kind)) {
            // Older archive-only builds did not record clears. Their empty rows/progress cannot
            // prove that restoration is safe; preserve those archives instead of guessing.
            if (!eligible(session, book)) continue
            val file = session.recordArchive(".starflow/legacy/${book.uuid}/records.json")
            if (!file.isFile) continue
            runCatching {
                val archive = JSONObject(file.readText(Charsets.UTF_8))
                require(archive.getString("kind") == kind && archive.getLong("legacyId") == book.id)
                if (kind == "manga") activateManga(session, book, archive,
                    oldManga.singleOrNull { it.id == book.id && it.addedAt == JSONObject(book.body).getLong("addedAt") })
                else activateNovel(session, book, archive)
            }.onFailure { LogCollector.w(TAG, "旧作品记录激活失败，保留归档以便重试：${book.uuid}", it) }
        }
    }

    private fun activateManga(session: LibrarySession, originalBook: LibraryBook, archive: JSONObject,
                              old: com.moe.starflow.mangaimport.data.ImportedManga?) {
        // Without the old page-order version and a matching source, page-indexed text could appear on another image.
        if (old == null) return
        val root = session.local(originalBook.source)
        if (!root.exists()) return
        // Build the same page table the scanner/reader will use before inserting page-indexed rows.
        // A legacy image directory may now also contain supported ZIP/PDF chapters.
        session.sourcePresence[originalBook.source] = true
        val errors = mutableListOf<String>()
        MangaDirectoryUpdates.refresh(session.context(), session, listOf(originalBook), errors)
        check(errors.isEmpty()) { errors.joinToString("\n") }
        val book = session.database.catalog().byUuid(originalBook.uuid) ?: return
        if (!eligible(session, book)) return
        val body = JSONObject(book.body)
        val source = ReaderPageSource(body.optBoolean("isArchive"), root.path, body.optBoolean("isPdf"))
        try {
            val index = MangaSourceIndex.load(session, body.optString("sourceIndexPath")) ?: return
            val directory = !body.optBoolean("isArchive") && !body.optBoolean("isPdf")
            val raw = if (directory) ArchivedMangaReader.listImageFilesInDir(root) else source.rawPageKeys()
            if (raw.size != old.pageCount) return
            val chapterOrder = if (body.optBoolean("isPdf")) raw else MangaChapterSplitter.split(raw).keys
            val legacyPlan = MangaPageOrder.legacyToNewPlan(raw, chapterOrder)
            val alreadyApplied = legacyPlan != null && old.pageOrderPlan == legacyPlan.joinToString(",")
            val oldOrder = if (old.pageOrderVersion >= MangaPageOrder.CURRENT_VERSION || alreadyApplied || body.optBoolean("isPdf"))
                chapterOrder else ArchivedMangaReader.sortNaturally(raw)
            val positions = if (directory) index.pages.withIndex().filter { it.value.kind == "IMAGE" }
                .associate { it.value.path to it.index }
                else source.orderedPageKeys().withIndex().associate { it.value to it.index }
            if (oldOrder.any { it !in positions }) return
            val plan = oldOrder.map { positions.getValue(it) }
            fun page(index: Int): Int? {
                return plan.getOrNull(index)
            }
            val records = archive.getJSONObject("records")
            val stamp = body.getLong("addedAt").toString()
            val key = "${session.locationId}:manga:${book.uuid}"
            val translations = records.rows("imported_page_translation").mapNotNull { row ->
                if (row.optLong("mangaId", -1) != book.id || !matchesMangaKey(row.optString("mangaKey"), stamp)) return@mapNotNull null
                ImportedPageTranslation(book.id, page(row.getInt("pageIndex")) ?: return@mapNotNull null,
                    row.optInt("state"), row.text("sourceText"), row.text("translatedText"), row.text("bubbleRects"),
                    row.text("failCode"), row.text("failMessage"), row.optLong("updatedAtMs"), key,
                    row.text("translatorName"), row.text("sourceLang"), row.text("targetLang"), row.text("ocrModel"))
            }
            val resolutions = records.rows("imported_page_sr").mapNotNull { row ->
                if (row.optLong("mangaId", -1) != book.id || row.optString("mangaKey") != stamp) return@mapNotNull null
                val targetPage = page(row.getInt("pageIndex")) ?: return@mapNotNull null
                ImportedPageSr(book.id, targetPage, row.optInt("state"), row.text("modelName"),
                    row.optInt("srcWidth"), row.optInt("srcHeight"), row.optLong("srcBytes"),
                    row.optInt("outWidth"), row.optInt("outHeight"), row.optLong("outBytes"),
                    row.text("failCode"), row.text("failMessage"), row.optLong("startedAtMs"),
                    row.optLong("finishedAtMs"), row.optLong("updatedAtMs"), key,
                    source.key(targetPage), row.text("executionJson"))
            }
            session.database.runInTransaction {
                val catalog = session.database.catalog()
                val latest = catalog.byUuid(book.uuid) ?: return@runInTransaction
                if (latest != book || !eligible(session, latest)) return@runInTransaction
                catalog.insertLegacyTranslations(translations)
                catalog.insertLegacyResolutions(resolutions)
                val updated = JSONObject(latest.body)
                if (catalog.meta("legacyProgress:${book.uuid}") == "pending")
                    updated.put("lastReadPage", page(updated.optInt("lastReadPage")) ?: 0)
                updated.put("pageOrderVersion", MangaPageOrder.CURRENT_VERSION)
                updated.put("pageOrderPlan", "")
                catalog.put(latest.copy(body = updated.toString()))
                catalog.meta(LibraryMeta("legacyActive:${book.uuid}", "1"))
                catalog.meta(LibraryMeta("legacyActivation:${book.uuid}", "complete"))
                catalog.meta(LibraryMeta("legacyProgress:${book.uuid}", "complete"))
            }
        } finally { source.close() }
    }

    private fun activateNovel(session: LibrarySession, book: LibraryBook, archive: JSONObject) {
        val body = JSONObject(book.body)
        val stamp = body.getLong("addedAt").toString()
        val key = "${session.locationId}:novel:${book.uuid}"
        val paragraphs = archive.getJSONObject("records").rows("novel_paragraph_translation").mapNotNull { row ->
            if (row.optLong("novelId", -1) != book.id || row.optString("novelKey") != stamp ||
                row.optInt("splitVersion", -1) != NovelParagraphSplitter.SPLIT_VERSION) return@mapNotNull null
            NovelParagraphTranslation(book.id, key, row.getInt("chapterIndex"), row.getInt("paraIndex"),
                row.getString("sourceText"), row.optString("translatedText"), row.optInt("state"),
                row.text("failCode"), row.text("translatorName"), row.text("sourceLang"),
                row.text("targetLang"), row.getInt("splitVersion"), row.optLong("updatedAt"), row.text("imagePayload"))
        }
        session.database.runInTransaction {
            val catalog = session.database.catalog()
            val latest = catalog.byUuid(book.uuid) ?: return@runInTransaction
            if (latest != book || !eligible(session, latest)) return@runInTransaction
            catalog.insertLegacyParagraphs(paragraphs)
            // Initial import already copied progress. Never restore stale preferences over app records.
            catalog.meta(LibraryMeta("legacyActive:${book.uuid}", "1"))
            catalog.meta(LibraryMeta("legacyActivation:${book.uuid}", "complete"))
            catalog.meta(LibraryMeta("legacyProgress:${book.uuid}", "complete"))
        }
    }

    private fun JSONObject.rows(name: String): List<JSONObject> = optJSONArray(name)?.let { rows: JSONArray ->
        (0 until rows.length()).map(rows::getJSONObject)
    }.orEmpty()

    private fun JSONObject.text(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
