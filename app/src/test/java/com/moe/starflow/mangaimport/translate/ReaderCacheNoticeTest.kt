package com.moe.starflow.mangaimport.translate

import android.content.Context
import com.moe.starflow.mangaimport.data.ImportedManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 「翻译完成（3/12 命中缓存，9 条调用 API）」这条提示的口径。
 *
 * 这是用户明确要的东西：**同页重翻时日志里一条 API 调用都没有，界面必须说得出来这次是缓存**，
 * 而且要说清命中多少条 —— 只写"有缓存"用户没法判断是不是坏了。
 *
 * 分子分母的定义有一处极易写错：分母是「有文字可判定的气泡数」，不是「气泡数」——
 * 只含符号的气泡不走翻译、但也确实没调 API，漏掉它们会让分母比用户数出来的少。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderCacheNoticeTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun controller(): ReaderTranslationController = ReaderTranslationController(
        ctx,
        ImportedManga(id = 1L, title = "t", localRoot = "/tmp/x", isArchive = false,
            coverPath = null, pageCount = 1, addedAt = 1L),
        CoroutineScope(Dispatchers.Unconfined),
    )

    /** 注入统计字段（生产 API 上不暴露它们，测试用反射读私有状态） */
    private fun ReaderTranslationController.setStats(hits: Int, candidates: Int) {
        val cls = ReaderTranslationController::class.java
        cls.getDeclaredField("cacheHits").apply { isAccessible = true }.set(this, hits)
        cls.getDeclaredField("cacheCandidates").apply { isAccessible = true }.set(this, candidates)
    }

    private fun ReaderTranslationController.notice(total: Int): String? {
        val m = ReaderTranslationController::class.java
            .getDeclaredMethod("cacheNotice", Int::class.java, Int::class.java)
        m.isAccessible = true
        return m.invoke(this, 1, total) as String?
    }

    @Test
    fun noCacheHit_returnsNull_soCallerKeepsPlainDoneText() {
        val c = controller().apply { setStats(hits = 0, candidates = 5) }
        assertNull("没有命中缓存就不该改文案", c.notice(total = 5))
    }

    @Test
    fun allFromCache_saysSoExplicitly() {
        val c = controller().apply { setStats(hits = 12, candidates = 12) }
        val text = c.notice(total = 12)!!
        assertTrue("必须点明一条都没调 API：$text", text.contains("12"))
    }

    @Test
    fun partialCache_reportsBothHitCountAndApiCount() {
        val c = controller().apply { setStats(hits = 3, candidates = 12) }
        val text = c.notice(total = 12)!!
        // 用户要的正是「12 条里面命中 3 条」这个口径
        assertTrue("要给出命中数：$text", text.contains("3"))
        assertTrue("要给出分母：$text", text.contains("12"))
        assertTrue("要给出真正调 API 的条数：$text", text.contains("9"))
    }

    @Test
    fun apiCountIsDerivedFromCandidatesMinusHits_neverNegative() {
        // API 条数 = 候选 - 命中；即便 total 传得离谱也不能出现负数
        val c = controller().apply { setStats(hits = 12, candidates = 12) }
        val text = c.notice(total = 1)!!
        assertTrue("不能出现负数：$text", !text.contains("-"))
    }

    @Test
    fun cacheNotice_neverThrowsOnZeroTotal() {
        val c = controller().apply { setStats(hits = 1, candidates = 1) }
        assertNotNull(c.notice(total = 0))
    }

    /**
     * **接线守卫**：上一轮真的踩过 —— 统计与 `cacheNotice()` 都写好了、单测也全绿，
     * 但成功路径那一行还是 `phase(SUCCESS, null)`，于是**日志有、界面没有**。
     * 这类"少写一句调用"编译不会报、行为测试也不进这条路径（要跑 Room + OCR 引擎）。
     *
     * 所以这里直接盯住源码里的三处接线。改动重命名时同步改这里即可。
     */
    @Test
    fun successPathIsWiredToCacheNoticeAndBubbleLog() {
        val src = java.io.File(
            "src/main/java/com/moe/starflow/mangaimport/translate/ReaderTranslationController.kt"
        )
        assertTrue("找不到控制器源码：${src.absolutePath}", src.exists())
        val text = src.readText()
        assertTrue(
            "成功路径必须把缓存提示交给状态浮层（phase(SUCCESS, cacheNotice(...))），" +
                "否则用户只看得到「翻译完成」，分不清这次到底调没调 API",
            text.contains("phase(ReaderTranslatePhase.SUCCESS, cacheNotice(page, translated.size))")
        )
        assertTrue("必须调用 logBubbles 输出气泡明细", text.contains("logBubbles(page, translated)"))
        assertTrue(
            "每页翻译前必须重置缓存统计，否则上一页的命中数会串到下一页",
            text.contains("cacheCandidates = 0") && text.contains("cacheHits = 0")
        )
    }
}
