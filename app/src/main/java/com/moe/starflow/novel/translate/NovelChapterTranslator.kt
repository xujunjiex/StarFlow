package com.moe.starflow.novel.translate

import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.data.NovelParagraphTranslation
import com.moe.starflow.data.NovelParagraphTranslationDao
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 章翻译编排：引擎产出 + 状态机写库。
 *
 * ### 状态机
 * 开翻前把该章所有待翻段写成 `TRANSLATING` → 每批把拿到的段写成 `SUCCESS`
 * → 完成时仍未拿到的段写 `FAILED`（带 failCode）。
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
) {

    private companion object {
        const val TAG = "NovelChapterTranslator"
        const val FAIL_CODE_EMPTY = "TRANSLATE_EMPTY"
    }

    fun translateChapter(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        sourceLang: String,
        targetLang: String,
        translatorName: String,
        batchSize: Int = NovelTranslationBatch.DEFAULT_BATCH_PARAGRAPHS,
    ): Flow<NovelChapterProgress> = flow {
        markTranslating(book, chapterIndex, paragraphs, translatorName, sourceLang, targetLang)
        engine.translateChapter(chapterIndex, paragraphs, sourceLang, targetLang, batchSize)
            .collect { progress ->
                persist(book, chapterIndex, paragraphs, progress, translatorName, sourceLang, targetLang)
                emit(progress)
            }
    }

    private suspend fun markTranslating(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
    ) {
        val now = System.currentTimeMillis()
        val rows = paragraphs
            .filter { it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank() }
            .map { p ->
                NovelParagraphTranslation(
                    novelId = book.id,
                    novelKey = book.translationKey,
                    chapterIndex = chapterIndex,
                    paraIndex = p.index,
                    sourceText = p.originalText,
                    translatedText = "",
                    state = NovelParagraphTranslation.STATE_TRANSLATING,
                    translatorName = translatorName,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    splitVersion = splitVersion,
                    updatedAt = now,
                )
            }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    private suspend fun persist(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        progress: NovelChapterProgress,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
    ) {
        val key = book.translationKey
        val now = System.currentTimeMillis()
        if (progress.translations.isNotEmpty()) {
            val sourceByIndex = paragraphs.associate { it.index to it.originalText }
            val rows = progress.translations.map { (paraIndex, text) ->
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
        if (progress.isComplete) {
            val stuck = dao.forChapter(book.id, key, chapterIndex, splitVersion)
                .filter { it.state == NovelParagraphTranslation.STATE_TRANSLATING }
            if (stuck.isNotEmpty()) {
                dao.upsertAll(
                    stuck.map {
                        it.copy(
                            state = NovelParagraphTranslation.STATE_FAILED,
                            failCode = FAIL_CODE_EMPTY,
                            updatedAt = now,
                        )
                    }
                )
                LogCollector.w(TAG, "第 $chapterIndex 章有 ${stuck.size} 段未拿到译文，标记 FAILED")
            }
        }
    }

    /**
     * 进入阅读器时清理残留的「翻译中」。
     *
     * ⚠️ 必须在**进入时**清（而不是退出时）：进程被杀/崩溃时「退出时」的清理根本不会执行，
     * 而残留的 TRANSLATING 会让该章既不显示译文也再也翻不了。
     */
    suspend fun resetStale(book: ImportedNovel) {
        dao.resetTranslating(book.id, book.translationKey)
    }

    /** 本章已成功的译文：`paraIndex -> 译文`。 */
    suspend fun loadTranslations(book: ImportedNovel, chapterIndex: Int): Map<Int, String> =
        dao.forChapter(book.id, book.translationKey, chapterIndex, splitVersion)
            .filter { it.state == NovelParagraphTranslation.STATE_SUCCESS && it.translatedText.isNotEmpty() }
            .associate { it.paraIndex to it.translatedText }

    /** 章状态聚合（目录徽章 / 队列判断用）。 */
    suspend fun chapterStats(book: ImportedNovel): Map<Int, NovelChapterStat> =
        dao.chapterStats(book.id, book.translationKey, splitVersion).associateBy { it.chapterIndex }

    /** 清空本书译文（按 (id, key) 成对删，不误伤复用同 id 的另一本书）。 */
    suspend fun clearBook(book: ImportedNovel) {
        dao.deleteForNovelScoped(book.id, book.translationKey)
    }

    suspend fun countFor(book: ImportedNovel): Int = dao.countFor(book.id, book.translationKey)
}
