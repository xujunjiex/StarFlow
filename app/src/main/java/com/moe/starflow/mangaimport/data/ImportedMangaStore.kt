package com.moe.starflow.mangaimport.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 导入漫画清单持久化（SharedPreferences + JSON）。
 * 清单是小数据（几十部漫画），不用 Room。
 */
object ImportedMangaStore {

    private const val PREFS_NAME = "manga_import"
    private const val KEY = "imported_manga_list"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<ImportedManga> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toManga() }
                // 迁移：旧版自定义 SAF 存储目录的条目（content:// localRoot）已不可读，丢弃
                .filterNot { it.localRoot.startsWith("content://") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, list: List<ImportedManga>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, manga: ImportedManga) {
        val list = load(context).toMutableList()
        list.add(manga)
        save(context, list)
    }

    fun remove(context: Context, id: Long) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun update(context: Context, manga: ImportedManga) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == manga.id }
        if (idx >= 0) {
            list[idx] = manga
            save(context, list)
        }
    }

    private fun ImportedManga.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("localRoot", localRoot)
        put("isArchive", isArchive)
        put("coverPath", coverPath ?: JSONObject.NULL)
        put("pageCount", pageCount)
        put("addedAt", addedAt)
        put("sizeBytes", sizeBytes)
        put("description", description)
        put("translatedPath", translatedPath ?: JSONObject.NULL)
        put("lastReadPage", lastReadPage)
    }

    private fun JSONObject.toManga(): ImportedManga = ImportedManga(
        id = getLong("id"),
        title = getString("title"),
        localRoot = getString("localRoot"),
        isArchive = getBoolean("isArchive"),
        coverPath = if (isNull("coverPath")) null else getString("coverPath"),
        pageCount = getInt("pageCount"),
        addedAt = getLong("addedAt"),
        sizeBytes = optLong("sizeBytes", 0),
        description = optString("description", ""),
        translatedPath = if (isNull("translatedPath")) null else getString("translatedPath"),
        lastReadPage = optInt("lastReadPage", 0)
    )
}
