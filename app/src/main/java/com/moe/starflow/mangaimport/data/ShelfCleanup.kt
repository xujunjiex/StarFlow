package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 书架删除漫画时的「翻译记录」侧清理（进程级作用域）。
 *
 * 为什么不用 `viewLifecycleOwner.lifecycleScope`：确认删除后**立刻切 tab / 旋转 / 退出书架**，
 * 协程会随 View 一起被取消，那条 DELETE 就永远不执行 —— 清单是同步删的（书架上看不出来），
 * 但 DB 里会留下孤儿行，要等下次进书架 `purgeOrphanTranslations` 才扫掉。
 *
 * ⚠️ 删除**按 (mangaId, mangaKey) 成对进行**（[TranslationHistoryDao.deleteMangaScoped]）：
 * 漫画 id 会被复用（`nextId` = 清单最大 id + 1），而这里是异步的 —— 只按 id 删的话，
 * 用户完全可能在它落地前就导入了一本复用同 id 的新书并翻了几页，把那本**新书**的记录删掉。
 *
 * 注：孤儿行本身不会串到新漫画（读取按 `mangaId + mangaKey` 过滤，见 [ImportedManga.translationKey]），
 * 这里只是为了「该删的删干净」，不留每页一行（含原文/译文/气泡 JSON）的垃圾。
 */
object ShelfCleanup {

    private const val TAG = "ShelfCleanup"

    /** 同 [ImportManager]：未捕获异常会走线程默认处理器 = 崩溃，兜一层至少留日志。 */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            LogCollector.e(TAG, "清理协程未捕获异常（已兜住，未崩溃）", e)
        }
    )

    /**
     * 这些漫画里，哪些**确实有翻译记录**（按身份指纹过滤，孤儿行不算）。
     * 删除确认弹窗据此决定是否提示「译文会一起删除、无法恢复」。应在后台线程调用。
     */
    suspend fun mangasWithTranslations(
        context: Context,
        mangas: List<ImportedManga>
    ): List<ImportedManga> {
        if (mangas.isEmpty()) return emptyList()
        val dao = TranslationHistoryDatabase.getInstance(context).importedPageTranslationDao()
        return mangas.filter { m ->
            runCatching { dao.countFor(m.id, m.translationKey) > 0 }.getOrDefault(false)
        }
    }

    /**
     * 删除这些漫画的每页翻译记录（进程级作用域，不随界面销毁被取消）。
     *
     * 逐部一条 `DELETE`（都带指纹）：语句本身是原子的，中途失败也只是留下孤儿行，
     * 由下次进书架的 `purgeOrphanTranslations` 兜底 —— 所以不需要额外事务包裹。
     */
    fun purgeTranslations(context: Context, mangas: List<ImportedManga>) {
        if (mangas.isEmpty()) return
        val app = context.applicationContext
        scope.launch {
            try {
                val dao = TranslationHistoryDatabase.getInstance(app).importedPageTranslationDao()
                mangas.forEach { m -> dao.deleteMangaScoped(m.id, m.translationKey) }
                LogCollector.i(TAG, "已删除 ${mangas.size} 部漫画的翻译记录: ${mangas.map { it.id }}")
            } catch (e: Exception) {
                // 删不掉不影响清单/文件已删的结果：下次进书架 purgeOrphanTranslations 还会兜底
                LogCollector.e(TAG, "删除翻译记录失败: ${mangas.map { it.id }}", e)
            }
        }
    }
}
