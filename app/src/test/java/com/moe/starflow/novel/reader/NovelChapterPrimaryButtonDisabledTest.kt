package com.moe.starflow.novel.reader

import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.translate.batch.ChapterJob
import com.moe.starflow.translate.batch.ChapterJobState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 小说章卡片**主按钮的可点性** —— 与漫画 `ChapterPrimaryButtonDisabledTest` 同一条口径
 * （用户 2026-10-01：「翻译本章已经完成就按钮应该置灰，除非用户手动删除了某张的译文，
 * 这个按钮才应该实时刷新变成可点状态」）。
 *
 * 小说侧单独的判据是 `isChapterDone(stats, totals, index)`（`success >= total`），
 * 与漫画的 `success >= pageCount` 同义但**数据源是两套**（这边是段数，那边是页数），
 * 所以两边各自钉一遍，别指望一个守卫盖住两个实现。
 *
 * ⚠️ 最要紧的是「跑着/暂停时**不能**置灰」—— 那时按钮是「暂停 / 继续」，禁掉就停不下来了。
 */
@RunWith(RobolectricTestRunner::class)
class NovelChapterPrimaryButtonDisabledTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private val chapters = listOf(
        NovelChapterMeta(index = 0, title = "第一章", locator = "c0"),
        NovelChapterMeta(index = 1, title = "第二章", locator = "c1"),
    )

    /** 三章以内的迷你书：章 0 一共 4 段。 */
    private fun bind(totals: Map<Int, Int>, stats: Map<Int, NovelChapterStat>, job: ChapterJob? = null): TextView {
        val adapter = NovelChapterStateAdapter(onJump = {})
        adapter.chapters = chapters
        adapter.totals = totals
        adapter.chars = emptyMap()
        adapter.stats = stats
        adapter.jobs = job?.let { mapOf(it.chapterIndex to it) } ?: emptyMap()
        adapter.currentChapter = 0
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView.findViewById(R.id.btn_chapter_primary)
    }

    private val totals = mapOf(0 to 4, 1 to 3)

    private fun stat(success: Int) = mapOf(0 to NovelChapterStat(chapterIndex = 0, total = 4, success = success))

    @Test
    fun nothingTranslatedCanRun() {
        assertTrue("一段都没翻，按钮必须可点", bind(totals, emptyMap()).isEnabled)
    }

    @Test
    fun partiallyTranslatedCanRun() {
        assertTrue("4 段只翻了 1 段 → 还有可翻的，必须可点", bind(totals, stat(1)).isEnabled)
    }

    @Test
    fun fullyTranslatedIsGreyedOut() {
        assertFalse("整章译完 → 必须置灰", bind(totals, stat(4)).isEnabled)
    }

    /** 「删掉某张译文后按钮实时刷新变成可点」——`isDone` 从 stats 算，重推一次就恢复。 */
    @Test
    fun deletingOneBatchReEnablesTheButton() {
        assertFalse("前置：整章译完 → 灰", bind(totals, stat(4)).isEnabled)
        assertTrue(
            "删掉一批译文后宿主重推 stats → 按钮必须立刻变回可点（不能等重开面板）",
            bind(totals, stat(3)).isEnabled,
        )
    }

    /** ⚠️ 跑着 = 「暂停」、暂停 = 「继续」：都必须可点，否则任务停不下来也接不回去。 */
    @Test
    fun runningAndPausedKeepTheButtonClickable() {
        for (state in listOf(ChapterJobState.RUNNING, ChapterJobState.PAUSED)) {
            val job = ChapterJob(chapterIndex = 0, total = 4, done = 4, state = state)
            assertTrue(
                "整章已译完但任务在 $state（按钮是暂停/继续）→ 不能置灰",
                bind(totals, stat(4), job).isEnabled,
            )
        }
    }

    /**
     * ⚠️ 分母拿不到（`totals` 还没解析出来）时**绝不能**判定为"已完成" —— 那会把整本书的
     * 主按钮全禁掉，用户点不动任何一章。`isChapterDone` 里 `total > 0` 那个前置就是干这个的。
     */
    @Test
    fun unknownTotalIsNotTreatedAsDone() {
        val statsNoTotal = mapOf(0 to NovelChapterStat(chapterIndex = 0, total = 0, success = 0))
        assertTrue("total 拿不到 → 不能当成已完成，必须保持可点", bind(emptyMap(), statsNoTotal).isEnabled)
    }
}
