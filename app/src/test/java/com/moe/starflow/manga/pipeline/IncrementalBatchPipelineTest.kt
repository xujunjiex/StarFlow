package com.moe.starflow.manga.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.moe.starflow.manga.TranslationCancelledException
import com.moe.starflow.manga.engine.PPOcrV5Engine
import com.moe.starflow.manga.state.RegionCacheManager
import com.moe.starflow.manga.types.CroppedBubble
import com.moe.starflow.manga.types.CroppedTextLine
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.types.RecResult
import com.moe.starflow.manga.types.TextBlockInfo
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.translate.widget.BallStateManager
import com.moe.starflow.utils.CustomPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.LinkedList

/**
 * 分批管线的编排契约测试。
 *
 * 存在意义：本次是**搬家**类改动，最容易出的错不是编译不过，而是"少调一步"或"顺序变了"
 * —— 编译能过、跑起来不报错、手动测很难穷尽。这里用假引擎记录调用序列，
 * 把"行为零变化"变成可断言的事实。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncrementalBatchPipelineTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** 调用记录。 */
    private data class Call(val name: String, val count: Int = 0)

    private class FakeOps : BatchOcrOps {
        val calls = mutableListOf<Call>()

        /** 每次 detect 返回的行数（决定是否触发分批）。 */
        var lineCount = 10
        var bubbleCount = 10
        var resolveRecLang: PPOcrV5Engine.RecLang? = PPOcrV5Engine.RecLang.JA

        override fun resolveRecLangV5(context: Context, lang: String): Pair<PPOcrV5Engine.RecLang?, String?> {
            calls += Call("resolveRecLangV5")
            return resolveRecLang to null
        }

        override suspend fun detectLinesV5(context: Context, bmp: Bitmap): List<CroppedTextLine> {
            calls += Call("detectLinesV5")
            return fakeLines(lineCount)
        }

        override suspend fun detectLinesV6(context: Context, bmp: Bitmap): List<CroppedTextLine> {
            calls += Call("detectLinesV6")
            return fakeLines(lineCount)
        }

        override suspend fun detectBubblesRTDetr(bmp: Bitmap, keepTextFree: Boolean): List<CroppedBubble> {
            calls += Call("detectBubblesRTDetr")
            return fakeBubbles(bubbleCount)
        }

        override suspend fun recognizeV5(
            context: Context, crops: List<Bitmap>, lang: PPOcrV5Engine.RecLang,
        ): List<RecResult> {
            calls += Call("recognizeV5", crops.size)
            return crops.mapIndexed { i, _ -> RecResult("v5-$i", 1f) }
        }

        override suspend fun recognizeV6(context: Context, crops: List<Bitmap>): List<RecResult> {
            calls += Call("recognizeV6", crops.size)
            return crops.mapIndexed { i, _ -> RecResult("v6-$i", 1f) }
        }

        override suspend fun recognizeCroppedBubbles(
            crops: List<CroppedBubble>, lang: String,
        ): List<TextBlockInfo> {
            calls += Call("recognizeCroppedBubbles", crops.size)
            return crops.mapIndexed { i, _ ->
                TextBlockInfo(
                    text = "rt-$i",
                    boundingBox = Rect(0, i * 100, 80, i * 100 + 80),
                    cornerPoints = null,
                    isVertical = true,
                    centerX = 40f,
                    centerY = i * 100f + 40f,
                )
            }
        }

        /**
         * 造 [n] 行、分成相距很远的两簇 —— 保证 `groupByProximity` 分出 2 组、
         * `splitAtGroupBoundaries` 均分成两批（target = n*2/5）。
         */
        private fun fakeLines(n: Int): List<CroppedTextLine> {
            val half = n / 2
            return (0 until n).map { i ->
                val second = i >= half
                val idx = if (second) i - half else i
                val top = if (second) 600 else 0
                CroppedTextLine(
                    croppedBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
                    rect = Rect(idx * 60, top + idx * 60, idx * 60 + 50, top + idx * 60 + 50),
                    angle = 0f,
                    centerX = idx * 60f + 25f,
                    centerY = top + idx * 60f + 25f,
                )
            }
        }

        private fun fakeBubbles(n: Int): List<CroppedBubble> = (0 until n).map { i ->
            val second = i >= n / 2
            val idx = if (second) i - n / 2 else i
            val top = if (second) 600 else 0
            CroppedBubble(
                croppedBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
                rect = Rect(idx * 60, top + idx * 60, idx * 60 + 50, top + idx * 60 + 50),
                classId = 0,
                confidence = 1f,
            )
        }
    }

    /** 返回固定译文的假翻译器（非 AI → 走 sequential 路径，不依赖网络）。 */
    private class FakeTranslator : TranslationTextAPI {
        override fun getTranslation(
            text: String, sourceLanguage: String, targetLanguage: String,
            callback: (TranslationResult) -> Unit,
        ) = callback(TranslationResult.Success("译:$text"))

        override fun cancelTranslation() {}
        override fun release() {}
    }

    private class FakeHost(
        override val context: Context,
        override val translator: TranslationTextAPI?,
    ) : BatchPipelineHost {
        val progress = mutableListOf<Int>()
        val toasts = mutableListOf<CharSequence>()
        val errors = mutableListOf<CharSequence>()
        val ballStates = mutableListOf<BallStateManager.State>()
        val partialRenders = mutableListOf<Int>()
        val batchResults = mutableListOf<Int>()
        var cancelled = false
        val history = LinkedList<Pair<String, String>>()
        val cache = RegionCacheManager()

        override suspend fun ensureEnginesReady(det: DetEngine, ocr: OcrEngine) {}
        override fun onProgress(textRes: Int) { progress += textRes }
        override fun onToast(text: String, long: Boolean) { toasts += text }
        override fun onError(text: String) { errors += text }
        override fun onBallState(state: BallStateManager.State) { ballStates += state }
        override fun onPartialRender(bubbles: List<TranslatedBubble>) { partialRenders += bubbles.size }
        override suspend fun onBatchResult(bubbles: List<TranslatedBubble>) { batchResults += bubbles.size }
        override fun isCancelled() = cancelled
        override fun contextHistory() = history
        override fun textCache() = cache
    }

    private lateinit var ops: FakeOps

    @Before
    fun setUp() {
        // withContext(Dispatchers.Main) 在管线里被用到；让它在测试中内联执行，避免 Robolectric 主循环死锁
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ops = FakeOps()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun config(
        det: DetEngine = DetEngine.PP_OCR_V5,
        ocr: OcrEngine = OcrEngine.PPOcrV5,
        incremental: Boolean = true,
        autoTranslating: Boolean = false,
    ) = BatchPipelineConfig(
        detEngine = det,
        ocrEngine = ocr,
        sourceLang = "ja",
        targetLang = "zh",
        textDirection = TextDirection.VERTICAL_RL,
        keepTextFree = false,
        prefs = CustomPreference.getInstance(ctx),
        incrementalEnabled = incremental,
        isAutoTranslating = autoTranslating,
    )

    private fun pipeline(host: FakeHost, cfg: BatchPipelineConfig, scope: kotlinx.coroutines.CoroutineScope) =
        IncrementalBatchPipeline(host, scope, cfg, ops)

    private fun bitmap() = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    // ---------- 路由判定 ----------

    @Test
    fun `开关关闭时不适用且不调引擎`() = runTest {
        val host = FakeHost(ctx, FakeTranslator())
        val outcome = pipeline(host, config(incremental = false), this).run(bitmap())
        assertEquals(BatchOutcome.NotApplicable, outcome)
        assertTrue("开关关闭不应触碰引擎", ops.calls.isEmpty())
    }

    @Test
    fun `ML Kit 组合不支持时不适用`() = runTest {
        val host = FakeHost(ctx, FakeTranslator())
        val cfg = config(det = DetEngine.MLKIT, ocr = OcrEngine.MLKit)
        assertEquals(BatchOutcome.NotApplicable, pipeline(host, cfg, this).run(bitmap()))
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun `翻译器未就绪时不适用`() = runTest {
        // translator == null 走不到分批之外的判断，先验证组合判定仍然成立
        val host = FakeHost(ctx, null)
        val cfg = config(det = DetEngine.MLKIT, ocr = OcrEngine.MLKit)
        assertEquals(BatchOutcome.NotApplicable, pipeline(host, cfg, this).run(bitmap()))
    }

    @Test
    fun `行数等于阈值时不适用`() = runTest {
        ops.lineCount = 6   // == INCREMENTAL_THRESHOLD，判定是 > 而非 >=
        val host = FakeHost(ctx, FakeTranslator())
        assertEquals(BatchOutcome.NotApplicable, pipeline(host, config(), this).run(bitmap()))
    }

    @Test
    fun `未检测到文字时已处理且提示`() = runTest {
        ops.lineCount = 0
        val host = FakeHost(ctx, FakeTranslator())
        val outcome = pipeline(host, config(), this).run(bitmap())
        assertEquals(BatchOutcome.Handled(emptyList()), outcome)
        assertEquals(1, host.toasts.size)
    }

    @Test
    fun `自动翻译中未检测到文字不提示`() = runTest {
        ops.lineCount = 0
        val host = FakeHost(ctx, FakeTranslator())
        pipeline(host, config(autoTranslating = true), this).run(bitmap())
        assertTrue("自动翻译中不应弹提示", host.toasts.isEmpty())
    }

    // ---------- 编排顺序 ----------

    @Test
    fun `v5 路线先检测再分批识别`() = runTest {
        ops.lineCount = 10
        val host = FakeHost(ctx, FakeTranslator())
        val outcome = pipeline(host, config(), this).run(bitmap())

        assertTrue(outcome is BatchOutcome.Handled)
        val names = ops.calls.map { it.name }
        // ⚠️ v5 路线第一步是 resolveRecLangV5（与旧代码一致），不是检测 —— 别断言 names.first()
        assertTrue("检测必须早于识别", names.indexOf("detectLinesV5") < names.indexOf("recognizeV5"))
        assertEquals("应分两批各识别一次", 2, names.count { it == "recognizeV5" })
    }

    @Test
    fun `v5 两批体量均分`() = runTest {
        ops.lineCount = 10
        val host = FakeHost(ctx, FakeTranslator())
        pipeline(host, config(), this).run(bitmap())

        val counts = ops.calls.filter { it.name == "recognizeV5" }.map { it.count }
        assertEquals(listOf(5, 5), counts)
    }

    @Test
    fun `v6 路线同样分两批`() = runTest {
        ops.lineCount = 10
        val host = FakeHost(ctx, FakeTranslator())
        val cfg = config(det = DetEngine.PP_OCR_V6, ocr = OcrEngine.PPOcrV6)
        pipeline(host, cfg, this).run(bitmap())

        val names = ops.calls.map { it.name }
        assertEquals("detectLinesV6", names.first())
        assertEquals(2, names.count { it == "recognizeV6" })
        assertTrue("v6 路线不应调 v5 识别", names.none { it == "recognizeV5" })
        assertTrue("v6 路线不应调 v5 语言解析", names.none { it == "resolveRecLangV5" })
    }

    @Test
    fun `rt-detr 路线分两批识别气泡`() = runTest {
        ops.bubbleCount = 10
        val host = FakeHost(ctx, FakeTranslator())
        val cfg = config(det = DetEngine.RT_DETR_V2, ocr = OcrEngine.MangaOcr)
        pipeline(host, cfg, this).run(bitmap())

        val names = ops.calls.map { it.name }
        assertEquals("detectBubblesRTDetr", names.first())
        assertEquals(2, names.count { it == "recognizeCroppedBubbles" })
    }

    @Test
    fun `第一批完整结果会上屏`() = runTest {
        ops.lineCount = 10
        val host = FakeHost(ctx, FakeTranslator())
        pipeline(host, config(), this).run(bitmap())

        assertTrue("首批结果必须上屏（这是分批的意义）", host.batchResults.isNotEmpty())
    }

    // ---------- 上下文回滚 ----------

    @Test
    fun `分批结束后上下文回滚到翻译前`() = runTest {
        ops.lineCount = 10
        val host = FakeHost(ctx, FakeTranslator())
        host.history.add("预置原文" to "预置译文")
        val before = host.history.size

        pipeline(host, config(), this).run(bitmap())

        assertEquals("分批不得污染后续页面的上下文", before, host.history.size)
    }

    // ---------- 取消 ----------

    @Test
    fun `取消时抛 TranslationCancelledException 而非当作失败`() = runTest {
        ops.lineCount = 10
        val host = FakeHost(ctx, FakeTranslator())
        host.cancelled = true

        var thrown = false
        try {
            pipeline(host, config(), this).run(bitmap())
        } catch (e: TranslationCancelledException) {
            thrown = true
        }
        assertTrue("取消必须重抛专用异常，否则会回退重跑 OCR", thrown)
    }
}
