package com.moe.starflow.mangaimport.reader

import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import com.moe.starflow.R
import com.moe.starflow.data.ImportedPageSr
import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.translate.ReaderTranslationController
import com.moe.starflow.translate.batch.ChapterJobState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 章卡片的**主按钮可点性**（用户口径 2026-10-01）：
 *
 * > 「超分本章或者翻译本章已经完成就按钮应该置灰，除非用户手动删除了某张的译文或者超分结果，
 * >  这个按钮才应该实时刷新变成可点状态（**只有存在未超分的页面这个按钮才能点击**）」
 *
 * 判据是**「真的有页可做」**，不是"跑没跑过"：整章都成功 → 置灰；任一页被删/失败 → 立刻恢复可点。
 *
 * ⚠️ 两条最容易写错、也最该钉死的：
 * 1. **跑着时必须保持可点** —— 那时按钮是「暂停 / 继续 / 取消」，一起禁掉用户就停不下来了。
 * 2. **状态是从记录表算出来的**（`success >= pageCount`），所以删一页记录后只要重新 `submit`
 *    就会自己变回可点 —— 不需要在删除路径里手动 setEnabled。守卫这条，是为了防止
 *    有人把 `isEnabled` 改成"进入面板时算一次"（那样删完要重开面板才生效）。
 *
 * 用 Robolectric 真绑一遍（而不是 grep 源码）：这里失效的样子是"按钮看着是灰的但点得动"
 * 或反过来 —— 都是 `isEnabled` 有没有真的落到 View 上的问题，源码断言盖不住。
 */
@RunWith(RobolectricTestRunner::class)
class ChapterPrimaryButtonDisabledTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private val chapters = listOf(
        MangaChapter(number = 1, title = "第一话", startPage = 0, pageCount = 2),
        MangaChapter(number = 2, title = "第二话", startPage = 2, pageCount = 3),
    )

    // ========== 超分面板 ==========

    private fun bindSrPrimary(
        records: List<ImportedPageSr>,
        job: ReaderTranslationController.SrChapterJob? = null,
    ): TextView {
        val adapter = ReaderSrStateAdapter(
            chapterLabelOf = { it.title },
            onJump = {}, onSelectChapter = {}, onChapterPrimary = {},
            onChapterSecondary = {}, onDeletePage = {},
        )
        adapter.submit(chapters, records, selectedChapter = 0, job = job)
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView.findViewById(R.id.btn_chapter_primary)
    }

    private fun srSuccess(page: Int) = ImportedPageSr(
        mangaId = 1L, pageIndex = page, state = ImportedPageSr.STATE_SUCCESS,
    )

    @Test
    fun srChapterWithNothingDoneCanRun() {
        assertTrue("一页都没超分，按钮必须可点", bindSrPrimary(emptyList()).isEnabled)
    }

    @Test
    fun srChapterPartiallyDoneCanRun() {
        val btn = bindSrPrimary(listOf(srSuccess(0)))
        assertTrue("第 1 章 2 页只超了 1 页 → 还有未超分的页，必须可点", btn.isEnabled)
    }

    @Test
    fun srChapterFullyDoneIsGreyedOut() {
        val btn = bindSrPrimary(listOf(srSuccess(0), srSuccess(1)))
        assertFalse("整章超分完就没什么可超的 → 必须置灰", btn.isEnabled)
    }

    /** 用户口径里最要紧的那半句：「手动删除了某张的超分结果，按钮才应该**实时刷新**变成可点」。 */
    @Test
    fun srDeletingOnePageReEnablesTheButton() {
        assertFalse(
            "前置：整章超分完 → 灰",
            bindSrPrimary(listOf(srSuccess(0), srSuccess(1))).isEnabled,
        )
        // 删掉第 1 页的记录后宿主会重新 push 一次（`MangaReaderActivity.confirmDeleteSrPage`）
        assertTrue(
            "删掉一页超分结果后重新推一次记录表 → 按钮必须立刻变回可点（不能等重开面板）",
            bindSrPrimary(listOf(srSuccess(1))).isEnabled,
        )
    }

    /** 失败页也算「还可做」：整章 2 页里 1 页成功 1 页失败 → 得让用户重试。 */
    @Test
    fun srFailedPageStillAllowsRetry() {
        val failed = ImportedPageSr(mangaId = 1L, pageIndex = 1, state = ImportedPageSr.STATE_FAILED)
        assertTrue("有失败页 → 必须可点（否则永远重试不了）", bindSrPrimary(listOf(srSuccess(0), failed)).isEnabled)
    }

    /** ⚠️ 跑着时按钮是「取消」，必须保持可点 —— 置灰就没法停了。 */
    @Test
    fun srRunningKeepsTheCancelButtonClickable() {
        val job = ReaderTranslationController.SrChapterJob(chapterIndex = 0, done = 2, total = 2, page = 2)
        val btn = bindSrPrimary(listOf(srSuccess(0), srSuccess(1)), job = job)
        assertTrue("整章已超分但同时有任务在跑（取消入口）→ 不能置灰", btn.isEnabled)
    }

    // ========== 翻译面板 ==========

    private fun bindTrPrimary(
        records: List<ImportedPageTranslation>,
        jobState: ChapterJobState? = null,
    ): TextView {
        val adapter = ReaderPageStateAdapter(
            chapterLabelOf = { it.title },
            onJump = {}, onSelectChapter = {}, onChapterPrimary = {},
            onChapterSecondary = {}, onDeletePage = {},
        )
        adapter.submit(
            chapters = chapters,
            records = records,
            selectedChapter = 0,
            jobs = jobState?.let { mapOf(0 to it) } ?: emptyMap(),
        )
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView.findViewById(R.id.btn_chapter_primary)
    }

    private fun trSuccess(page: Int) = ImportedPageTranslation(
        mangaId = 1L, pageIndex = page, state = ImportedPageTranslation.STATE_SUCCESS,
    )

    @Test
    fun trChapterFullyTranslatedIsGreyedOut() {
        assertFalse(
            "整章译完 → 必须置灰",
            bindTrPrimary(listOf(trSuccess(0), trSuccess(1))).isEnabled,
        )
    }

    @Test
    fun trDeletingOnePageReEnablesTheButton() {
        assertFalse("前置：整章译完 → 灰", bindTrPrimary(listOf(trSuccess(0), trSuccess(1))).isEnabled)
        assertTrue(
            "删掉一页译文后重新推一次记录表 → 按钮必须立刻变回可点",
            bindTrPrimary(listOf(trSuccess(1))).isEnabled,
        )
    }

    @Test
    fun trChapterWithNothingDoneCanRun() {
        assertTrue("一页都没翻，按钮必须可点", bindTrPrimary(emptyList()).isEnabled)
    }

    /** ⚠️ 跑着 = 「暂停」、暂停 = 「继续」，两者都必须可点，否则任务停不下来也接不回去。 */
    @Test
    fun trRunningAndPausedKeepTheButtonClickable() {
        val done = listOf(trSuccess(0), trSuccess(1))
        assertTrue(
            "整章已译完但任务在跑（按钮是暂停）→ 不能置灰",
            bindTrPrimary(done, jobState = ChapterJobState.RUNNING).isEnabled,
        )
        assertTrue(
            "暂停态（按钮是继续）→ 不能置灰",
            bindTrPrimary(done, jobState = ChapterJobState.PAUSED).isEnabled,
        )
    }
}
