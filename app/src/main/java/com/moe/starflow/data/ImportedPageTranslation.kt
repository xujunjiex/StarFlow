package com.moe.starflow.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
) {
    companion object {
        const val STATE_IDLE = 0          // 未翻译
        const val STATE_TRANSLATING = 1   // 翻译中
        const val STATE_SUCCESS = 2       // 成功
        const val STATE_FAILED = 3        // 失败
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

    @Query("DELETE FROM imported_page_translation WHERE mangaId = :mangaId")
    suspend fun deleteManga(mangaId: Long)
}