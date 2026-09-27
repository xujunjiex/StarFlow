package com.moe.starflow.novel.translate

import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.data.NovelParagraphTranslation
import com.moe.starflow.data.NovelFailureRow
import com.moe.starflow.data.NovelParagraphTranslationDao
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.utils.LogCollector

/**
 * 队列真正需要的那一件事：**翻一批并落库**。
 *
 * 抽成接口只有一个目的 —— 让队列的推进逻辑（页锚点、批配额、何时停）能在普通单测里
 * 用假实现驱动。那部分语义已经做错过一次（增量被做成"向后 N 章"），必须能在 JVM 单测里钉住，
 * 而 `NovelChapterTranslator` 直接依赖 Room DAO，测试里造不起。
 */
interface NovelBatchTranslator {
    suspend fun translateBatch(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        paraIndexes: List<Int>,
        sourceLang: String,
        targetLang: String,
        translatorName: String,
    ): NovelBatchResult
}

/**
 * 章翻译编排：引擎产出 + 状态机写库。
 *
 * ### 状态机（**逐批**推进，不是整章一把）
 * 每一批：本批写 `TRANSLATING` → 整章其余可翻译段补成 `IDLE`（分母）→ 引擎翻 →
 * 拿到的段写 `SUCCESS`、**没拿到的段立刻写 `FAILED`**（带 `failCode` 原始原因）。
 *
 * ⚠️ **失败绝不写空译文**：空串会被读回路径当成「已成功翻译」而永不重试，
 * 于是该段永远显示空白。参考实现就踩了这个（把空串 putIfAbsent 进缓存）。
 *
 * ### 为什么 `TRANSLATING` 要落库
 * 它是跨进程的「本章正在翻」标记，也是 [resetStale] 的清理依据 —— 进程被杀会让它永久残留。
 */
class NovelChapterTranslator(
    private val dao: NovelParagraphTranslationDao,
    private val engine: NovelTranslationEngine,
    /** 必须与 `NovelParagraphSplitter.SPLIT_VERSION` 一致；不一致的旧译文读不出来。 */
    private val splitVersion: Int,
) : NovelBatchTranslator {

    private companion object {
        const val TAG = "NovelChapterTranslator"

        /** ⚠️ 与显示端共用同一个字面量（`NovelFailCode`）：两处各写一份，改一处就静默不一致 */
        const val FAIL_CODE_EMPTY = com.moe.starflow.novel.reader.NovelFailCode.TRANSLATE_EMPTY
    }

    /**
     * 把段落表映射成**未翻译（IDLE）**的行对象，且**不带译文**。
     *
     * ⚠️ 只用于建行（`insertIgnore`），绝不能拿它去 `upsertAll`（REPLACE）：
     * `translatedText` 是空串，REPLACE 会把已有的译文覆盖成空 ——
     * 用户看到的就是「翻过的段又变回空白」。
     */
    private fun idleRows(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
    ): List<NovelParagraphTranslation> {
        val now = System.currentTimeMillis()
        return paragraphs
            .filter { it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank() }
            .map { p ->
                NovelParagraphTranslation(
                    novelId = book.id,
                    novelKey = book.translationKey,
                    chapterIndex = chapterIndex,
                    paraIndex = p.index,
                    sourceText = p.originalText,
                    translatedText = "",
                    state = NovelParagraphTranslation.STATE_IDLE,
                    translatorName = translatorName,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    splitVersion = splitVersion,
                    updatedAt = now,
                )
            }
    }

    /**
     * 已经补齐过「本章全部可翻译段」的章（键 = 书 + 指纹 + 章 + 切分版本）。
     *
     * ⚠️ 不记这个标记的话，**每一批**都会把该章所有段重新造成对象再写一次库：
     * `insertIgnore` 对已存在的行确实不写，但代价在 `O(章内段数)` 的对象分配 + N 条语句执行上 ——
     * 一章 300 段、按默认每批 3 段翻完就是 100 轮 × 300 条 ≈ 3 万条 INSERT 语句；
     * 无章节标记的 txt（`TxtChapterSplitter.wholeBook`，整本当一章）段数上万，
     * 逐批累积会把翻译吞吐拖垮，用户看到的就是「越翻越慢」。
     */
    private val ensuredChapters: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun ensureKey(book: ImportedNovel, chapterIndex: Int) =
        "${book.id}:${book.translationKey}:$chapterIndex:$splitVersion"

    /**
     * 把该章**所有可翻译段**先落成 IDLE 行（`insertIgnore`：已有行一个字段都不动）。
     *
     * ⚠️ 这是「本章翻完了吗」的**分母**来源。SQL 那边是 `COUNT(*)`，而行是**按批惰性写的** ——
     * 只写这一批的话，分母退化成"已经尝试过的段数"，于是翻了几段就把整章标成「已翻译」
     * （用户报的「某一章没翻完却显示已经全部翻译完成」就是这个）。补齐之后
     * `success >= total` 才真的等于「全章可翻译段都翻完了」。
     *
     * ⚠️ 每章只补一次（见 [ensuredChapters]）。清空本章译文时标记要一起清掉（[clearChapter]），
     * 否则行被删了而标记还在 —— 分母再也补不回来，该章永远显示不出「已完成」。
     */
    private suspend fun ensureChapterRows(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
    ) {
        val key = ensureKey(book, chapterIndex)
        if (!ensuredChapters.add(key)) return
        try {
            val rows = idleRows(book, chapterIndex, paragraphs, translatorName, sourceLang, targetLang)
            if (rows.isNotEmpty()) dao.insertIgnore(rows)
        } catch (e: Exception) {
            // 写失败就把标记退回去：否则这次失败会让该章的分母**永久**缺失
            ensuredChapters.remove(key)
            throw e
        }
    }

    /**
     * 一批开翻前的两步准备：**先补齐整章的行（分母），再把这一批标成「翻译中」**。
     *
     * 两步都别挪走、别合并：
     * - 补齐整章可翻译段是**分母**的来源（章徽章 / 筛选 / 目录的分母是 `COUNT(*)`）。
     *   只写这一批的话分母退化成"翻过的段数"，于是翻了几段就把整章标成「已翻译」——
     *   用户报的「没翻完却显示全部完成」。
     * - 标「翻译中」必须走 `dao.markTranslatingRows`（**UPDATE**）而不是 `INSERT OR IGNORE`：
     *   行刚被上一步建出来，`insertIgnore` 对已存在的行是空操作 —— 早先靠「先标记再补齐」的
     *   顺序绕过，但那只对该章的**第一批**有效，第二批起仍然标不上（见 DAO 的注释）。
     */
    private suspend fun prepareChapterForBatch(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        batch: List<NovelParagraph>,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
    ) {
        ensureChapterRows(book, chapterIndex, paragraphs, translatorName, sourceLang, targetLang)
        val inBatch = batch
            .filter { it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank() }
            .map { it.index }
        if (inBatch.isEmpty()) return
        dao.markTranslatingRows(
            novelId = book.id,
            novelKey = book.translationKey,
            chapterIndex = chapterIndex,
            splitVersion = splitVersion,
            paraIndexes = inBatch,
            translatorName = translatorName,
            now = System.currentTimeMillis(),
        )
    }

    private suspend fun persist(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        translations: Map<Int, String>,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
    ) {
        if (translations.isEmpty()) return
        val key = book.translationKey
        val now = System.currentTimeMillis()
        val sourceByIndex = paragraphs.associate { it.index to it.originalText }
        val rows = translations.map { (paraIndex, text) ->
            NovelParagraphTranslation(
                novelId = book.id,
                novelKey = key,
                chapterIndex = chapterIndex,
                paraIndex = paraIndex,
                sourceText = sourceByIndex[paraIndex] ?: "",
                translatedText = text,
                state = NovelParagraphTranslation.STATE_SUCCESS,
                translatorName = translatorName,
                sourceLang = sourceLang,
                targetLang = targetLang,
                splitVersion = splitVersion,
                updatedAt = now,
            )
        }
        dao.upsertAll(rows)
    }

    /**
     * 进入阅读器时清理残留的「翻译中」。
     *
     * ⚠️ 必须在**进入时**清（而不是退出时）：进程被杀/崩溃时「退出时」的清理根本不会执行，
     * 而残留的 TRANSLATING 会让该章既不显示译文也再也翻不了。
     */
    suspend fun resetStale(book: ImportedNovel) {
        dao.resetTranslating(book.id, book.translationKey)
        // ⚠️ 同一个入口顺手清掉**非当前分段版本**的行：主键不含 splitVersion，留着它们会让
        // 新版本的行 `insertIgnore` 静默写不进去（分母补不齐、「翻译中」标不上）。
        // 放在进入阅读器时做，和 resetTranslating 同一个理由（进程被杀时"退出时清理"不会执行）。
        dao.deleteOtherVersions(book.id, book.translationKey, splitVersion)
    }

    /**
     * 翻**一批**并落库（[paraIndexes] 来自 `NovelBatchPlanner.nextBatch`）。
     *
     * **只发一次请求、只写这一批的库** —— 队列靠它实现「点一次翻一批」和「增量配额 x 批」；
     * 要翻整章是 `NovelTranslationQueue.translateWholeChapter` 一批批地调它。
     *
     * 这一批里没拿到的段标 `FAILED`（带 failCode）—— **绝不写空译文**：
     * 空串会被读回路径当成「已成功翻译」而永不重试（见 [persist] 的说明）。
     */
    override suspend fun translateBatch(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        paraIndexes: List<Int>,
        sourceLang: String,
        targetLang: String,
        translatorName: String,
    ): NovelBatchResult {
        if (paraIndexes.isEmpty()) return NovelBatchResult(emptyMap())
        val wanted = paraIndexes.toSet()
        val mine = paragraphs.filter {
            it.index in wanted && it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank()
        }
        if (mine.isEmpty()) return NovelBatchResult(emptyMap())

        // 落库前的两步准备工作合在一处（顺序敏感，见下）
        prepareChapterForBatch(book, chapterIndex, paragraphs, mine, translatorName, sourceLang, targetLang)
        val result = engine.translateBatch(paragraphs, mine.map { it.index }, sourceLang, targetLang)
        val got = result.translations
        persist(
            book = book,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            translations = got,
            translatorName = translatorName,
            sourceLang = sourceLang,
            targetLang = targetLang,
        )
        val missed = mine.map { it.index }.filter { it !in got }
        if (missed.isNotEmpty()) {
            dao.markFailed(
                novelId = book.id,
                novelKey = book.translationKey,
                chapterIndex = chapterIndex,
                splitVersion = splitVersion,
                paraIndexes = missed,
                // ⚠️ failCode 存**原始原因**（异常类型 + message 整条链 / 模型到底回了什么），
                // 不是一句"失败"：用户在章行展开里看到的就是它，含糊的文案等于没报错。
                failCode = result.error ?: FAIL_CODE_EMPTY,
                now = System.currentTimeMillis(),
            )
            LogCollector.w(TAG, "第 $chapterIndex 章有 ${missed.size} 段没拿到译文：${result.error}")
        }
        return result
    }

    /** 失败明细（章行展开显示原因用），按章分组。 */
    suspend fun failuresOf(book: ImportedNovel): Map<Int, List<NovelFailureRow>> =
        dao.failures(book.id, book.translationKey, splitVersion).groupBy { it.chapterIndex }

    /**
     * 只清**本章**译文（用户明确要求：不要一点就清整本）。
     *
     * ⚠️ 行删了，[ensuredChapters] 里那个标记也必须删：留着它的话下次翻这一章不会再补分母，
     * `COUNT(*)` 恒为 0，该章永远显示不出「已完成」。
     */
    suspend fun clearChapter(book: ImportedNovel, chapterIndex: Int) {
        dao.deleteForChapter(book.id, book.translationKey, chapterIndex)
        ensuredChapters.remove(ensureKey(book, chapterIndex))
    }

    /** 本章已成功的译文：`paraIndex -> 译文`。 */
    suspend fun loadTranslations(book: ImportedNovel, chapterIndex: Int): Map<Int, String> =
        dao.forChapter(book.id, book.translationKey, chapterIndex, splitVersion)
            .filter { it.state == NovelParagraphTranslation.STATE_SUCCESS && it.translatedText.isNotEmpty() }
            .associate { it.paraIndex to it.translatedText }

    /** 章状态聚合（目录徽章 / 队列判断用）。 */
    suspend fun chapterStats(book: ImportedNovel): Map<Int, NovelChapterStat> =
        dao.chapterStats(book.id, book.translationKey, splitVersion).associateBy { it.chapterIndex }
}
