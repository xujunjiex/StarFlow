package com.moe.starflow.novel.reader

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.novel.model.NovelChapterMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「这一章翻完了吗」的**唯一判据**回归守卫。
 *
 * 用户报过的 bug：「某一章没有翻译完但是会显示已经全部翻译完成」。根因是分母用了
 * 数据库里这一章的**行数**，而行是**按批惰性写的** —— 只翻了几段时 `success >= total`
 * 也成立。面板侧修了以后，**目录侧还留着旧判据**（`st.total`），于是同一章在目录里
 * 写着「已完成」、在面板里写着「进行中」。
 *
 * 现在两处共用 [isChapterDone]，这里把两侧都钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelTocBadgeTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private val stat = NovelChapterStat(chapterIndex = 0, total = 1, success = 1)

    private fun badgeOf(
        stats: Map<Int, NovelChapterStat>,
        totals: Map<Int, Int>,
    ): CharSequence {
        val adapter = NovelTocDialog.RowAdapter(
            chapters = listOf(NovelChapterMeta(0, "第一章", "0,10")),
            stats = stats,
            totals = totals,
            currentChapter = 0,
            dark = false,
            onNeedTotal = null,
            onPick = {},
        )
        val parent: ViewGroup = FrameLayout(ctx)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView.findViewById<TextView>(R.id.tv_chapter_badge).text
    }

    /**
     * 库里只有 1 行（翻过 1 段且成功），真分母是 40 → **不能**显示「已完成」。
     * 把判据改回 `st.total` 这条就红。
     */
    @Test
    fun `分母没到齐时目录不显示已完成`() {
        val done = ctx.getString(R.string.novel_chapter_done)

        val badge = badgeOf(stats = mapOf(0 to stat), totals = mapOf(0 to 40))

        assertFalse("1/40 不该判成已完成，实际=$badge", badge.toString() == done)
        assertEquals(
            ctx.getString(R.string.novel_chapter_partial, 1, 40),
            badge.toString(),
        )
    }

    /** 分母齐了且翻满 → 才算已完成。 */
    @Test
    fun `分母齐了且翻满才算已完成`() {
        val badge = badgeOf(
            stats = mapOf(0 to NovelChapterStat(0, total = 40, success = 40)),
            totals = mapOf(0 to 40),
        )
        assertEquals(ctx.getString(R.string.novel_chapter_done), badge.toString())
    }

    /** 分母还不知道时按「未完成」显示（宁可暂时不显示完成，也不能误报完成）。 */
    @Test
    fun `分母未知时不算已完成`() {
        val badge = badgeOf(
            stats = mapOf(0 to NovelChapterStat(0, total = 1, success = 1)),
            totals = emptyMap(),
        )
        assertFalse(badge.toString() == ctx.getString(R.string.novel_chapter_done))
    }

    /** 目录与面板必须是**同一个结论** —— 两处各写一份判据正是这个 bug 的来源。 */
    @Test
    fun `目录与面板对同一份数据结论一致`() {
        val stats = mapOf(0 to stat)
        val totals = mapOf(0 to 40)

        assertEquals(
            "面板判完成与否",
            isChapterDone(stats, totals, 0),
            badgeOf(stats, totals).toString() == ctx.getString(R.string.novel_chapter_done),
        )
        assertEquals(false, isChapterDone(stats, totals, 0))
    }
}
