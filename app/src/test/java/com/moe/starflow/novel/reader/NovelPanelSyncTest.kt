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
import com.moe.starflow.novel.model.NovelChapterMeta
import com.moe.starflow.novel.translate.NovelBatchWarning
import com.moe.starflow.novel.translate.NovelTranslateMode
import com.moe.starflow.novel.translate.NovelWaitingBatch
import com.moe.starflow.translate.batch.ChapterJob
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.utils.TranslationConcurrency
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
        chars: Map<Int, Int> = emptyMap(),
        jobs: Map<Int, ChapterJob> = emptyMap(),
        waiting: Map<Int, List<NovelWaitingBatch>> = emptyMap(),
        concurrency: Int = TranslationConcurrency.NOVEL_DEFAULT,
        warnIndex: Int = NovelBatchWarning.defaultIndex,
    ) = NovelPanelHostState(
        chapterIndex = chapter,
        chapterStats = stats,
        chapterTotals = totals,
        chapterFailures = failures,
        chapterChars = chars,
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
        chapterJobs = jobs,
        waitingBatches = waiting,
        concurrency = concurrency,
        batchWarnIndex = warnIndex,
    )

    /**
     * **进度那一行要同时给出「已翻几段」和「本章多少字」**（用户指定的位置：字数和共多少段同一行）。
     *
     * 走真实链路：宿主状态 → 面板 → 章行适配器 → 那一行。
     */
    @Test
    fun `章行进度行同时显示段数与字数`() {
        val (sheet, v) = attach(
            chapters = listOf(com.moe.starflow.novel.model.NovelChapterMeta(0, "第一章", "0,10")),
        )
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_chapters)
        val adapter = { rv.adapter as NovelChapterStateAdapter }
        val holder = adapter().onCreateViewHolder(rv, 0)
        val stats = mapOf(0 to NovelChapterStat(0, total = 40, success = 3))
        val line = { holder.itemView.findViewById<TextView>(R.id.tv_fail_message).text.toString() }

        // 字数还没解析出来 → 只有进度，不显示 0 字
        sheet.renderHostState(state(chapter = 0, stats = stats, totals = mapOf(0 to 40)))
        adapter().onBindViewHolder(holder, 0)
        assertEquals(v.context.getString(R.string.novel_chapter_progress, 3, 40), line())

        // 字数回推 → 同一行末尾接上「12,345字」
        sheet.renderHostState(
            state(chapter = 0, stats = stats, totals = mapOf(0 to 40), chars = mapOf(0 to 12345)),
        )
        adapter().onBindViewHolder(holder, 0)
        assertEquals(
            v.context.getString(
                R.string.novel_chapter_progress_chars,
                v.context.getString(R.string.novel_chapter_progress, 3, 40),
                v.context.getString(R.string.novel_chapter_chars, "12,345"),
            ),
            line(),
        )
    }

    /** 字数用**精确值 + 千位分隔**（估费用要准，不能四舍五入成「1.2 万」）。 */
    @Test
    fun `字数标签是精确值带千位分隔`() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        assertTrue(chapterCharLabel(ctx, 999).contains("999"))
        assertTrue(chapterCharLabel(ctx, 1234).contains("1,234"))
        assertTrue(chapterCharLabel(ctx, 12345).contains("12,345"))
    }

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
     * **回归**：「失败」筛选下，新失败的章必须出现在列表里。
     *
     * `failures` 的 setter 若只 `notifyItemRangeChanged`（不 `rebuild()`），缓存过滤表
     * `visible` 就不变 —— 而宿主更新这一行是**两次推送**：一批全部失败时 `stats` 的
     * success/total 一个都没变（data class 相等 → setter 提前 return），真正变的只有 `failures`。
     * 于是用户开着面板、切到「失败」筛选，刚失败的章就是不出来（其余三个 setter 都 rebuild，漏的是这条）。
     */
    @Test
    fun `失败筛选下新失败的章会出现在列表里`() {
        val (sheet, v) = attach(
            chapters = listOf(com.moe.starflow.novel.model.NovelChapterMeta(0, "第一章", "0,10")),
        )
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_chapters)
        val adapter = { rv.adapter as NovelChapterStateAdapter }
        adapter().filterKey = 3

        val stats = mapOf(0 to NovelChapterStat(0, total = 40, success = 0))
        val totals = mapOf(0 to 40)
        sheet.renderHostState(state(chapter = 0, stats = stats, totals = totals))
        assertTrue("还没有失败时不该出现", adapter().visibleIndexes().isEmpty())

        // 只推 failures：stats 与上一次逐字节相同（setter 会提前 return）
        sheet.renderHostState(
            state(
                chapter = 0,
                stats = stats,
                totals = totals,
                failures = mapOf(0 to listOf(NovelFailureRow(0, 3, "HTTP 429", "原文"))),
            ),
        )

        assertTrue("失败筛选下必须出现这一章", adapter().visibleIndexes().contains(0))
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

    // ===== 章节卡片 / 等待行 / 两个新滑块（2026-10 改版）=====

    /**
     * **章卡片两个按钮的文案随任务状态切换**（用户口径）：
     * 没任务 = 翻译本章 / 清除本章译文；跑着 = 暂停 / 取消；暂停了 = 继续 / 取消。
     *
     * ⚠️ 这条同时钉死「宿主推的状态必须立刻反映到按钮上」——面板是打开那一刻的快照，
     * 不重绑就永远停在打开时的文案。
     */
    @Test
    fun `章卡片按钮随任务状态切换`() {
        val (sheet, v) = attach(
            chapters = listOf(NovelChapterMeta(0, "第一章", "0,10")),
        )
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_chapters)
        val adapter = rv.adapter as NovelChapterStateAdapter
        val holder = adapter.onCreateViewHolder(rv, 0)
        fun primary() = holder.itemView.findViewById<TextView>(R.id.btn_chapter_primary).text.toString()
        fun secondary() = holder.itemView.findViewById<TextView>(R.id.btn_chapter_secondary).text.toString()

        sheet.renderHostState(state(chapter = 0, totals = mapOf(0 to 40)))
        adapter.onBindViewHolder(holder, 0)
        assertEquals(v.context.getString(R.string.reader_translate_chapter_translate), primary())
        assertEquals(v.context.getString(R.string.novel_translate_clear_chapter), secondary())

        val running = ChapterJob(0, total = 10, done = 3, state = ChapterJobState.RUNNING, label = "第一章")
        sheet.renderHostState(state(chapter = 0, totals = mapOf(0 to 40), jobs = mapOf(0 to running)))
        adapter.onBindViewHolder(holder, 0)
        assertEquals(v.context.getString(R.string.reader_translate_chapter_pause), primary())
        assertEquals(v.context.getString(R.string.cancel), secondary())

        val paused = running.copy(state = ChapterJobState.PAUSED)
        sheet.renderHostState(state(chapter = 0, totals = mapOf(0 to 40), jobs = mapOf(0 to paused)))
        adapter.onBindViewHolder(holder, 0)
        assertEquals(v.context.getString(R.string.reader_translate_chapter_resume), primary())
        assertEquals(v.context.getString(R.string.cancel), secondary())
    }

    /**
     * **排队中的批要显示成「等待」行**（纯内存态，来自 `ChapterJobRunner.waitingPages`）。
     * 展开的章里：卡片一行 + 每批一行；批行的徽章就是「等待」。
     */
    @Test
    fun `排队中的批显示等待行`() {
        val (sheet, v) = attach(
            chapters = listOf(NovelChapterMeta(0, "第一章", "0,10")),
        )
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_translate_chapters)
        val adapter = rv.adapter as NovelChapterStateAdapter
        val running = ChapterJob(0, total = 10, done = 3, state = ChapterJobState.RUNNING, label = "第一章")

        sheet.renderHostState(
            state(
                chapter = 0, totals = mapOf(0 to 40), jobs = mapOf(0 to running),
                waiting = mapOf(
                    0 to listOf(
                        NovelWaitingBatch(0, ordinal = 1, paraIndexes = listOf(3, 4, 5)),
                        NovelWaitingBatch(0, ordinal = 2, paraIndexes = listOf(6, 7, 8)),
                    ),
                ),
            ),
        )

        assertEquals("卡片 + 两条等待行（当前章默认展开）", 3, adapter.itemCount)
        val batchViewType = adapter.getItemViewType(1)
        val batchHolder = adapter.onCreateViewHolder(rv, batchViewType)
        adapter.onBindViewHolder(batchHolder, 1)
        assertEquals(
            v.context.getString(R.string.reader_translate_state_waiting),
            batchHolder.itemView.findViewById<TextView>(R.id.tv_state_badge).text.toString(),
        )
        val label = batchHolder.itemView.findViewById<TextView>(R.id.tv_page_label).text.toString()
        assertEquals(
            v.context.getString(R.string.novel_translate_batch_row, 2, 4, 6),
            label,
        )
    }

    /** 「同时 API 请求数」与「单批预警阈值」两个滑块必须跟着宿主状态变（含数值文本）。 */
    @Test
    fun `并发数与预警阈值滑块跟着状态变`() {
        val (sheet, v) = attach()

        sheet.renderHostState(state())
        assertEquals(TranslationConcurrency.NOVEL_DEFAULT, v.findViewById<SeekBar>(R.id.sb_concurrency).progress)
        assertEquals(
            "${NovelBatchWarning.DEFAULT_THRESHOLD}",
            v.findViewById<TextView>(R.id.tv_batch_warn_value).text,
        )

        sheet.renderHostState(state(concurrency = 8, warnIndex = 3))
        assertEquals(8, v.findViewById<SeekBar>(R.id.sb_concurrency).progress)
        assertEquals("8", v.findViewById<TextView>(R.id.tv_concurrency_value).text)
        assertEquals(3, v.findViewById<SeekBar>(R.id.sb_batch_warn).progress)
        assertEquals(
            "${NovelBatchWarning.thresholdAt(3)}",
            v.findViewById<TextView>(R.id.tv_batch_warn_value).text,
        )
    }

    /** 旧的两个**全局**按钮必须已经不在面板里了（用户要求整体删掉，能力都挪到章卡片上）。 */
    @Test
    fun `面板里不再有全局翻译与清空按钮`() {
        val (_, v) = attach()
        // ⚠️ 用 `getIdentifier` 而不是 `R.id.xxx`：删掉控件之后 R 里就没有这个字段了，
        // 直接引用会**编译不过**（那正是「已删除」的证明，但测试本身得先能编译）
        fun idOf(name: String): Int =
            v.context.resources.getIdentifier(name, "id", v.context.packageName)

        assertEquals("btn_translate_action 必须已从布局里删除", 0, idOf("btn_translate_action"))
        assertEquals("btn_translate_clear 必须已从布局里删除", 0, idOf("btn_translate_clear"))
        // 反向守卫：新控件必须真的存在（别把整段删空了也算通过）
        assertTrue(idOf("sb_concurrency") != 0)
        assertTrue(idOf("sb_batch_warn") != 0)
    }
}
