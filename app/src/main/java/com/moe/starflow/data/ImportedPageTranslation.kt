package com.moe.starflow.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 阅读器每页翻译记录（key = mangaId + pageIndex）。
 * state 用 [STATE_IDLE] 等常量；文本用 "[N] 原文\n[2] …" 编号串（TranslationCacheUtils 可解析）。
 */
@Entity(tableName = "imported_page_translation", primaryKeys = ["mangaId", "pageIndex"])
data class ImportedPageTranslation(
    val mangaId: Long,
    val pageIndex: Int,
    val state: Int,
    val sourceText: String? = null,      // "[N] 原文\n[2] …"
    val translatedText: String? = null,  // "[N] 译文\n[2] …"
    val bubbleRects: String? = null,     // TranslationCacheUtils.serializeBubbleRects 的 JSON
    val failCode: String? = null,        // OCR_EMPTY / TRANSLATE_EMPTY / PROCESS_EXCEPTION / …
    val failMessage: String? = null,     // 给人看的文案
    val updatedAtMs: Long = 0L,
    /**
     * 漫画身份指纹（`title|addedAt`）。
     * ⚠️ 必要性：漫画 id = 书架当前最大 id + 1，**删除后再导入会复用旧 id**，
     * 而删除时若残留旧记录（孤儿行），`forManga(旧id)` 会把**已删除漫画的译图层错误映射到新漫画**。
     * 指纹随每次导入唯一（addedAt），不匹配的记录一律不采用 → 从根上杜绝 id 复用串数据。
     */
    val mangaKey: String? = null,
    /** 翻译器显示名（如 OpenAITranslation(gpt-4o) | PP-OCRv5+PPOcrV5）。详情面板展示用。 */
    val translatorName: String? = null,
    /** 源语言（如 ja）。 */
    val sourceLang: String? = null,
    /** 目标语言（如 zh）。 */
    val targetLang: String? = null,
) {
    companion object {
        const val STATE_IDLE = 0          // 未翻译
        const val STATE_TRANSLATING = 1   // 翻译中
        const val STATE_SUCCESS = 2       // 成功
        const val STATE_FAILED = 3        // 失败（保留已有 OCR/译文载荷）
        const val STATE_OCR = 4           // OCR 完成，尚未翻译
    }
}

@Dao
interface ImportedPageTranslationDao {
    /** 某部漫画全部记录（按身份指纹过滤，见 [ImportedPageTranslation.mangaKey]），页码升序。 */
    @Query("SELECT * FROM imported_page_translation WHERE mangaId = :mangaId AND mangaKey = :mangaKey ORDER BY pageIndex")
    suspend fun forManga(mangaId: Long, mangaKey: String): List<ImportedPageTranslation>

    @Query("SELECT * FROM imported_page_translation WHERE mangaId = :mangaId AND pageIndex = :pageIndex")
    suspend fun get(mangaId: Long, pageIndex: Int): ImportedPageTranslation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ImportedPageTranslation)

    /** 全部出现过记录的 mangaId（去重），供书架清理孤儿行用。 */
    @Query("SELECT DISTINCT mangaId FROM imported_page_translation")
    suspend fun allMangaIds(): List<Long>

    /**
     * **页序迁移**：把这本书的行按 [mapping]（旧下标 → 新下标）重排（见 `MangaPageOrder`）。
     *
     * ⚠️ 必须整体在一个事务里（先读、再删、再插）：中途失败会留下"一半新序一半旧序"的行，比不迁移更糟。
     * ⚠️ 也不能逐行 `UPDATE pageIndex`：`pageIndex` 是主键的一部分，置换过程中会与尚未搬迁的行撞键
     * （`OnConflictStrategy.REPLACE` 会**静默吃掉**一行）。
     */
    @Transaction
    suspend fun rekeyPages(mangaId: Long, mangaKey: String, mapping: IntArray) {
        val rows = forManga(mangaId, mangaKey)
        if (rows.isEmpty()) return
        deleteMangaScoped(mangaId, mangaKey)
        rows.forEach { row ->
            val target = mapping.getOrNull(row.pageIndex)
            upsert(if (target != null && target >= 0) row.copy(pageIndex = target) else row)
        }
    }

    /** 某漫画下出现过的全部身份指纹（供旧指纹一次性迁移）。 */
    @Query("SELECT DISTINCT mangaKey FROM imported_page_translation WHERE mangaId = :mangaId")
    suspend fun mangaKeysFor(mangaId: Long): List<String>

    /**
     * 该部漫画是否有翻译记录（按身份指纹过滤，孤儿行不算）。
     * 删除书架前提示「译文会一起删除」用（用户可能没导出过译文）。
     */
    @Query("SELECT COUNT(*) FROM imported_page_translation WHERE mangaId = :mangaId AND mangaKey = :mangaKey")
    suspend fun countFor(mangaId: Long, mangaKey: String): Int

    /** 把某漫画下一页记录的指纹从 [oldKey] 改写成 [newKey]（只改指纹，不动译文载荷）。 */
    @Query("UPDATE imported_page_translation SET mangaKey = :newKey WHERE mangaId = :mangaId AND mangaKey = :oldKey")
    suspend fun rewriteMangaKey(mangaId: Long, oldKey: String, newKey: String)

    /**
     * 删除**某一页的翻译数据**（面板「详情 → 删除」用）。
     *
     * 删的是整行（原文 + 译文 + 气泡坐标 + 状态），不是「只删记录」——删完该页回到未翻译态，
     * 显示回落原图。指纹过滤与读取侧（[forManga]）保持同一口径：id 会被复用，
     * 只按 id+页号删可能删到复用同一 id 的新书。
     */
    @Query(
        "DELETE FROM imported_page_translation " +
            "WHERE mangaId = :mangaId AND (mangaKey = :mangaKey OR mangaKey IS NULL) AND pageIndex = :pageIndex"
    )
    suspend fun deletePage(mangaId: Long, mangaKey: String, pageIndex: Int)

    /**
     * 删除某段页号的翻译数据（「清除本章译文」用；`from`/`to` 都含，全书页号）。
     * 指纹过滤同上 —— 不匹配的行一律不动。
     */
    @Query(
        "DELETE FROM imported_page_translation " +
            "WHERE mangaId = :mangaId AND (mangaKey = :mangaKey OR mangaKey IS NULL) " +
            "AND pageIndex BETWEEN :from AND :to"
    )
    suspend fun deletePageRange(mangaId: Long, mangaKey: String, from: Int, to: Int)

    /** 删除单部漫画的全部记录（孤儿清理用：该 id 已不在书架里）。 */
    @Query("DELETE FROM imported_page_translation WHERE mangaId = :mangaId")
    suspend fun deleteManga(mangaId: Long)

    /**
     * 删除「某 id + 某身份指纹」的记录（顺带清掉同 id 下 key 为 NULL 的升级前残留）。
     *
     * ⚠️ **必须带 key**：漫画 id 会被复用（`nextId` = 清单最大 id + 1），而删除翻译记录是**异步**的
     * （进程级作用域）。只按 id 删的话，用户完全可能在它落地前就导入了一本复用同 id 的新书并翻了几页
     * → 把这本**新书**的翻译行删掉。指纹过滤与读取侧（[forManga] / [countFor]）保持同一口径。
     */
    @Query(
        "DELETE FROM imported_page_translation " +
            "WHERE mangaId = :mangaId AND (mangaKey = :mangaKey OR mangaKey IS NULL)"
    )
    suspend fun deleteMangaScoped(mangaId: Long, mangaKey: String)

    /**
     * 把残留的「翻译中」重置为「未翻译」。
     *
     * ⚠️ 必要性：`STATE_TRANSLATING` 是在翻译**开始时**写库的，只有跑完才改成 SUCCESS。
     * 若期间退出阅读器 / 进程被杀 / 崩溃，协程被直接掐死，记录会**永久停在「翻译中」**——
     * 该页从此既不显示译文（取图要求 state==SUCCESS），翻译按钮也只会弹「正在翻译中」而再也翻不了。
     *
     * 放在**进入阅读器时**清理（而非退出时），因为进程被杀/崩溃时「退出时」的清理根本不会执行，
     * 「进入时」能覆盖所有异常退出路径。
     *
     * state 字面量 0/1 与 [ImportedPageTranslation.STATE_IDLE] / [STATE_TRANSLATING] 对应
     * —— @Query 里不能引用 Kotlin 常量，改这两个值时必须同步改这里。
     */
    @Query("UPDATE imported_page_translation SET state = CASE WHEN sourceText IS NOT NULL AND trim(sourceText) <> '' THEN 4 ELSE 0 END WHERE mangaId = :mangaId AND mangaKey = :mangaKey AND state = 1")
    suspend fun resetTranslating(mangaId: Long, mangaKey: String)
}