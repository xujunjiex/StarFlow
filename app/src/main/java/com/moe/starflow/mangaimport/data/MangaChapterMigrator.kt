package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 老条目（导入时还没分章概念）的章节表回填。
 *
 * 为什么要回填：章数要显示在书架卡片上，而**扫描一个 zip 的中央目录 / 列一遍目录**是磁盘 IO，
 * 不该在 binder/UI 线程做、也不该在书架每次刷新时做。导入时算好持久化，老条目在这里补一次；
 * 之后 `chapters` 非空即短路，是幂等的。
 *
 * ⚠️ 逐个 `update` 而不是「读全表 → 改 → 整表 save」：后者与**并发导入**的收尾写入会互相覆盖
 * （`ImportedMangaStore` 的注释里记着这个坑）。丢文件 / 0 页的条目直接跳过（下次打开再试，
 * 判断只是 `File.exists()`，很便宜）。
 */
object MangaChapterMigrator {

    private const val TAG = "MangaChapterMigrator"

    /** @return 是否改写过清单（调用方据此决定要不要刷新书架） */
    suspend fun ensureChapters(context: Context): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        val list = ImportedMangaStore.load(context)
        for (manga in list) {
            if (manga.chapters.isNotEmpty() || manga.pageCount <= 0 || manga.importing) continue
            val root = File(manga.localRoot)
            if (!root.exists()) continue
            val raw = try {
                if (manga.isArchive) ArchivedMangaReader.listImageFilesInArchive(root)
                else ArchivedMangaReader.listImageFilesInDir(root)
            } catch (e: Exception) {
                emptyList()
            }
            if (raw.isEmpty()) continue
            // ⚠️ 用 setChapters（锁内**只改 chapters 一个字段**），不要 update(manga.copy(...))：
            //    本循环是秒级的长任务（每条都要列目录/读 zip 中央目录），而 `list` 是循环开始时的
            //    快照 —— 整条替换会把这段时间里用户翻页写的 lastReadPage、改的书名回滚掉
            //    （表现是「翻了几页后进度自己退回去」）。
            ImportedMangaStore.setChapters(context, manga.id, MangaChapterSplitter.split(raw).chapters)
            changed = true
        }
        if (changed) {
            LogCollector.i(TAG, "已为老条目回填章节表")
        }
        changed
    }
}
