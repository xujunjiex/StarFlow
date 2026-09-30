package com.moe.starflow.mangaimport.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 导入漫画清单持久化（SharedPreferences + JSON）。
 * 清单是小数据（几十部漫画），不用 Room。
 *
 * ⚠️ **全部方法 @Synchronized**：写操作是「load → 改 → save」三步，不是原子的。
 * 导入改成「一个文件/文件夹一个并发任务」之后，两个任务同毫秒收尾就会互相覆盖
 * （各自读到不含对方的旧列表 → 后写的把先写的整条吞掉 = 书架少一部、占位卡片凭空消失）。
 * 同进程内共用一个监视器即可解决；跨进程不存在（只有一个应用进程写这份 prefs）。
 */
object ImportedMangaStore {

    private const val PREFS_NAME = "manga_import"
    private const val KEY = "imported_manga_list"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
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

    @Synchronized
    fun save(context: Context, list: List<ImportedManga>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun add(context: Context, manga: ImportedManga) {
        val list = load(context).toMutableList()
        list.add(manga)
        save(context, list)
    }

    @Synchronized
    fun remove(context: Context, id: Long) {
        save(context, load(context).filterNot { it.id == id })
    }

    @Synchronized
    fun update(context: Context, manga: ImportedManga) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == manga.id }
        if (idx >= 0) {
            list[idx] = manga
            save(context, list)
        }
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
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(chapters = chapters)
        save(context, list)
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
        title = getString("title"),
        localRoot = getString("localRoot"),
        isArchive = getBoolean("isArchive"),
        coverPath = if (isNull("coverPath")) null else getString("coverPath"),
        pageCount = getInt("pageCount"),
        addedAt = getLong("addedAt"),
        sizeBytes = optLong("sizeBytes", 0),
        description = optString("description", ""),
        translatedPath = if (isNull("translatedPath")) null else getString("translatedPath"),
        lastReadPage = optInt("lastReadPage", 0),
        // 缺失 = 0 = 还没迁移（老条目语义），与 toJson 的「非 0 才写」配对
        pageOrderVersion = optInt("pageOrderVersion", 0),
        pageOrderPlan = optString("pageOrderPlan", ""),
        chapters = MangaChapter.listFromJson(optString("chapters", ""))
    )
}
