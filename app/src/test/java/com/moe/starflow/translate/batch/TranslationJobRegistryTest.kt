package com.moe.starflow.translate.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量翻译**注册表 / 通知 id / 前台服务判据**的守卫（纯 JVM）。
 *
 * 起因（2026-09-28 `/review` 审查）：
 * - `TranslationJobService` 与 `TranslationJobRegistry` 在 `app/src/test` 下**零引用** ——
 *   通知栏那条链路（"任务开始 4 秒后服务已停止、整段没有通知"）改坏了没有任何测试会红；
 * - `ActiveChapterJob.notificationId` 的派生公式会**撞上汇总通知 id**（bookId=322/ch=16 → 9998）
 *   且**跨书撞号**（book1/ch31 与 book2/ch0 都是 62）→ 两条章通知互相覆盖。
 */
class TranslationJobRegistryTest {

    private class Src(
        override val key: String,
        initial: List<ActiveChapterJob>,
    ) : ChapterJobSource {
        @Volatile var jobs: List<ActiveChapterJob> = initial
        val actions = mutableListOf<Pair<JobAction, Int>>()
        override fun snapshot(): List<ActiveChapterJob> = jobs
        override fun onAction(action: JobAction, job: ActiveChapterJob): Boolean {
            actions += action to job.chapterIndex
            return true
        }
    }

    private fun job(state: ChapterJobState, chapter: Int = 0, book: Long = 1L) = ActiveChapterJob(
        kind = TranslationJobKind.MANGA,
        bookId = book,
        bookTitle = "书",
        chapterIndex = chapter,
        chapterLabel = "第1章",
        total = 3,
        done = 1,
        state = state,
    )

    /**
     * 只有 RUNNING/PAUSED/QUEUED 算活动任务：DONE/CANCELLED 也算的话
     * 通知会永远挂着、前台服务永不停。
     *
     * ⚠️ `activeJobs` 快照**故意保留** DONE/CANCELLED（面板要显示"已完成 x/y"），
     * 过滤发生在服务侧；所以这里断言的是 `hasActiveJobs`（= 活动的唯一判据）。
     */
    @Test
    fun activeJobs_onlyRunningPausedQueued() {
        val src = Src("t1", listOf(job(ChapterJobState.DONE), job(ChapterJobState.CANCELLED)))
        TranslationJobRegistry.register(src)
        try {
            assertFalse("DONE/CANCELLED 不算活动任务", TranslationJobRegistry.hasActiveJobs.value)
            assertEquals("快照仍保留完成/取消的章（面板要用）", 2, TranslationJobRegistry.activeJobs.value.size)

            src.jobs = listOf(job(ChapterJobState.PAUSED))
            TranslationJobRegistry.notifyChanged()
            assertEquals(1, TranslationJobRegistry.activeJobs.value.size)
            assertTrue(TranslationJobRegistry.hasActiveJobs.value)
        } finally {
            TranslationJobRegistry.unregister("t1")
        }
    }

    /** 通知动作只转发给返回 true 的 source，且广播后刷新快照。 */
    @Test
    fun dispatch_forwardsActionAndRefreshesSnapshot() {
        val src = Src("t2", listOf(job(ChapterJobState.RUNNING, chapter = 2)))
        TranslationJobRegistry.register(src)
        try {
            val target = src.jobs.single()
            assertTrue(TranslationJobRegistry.dispatch(JobAction.CANCEL, target))
            assertEquals(listOf(JobAction.CANCEL to 2), src.actions)
        } finally {
            TranslationJobRegistry.unregister("t2")
        }
    }

    /**
     * 章通知 id：**不许撞汇总通知 id**（9998），且**跨书不撞**。
     * 撞了就是一条章通知把另一条（或汇总）顶掉、服务的 `shown - alive` 差集还会 cancel 别人的通知。
     */
    @Test
    fun notificationIds_avoidSummaryAndCollisions() {
        val summary = TranslationJobService.NOTIFICATION_ID_SERVICE
        val seen = HashMap<Int, String>()
        for (bookId in 1L..400L) {
            for (ch in 0..40) {
                val id = ActiveChapterJob(
                    kind = TranslationJobKind.MANGA, bookId = bookId, bookTitle = "b",
                    chapterIndex = ch, chapterLabel = "c", total = 1, done = 0,
                    state = ChapterJobState.RUNNING,
                ).notificationId
                assertNotEquals("book=$bookId ch=$ch 撞上汇总通知 id", summary, id)
                val prev = seen.put(id, "$bookId/$ch")
                if (prev != null) {
                    throw AssertionError("通知 id 撞号：$prev 与 $bookId/$ch 都是 $id")
                }
            }
        }
        // 审查报告里点名的两组历史撞号
        assertNotEquals(
            "book1/ch31 与 book2/ch0 不许撞",
            job(ChapterJobState.RUNNING, chapter = 31, book = 1L).notificationId,
            job(ChapterJobState.RUNNING, chapter = 0, book = 2L).notificationId,
        )
        // 小说与漫画同书同章也不能撞
        val manga = job(ChapterJobState.RUNNING, chapter = 5, book = 9L)
        val novel = manga.copy(kind = TranslationJobKind.NOVEL)
        assertNotEquals(manga.notificationId, novel.notificationId)
    }
}
