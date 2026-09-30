package com.moe.starflow.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 阅读器每页的**超分记录**（key = mangaId + pageIndex）—— 与 [ImportedPageTranslation] 同一套形态。
 *
 * ## 为什么单独一张表，而不是塞进 `imported_page_translation`
 * 两者是**两条独立的流水线**：一页可以"翻了但没超分""超了但没翻""都做了""都失败了"，
 * 状态机与失败原因完全不同（翻译有 OCR 空/译文空，超分有模型缺失/图太大/推理失败）。
 * 合成一行会立刻出现"谁覆盖谁"的语义问题。
 *
 * ## 真值口径
 * **超分结果的真值仍然是磁盘上的文件**（`SrStore.exists`，见那边的注释）——
 * 这张表**只记过程与元数据**：状态、用的哪个模型、原始/产物的尺寸与字节、时间、失败原因。
 * 所以「文件被系统清掉」不会让这张表变成谎言：读回时会按 `SrStore` 校正成未超分。
 * 反过来说：**不要**拿这张表的 state 去决定"要不要渲染超分底图"，那不是它的职责。
 *
 * ## ⚠️ 身份指纹
 * `mangaKey` 的语义与理由与 [ImportedPageTranslation.mangaKey] **完全一致**（漫画 id 会被复用），
 * 所有读/删都必须带它。
 */
@Entity(tableName = "imported_page_sr", primaryKeys = ["mangaId", "pageIndex"])
data class ImportedPageSr(
    val mangaId: Long,
    val pageIndex: Int,
    val state: Int,
    /** 超分用的模型**显示名**（详情里给人看；不是 [com.moe.starflow.download.ModelKey.name]）。 */
    val modelName: String? = null,
    /** 原始页尺寸 / 字节数 —— 详情里「原始 vs 超分后」的左半边。 */
    val srcWidth: Int = 0,
    val srcHeight: Int = 0,
    val srcBytes: Long = 0L,
    /** 超分产物的尺寸 / 字节数 —— 右半边。 */
    val outWidth: Int = 0,
    val outHeight: Int = 0,
    val outBytes: Long = 0L,
    val failCode: String? = null,
    /** 给人看的失败原因原文（超分侧一贯要求"写清具体原因"，不许糊）。 */
    val failMessage: String? = null,
    /** 这一次超分**开始**的时刻（详情里的「时间」）。 */
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val mangaKey: String? = null,
) {
    companion object {
        const val STATE_IDLE = 0     // 没超过
        const val STATE_RUNNING = 1  // 超分中
        const val STATE_SUCCESS = 2  // 完成
        const val STATE_FAILED = 3   // 失败
    }
}

@Dao
interface ImportedPageSrDao {

    /** 某部漫画全部超分记录（按身份指纹过滤），页码升序。 */
    @Query("SELECT * FROM imported_page_sr WHERE mangaId = :mangaId AND mangaKey = :mangaKey ORDER BY pageIndex")
    suspend fun forManga(mangaId: Long, mangaKey: String): List<ImportedPageSr>

    @Query("SELECT * FROM imported_page_sr WHERE mangaId = :mangaId AND pageIndex = :pageIndex")
    suspend fun get(mangaId: Long, pageIndex: Int): ImportedPageSr?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ImportedPageSr)

    /** 全部出现过超分记录的 mangaId（去重），供书架清理孤儿行用。 */
    @Query("SELECT DISTINCT mangaId FROM imported_page_sr")
    suspend fun allMangaIds(): List<Long>

    /**
     * **页序迁移**：把这本书的超分记录按 [mapping]（旧下标 → 新下标）重排。
     *
     * ⚠️ 与 [ImportedPageTranslationDao.rekeyPages] 同一条硬约束：必须整体一个事务
     * （先读、再删、再插）。逐行 `UPDATE pageIndex` 会在置换过程中与尚未搬迁的行**撞主键**，
     * `REPLACE` 会静默吃掉一行。
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

    @Query("SELECT DISTINCT mangaKey FROM imported_page_sr WHERE mangaId = :mangaId")
    suspend fun mangaKeysFor(mangaId: Long): List<String>

    /** 删除某一页的超分记录（「删除本页超分结果」用；指纹口径与读取侧一致）。 */
    @Query(
        "DELETE FROM imported_page_sr " +
            "WHERE mangaId = :mangaId AND (mangaKey = :mangaKey OR mangaKey IS NULL) AND pageIndex = :pageIndex"
    )
    suspend fun deletePage(mangaId: Long, mangaKey: String, pageIndex: Int)

    /** 删除某段页号的超分记录（「清除本章超分」用；`from`/`to` 都含）。 */
    @Query(
        "DELETE FROM imported_page_sr " +
            "WHERE mangaId = :mangaId AND (mangaKey = :mangaKey OR mangaKey IS NULL) " +
            "AND pageIndex BETWEEN :from AND :to"
    )
    suspend fun deletePageRange(mangaId: Long, mangaKey: String, from: Int, to: Int)

    /** 删除单部漫画的全部超分记录（孤儿清理用）。 */
    @Query("DELETE FROM imported_page_sr WHERE mangaId = :mangaId")
    suspend fun deleteManga(mangaId: Long)

    /** 删除「某 id + 某身份指纹」的记录（顺带清掉同 id 下 key 为 NULL 的升级前残留）。 */
    @Query(
        "DELETE FROM imported_page_sr " +
            "WHERE mangaId = :mangaId AND (mangaKey = :mangaKey OR mangaKey IS NULL)"
    )
    suspend fun deleteMangaScoped(mangaId: Long, mangaKey: String)

    /**
     * 把残留的「超分中」重置为「未超分」。
     *
     * ⚠️ 与翻译侧的 `resetTranslating` 同一条理由：`STATE_RUNNING` 是在超分**开始时**写库的，
     * 退出阅读器 / 进程被杀会让它**永久停在「超分中」**，面板上那一行从此既显示不出结果、
     * 也再也不会被重新挑中。放在**进入阅读器时**清理（"退出时"清理在进程被杀时根本不执行）。
     *
     * state 字面量 0/1 与 [ImportedPageSr.STATE_IDLE] / [STATE_RUNNING] 对应
     * —— @Query 里不能引用 Kotlin 常量，改这两个值时必须同步改这里。
     */
    @Query("UPDATE imported_page_sr SET state = 0 WHERE mangaId = :mangaId AND mangaKey = :mangaKey AND state = 1")
    suspend fun resetRunning(mangaId: Long, mangaKey: String)
}
