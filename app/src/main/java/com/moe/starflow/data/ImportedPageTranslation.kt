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

    /** 某漫画下出现过的全部身份指纹（供旧指纹一次性迁移）。 */
    @Query("SELECT DISTINCT mangaKey FROM imported_page_translation WHERE mangaId = :mangaId")
    suspend fun mangaKeysFor(mangaId: Long): List<String>

    /** 把某漫画下一页记录的指纹从 [oldKey] 改写成 [newKey]（只改指纹，不动译文载荷）。 */
    @Query("UPDATE imported_page_translation SET mangaKey = :newKey WHERE mangaId = :mangaId AND mangaKey = :oldKey")
    suspend fun rewriteMangaKey(mangaId: Long, oldKey: String, newKey: String)

    @Query("DELETE FROM imported_page_translation WHERE mangaId = :mangaId")
    suspend fun deleteManga(mangaId: Long)

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
    @Query("UPDATE imported_page_translation SET state = 0 WHERE mangaId = :mangaId AND mangaKey = :mangaKey AND state = 1")
    suspend fun resetTranslating(mangaId: Long, mangaKey: String)
}