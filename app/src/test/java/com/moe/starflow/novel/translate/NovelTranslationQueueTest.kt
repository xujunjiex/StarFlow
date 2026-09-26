package com.moe.starflow.novel.translate

import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列推进的**语义守卫**（纯 JVM，用假翻译器驱动）。
 *
 * 这里钉死的是三种模式到底怎么往前走 —— 这一块曾经整体做错过一次
 * （把「增量」实现成了「向后 N **章**」），所以每条语义都配一条断言：
 * 自动只在本页内推进、增量向后按**批**、章末停下、配额用尽停下、一批失败不无限重试。
 */
class NovelTranslationQueueTest {

    private class FakeTranslator : NovelBatchTranslator {
        val batches = mutableListOf<List<Int>>()
        var failAll = false

        override suspend fun translateBatch(
            book: ImportedNovel,
            chapterIndex: Int,
            paragraphs: List<NovelParagraph>,
            paraIndexes: List<Int>,
            sourceLang: String,
            targetLang: String,
            translatorName: String,
        ): NovelBatchResult {
            batches += paraIndexes
            if (failAll) return NovelBatchResult(emptyMap(), "fake 失败")
            return NovelBatchResult(paraIndexes.associateWith { "译$it" })
        }
    }

    private fun book() = ImportedNovel(
        id = 1, title = "t", localRoot = "/tmp", format = NovelFormat.TXT, chapterCount = 3, addedAt = 1,
    )

    private fun queue(
        scope: CoroutineScope,
        translator: FakeTranslator,
        translated: MutableSet<Int>,
        page: () -> List<Int>,
        chapterSize: Int,
        batchSize: Int,
    ) = NovelTranslationQueue(
        scope = scope,
        translator = translator,
        paragraphsOf = { _, _ ->
            (0 until chapterSize).map { NovelParagraph(it, NovelParagraphType.TEXT, "p$it") }
        },
        sourceLang = { "en" },
        targetLang = { "zh" },
        translatorName = { "fake" },
        batchSize = { batchSize },
        debounceMs = { 0 },
        currentPageParaIndexes = page,
        chapterParaIndexes = { _, _ -> (0 until chapterSize).toList() },
        translatedIndexes = { _, _ -> translated.toSet() },
    )

    /** 自动：当前页是唯一锚点 —— 本页翻完就停，**不越页**。 */
    @Test
    fun `自动模式把当前页翻完就停`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 2)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)

        assertEquals("只翻当前页那一批", listOf(listOf(0, 1)), t.batches)
        q.stop()
    }

    /** 自动：本页有多批时要**一批批翻到整页翻完**（用户确认过的口径）。 */
    @Test
    fun `自动模式在当前页内一批批翻到翻完`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1, 2) }, chapterSize = 6, batchSize = 1)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)

        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), t.batches)
        q.stop()
    }

    /** 增量：页内翻完后**继续在本章向后**，翻满配额即停（不是翻后面几章）。 */
    @Test
    fun `增量模式向后翻满配额即停`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 20, batchSize = 2)

        q.start(
            book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(2), currentChapter = { 0 },
        ) { _, r -> translated += r.translations.keys }
        advanceTimeBy(60_000)

        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), t.batches)
        q.stop()
    }

    /** 增量：章末就停，**不去下一章**。 */
    @Test
    fun `增量模式在本章末尾停下`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(4) }, chapterSize = 5, batchSize = 2)

        q.start(
            book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(9), currentChapter = { 0 },
        ) { _, r -> translated += r.translations.keys }
        advanceTimeBy(60_000)

        assertEquals(listOf(listOf(4)), t.batches)
        q.stop()
    }

    /** 手动不进队列循环；点一次 = 从当前页锚点翻**一批**。 */
    @Test
    fun `手动模式不进队列但要一批就翻一批`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(2, 3) }, chapterSize = 20, batchSize = 2)

        q.start(book(), NovelTranslateMode.MANUAL, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)
        assertTrue("手动模式队列不该自己翻", t.batches.isEmpty())

        val got = q.translateOneBatch(book(), 0)
        assertEquals(mapOf(2 to "译2", 3 to "译3"), got.translations)
        assertEquals(listOf(listOf(2, 3)), t.batches)
        q.stop()
    }

    /** 面板打开即暂停；关掉之后按同一套参数接着走。 */
    @Test
    fun `面板打开暂停关闭后继续`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 2)

        q.setPanelOpen(true)
        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)
        assertTrue("面板开着时不该翻", t.batches.isEmpty())

        q.setPanelOpen(false)
        advanceTimeBy(5_000)
        assertEquals(listOf(listOf(0, 1)), t.batches)
        q.stop()
    }

    /**
     * 一批彻底失败（模型回空）不能变成无限重试：失败的锚点要记账跳过，
     * 否则每轮重挑同一批 —— 额度烧光、进度条永远不动。
     */
    @Test
    fun `失败的批不会无限重试`() = runTest {
        val t = FakeTranslator().apply { failAll = true }
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 2)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)
        val afterFirst = t.batches.size

        advanceTimeBy(60_000)
        assertEquals("失败后不该继续重挑同一批", afterFirst, t.batches.size)
        assertEquals("失败 [0,1] → 锚点前移再失败 [1,2] → 之后再无待翻段，停", 2, afterFirst)
        q.stop()
    }

    /**
     * **重翻**（长按多选 → 重新翻译）：已经在译文表里的段**照样送出去**。
     *
     * 这条路刻意不经过 `NovelBatchPlanner` —— 规划器把「已有译文」当成已完成而跳过，
     * 用了它重翻就永远翻不动（用户看到的是"点了重翻但什么都没变"）。
     */
    @Test
    fun `按段翻译不跳过已有译文的段`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf(0, 1, 5)
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 3)

        val got = q.translateExact(book(), 0, listOf(0, 1, 4))

        assertEquals(mapOf(0 to "译0", 1 to "译1", 4 to "译4"), got.translations)
        assertEquals("选谁翻谁、一次请求", listOf(listOf(0, 1, 4)), t.batches)
        q.stop()
    }

    /** 空选择不发请求（白抢一次锁、白烧一次额度）。 */
    @Test
    fun `空选择不发请求`() = runTest {
        val t = FakeTranslator()
        val q = queue(backgroundScope, t, mutableSetOf(), page = { listOf(0) }, chapterSize = 3, batchSize = 3)

        val got = q.translateExact(book(), 0, emptyList())

        assertTrue(got.isEmpty)
        assertTrue(t.batches.isEmpty())
        q.stop()
    }

    /**
     * **增量窗口跟着当前页走**（用户口径）：额度用完不是终点 —— 用户翻页后窗口前移，
     * 自动接着往后翻。做成"一次性额度"的话翻页也毫无反应（用户报的"配额用完就不动了"）。
     */
    @Test
    fun `增量窗口用完后再翻页会接着往后翻`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        var page = listOf(0, 1)
        val q = queue(backgroundScope, t, translated, page = { page }, chapterSize = 30, batchSize = 2)

        q.start(
            book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(2), currentChapter = { 0 },
        ) { _, r -> translated += r.translations.keys }
        advanceTimeBy(10_000)

        assertEquals("本页一批 + 窗口内向后一批（2 批 × 2 段 = 4 段）", listOf(listOf(0, 1), listOf(2, 3)), t.batches)
        advanceTimeBy(60_000)
        assertEquals("窗口内翻完就停，不越界继续往后", 2, t.batches.size)

        page = listOf(10, 11)
        advanceTimeBy(10_000)
        assertEquals(
            "翻页 → 窗口前移（10 起往后 4 段），接着翻两批",
            listOf(listOf(10, 11), listOf(12, 13)),
            t.batches.drop(2),
        )
        advanceTimeBy(60_000)
        assertEquals("新窗口翻完又停下，不会一路翻到章末", 4, t.batches.size)
        q.stop()
    }

    /** 没有可翻的段（页内全翻完）→ 停在 DRAINED，而不是空转刷状态。 */
    @Test
    fun `没有待翻的段时停在 DRAINED`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf(0, 1)
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 2)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)

        assertTrue(t.batches.isEmpty())
        assertEquals(NovelQueuePhase.DRAINED, q.state.value.phase)
        assertNull("自动模式没有配额概念", q.state.value.remaining)
        q.stop()
    }
}
