package com.moe.starflow.novel.reader

import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.moe.starflow.R
import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.data.NovelFailureRow
import com.moe.starflow.novel.translate.NovelTranslateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **面板状态同步的契约测试**：宿主推一份新状态，面板上**所有**宿主相关控件都必须跟着变。
 *
 * ### 为什么要有它
 * 「某个地方切了 UI 不同步」反复出现 —— 根因是宿主→面板的方向曾经是「每个字段一次推送」，
 * 漏一个就有一处永远停在打开那一刻。现在收敛成 `NovelPanelHostState` + 一个
 * `renderHostState`，这条测试把这个契约钉住：**新增宿主字段时也要在这里加断言**，
 * 否则等于给自己留了一个"又会不同步"的口子。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelPanelSyncTest {

    private fun state(
        chapter: Int = 0,
        mode: NovelTranslateMode = NovelTranslateMode.MANUAL,
        readerMode: Int = NovelPanelStyle.READER_PAGED,
        animation: Int = NovelPanelStyle.ANIM_SLIDE,
        background: Int = 0,
        autoTurn: Boolean = false,
        intervalSec: Int = 5,
        debounceMs: Int = 500,
        aheadBatches: Int = 5,
        batchSize: Int = 3,
        rotate: String = "竖屏",
        keepWhole: Boolean = false,
        stats: Map<Int, NovelChapterStat> = emptyMap(),
        totals: Map<Int, Int> = emptyMap(),
        failures: Map<Int, List<NovelFailureRow>> = emptyMap(),
    ) = NovelPanelHostState(
        chapterIndex = chapter,
        chapterStats = stats,
        chapterTotals = totals,
        chapterFailures = failures,
        translateMode = mode,
        readerMode = readerMode,
        animation = animation,
        background = background,
        autoTurn = autoTurn,
        intervalSec = intervalSec,
        debounceMs = debounceMs,
        aheadBatches = aheadBatches,
        batchSize = batchSize,
        rotateLabel = rotate,
        keepParagraphsWhole = keepWhole,
    )

    /** 挂上面板（走真实 onCreateView + 真实布局），返回它的根视图。 */
    private fun attach(
        chapters: List<com.moe.starflow.novel.model.NovelChapterMeta> = emptyList(),
    ): Pair<NovelPanelSheet, android.view.View> {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val state = NovelPanelState(chapters = chapters)
        val callbacks = NovelPanelCallbacks(
            onReaderMode = {}, onAnimation = {}, onBackground = {},
            onLineSpacing = {}, onParagraphSpacing = {}, onPadding = {},
            onAutoTurn = { _, _ -> }, onRotate = {}, onSettings = {},
        )
        val sheet = NovelPanelSheet(state, callbacks)
        activity.supportFragmentManager.beginTransaction().add(sheet, "panel").commitNow()
        val view = sheet.view ?: error("面板视图没建出来")
        return sheet to view
    }

    @Test
    fun `推一份新状态后面板所有宿主相关控件都跟着变`() {
        val (sheet, v) = attach()

        // 初值：全手动 / 译文 / 左右翻页 / 滑动动画 / 默认背景 / 3 段 5 批
        sheet.renderHostState(state())

        val autoChecked = v.findViewById<RadioButton>(R.id.translate_mode_auto).isChecked
        assertEquals(false, autoChecked)
        assertEquals("3", v.findViewById<TextView>(R.id.tv_batch_value).text)
        // 增量行显示的是**换算后的段数**（"5 批 = 15 段"），不是光秃秃的批数
        assertEquals(
            v.context.getString(R.string.novel_translate_ahead_value, 5, 15),
            v.findViewById<TextView>(R.id.tv_ahead_value).text,
        )
        assertEquals("500 ms", v.findViewById<TextView>(R.id.tv_debounce_value).text)
        assertEquals(false, v.findViewById<Switch>(R.id.sw_keep_paragraphs).isChecked)

        // 换一份**每一项都不同**的状态
        sheet.renderHostState(
            state(
                chapter = 2,
                mode = NovelTranslateMode.AHEAD,
                readerMode = NovelPanelStyle.READER_SCROLL,
                animation = NovelPanelStyle.ANIM_SIMULATION,
                autoTurn = true,
                intervalSec = 8,
                debounceMs = 900,
                aheadBatches = 9,
                batchSize = 7,
                rotate = "横屏",
                keepWhole = true,
                stats = mapOf(2 to NovelChapterStat(2, total = 60, success = 30)),
                totals = mapOf(2 to 60),
            ),
        )

        assertTrue("增量模式要自动选中", v.findViewById<RadioButton>(R.id.translate_mode_incremental).isChecked)
        assertEquals("7", v.findViewById<TextView>(R.id.tv_batch_value).text)
        assertEquals(
            "9 批 × 每批 7 段",
            v.context.getString(R.string.novel_translate_ahead_value, 9, 63),
            v.findViewById<TextView>(R.id.tv_ahead_value).text,
        )
        assertEquals(9, v.findViewById<SeekBar>(R.id.sb_ahead).progress)
        assertEquals("900 ms", v.findViewById<TextView>(R.id.tv_debounce_value).text)
        assertTrue("段落完整开关要跟着变", v.findViewById<Switch>(R.id.sw_keep_paragraphs).isChecked)
        assertTrue("自动翻页开关要跟着变", v.findViewById<Switch>(R.id.sw_auto_turn).isChecked)
        assertEquals("8 s", v.findViewById<TextView>(R.id.tv_interval_value).text)
        assertEquals("横屏", v.findViewById<TextView>(R.id.tv_rotate_value).text)
        // 增量模式才显示配额行
        assertEquals(android.view.View.VISIBLE, v.findViewById<android.view.View>(R.id.row_ahead_chapters).visibility)
    }

    /** 章行：段数分母（宿主解析来的）与失败原因都要能推上去。 */
    @Test
    fun `章行能收到段数与失败原因`() {
        val (sheet, v) = attach()
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_chapters)
        // 章行需要章节标题（本测试只关心文本内容，给一章即可）
        assertTrue(rv != null)

        sheet.renderHostState(
            state(
                chapter = 0,
                stats = mapOf(0 to NovelChapterStat(0, total = 40, success = 12)),
                totals = mapOf(0 to 40),
                failures = mapOf(0 to listOf(NovelFailureRow(0, 3, "TRANSLATE_EMPTY", "原文"))),
            ),
        )

        // 列表是 RecyclerView：这里只断言"数据到达了 adapter"（视图渲染由 RecyclerView 自己负责）
        val adapter = rv.adapter as NovelChapterStateAdapter
        assertEquals(40, adapter.totals[0])
        assertEquals(1, adapter.failures[0]?.size)
    }

    /**
     * **回归**：章行「已翻译」的分母必须是**宿主解析出的真实可翻译段数**，不是数据库里的行数。
     *
     * 库里的行是**按批惰性写的**，拿它当分母的话"翻了几段且都成功"就等于"整章翻完"——
     * 用户报的「某一章没翻完却显示已经全部翻译完成」就是这个。把 `totalOf(index)` 改回
     * `st.total`，这条会红。
     */
    @Test
    fun `章行完成与否按真分母判而不是按库里行数`() {
        val (sheet, v) = attach(
            chapters = listOf(com.moe.starflow.novel.model.NovelChapterMeta(0, "第一章", "0,10")),
        )
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_chapters)
        val adapter = { rv.adapter as NovelChapterStateAdapter }
        adapter().filterKey = 1        // 「已完成」筛选：只看真的翻完的章

        // 库里只有 1 行（翻过 1 段且成功），真分母是 40 → 不算完成
        sheet.renderHostState(
            state(
                chapter = 0,
                stats = mapOf(0 to NovelChapterStat(0, total = 1, success = 1)),
                totals = mapOf(0 to 40),
            ),
        )
        assertTrue("1/40 不该被判成已完成", adapter().visibleIndexes().isEmpty())

        // 真分母到齐且已翻满 → 才算完成
        sheet.renderHostState(
            state(
                chapter = 0,
                stats = mapOf(0 to NovelChapterStat(0, total = 40, success = 40)),
                totals = mapOf(0 to 40),
            ),
        )
        assertTrue("分母齐了且翻满才算完成", adapter().visibleIndexes().contains(0))
    }

    /** 面板不该有自己的第二份真相：渲染完再推同一份状态，控件值不能变回去。 */
    @Test
    fun `重复推同一份状态是幂等的`() {
        val (sheet, v) = attach()
        val s = state(mode = NovelTranslateMode.AUTO, batchSize = 4)
        sheet.renderHostState(s)
        sheet.renderHostState(s)
        assertTrue(v.findViewById<RadioButton>(R.id.translate_mode_auto).isChecked)
        assertEquals("4", v.findViewById<TextView>(R.id.tv_batch_value).text)
    }

}
