package com.moe.starflow.novel.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 小说阅读器**翻译行为**的源码级守卫（不需要设备；纯 JVM 读源码断言）。
 *
 * 这里钉的都是用户在真机上反复报过、且"改回去很难被发现"的几条口径：
 * 1. **切后台 = 关掉自动/增量 + 回退手动**（2026-10-04 用户口径，撤销了 2026-09 的"只暂停"）
 * 2. **清译文 = 一次显式编辑**：停前台队列 + 回退手动；队列里**不许**再有"清过的段别再自动翻"的账
 * 3. **等页面稳定**：翻屏 **或** 上一批收尾都要重排一次等待；黄底只画"当前屏真的在飞"的段
 * 4. **整章任务要有 app 内的进度提示**（状态浮层，用户说的"app 的通知系统"）
 */
class NovelReaderTranslateRulesTest {

    private fun activity(): String = source("com/moe/starflow/novel/reader/NovelReaderActivity.kt")
    private fun queue(): String = source("com/moe/starflow/novel/translate/NovelTranslationQueue.kt")

    private fun source(path: String): String {
        val f = File("src/main/java/$path")
        assertTrue("找不到源码 $path（工作目录=${File(".").absolutePath}）", f.exists())
        return f.readText()
    }

    /** 切后台与离开阅读器**同一套**：不再按 `isFinishing` 分叉、不再只暂停。 */
    @Test
    fun `切后台也要关掉自动增量并回退手动`() {
        val src = activity()
        assertFalse(
            "`isFinishing` 不能再作为'只暂停'的分支条件（用户 2026-10-04 明确撤销了那个口径）",
            src.contains("val leavingReader = isFinishing"),
        )
        assertFalse("不再有'切后台只暂停'的通知分支", src.contains("notifyBackground"))
        assertTrue(
            "停在翻时把模式回退成手动（切后台/退出都一样）",
            Regex("""if \(wasTranslating\) \{\s*NovelPanelStyle\.setTranslateMode\(prefs, NovelTranslateMode\.MANUAL\)""")
                .containsMatchIn(src),
        )
    }

    /** 清译文 = 显式编辑：停前台队列 + 回退手动；队列里不再有"别再自动翻"的账。 */
    @Test
    fun `清译文停队列且队列里没有跳过账`() {
        val act = activity()
        val q = queue()
        assertTrue(
            "清译文后必须停前台队列并回退手动（否则要么立刻又翻回来、要么再开启时被静默跳过）",
            act.contains("pauseToManual()"),
        )
        assertFalse("队列不许再有跳过账的 API", q.contains("skipParagraphs"))
        assertFalse("队列不许再有跳过账的字段", q.contains("userClearedParaIndexes"))
        assertTrue(
            "自动/增量的 done 集合只由 已译 + failedAnchors + 在飞 组成",
            q.contains("translated + failedAnchors"),
        )
    }

    /** 等页面稳定：翻屏 / 上批收尾都要排等待；黄底只留当前屏的。 */
    @Test
    fun `等页面稳定且黄底只画当前屏`() {
        val q = queue()
        assertTrue("翻屏置位", q.contains("debouncedSpanKey = spanKey"))
        assertTrue("翻屏与收尾用两个 AtomicBoolean（普通 var 会丢更新）", q.contains("AtomicBoolean(false)"))
        assertTrue("翻屏那个标志", q.contains("spanChanged.set(true)"))
        assertTrue(
            "有批收尾（在飞清零）也要置位",
            Regex("""if \(idle\) settled\.set\(true\)""").containsMatchIn(q),
        )
        assertTrue(
            "调度侧分别消费这两个位",
            q.contains("spanChanged.getAndSet(false)") && q.contains("settled.getAndSet(false)"),
        )
        assertTrue("在飞清零的判据是 inflight.isEmpty()", q.contains("inflight.isEmpty()"))
        assertTrue("等待期间要留日志（用户要能对上时间）", q.contains("页面稳定等待"))
        assertTrue(
            "等待期间要有独立阶段（界面上才能显示「等待页面稳定…」）",
            q.contains("phase = NovelQueuePhase.WAITING_STABLE"),
        )
        assertTrue(
            "界面上要把这一态显示出来",
            activity().contains("R.string.novel_queue_waiting_stable"),
        )
        assertTrue(
            "翻屏时把不在本屏的在飞批从高亮里摘掉（用户口径：一直翻页不该有黄底）",
            q.contains("cur.copy(batchParaIndexes = shown)"),
        )
        assertTrue(
            "一批落定立刻从高亮里摘掉（出结果立刻擦）",
            q.contains("cur.copy(batchParaIndexes = remaining)"),
        )
    }

    /** 整章任务要有 app 内的进度提示（同一条状态浮层）。 */
    @Test
    fun `整章任务在浮层上有进度提示`() {
        val src = activity()
        assertTrue("整章进度刷新的函数", src.contains("private fun refreshChapterJobChips()"))
        assertTrue(
            "走状态浮层的追加式提示（可与别的提示并存），而不是替换顶部那条",
            src.contains("showRunning(text)"),
        )
        assertTrue(
            "进度变化时要刷新",
            Regex("""onChapterJobProgress\(\)[\s\S]{0,600}refreshChapterJobChips\(\)""").containsMatchIn(src),
        )
        assertTrue(
            "章任务流也要刷新（面板里也能看到）",
            src.contains("chapterJobs?.collect { pushPanelState(); refreshChapterJobChips() }"),
        )
    }

    /** 手动空闲双击快捷开启自动；运行中的自动队列仍由双击/面板退出到手动。 */
    @Test
    fun `手动空闲双击开启自动且面板仍退出队列`() {
        val src = activity()
        assertTrue("空闲单击必须延迟一个双击窗口，避免先启动手动批次", src.contains("postDelayed(action, DOUBLE_CLICK_MS)"))
        assertTrue("第二击必须走快捷自动分支", src.contains("startAutoFromTranslateButton()"))
        assertTrue("快捷分支要保存自动模式", src.contains("NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.AUTO)"))
        assertTrue("自动队列中的双击继续回退手动", src.contains("pauseToManual(getString(R.string.reader_translate_force_stopped_to_manual))"))
        assertTrue("开面板仍走既有停止入口", src.contains("queue?.setPanelOpen(true)"))
        assertTrue("文本选择态不能被自动模式双击抢走", src.contains("if (selecting && selectedPara.isNotEmpty())"))
    }
}
