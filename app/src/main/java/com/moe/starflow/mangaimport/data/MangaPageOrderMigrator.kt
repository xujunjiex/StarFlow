package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.mangaimport.reader.ReaderPageSource
import com.moe.starflow.utils.LogCollector

/**
 * **一次性页序迁移**（见 [MangaPageOrder]）。
 *
 * 章节系统把「最外层散图」排到第 0 章、放在最前，而旧版是 `sortNaturally(完整路径)` ——
 * 对「根散图 + 子目录混放」的书，同一 `pageIndex` 指向的图变了，而译文/气泡是按下标存的：
 * 升级后打开这种书就会看到**译文挂错页、气泡坐标错位**，删除/清除也会作用到别的页。
 *
 * 这里把行按置换**重排**（不删、不重翻），并把 `lastReadPage` 一起搬到新序；
 * 完成后写回 [ImportedManga.pageOrderVersion] 标记，之后不再重复。
 *
 * ⚠️ 必须在**控制器 `load()` 之前**跑：`load()` 一读就是按 `pageIndex` 取行。
 * ⚠️ 迁移自身失败**绝不能让阅读器打不开**：整体 `runCatching`，失败只记 W 日志。
 */
object MangaPageOrderMigrator {

    private const val TAG = "MangaPageOrder"

    /**
     * @return 是否真的重排过（host 只在需要时打日志）
     */
    suspend fun migrateIfNeeded(context: Context, manga: ImportedManga, source: ReaderPageSource): Boolean {
        if (manga.pageOrderVersion >= MangaPageOrder.CURRENT_VERSION) return false
        val plan = MangaPageOrder.legacyToNewPlan(source.rawPageKeys(), source.orderedPageKeys())
        var rekeyed = false
        if (plan != null) {
            val db = TranslationHistoryDatabase.getInstance(context)
            val dao = db.importedPageTranslationDao()
            val rows = runCatching { dao.countFor(manga.id, manga.translationKey) }.getOrDefault(0)
            if (rows > 0) {
                dao.rekeyPages(manga.id, manga.translationKey, plan)
                rekeyed = true
                LogCollector.w(
                    TAG,
                    "页序迁移：mangaId=${manga.id} 把 $rows 行译文从旧页序重排到新页序" +
                        "（章节系统之前的「根散图 + 子目录混放」书：不改就是把译文挂到别的图上）",
                )
            }
        }
        // 断点续读也在旧序空间里 → 一起搬（超出范围就夹回 0）
        val mappedPage = MangaPageOrder.mapPage(plan, manga.lastReadPage)
        val updated = manga.copy(
            pageOrderVersion = MangaPageOrder.CURRENT_VERSION,
            lastReadPage = mappedPage.coerceIn(0, (source.size - 1).coerceAtLeast(0)),
        )
        runCatching { ImportedMangaStore.update(context, updated) }
            .onFailure { LogCollector.w(TAG, "页序迁移：回写清单失败（下次打开会再试一次）", it) }
        LogCollector.i(
            TAG,
            "页序检查完成 mangaId=${manga.id} 重排=${rekeyed} 续读页=${manga.lastReadPage}→$mappedPage",
        )
        return rekeyed
    }
}
