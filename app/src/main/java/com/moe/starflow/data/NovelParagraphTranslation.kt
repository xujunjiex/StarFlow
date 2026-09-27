package com.moe.starflow.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 小说段落译文（主键 = 书 + 身份指纹 + 章 + 段）。
 *
 * ### 三条不能动的约束
 *
 * 1. **`paraIndex` 是唯一权威，绝不用 `sourceText` 反查译文**。
 *    参考实现 (Kototoro) 存了 `Map<Int, String>` 却在渲染时改用原文文本做 key，
 *    于是同章里重复的段落（`"……"`、短对白）会共用同一条译文。
 *
 * 2. **`novelKey` 是身份指纹（= `addedAt`）**。书籍 id = 书架最大 id + 1，删书后重导会
 *    复用同一 id；不带指纹就会把上一本书的译文串到新书上。删除也必须按 (id, key) **成对**删。
 *
 * 3. **`splitVersion`**：段落切分规则一改，旧 `paraIndex` 整体错位、且**看不出错**
 *    （译文还在，只是对错了段）。切分规则变更时递增 `NovelParagraphSplitter.SPLIT_VERSION`，
 *    旧行自动失效。
 */
@Entity(
    tableName = "novel_paragraph_translation",
    primaryKeys = ["novelId", "novelKey", "chapterIndex", "paraIndex"]
)
data class NovelParagraphTranslation(
    val novelId: Long,
    val novelKey: String,
    val chapterIndex: Int,
    val paraIndex: Int,
    val sourceText: String,
    val translatedText: String = "",
    val state: Int = STATE_IDLE,
    val failCode: String? = null,
    val translatorName: String? = null,
    val sourceLang: String? = null,
    val targetLang: String? = null,
    val splitVersion: Int = 0,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val STATE_IDLE = 0          // 未翻译
        const val STATE_TRANSLATING = 1   // 翻译中
        const val STATE_SUCCESS = 2       // 成功
        const val STATE_FAILED = 3        // 失败（**不写空译文**，可重挑）
    }
}

/** 一条失败明细（章行展开显示「为什么失败」）。 */
data class NovelFailureRow(
    val chapterIndex: Int,
    val paraIndex: Int,
    val failCode: String?,
    val sourceText: String,
)

/** 章级聚合，目录面板的状态徽章用。 */
data class NovelChapterStat(
    val chapterIndex: Int,
    val total: Int,
    val success: Int,
)

@Dao
interface NovelParagraphTranslationDao {

    /**
     * 读某章全部记录。
     * ⚠️ `splitVersion` 必须传（见 Entity 注释第 3 条）—— 不传默认值：默认值会让
     * 「忘了按版本过滤」变成一行看不出来的静默错误。
     */
    @Query(
        "SELECT * FROM novel_paragraph_translation " +
            "WHERE novelId = :novelId AND novelKey = :novelKey AND chapterIndex = :chapterIndex " +
            "AND splitVersion = :splitVersion ORDER BY paraIndex"
    )
    suspend fun forChapter(
        novelId: Long,
        novelKey: String,
        chapterIndex: Int,
        splitVersion: Int,
    ): List<NovelParagraphTranslation>

    @Query(
        "SELECT * FROM novel_paragraph_translation " +
            "WHERE novelId = :novelId AND novelKey = :novelKey " +
            "AND chapterIndex = :chapterIndex AND paraIndex = :paraIndex"
    )
    suspend fun get(
        novelId: Long,
        novelKey: String,
        chapterIndex: Int,
        paraIndex: Int,
    ): NovelParagraphTranslation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NovelParagraphTranslation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NovelParagraphTranslation>)

    /**
     * **只在没有该行时插入**（已有行原样保留）。
     *
     * 批翻译的「标记翻译中」用它：`REPLACE` 会把这一段**已经拿到的译文覆盖成空**，
     * 用户看到的就是「翻过的段又变回空白」。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<NovelParagraphTranslation>)

    /**
     * 把指定段标成失败（**不写空译文**，可被下一轮重挑）。
     *
     * `state != 2` 的条件是必须的：已经有译文（SUCCESS）的段不该因为一次失败被抹掉。
     * state 字面量 2/3 = [NovelParagraphTranslation.STATE_SUCCESS] / [STATE_FAILED]。
     */
    @Query(
        "UPDATE novel_paragraph_translation SET state = 3, failCode = :failCode, updatedAt = :now " +
            "WHERE novelId = :novelId AND novelKey = :novelKey AND chapterIndex = :chapterIndex " +
            "AND splitVersion = :splitVersion AND paraIndex IN (:paraIndexes) AND state != 2"
    )
    suspend fun markFailedRows(
        novelId: Long,
        novelKey: String,
        chapterIndex: Int,
        splitVersion: Int,
        paraIndexes: List<Int>,
        failCode: String,
        now: Long,
    )

    /**
     * 标失败（带**空集合守卫**）。
     *
     * ⚠️ `paraIndexes` 为空时 Room 会生成 `IN ()` —— SQLite 直接语法错、运行时崩。
     * 之前只靠调用点自己判空，任何新调用方漏了就是一次崩溃。
     */
    suspend fun markFailed(
        novelId: Long,
        novelKey: String,
        chapterIndex: Int,
        splitVersion: Int,
        paraIndexes: List<Int>,
        failCode: String,
        now: Long,
    ) {
        if (paraIndexes.isEmpty()) return
        markFailedRows(novelId, novelKey, chapterIndex, splitVersion, paraIndexes, failCode, now)
    }

    /** 失败明细（章行展开显示原因用）：只取 FAILED 行。 */
    @Query(
        "SELECT chapterIndex, paraIndex, failCode, sourceText FROM novel_paragraph_translation " +
            "WHERE novelId = :novelId AND novelKey = :novelKey AND splitVersion = :splitVersion " +
            "AND state = 3 ORDER BY chapterIndex, paraIndex"
    )
    suspend fun failures(
        novelId: Long,
        novelKey: String,
        splitVersion: Int,
    ): List<NovelFailureRow>

    /** 按章删译文（「清空本章译文」用）。 */
    @Query(
        "DELETE FROM novel_paragraph_translation " +
            "WHERE novelId = :novelId AND novelKey = :novelKey AND chapterIndex = :chapterIndex"
    )
    suspend fun deleteForChapter(
        novelId: Long,
        novelKey: String,
        chapterIndex: Int,
    )

    /**
     * 全部章的 (总数, 成功数)。**只统计本指纹 + 本 splitVersion**：否则旧版本的行会把
     * 「已翻译比例」算虚高，而渲染时那些行其实会被过滤掉。
     *
     * state 字面量 2 = [NovelParagraphTranslation.STATE_SUCCESS]（@Query 里引不到 Kotlin 常量）。
     */
    @Query(
        "SELECT chapterIndex AS chapterIndex, COUNT(*) AS total, " +
            "SUM(CASE WHEN state = 2 THEN 1 ELSE 0 END) AS success " +
            "FROM novel_paragraph_translation " +
            "WHERE novelId = :novelId AND novelKey = :novelKey AND splitVersion = :splitVersion " +
            "GROUP BY chapterIndex"
    )
    suspend fun chapterStats(novelId: Long, novelKey: String, splitVersion: Int): List<NovelChapterStat>

    @Query(
        "SELECT COUNT(*) FROM novel_paragraph_translation " +
            "WHERE novelId = :novelId AND novelKey = :novelKey"
    )
    suspend fun countFor(novelId: Long, novelKey: String): Int

    /**
     * 把指定段标成「翻译中」。
     *
     * ⚠️ **必须是 UPDATE，不能靠 `insertIgnore` 建行**（真实踩过）：整章的行已经由
     * 「补齐分母」那一步创建好了，`INSERT OR IGNORE` 对已存在的行是**空操作** ——
     * 于是只有**该章的第一批**能被标上，第二批开始的每一批都标不上去
     * （早先靠「先标记再补齐」的顺序绕过，对第二批起仍然无效）。
     * UPDATE 与调用顺序无关，语义也更直白。
     *
     * `state != 2`：已有译文的段不动 —— 重翻时不能把拿到的译文弄丢。
     * `failCode` 清空：这一段正在被重试，旧的失败原因不该继续挂着。
     */
    @Query(
        "UPDATE novel_paragraph_translation SET state = 1, translatorName = :translatorName, " +
            "failCode = NULL, updatedAt = :now " +
            "WHERE novelId = :novelId AND novelKey = :novelKey AND chapterIndex = :chapterIndex " +
            "AND splitVersion = :splitVersion AND paraIndex IN (:paraIndexes) AND state != 2"
    )
    suspend fun markTranslatingRows(
        novelId: Long,
        novelKey: String,
        chapterIndex: Int,
        splitVersion: Int,
        paraIndexes: List<Int>,
        translatorName: String,
        now: Long,
    )

    /**
     * 把残留的「翻译中」重置为「未翻译」。
     *
     * ⚠️ 必要性：`STATE_TRANSLATING` 是在翻译**开始时**写库的，只有跑完才改成 SUCCESS。
     * 期间退出阅读器 / 进程被杀 / 崩溃会让该章**永久停在「翻译中」** —— 既不显示译文，
     * 翻译按钮也只会说「正在翻译中」而再也翻不了。
     *
     * 放在**进入阅读器时**清理（而不是退出时）：进程被杀/崩溃时「退出时」的清理根本不会执行。
     */
    @Query(
        "UPDATE novel_paragraph_translation SET state = 0 " +
            "WHERE novelId = :novelId AND novelKey = :novelKey AND state = 1"
    )
    suspend fun resetTranslating(novelId: Long, novelKey: String)

    /**
     * 删除「某 id + 某指纹」的全部记录（顺带清同 id 下 key 为 NULL 的升级前残留）。
     *
     * ⚠️ **必须带 key**：书籍 id 会被复用，而删除是异步的 —— 只按 id 删的话，用户完全可能
     * 在它落地前就导入了一本复用同 id 的新书并翻了几章 → 把这本**新书**的译文删掉。
     */
    @Query(
        "DELETE FROM novel_paragraph_translation " +
            "WHERE novelId = :novelId AND (novelKey = :novelKey OR novelKey IS NULL)"
    )
    suspend fun deleteForNovelScoped(novelId: Long, novelKey: String)

    /**
     * 按 id 删掉该书**全部**译文行（**不分指纹**）。
     *
     * ⚠️ 只给**孤儿清理**用（`allNovelIds()` 求差后得到的那几个 id）：那时手上只有 id，
     * 拿不到指纹，而历史遗留行可能挂着旧指纹。用户主动删书仍然是
     * [deleteForNovelScoped]（带指纹）—— 那条路必须防「id 被复用后误删新书」，
     * 这条路的前提恰恰是「这个 id 已经不在书架上了」。
     */
    @Query("DELETE FROM novel_paragraph_translation WHERE novelId = :novelId")
    suspend fun deleteForNovelId(novelId: Long)

    /** 全部出现过的 novelId（去重），供书架清理孤儿行。 */
    @Query("SELECT DISTINCT novelId FROM novel_paragraph_translation")
    suspend fun allNovelIds(): List<Long>
}
