package com.moe.starflow.mangaimport.translate

import android.content.Context
import com.moe.starflow.mangaimport.data.ImportedManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * ⚠️ 2026-10 R6.5：统计从"控制器的两个实例字段"改成**按次调用的产物** [CacheOutcome]
 * （两页并发时字段会互串，而且章节批量路径只写不读、纯污染）。所以这里改成直接调新签名。
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

    private fun ReaderTranslationController.notice(hits: Int, candidates: Int, total: Int): String? =
        cacheNotice(page = 1, total = total, cache = CacheOutcome(candidates = candidates, hits = hits))

    @Test
    fun noCacheHit_returnsNull_soCallerKeepsPlainDoneText() {
        val c = controller()
        assertNull("没有命中缓存就不该改文案", c.notice(hits = 0, candidates = 5, total = 5))
    }

    @Test
    fun allFromCache_saysSoExplicitly() {
        val text = controller().notice(hits = 12, candidates = 12, total = 12)!!
        assertTrue("必须点明一条都没调 API：$text", text.contains("12"))
    }

    @Test
    fun partialCache_reportsBothHitCountAndApiCount() {
        val text = controller().notice(hits = 3, candidates = 12, total = 12)!!
        // 用户要的正是「12 条里面命中 3 条」这个口径
        assertTrue("要给出命中数：$text", text.contains("3"))
        assertTrue("要给出分母：$text", text.contains("12"))
        assertTrue("要给出真正调 API 的条数：$text", text.contains("9"))
    }

    @Test
    fun apiCountIsDerivedFromCandidatesMinusHits_neverNegative() {
        // API 条数 = 候选 - 命中；即便 total 传得离谱也不能出现负数
        val text = controller().notice(hits = 12, candidates = 12, total = 1)!!
        assertTrue("不能出现负数：$text", !text.contains("-"))
    }

    @Test
    fun cacheNotice_neverThrowsOnZeroTotal() {
        assertNotNull(controller().notice(hits = 1, candidates = 1, total = 0))
    }

    /**
     * **接线守卫**：上一轮真的踩过 —— 统计与 `cacheNotice()` 都写好了、单测也全绿，
     * 但成功路径那一行还是 `phase(SUCCESS, null)`，于是**日志有、界面没有**。
     * 这类"少写一句调用"编译不会报、行为测试也不进这条路径（要跑 Room + OCR 引擎）。
     *
     * 所以这里直接盯住源码里的几处接线。改动重命名时同步改这里即可。
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
            text.contains("phase(ReaderTranslatePhase.SUCCESS, cacheNotice(page, translated.size, outcome.cache))")
        )
        assertTrue("必须调用 logBubbles 输出气泡明细", text.contains("logBubbles(page, translated, det, ocr)"))
        assertTrue(
            "统计必须按「本次调用」取回（管线每次 run 新建）",
            text.contains("val stats = CacheOutcome(pipeline.cacheStats.candidates, pipeline.cacheStats.hits)"),
        )
        assertFalse(
            "不允许再往共用字段上累加 —— 两页并发时会串号，章节路径还会污染前台数字",
            text.contains("cacheCandidates +=") || text.contains("cacheCandidates = 0"),
        )
    }
}
