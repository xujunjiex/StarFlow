package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.novel.model.NovelFormat
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Per-library Room records and portable commit journal; novel IDs have a separate namespace. */
object NovelStore {

    private const val PREFS_NAME = "novel_shelf"
    private const val KEY = "imported_novel_list"
    /** 清单整体解析不了时，把原始串另存这里（见 [save]）—— 给用户留一条找回的路。 */
    private const val KEY_BROKEN = "novels_broken_backup"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    internal fun loadLegacy(context: Context): List<ImportedNovel> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toNovel() }
        } catch (e: Exception) {
            throw java.io.IOException("旧小说清单损坏，已停止迁移并保留原资料", e)
        }
    }

    @Synchronized
    fun load(context: Context): List<ImportedNovel> {
        val session = com.moe.starflow.library.LibrarySessions.current(context)
        return session.books("novel").map { row ->
            val value = JSONObject(row.body).toNovel()
            value.copy(bookUuid = row.uuid, libraryLocationId = session.locationId,
                localRoot = session.local(value.localRoot).path,
                coverPath = value.coverPath?.let(session::cover),
                lost = session.sourcePresence[row.source] == false || row.deleting,
                deletionError = if (row.deleting) row.error ?: "删除未完成" else null)
        }
    }

    @Synchronized
    fun save(context: Context, list: List<ImportedNovel>) {
        val session = com.moe.starflow.library.LibrarySessions.current(context)
        list.forEach { persist(context, it) }
        session.books("novel").filter { row -> list.none { it.id == row.id } }
            .forEach { session.removeBook("novel", it.id) }
    }

    private fun persist(context: Context, value: ImportedNovel) {
        val session = com.moe.starflow.library.LibrarySessions.forLocation(context, value.libraryLocationId)
        val uuid = value.bookUuid.ifEmpty { session.uuid("novel", value.id) }
        val json = value.toJson().put("bookUuid", uuid)
            .put("localRoot", session.relative(File(value.localRoot)))
        val old = session.database.catalog().book("novel", value.id)?.let { JSONObject(it.body) }
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
        session.putBook("novel", value.id, json, stagingCover = cover?.takeIf { !it.startsWith("content://") }
            ?.let { runCatching { session.relative(File(it)) }.getOrNull() })
        com.moe.starflow.library.LibrarySessions.changes.value += 1
    }

    @Synchronized
    fun patch(context: Context, value: ImportedNovel, change: (ImportedNovel) -> ImportedNovel) {
        val session = com.moe.starflow.library.LibrarySessions.forLocation(context, value.libraryLocationId)
        val pinned = session.context()
        val latest = load(pinned).firstOrNull { it.id == value.id && (value.bookUuid.isEmpty() || it.bookUuid == value.bookUuid) } ?: return
        persist(pinned, change(latest))
    }

    @Synchronized
    fun add(context: Context, value: ImportedNovel) { persist(context, value) }

    /** Merge only new scan results under the same monitor as manual imports and shelf edits. */
    @Synchronized
    internal fun mergeScanned(context: Context, scanned: List<ImportedNovel>): Int {
        val latest = load(context).toMutableList()
        val ids = latest.mapTo(mutableSetOf()) { it.id }
        val paths = latest.mapTo(mutableSetOf()) { canonical(it.localRoot) }
        var added = 0
        scanned.forEach { novel ->
            val path = canonical(novel.localRoot)
            if (novel.id !in ids && path !in paths) {
                persist(context, novel)
                latest += novel
                ids += novel.id
                paths += path
                added++
            }
        }
        return added
    }

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    @Synchronized
    fun remove(context: Context, id: Long) {
        com.moe.starflow.library.LibrarySessions.current(context).removeBook("novel", id)
    }

    @Synchronized
    fun update(context: Context, value: ImportedNovel) {
        val session = com.moe.starflow.library.LibrarySessions.forLocation(context, value.libraryLocationId)
        if (session.database.catalog().book("novel", value.id) != null) persist(context, value)
    }

    private fun ImportedNovel.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("bookUuid", bookUuid)
        put("title", title)
        put("author", author ?: JSONObject.NULL)
        put("localRoot", localRoot)
        put("format", format.name)
        put("coverPath", coverPath ?: JSONObject.NULL)
        put("chapterCount", chapterCount)
        put("addedAt", addedAt)
        put("sizeBytes", sizeBytes)
        put("description", description)
        put("lastReadChapter", lastReadChapter)
        put("lastReadParaIndex", lastReadParaIndex)
        put("lastReadPage", lastReadPage)
        put("lastReadAt", lastReadAt)
    }

    /**
     * 读一个「可空字符串」字段。
     *
     * ⚠️ **不能写成 `optString(k, "").ifBlank { null }`**（真实踩过）：写侧用的是
     * `put(k, v ?: JSONObject.NULL)`，而 Android 的 `optString` 走 `JSON.toString(obj)` →
     * `String.valueOf(JSONObject.NULL)` → 拿到的是**字面量 "null"**（四字符，`ifBlank` 判不出）。
     * 于是只要发生过一次 save→load 往返，`author` 就显示成「null」、`coverPath` 变成
     * `File("null")` 找不到 → **已导入的书封面永久消失**（退出阅读器就会 update→save，
     * 所以这是必现的）。这里显式判 JSON null。
     */
    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, "").ifBlank { null } else null

    private fun JSONObject.toNovel(): ImportedNovel = ImportedNovel(
        id = getLong("id"),
        bookUuid = optString("bookUuid", ""),
        title = optString("title", ""),
        author = optNullableString("author"),
        localRoot = getString("localRoot"),
        format = runCatching { NovelFormat.valueOf(optString("format", NovelFormat.TXT.name)) }
            .getOrDefault(NovelFormat.TXT),
        coverPath = optNullableString("coverPath"),
        chapterCount = optInt("chapterCount", 0),
        addedAt = getLong("addedAt"),
        sizeBytes = optLong("sizeBytes", 0),
        description = optString("description", ""),
        lastReadChapter = optInt("lastReadChapter", 0),
        lastReadParaIndex = optInt("lastReadParaIndex", 0),
        lastReadPage = optInt("lastReadPage", 0),
        // Legacy shelf entries have no recent-read timestamp; keep them at the end of that sort.
        lastReadAt = optLong("lastReadAt", 0),
    )
}
