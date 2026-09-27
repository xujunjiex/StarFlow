package com.moe.starflow.novel.data

import android.content.Context
import com.moe.starflow.novel.model.NovelFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小说书架清单持久化（SharedPreferences + JSON）。清单是小数据（几十部书），不用 Room。
 *
 * ⚠️ **全部公开方法 @Synchronized**：写操作是「load → 改 → save」三步，不是原子的。
 * 文件夹导入会并发产生多个任务，两个任务同毫秒收尾就会互相覆盖（各自读到不含对方的旧列表
 * → 后写的把先写的整条吞掉 = 书架少一部、占位卡片凭空消失）。
 *
 * 与漫画的 `ImportedMangaStore` 用**不同的 prefs 文件**（`novel_shelf`），互不干扰 ——
 * 两套 id 空间独立，共用一个清单会让「小说 id=1」和「漫画 id=1」互相顶掉。
 */
object NovelStore {

    private const val PREFS_NAME = "novel_shelf"
    private const val KEY = "imported_novel_list"
    /** 清单整体解析不了时，把原始串另存这里（见 [save]）—— 给用户留一条找回的路。 */
    private const val KEY_BROKEN = "novels_broken_backup"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): List<ImportedNovel> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                // ⚠️ **逐条容错**：`toNovel()` 里 `id/addedAt/localRoot` 是严格取值，
                // 一条坏数据会抛异常 —— 整份 catch 成 emptyList() 的话，下一次 `save()`
                // （退出阅读器就会调）就把这份空清单覆盖落盘，书的文件还在但清单永久没了
                .mapNotNull { i -> runCatching { arr.getJSONObject(i).toNovel() }.getOrNull() }
                // 没有本地路径的条目不可用（占位条目从不入库，出现即脏数据）
                .filterNot { it.localRoot.isBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, list: List<ImportedNovel>) {
        // ⚠️ 整份清单解析不了时（不是单条坏、是整体坏了），先把原始串另存一份再覆盖：
        // 否则这次 save 会把用户仅剩的清单原地抹掉，再也没有找回的余地
        val raw = prefs(context).getString(KEY, null)
        if (!raw.isNullOrEmpty() && runCatching { JSONArray(raw) }.isFailure) {
            prefs(context).edit().putString(KEY_BROKEN, raw).apply()
        }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun add(context: Context, novel: ImportedNovel) {
        save(context, load(context) + novel)
    }

    @Synchronized
    fun remove(context: Context, id: Long) {
        save(context, load(context).filterNot { it.id == id })
    }

    @Synchronized
    fun update(context: Context, novel: ImportedNovel) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == novel.id }
        if (idx >= 0) {
            list[idx] = novel
            save(context, list)
        }
    }

    private fun ImportedNovel.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
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
        // ⚠️ lost / importing / importPhase / importPercent 刻意不写 —— 瞬态字段，
        // 持久化会让「文件丢失」这种实时推导的结论变成过期缓存
    }

    private fun JSONObject.toNovel(): ImportedNovel = ImportedNovel(
        id = getLong("id"),
        title = optString("title", ""),
        author = optString("author", "").ifBlank { null },
        localRoot = getString("localRoot"),
        format = runCatching { NovelFormat.valueOf(optString("format", NovelFormat.TXT.name)) }
            .getOrDefault(NovelFormat.TXT),
        coverPath = optString("coverPath", "").ifBlank { null },
        chapterCount = optInt("chapterCount", 0),
        addedAt = getLong("addedAt"),
        sizeBytes = optLong("sizeBytes", 0),
        description = optString("description", ""),
        lastReadChapter = optInt("lastReadChapter", 0),
        lastReadParaIndex = optInt("lastReadParaIndex", 0),
        lastReadPage = optInt("lastReadPage", 0),
    )
}
