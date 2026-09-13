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
    /** 某部漫画全部记录，按页码升序。 */
    @Query("SELECT * FROM imported_page_translation WHERE mangaId = :mangaId ORDER BY pageIndex")
    suspend fun forManga(mangaId: Long): List<ImportedPageTranslation>

    @Query("SELECT * FROM imported_page_translation WHERE mangaId = :mangaId AND pageIndex = :pageIndex")
    suspend fun get(mangaId: Long, pageIndex: Int): ImportedPageTranslation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ImportedPageTranslation)

    @Query("DELETE FROM imported_page_translation WHERE mangaId = :mangaId")
    suspend fun deleteManga(mangaId: Long)
}