package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一部导入漫画里的一章。
 *
 * 心智与小说模块一致：**整个夹 = 一部漫画，子文件夹 = 一章**（见 [MangaChapterSplitter]）。
 * 页号是**全书连续页号**（`ReaderPageSource` 的位置下标），章只记自己那一段区间 ——
 * 翻译记录表的主键是 `(mangaId, pageIndex)`，用全书页号就**不需要任何库迁移**。
 *
 * @param number 展示用章号。**根散图章 = 0**（用户指定：最外层目录里的散图算「第0章」），
 *               其余章从 1 起编号；单章漫画（没有子文件夹）也是 1。
 * @param title 章标题 = 子文件夹名；根散图章 / 单章漫画为空串（此时显示「第N章」）。
 * @param startPage 本章第一页在全书里的页号（0 起）
 * @param pageCount 本章页数
 */
data class MangaChapter(
    val number: Int,
    val title: String,
    val startPage: Int,
    val pageCount: Int
) {
    /** 本章最后一页的全书页号（含）。 */
    val endPage: Int get() = startPage + pageCount - 1

    fun containsPage(page: Int): Boolean = pageCount > 0 && page in startPage..endPage

    fun toJson(): JSONObject = JSONObject().apply {
        put("number", number)
        put("title", title)
        put("startPage", startPage)
        put("pageCount", pageCount)
    }

    companion object {
        /** 清单持久化（[ImportedMangaStore]）。空表存空串，加载回空表。 */
        fun listToJson(chapters: List<MangaChapter>): String {
            if (chapters.isEmpty()) return ""
            val arr = JSONArray()
            chapters.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String?): List<MangaChapter> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    MangaChapter(
                        number = o.optInt("number", i + 1),
                        title = o.optString("title", ""),
                        startPage = o.optInt("startPage", 0),
                        pageCount = o.optInt("pageCount", 0)
                    )
                }.filter { it.pageCount > 0 }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * 章标题的显示规则 —— 与小说模块 `chapterDisplayTitle` **同一个约定**：
 * **有标题就只显示标题**（文件夹名本身就是「第 12 话」这种），没标题才用「第N章」兜底。
 *
 * ⚠️ 不要一律拼成「第1章 ch1」：用户的文件夹名往往已经带编号，
 * 拼起来就是「第1章 第1话」这种双编号，看着莫名其妙（小说那边踩过）。
 */
fun mangaChapterLabel(context: Context, chapter: MangaChapter): String =
    chapter.title.trim().ifEmpty { context.getString(R.string.manga_chapter_label, chapter.number) }

/**
 * 页号 → 章下标（找不到返回 0）。纯函数，阅读器与面板共用同一份判据。
 */
fun chapterIndexOf(chapters: List<MangaChapter>, page: Int): Int {
    if (chapters.isEmpty()) return 0
    val hit = chapters.indexOfFirst { it.containsPage(page) }
    return if (hit >= 0) hit else 0
}

/**
 * 把页面存储 key（zip 内条目名 / 目录内相对路径）切成章节。
 *
 * 规则（用户 2026-10 定的口径）：
 * 1. **先剥掉「所有页共有的最外层目录」**（zip 常常多包一层书名目录）。剥到没有公共外层为止；
 * 2. 剥完后按**第一层子目录**分章（`ch1/part1/001.jpg` 与 `ch1/part2/001.jpg` 各成一章）；
 * 3. **最外层散图（剥完后没有目录的那些页）单独成章，排在最前，显示为「第0章」**；
 * 4. 章顺序 / 章内页序都走**自然排序**（`page2 < page10`，与 [ArchivedMangaReader] 同一套比较器）。
 *
 * ⚠️ **切分同时负责「重排页序」**：原实现按**完整路径**自然排序，散图与子目录会交错
 * （`a/1.jpg` 排在 `z.jpg` 前面），章区间就不再连续。所以 [split] 返回的
 * [Result.keys] 才是**权威阅读顺序**，`ReaderPageSource` 必须用它（导入侧与阅读侧同源）。
 */
object MangaChapterSplitter {

    /**
     * @param keys 权威页序（按章分组、章内自然排序）
     * @param chapters 章表（`startPage`/`pageCount` 都是 [keys] 里的下标区间）
     */
    data class Result(val keys: List<String>, val chapters: List<MangaChapter>)

    fun split(pageKeys: List<String>): Result {
        val normalized = pageKeys
            .map { it.replace('\\', '/').trim('/') }
            .filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return Result(emptyList(), emptyList())

        // ① 剥公共最外层目录（记着原始 key，落盘/导出还得用原名）
        val stripped = stripCommonRoot(normalized)

        // ② 按剥完后路径的第一层目录分组（"" = 最外层散图）
        val groups = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for ((original, relative) in stripped) {
            val dir = relative.substringBeforeLast('/', "")
            val top = if (dir.isEmpty()) "" else dir.substringBefore('/')
            groups.getOrPut(top) { mutableListOf() }.add(original to relative)
        }

        val rootPages = groups.remove("")
        val ordered = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        // 根散图章在最前（第0章）
        if (!rootPages.isNullOrEmpty()) ordered += "" to rootPages
        groups.entries
            .sortedWith(compareBy(ArchivedMangaReader.naturalComparator()) { it.key })
            .forEach { ordered += it.key to it.value }
        // ⚠️ 「第0章」只在**根散图与子文件夹章并存**时成立：整本没有子文件夹（或剥完外层后
        // 只剩一层）时它就是**唯一**的章，编号必须是 1 —— 否则单章漫画会显示成「第0章 / 共1章」
        val hasRootChapter = ordered.firstOrNull()?.first == "" && ordered.size > 1

        val keys = ArrayList<String>(normalized.size)
        val chapters = ArrayList<MangaChapter>(ordered.size)
        ordered.forEachIndexed { idx, (title, entries) ->
            val sorted = entries.sortedWith(
                compareBy(ArchivedMangaReader.naturalComparator()) { it.second }
            )
            val start = keys.size
            sorted.forEach { keys += it.first }
            chapters += MangaChapter(
                number = if (hasRootChapter) idx else idx + 1,
                title = title,
                startPage = start,
                pageCount = sorted.size
            )
        }
        return Result(keys, chapters)
    }

    /**
     * 反复剥掉「所有页共有的最外层目录」。
     *
     * - `Book/ch1/a.jpg` + `Book/ch2/b.jpg` → 剥掉 `Book` → 两章
     * - `ch1/a.jpg` + `ch1/b.jpg` → 剥掉 `ch1` → 单章（名为空 → 显示「第1章」）
     * - 只要有一页直接在根上就不再剥（那种情况根散图本身就是一章）
     */
    private fun stripCommonRoot(paths: List<String>): List<Pair<String, String>> {
        var entries = paths.map { it to it }
        while (true) {
            val relatives = entries.map { it.second }
            val dirs = relatives.map { it.substringBeforeLast('/', "") }
            // 有页直接在根上 → 不存在公共外层
            if (dirs.any { it.isEmpty() }) break
            val tops = dirs.map { it.substringBefore('/') }.toSet()
            if (tops.size != 1) break
            val root = tops.first()
            entries = entries.map { it.first to it.second.removePrefix("$root/") }
        }
        return entries
    }
}
