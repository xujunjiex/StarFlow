package com.moe.starflow.library

import android.content.Context
import com.moe.starflow.data.ImportedPageSr
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.NovelParagraphTranslation
import com.moe.starflow.mangaimport.data.ImportedMangaStore
import com.moe.starflow.mangaimport.data.MangaPageOrder
import com.moe.starflow.mangaimport.reader.ReaderPageSource
import com.moe.starflow.novel.data.NovelStore
import com.moe.starflow.novel.translate.NovelParagraphSplitter
import com.moe.starflow.utils.LogCollector
import org.json.JSONArray
import org.json.JSONObject

/** Promote verified legacy rows after the archival migration, including installations already archived by an older build. */
internal object LegacyRecordActivation {
    private const val TAG = "LegacyRecordActivation"

    fun activate(app: Context, session: LibrarySession) {
        val catalog = session.database.catalog()
        val oldManga = runCatching { ImportedMangaStore.loadLegacy(app) }.getOrElse {
            LogCollector.w(TAG, "旧漫画清单不可读，暂不激活旧页记录", it)
            emptyList()
        }
        val oldNovels = runCatching { NovelStore.loadLegacy(app) }.getOrElse {
            LogCollector.w(TAG, "旧小说清单不可读，暂不恢复阅读进度", it)
            emptyList()
        }
        for (kind in listOf("manga", "novel")) for (book in catalog.books(kind)) {
            if (book.deleting || catalog.meta("removed:${book.uuid}") == "1" ||
                catalog.meta("legacyActive:${book.uuid}") == "1") continue
            val file = session.recordArchive(".starflow/legacy/${book.uuid}/records.json")
            if (!file.isFile) continue
            runCatching {
                val archive = JSONObject(file.readText(Charsets.UTF_8))
                require(archive.getString("kind") == kind && archive.getLong("legacyId") == book.id)
                if (kind == "manga") activateManga(session, book, archive,
                    oldManga.singleOrNull { it.id == book.id && it.addedAt == JSONObject(book.body).getLong("addedAt") })
                else activateNovel(session, book, archive,
                    oldNovels.singleOrNull { it.id == book.id && it.addedAt == JSONObject(book.body).getLong("addedAt") })
            }.onFailure { LogCollector.w(TAG, "旧作品记录激活失败，保留归档以便重试：${book.uuid}", it) }
        }
    }

    private fun activateManga(session: LibrarySession, book: LibraryBook, archive: JSONObject,
                              old: com.moe.starflow.mangaimport.data.ImportedManga?) {
        // Without the old page-order version and a matching source, page-indexed text could appear on another image.
        if (old == null) return
        val body = JSONObject(book.body)
        val root = session.local(book.source)
        if (!root.exists()) return
        val source = ReaderPageSource(body.optBoolean("isArchive"), root.path, body.optBoolean("isPdf"))
        try {
            if (source.size != old.pageCount) return
            val plan = if (old.pageOrderVersion < MangaPageOrder.CURRENT_VERSION)
                MangaPageOrder.legacyToNewPlan(source.rawPageKeys(), source.orderedPageKeys()) else null
            val planKey = plan?.joinToString(",").orEmpty()
            val alreadyApplied = plan != null && old.pageOrderPlan == planKey
            fun page(index: Int): Int? {
                if (index !in 0 until source.size) return null
                return (if (alreadyApplied) index else MangaPageOrder.mapPage(plan, index))
                    .takeIf { it in 0 until source.size }
            }
            val records = archive.getJSONObject("records")
            val stamp = body.getLong("addedAt").toString()
            val key = "${session.locationId}:manga:${book.uuid}"
            val translations = records.rows("imported_page_translation").mapNotNull { row ->
                if (row.optLong("mangaId", -1) != book.id || row.optString("mangaKey") != stamp) return@mapNotNull null
                ImportedPageTranslation(book.id, page(row.getInt("pageIndex")) ?: return@mapNotNull null,
                    row.optInt("state"), row.text("sourceText"), row.text("translatedText"), row.text("bubbleRects"),
                    row.text("failCode"), row.text("failMessage"), row.optLong("updatedAtMs"), key,
                    row.text("translatorName"), row.text("sourceLang"), row.text("targetLang"), row.text("ocrModel"))
            }
            val resolutions = records.rows("imported_page_sr").mapNotNull { row ->
                if (row.optLong("mangaId", -1) != book.id || row.optString("mangaKey") != stamp) return@mapNotNull null
                val index = page(row.getInt("pageIndex")) ?: return@mapNotNull null
                ImportedPageSr(book.id, index, row.optInt("state"), row.text("modelName"),
                    row.optInt("srcWidth"), row.optInt("srcHeight"), row.optLong("srcBytes"),
                    row.optInt("outWidth"), row.optInt("outHeight"), row.optLong("outBytes"),
                    row.text("failCode"), row.text("failMessage"), row.optLong("startedAtMs"),
                    row.optLong("finishedAtMs"), row.optLong("updatedAtMs"), key,
                    source.key(index), row.text("executionJson"))
            }
            session.database.runInTransaction {
                if (session.database.catalog().byUuid(book.uuid)?.id != book.id) return@runInTransaction
                val catalog = session.database.catalog()
                if (catalog.meta("legacyActive:${book.uuid}") == "1") return@runInTransaction
                catalog.insertLegacyTranslations(translations)
                catalog.insertLegacyResolutions(resolutions)
                val latest = catalog.book("manga", book.id) ?: return@runInTransaction
                val updated = JSONObject(latest.body)
                if ((updated.optInt("pageOrderVersion") < MangaPageOrder.CURRENT_VERSION ||
                        updated.optInt("lastReadPage") == 0) && old.lastReadPage in 0 until source.size)
                    updated.put("lastReadPage", page(old.lastReadPage) ?: 0)
                updated.put("pageOrderVersion", MangaPageOrder.CURRENT_VERSION)
                if (planKey.isNotEmpty()) updated.put("pageOrderPlan", planKey)
                catalog.put(latest.copy(body = updated.toString()))
                catalog.meta(LibraryMeta("legacyActive:${book.uuid}", "1"))
            }
        } finally { source.close() }
    }

    private fun activateNovel(session: LibrarySession, book: LibraryBook, archive: JSONObject,
                              old: com.moe.starflow.novel.data.ImportedNovel?) {
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
            if (catalog.byUuid(book.uuid)?.id != book.id || catalog.meta("legacyActive:${book.uuid}") == "1")
                return@runInTransaction
            catalog.insertLegacyParagraphs(paragraphs)
            val latest = catalog.book("novel", book.id) ?: return@runInTransaction
            if (old != null && JSONObject(latest.body).optLong("lastReadAt") == 0L) {
                val updated = JSONObject(latest.body).put("lastReadChapter", old.lastReadChapter)
                    .put("lastReadParaIndex", old.lastReadParaIndex).put("lastReadPage", 0)
                    .put("lastReadAt", old.lastReadAt)
                catalog.put(latest.copy(body = updated.toString()))
            }
            catalog.meta(LibraryMeta("legacyActive:${book.uuid}", "1"))
        }
    }

    private fun JSONObject.rows(name: String): List<JSONObject> = optJSONArray(name)?.let { rows: JSONArray ->
        (0 until rows.length()).map(rows::getJSONObject)
    }.orEmpty()

    private fun JSONObject.text(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
