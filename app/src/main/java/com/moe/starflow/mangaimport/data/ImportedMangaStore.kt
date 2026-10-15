package com.moe.starflow.mangaimport.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Per-library Room records and portable commit journal. SharedPreferences is a legacy input only. */
object ImportedMangaStore {

    private const val PREFS_NAME = "manga_import"
    private const val KEY = "imported_manga_list"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    internal fun loadLegacy(context: Context): List<ImportedManga> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toManga() }
        } catch (e: Exception) {
            throw java.io.IOException("旧漫画清单损坏，已停止迁移并保留原资料", e)
        }
    }

    @Synchronized
    fun load(context: Context): List<ImportedManga> {
        val session = com.moe.starflow.library.LibrarySessions.current(context)
        return session.books("manga").map { row ->
            val value = JSONObject(row.body).toManga()
            value.copy(bookUuid = row.uuid, libraryLocationId = session.locationId,
                localRoot = session.local(value.localRoot).path,
                coverPath = value.coverPath?.let(session::cover),
                lost = session.sourcePresence[row.source] == false || row.deleting,
                deleting = row.deleting && row.error == null && com.moe.starflow.library.LibraryDeletion.isDeleting(session.locationId, "manga", row.uuid),
                deletionError = if (row.deleting) row.error ?: if (com.moe.starflow.library.LibraryDeletion.isDeleting(session.locationId, "manga", row.uuid)) null
                    else context.getString(com.moe.starflow.R.string.library_delete_failed) else null)
        }
    }

    @Synchronized
    fun save(context: Context, list: List<ImportedManga>) {
        val session = com.moe.starflow.library.LibrarySessions.current(context)
        list.forEach { persist(context, it) }
        session.books("manga").filter { row -> list.none { it.id == row.id } }
            .forEach { session.removeBook("manga", it.id) }
    }

    private fun persist(context: Context, value: ImportedManga) {
        val session = com.moe.starflow.library.LibrarySessions.forLocation(context, value.libraryLocationId)
        val uuid = value.bookUuid.ifEmpty { session.uuid("manga", value.id) }
        val json = value.toJson().put("bookUuid", uuid)
            .put("localRoot", session.relative(File(value.localRoot)))
        val old = session.database.catalog().book("manga", value.id)?.let { JSONObject(it.body) }
        val cover = value.coverPath
        if (!cover.isNullOrEmpty()) {
            if (cover.startsWith("content://")) json.put("coverPath", old?.opt("coverPath") ?: JSONObject.NULL)
            else {
                val original = File(cover)
                val path = ".starflow/books/$uuid/cover/${original.name}"
                val target = session.local(path)
                if (original.isFile && original.canonicalFile != target.canonicalFile) {
                    target.parentFile?.mkdirs()
                    if (!target.isFile) original.copyTo(target, overwrite = false)
                }
                json.put("coverPath", path)
            }
        }
        session.putBook("manga", value.id, json, stagingCover = cover?.takeIf { !it.startsWith("content://") }
            ?.let { runCatching { session.relative(File(it)) }.getOrNull() })
        com.moe.starflow.library.LibrarySessions.changes.value += 1
    }

    @Synchronized
    fun patch(context: Context, value: ImportedManga, change: (ImportedManga) -> ImportedManga) {
        val session = com.moe.starflow.library.LibrarySessions.forLocation(context, value.libraryLocationId)
        val pinned = session.context()
        val latest = load(pinned).firstOrNull { it.id == value.id && (value.bookUuid.isEmpty() || it.bookUuid == value.bookUuid) } ?: return
        persist(pinned, change(latest))
    }

    @Synchronized
    fun add(context: Context, value: ImportedManga) { persist(context, value) }

    /** Merge only new scan results under the same monitor as manual imports and shelf edits. */
    @Synchronized
    internal fun mergeScanned(context: Context, scanned: List<ImportedManga>): Int {
        val latest = load(context).toMutableList()
        val ids = latest.mapTo(mutableSetOf()) { it.id }
        val paths = latest.mapTo(mutableSetOf()) { canonical(it.localRoot) }
        var added = 0
        scanned.forEach { manga ->
            val path = canonical(manga.localRoot)
            if (manga.id !in ids && paths.none { existing ->
                    path == existing || path.startsWith(existing + File.separator) || existing.startsWith(path + File.separator)
                }) {
                persist(context, manga)
                latest += manga
                ids += manga.id
                paths += path
                added++
            }
        }
        return added
    }

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    @Synchronized
    fun remove(context: Context, id: Long) {
        com.moe.starflow.library.LibrarySessions.current(context).removeBook("manga", id)
    }

    @Synchronized
    fun update(context: Context, value: ImportedManga) {
        val session = com.moe.starflow.library.LibrarySessions.forLocation(context, value.libraryLocationId)
        if (session.database.catalog().book("manga", value.id) != null) persist(context, value)
    }

    /**
     * **只改 `chapters` 一个字段**（锁内读-改-写）。
     *
     * ⚠️ 需要它的原因：章节回填（[MangaChapterMigrator]）与阅读器侧自愈都是**秒级长循环**，
     * 而 [update] 收的是**整条实体**且实现是 `list[idx] = manga` —— 拿循环开始时的快照去写，
     * 会把这段时间里用户翻页写的 `lastReadPage`、改过的书名**整条回滚**
     * （表现是「翻了几页后进度自己退回去」，而且怎么复现都说不清）。
     * 凡是只改某一个字段的写入，都走这里，不要再 `update(manga.copy(...))`。
     */
    @Synchronized
    fun setChapters(context: Context, id: Long, chapters: List<MangaChapter>) {
        val value = load(context).firstOrNull { it.id == id } ?: return
        persist(context, value.copy(chapters = chapters))
    }

    private fun ImportedManga.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("bookUuid", bookUuid)
        put("title", title)
        put("localRoot", localRoot)
        put("isArchive", isArchive)
        // PDF 标记：老清单没有这个键 → 读回 false（旧条目本来也不可能是 PDF）
        put("isPdf", isPdf)
        put("coverPath", coverPath ?: JSONObject.NULL)
        put("pageCount", pageCount)
        put("addedAt", addedAt)
        put("sizeBytes", sizeBytes)
        put("description", description)
        put("metadataVersion", metadataVersion)
        put("sourceIndexPath", sourceIndexPath)
        put("titleOrigin", titleOrigin)
        put("sourceName", sourceName)
        put("missingPages", missingPages)
        put("changedPages", changedPages)
        put("sourceNotice", sourceNotice)
        put("sourceNoticeId", sourceNoticeId)
        put("sourceNoticeSeen", sourceNoticeSeen)
        put("missingRecordKeys", missingRecordKeys)
        put("ignoredMissingRecordKeys", ignoredMissingRecordKeys)
        put("translatedPath", translatedPath ?: JSONObject.NULL)
        put("lastReadPage", lastReadPage)
        // ⚠️ 页序迁移标记**必须持久化**：不写的话读回恒为默认 0，`MangaPageOrderMigrator`
        // 的 "已经迁过就跳过" 判据永远为假 → **每次打开阅读器都再跑一次页序置换**
        // （置换不是幂等的，译文会被一次次挪到别的图上）。
        // 只在非 0 时写，保持与旧清单的兼容（老条目读回 0 = 还没迁移，正是想要的语义）。
        if (pageOrderVersion != 0) put("pageOrderVersion", pageOrderVersion)
        // 已施加过的置换（幂等判据，见 ImportedManga.pageOrderPlan）；空串 = 没施加过
        if (pageOrderPlan.isNotEmpty()) put("pageOrderPlan", pageOrderPlan)
        // 章节表存成 JSON 串（空串 = 无章节信息，与旧版清单二进制兼容：读回空表）
        put("chapters", MangaChapter.listToJson(chapters))
    }

    private fun JSONObject.toManga(): ImportedManga = ImportedManga(
        id = getLong("id"),
        bookUuid = optString("bookUuid", ""),
        title = getString("title"),
        localRoot = getString("localRoot"),
        isArchive = getBoolean("isArchive"),
        isPdf = optBoolean("isPdf", false),
        coverPath = if (isNull("coverPath")) null else getString("coverPath"),
        pageCount = getInt("pageCount"),
        addedAt = getLong("addedAt"),
        sizeBytes = optLong("sizeBytes", 0),
        description = optString("description", ""),
        metadataVersion = optInt("metadataVersion", 0),
        sourceIndexPath = optString("sourceIndexPath"),
        titleOrigin = optString("titleOrigin", "user"),
        sourceName = optString("sourceName"),
        missingPages = optInt("missingPages"),
        changedPages = optInt("changedPages"),
        sourceNotice = optString("sourceNotice"),
        sourceNoticeId = optString("sourceNoticeId"),
        sourceNoticeSeen = optString("sourceNoticeSeen"),
        missingRecordKeys = optString("missingRecordKeys", "[]"),
        ignoredMissingRecordKeys = optString("ignoredMissingRecordKeys", "[]"),
        translatedPath = if (isNull("translatedPath")) null else getString("translatedPath"),
        lastReadPage = optInt("lastReadPage", 0),
        // 缺失 = 0 = 还没迁移（老条目语义），与 toJson 的「非 0 才写」配对
        pageOrderVersion = optInt("pageOrderVersion", 0),
        pageOrderPlan = optString("pageOrderPlan", ""),
        chapters = MangaChapter.listFromJson(optString("chapters", ""))
    )
}
