package com.moe.starflow.manga.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import com.moe.starflow.R
import com.moe.starflow.manga.TranslateUtils
import com.moe.starflow.manga.TranslationCancelledException
import com.moe.starflow.manga.engine.PPOcrV5Engine
import com.moe.starflow.manga.engine.PPOcrV6Engine
import com.moe.starflow.manga.merge.MangaSpatialGrouping
import com.moe.starflow.manga.merge.PPOcrPostProcessing
import com.moe.starflow.manga.merge.TextRegionMerger
import com.moe.starflow.manga.merge.toTextRegion
import com.moe.starflow.manga.state.RegionCacheManager
import com.moe.starflow.manga.types.BubbleRegion
import com.moe.starflow.manga.types.CroppedTextLine
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.types.TextBlockInfo
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TextRegionGroup
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.translate.widget.BallStateManager
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import translationapi.hymt2translation.HyMT2Translation

/**
 * 分批翻译管线（从 `MangaFloatingService` 抽出，行为保持不变）。
 *
 * 同一份代码供两处使用：
 * - **截屏翻译**：宿主是 `MangaFloatingService`（有悬浮球 / 结果浮层 / 缓存表）
 * - **阅读器**（Spec 2）：宿主是 `ReaderTranslationController`（写页记录 / 渲染到页）
 *
 * 分工：
 * - 本管线负责**编排**：该不该分批、怎么切两批、两批 OCR 怎么并行、批次间上下文怎么回滚
 * - 宿主负责**副作用**：进度提示、错误上报、结果上屏、缓存
 * - `finalizeIncremental` 等收尾逻辑**不在这里** —— 它属于宿主
 */
class IncrementalBatchPipeline(
    private val host: BatchPipelineHost,
    /** 原 `lifecycleScope`。 */
    private val scope: CoroutineScope,
    /** 本次任务的不变入参。 */
    private val config: BatchPipelineConfig,
    private val ops: BatchOcrOps = RealBatchOcrOps,
) {

    companion object {
        private const val TAG = "IncrementalBatch"

        /** 触发分批的气泡数量阈值（原 `MangaFloatingService.INCREMENTAL_THRESHOLD`）。 */
        private const val INCREMENTAL_THRESHOLD = 6
    }

    // ========== 总闸 ==========

    /**
     * 尝试走分批路径。
     *
     * 返回 [BatchOutcome.NotApplicable] 时调用方应回退到普通单批流程
     * （包含"气泡太少"与"中途出错"两种情况，与旧 `return false` 语义一致）。
     */
    suspend fun run(bitmap: Bitmap): BatchOutcome {
        // Hy-MT2 本地引擎：不走分批渲染（合并一次翻译 + 流式逐个显示，避免多次 prefill 拖慢）
        if (host.translator is HyMT2Translation) {
            LogCollector.d(TAG, "run: Hy-MT2 禁用分批渲染，走普通一次翻译+流式")
            return BatchOutcome.NotApplicable
        }
        if (!config.incrementalEnabled) return BatchOutcome.NotApplicable

        val isRTDetrMangaOcr = config.detEngine == DetEngine.RT_DETR_V2 && config.ocrEngine == OcrEngine.MangaOcr
        val isPPOcrV5Standalone = config.detEngine == DetEngine.PP_OCR_V5 && config.ocrEngine == OcrEngine.PPOcrV5
        val isPPOcrV6Standalone = config.detEngine == DetEngine.PP_OCR_V6 && config.ocrEngine == OcrEngine.PPOcrV6
        if (!isRTDetrMangaOcr && !isPPOcrV5Standalone && !isPPOcrV6Standalone) return BatchOutcome.NotApplicable

        return if (isRTDetrMangaOcr) {
            rtDetrMangaOcr(bitmap)
        } else if (isPPOcrV6Standalone) {
            ppOcrV6(bitmap)
        } else {
            ppOcrV5(bitmap)
        }
    }

    // ========== 两批编排骨架 ==========

    /**
     * 两批并行 OCR + 翻译 + 合并 + 上下文回滚 公共骨架（原 `translateFirstThenSecondBatch`）。
     * 调用方负责：第一批 OCR（[firstBubbleRegions]）、第二批 OCR 异步任务（[secondOcrJob]）的启动与取消。
     */
    /**
     * 文本级缓存的命中统计。
     *
     * [candidates] 刻意大于「气泡数」：只含符号/空白的气泡不走翻译，但它们也是"这次没调 API"
     * 的一条 —— 不计入的话「3/12」的分母会比用户数出来的气泡少，看起来像漏报。
     */
    class TextCacheStats {
        var candidates: Int = 0
            internal set
        var hits: Int = 0
            internal set
    }

    /** 分批翻译结果：译文 + 「有没有识别出任何文字块」（决定空结果算"未检测到文字"还是"翻译没产出"）。 */
    private data class BatchTranslationResult(
        val bubbles: List<TranslatedBubble>,
        val recognizedAny: Boolean,
    )

    private suspend fun translateFirstThenSecondBatch(
        firstBubbleRegions: List<BubbleRegion>,
        secondOcrJob: Deferred<List<TextBlockInfo>>,
    ): BatchTranslationResult {
        // 保存上下文历史大小，分批翻译完后回滚，避免污染后续页面的上下文
        val history = host.contextHistory()
        val contextSnapshotSize = history.size
        suspend fun markTranslating() = withContext(Dispatchers.Main) {
            host.onProgress(R.string.translating_do_not_tap)
            host.onBallState(BallStateManager.State.Translating)
        }

        val firstTranslated = if (firstBubbleRegions.isEmpty()) {
            emptyList()
        } else {
            markTranslating()
            val result = translateWithCache(firstBubbleRegions, forceContext = true) { partialBubbles ->
                if (partialBubbles.isNotEmpty()) {
                    host.onPartialRender(partialBubbles)
                }
            }
            if (result.isNotEmpty()) {
                host.onBatchResult(result)
            }
            result
        }

        // ⚠️ 第二批**必须**等出来并翻译：第一批识别为空（整组被合并/丢弃）时旧写法直接返回，
        // 第二批既不 OCR 也不翻译、裁剪图也不回收，而服务侧照样按「翻译完成」收尾
        // （盖 lastTranslatedHash）→ 这半页永远翻不回来，自动翻译下更是从此跳过该页
        val secondTextBlocks = secondOcrJob.await()
        LogCollector.d(TAG, "第二批 OCR ${secondTextBlocks.size} 个文字块")
        val secondTranslated = if (secondTextBlocks.isEmpty()) {
            emptyList()
        } else {
            if (firstTranslated.isEmpty()) markTranslating()  // 第一批没内容 → 这里补上翻译中状态
            val secondBubbleRegions = MangaSpatialGrouping.textBlocksToBubbleRegions(
                secondTextBlocks, config.renderTextDirection
            )
            translateWithCache(secondBubbleRegions, forceContext = true) { partialBubbles ->
                if (partialBubbles.isNotEmpty()) {
                    host.onPartialRender(partialBubbles)
                }
            }
        }

        // 回滚分批渲染添加的上下文，只保留翻译前的历史
        while (history.size > contextSnapshotSize) {
            history.removeLast()
        }
        return BatchTranslationResult(
            bubbles = firstTranslated + secondTranslated,
            recognizedAny = firstBubbleRegions.isNotEmpty() || secondTextBlocks.isNotEmpty(),
        )
    }

    /**
     * 取消并行 OCR 并**等它真正退出**后才返回。
     *
     * ⚠️ 不能只 `cancel()` 就去回收裁剪图：`cancel()` 只是置协程取消标志，而引擎的
     * `recognizeBatchWithCls` 是**同步 native 调用**（`synchronized` 块里），不会在调用中途
     * 响应取消 —— worker 可能仍在读那些像素缓冲，此时 `recycle()` 就是 use-after-recycle，
     * 表现为 native 崩溃/garbage（不是 Java 层能捕获的 IllegalStateException）。
     * 必须 join 等它退出后才安全回收。
     *
     * 用 [NonCancellable]：调用方自己可能正处于取消状态，否则 `cancelAndJoin` 会立刻抛出。
     */
    private suspend fun cancelAndJoinQuietly(job: Deferred<*>?) {
        if (job == null) return
        withContext(NonCancellable) {
            runCatching { job.cancelAndJoin() }
        }
    }

    // ========== 路线③ RT-DETR-V2 + MangaOcr ==========

    /** 检测气泡 → 分批 MangaOcr encoder+decoder → 翻译+渲染。 */
    private suspend fun rtDetrMangaOcr(bitmap: Bitmap): BatchOutcome {
        host.ensureEnginesReady(config.detEngine, config.ocrEngine)

        LogCollector.d(TAG, "rtDetrMangaOcr: 开始检测+裁剪, keepTextFree=${config.keepTextFree}, rtDirection=${config.rtTextDirection}")
        val croppedBubbles = ops.detectBubblesRTDetr(bitmap, config.keepTextFree)
        if (croppedBubbles.isEmpty()) {
            LogCollector.d(TAG, "rtDetrMangaOcr: 未检测到气泡")
            if (!config.isAutoTranslating) {
                withContext(Dispatchers.Main) { host.onToast(host.context.getString(R.string.no_text_found), true) }
            }
            // 旧实现此处直接 return true（不调 finalizeIncremental）—— 见 BatchOutcome.HandledEmpty
            return BatchOutcome.HandledEmpty
        }

        if (croppedBubbles.size <= INCREMENTAL_THRESHOLD) {
            LogCollector.d(TAG, "rtDetrMangaOcr: ${croppedBubbles.size} <= $INCREMENTAL_THRESHOLD，不触发")
            // ⚠️ 不回收裁剪图：交给调用方复用（少跑一次整页 RT 检测）。见 BatchOutcome.DetectedNotBatched
            return BatchOutcome.DetectedNotBatched(croppedBubbles)
        }

        val sorted = MangaSpatialGrouping.sortByMangaReadingOrder(croppedBubbles)
        val groups = MangaSpatialGrouping.groupByProximity(sorted, { it.rect }, "RT-DETR")
        val (firstBatch, secondBatch) = MangaSpatialGrouping.splitAtGroupBoundaries(groups)
        LogCollector.d(TAG, "rtDetrMangaOcr: 第一批 ${firstBatch.size}，第二批 ${secondBatch.size}")

        var ocrJob: Deferred<List<TextBlockInfo>>? = null
        try {
            host.onProgress(R.string.recognizing_half)
            val firstTextBlocks = ops.recognizeCroppedBubbles(firstBatch, config.sourceLang, config.rtTextDirection)
            LogCollector.d(TAG, "rtDetrMangaOcr: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            val firstBubbleRegions = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                MangaSpatialGrouping.textBlocksToBubbleRegions(firstTextBlocks, config.renderTextDirection)
            }
            val ocr = scope.async(Dispatchers.IO) {
                ops.recognizeCroppedBubbles(secondBatch, config.sourceLang, config.rtTextDirection)
            }
            ocrJob = ocr
            val batch = translateFirstThenSecondBatch(firstBubbleRegions, ocr)

            // 两批都没识别出文字 → 与「未检测到文字」同义：走 HandledEmpty（不盖 lastTranslatedHash）。
            // 只翻不出东西（识别到了但翻译没产出）仍按 Handled 收尾，避免失败页被无脑重试
            if (batch.bubbles.isEmpty() && !batch.recognizedAny) {
                LogCollector.d(TAG, "rtDetrMangaOcr: 两批均无文字块")
                if (!config.isAutoTranslating) {
                    withContext(Dispatchers.Main) {
                        host.onToast(host.context.getString(R.string.no_text_found), true)
                    }
                }
                return BatchOutcome.HandledEmpty
            }
            return BatchOutcome.Handled(batch.bubbles)
        } catch (e: TranslationCancelledException) {
            // 用户停止翻译：重抛让 collector 识别为取消，绝不回退重新 OCR
            cancelAndJoinQuietly(ocrJob)
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（退出阅读器 / 关服务 / 双击暂停）：CancellationException 也是 Exception，
            // 落到下面那个 catch 就会被记成「批次失败」并回退重跑 OCR（本文件的单批识别
            // ocrBatch* 第 366/408 行就是这么处理的，这里必须一致）——重抛才是取消的语义
            cancelAndJoinQuietly(ocrJob)
            // 取消同样要回收第二批裁剪图：它不属于任何缓存，不回收就等 GC，下一批还要再分配一批
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "rtDetrMangaOcr: 失败", e)
            cancelAndJoinQuietly(ocrJob)
            // ⚠️ 首批裁剪图也要回收：`recognizeCroppedBubbles` 只在**识别成功后**回收，
            // 识别本身抛异常（引擎未初始化 / native 异常）时首批就漏在这儿了。
            // isRecycled 守卫保证成功过的那批（已回收）不会重复回收。
            firstBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            return BatchOutcome.NotApplicable
        }
    }

    // ========== 路线① PP-OCRv5 独立 ==========

    /** det 检测全部文字行 → 逐行裁剪 → 分批 OCR + TextLineMerger 合并 → 翻译+渲染。 */
    private suspend fun ppOcrV5(bitmap: Bitmap): BatchOutcome {
        host.ensureEnginesReady(config.detEngine, config.ocrEngine)

        val (ppRecLang, hint) = ops.resolveRecLangV5(host.context, config.sourceLang)
        if (hint != null) withContext(Dispatchers.Main) { host.onToast(hint, true) }
        // 非默认模型时提示
        if (ppRecLang != null && ppRecLang != PPOcrV5Engine.RecLang.ZH && ppRecLang != PPOcrV5Engine.RecLang.JA) {
            withContext(Dispatchers.Main) {
                host.onToast(host.context.getString(R.string.toast_using_dedicated_model, ppRecLang.code), false)
            }
        }
        if (ppRecLang == null) return BatchOutcome.NotApplicable

        LogCollector.d(TAG, "ppOcrV5: 开始检测")
        val textLines = ops.detectLinesV5(host.context, bitmap, config.textDirection)
        if (textLines.isEmpty()) {
            LogCollector.d(TAG, "ppOcrV5: 未检测到文字")
            if (!config.isAutoTranslating) {
                withContext(Dispatchers.Main) { host.onToast(host.context.getString(R.string.no_text_found), true) }
            }
            // 旧实现此处直接 return true（不调 finalizeIncremental）—— 见 BatchOutcome.HandledEmpty
            return BatchOutcome.HandledEmpty
        }

        if (textLines.size <= INCREMENTAL_THRESHOLD) {
            LogCollector.d(TAG, "ppOcrV5: ${textLines.size} <= $INCREMENTAL_THRESHOLD，不触发")
            textLines.forEach { it.croppedBitmap.recycle() }
            return BatchOutcome.NotApplicable
        }

        val groups = MangaSpatialGrouping.groupByProximity(textLines, { it.rect }, "PP-OCRv5")
        val (firstBatch, secondBatch) = MangaSpatialGrouping.splitAtGroupBoundaries(groups)
        LogCollector.d(TAG, "ppOcrV5: 第一批 ${firstBatch.size} 行，第二批 ${secondBatch.size} 行")

        var ocrJob: Deferred<List<TextBlockInfo>>? = null
        try {
            host.onProgress(R.string.recognizing_half)
            val firstTextBlocks = recognizePpBatchV5(firstBatch, ppRecLang)
            LogCollector.d(TAG, "ppOcrV5: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            val firstBubbleRegions = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                MangaSpatialGrouping.textBlocksToBubbleRegions(firstTextBlocks, config.textDirection)
            }
            val ocr = scope.async(Dispatchers.IO) { recognizePpBatchV5(secondBatch, ppRecLang) }
            ocrJob = ocr
            val batch = translateFirstThenSecondBatch(firstBubbleRegions, ocr)

            if (batch.bubbles.isEmpty() && !batch.recognizedAny) {
                LogCollector.d(TAG, "ppOcrV5: 两批均无文字块")
                if (!config.isAutoTranslating) {
                    withContext(Dispatchers.Main) {
                        host.onToast(host.context.getString(R.string.no_text_found), true)
                    }
                }
                return BatchOutcome.HandledEmpty
            }
            return BatchOutcome.Handled(batch.bubbles)
        } catch (e: TranslationCancelledException) {
            // 用户停止翻译：重抛让 collector 识别为取消，绝不回退重新 OCR
            cancelAndJoinQuietly(ocrJob)
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（退出阅读器 / 关服务 / 双击暂停）：CancellationException 也是 Exception，
            // 落到下面那个 catch 就会被记成「批次失败」并回退重跑 OCR（本文件的单批识别
            // ocrBatch* 第 366/408 行就是这么处理的，这里必须一致）——重抛才是取消的语义
            cancelAndJoinQuietly(ocrJob)
            // 取消同样要回收第二批裁剪图：它不属于任何缓存，不回收就等 GC，下一批还要再分配一批
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "ppOcrV5: 失败", e)
            // 必须先 join 等第二批 OCR 真正退出，再回收它的裁剪图（见 cancelAndJoinQuietly）
            cancelAndJoinQuietly(ocrJob)
            // 回收未处理的裁剪图片（firstBatch 已在 recognizePpBatchV5 内部回收，跳过）
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            return BatchOutcome.NotApplicable
        }
    }

    // ========== 路线② PP-OCRv6 独立 ==========

    private suspend fun ppOcrV6(bitmap: Bitmap): BatchOutcome {
        host.ensureEnginesReady(config.detEngine, config.ocrEngine)

        LogCollector.d(TAG, "ppOcrV6: 开始检测")
        val textLines = ops.detectLinesV6(host.context, bitmap, config.textDirection)
        if (textLines.isEmpty()) {
            LogCollector.d(TAG, "ppOcrV6: 未检测到文字")
            if (!config.isAutoTranslating) {
                withContext(Dispatchers.Main) { host.onToast(host.context.getString(R.string.no_text_found), true) }
            }
            // 旧实现此处直接 return true（不调 finalizeIncremental）—— 见 BatchOutcome.HandledEmpty
            return BatchOutcome.HandledEmpty
        }

        if (textLines.size <= INCREMENTAL_THRESHOLD) {
            LogCollector.d(TAG, "ppOcrV6: ${textLines.size} <= $INCREMENTAL_THRESHOLD，不触发")
            textLines.forEach { it.croppedBitmap.recycle() }
            return BatchOutcome.NotApplicable
        }

        val groups = MangaSpatialGrouping.groupByProximity(textLines, { it.rect }, "PP-OCRv6")
        val (firstBatch, secondBatch) = MangaSpatialGrouping.splitAtGroupBoundaries(groups)
        LogCollector.d(TAG, "ppOcrV6: 第一批 ${firstBatch.size} 行，第二批 ${secondBatch.size} 行")

        var ocrJob: Deferred<List<TextBlockInfo>>? = null
        try {
            host.onProgress(R.string.recognizing_half)
            val firstTextBlocks = recognizePpBatchV6(firstBatch)
            LogCollector.d(TAG, "ppOcrV6: 第一批 OCR ${firstTextBlocks.size} 个文字块")

            val firstBubbleRegions = if (firstTextBlocks.isEmpty()) {
                emptyList()
            } else {
                MangaSpatialGrouping.textBlocksToBubbleRegions(firstTextBlocks, config.textDirection)
            }
            val ocr = scope.async(Dispatchers.IO) { recognizePpBatchV6(secondBatch) }
            ocrJob = ocr
            val batch = translateFirstThenSecondBatch(firstBubbleRegions, ocr)

            if (batch.bubbles.isEmpty() && !batch.recognizedAny) {
                LogCollector.d(TAG, "ppOcrV6: 两批均无文字块")
                if (!config.isAutoTranslating) {
                    withContext(Dispatchers.Main) {
                        host.onToast(host.context.getString(R.string.no_text_found), true)
                    }
                }
                return BatchOutcome.HandledEmpty
            }
            return BatchOutcome.Handled(batch.bubbles)
        } catch (e: TranslationCancelledException) {
            cancelAndJoinQuietly(ocrJob)
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（退出阅读器 / 关服务 / 双击暂停）：CancellationException 也是 Exception，
            // 落到下面那个 catch 就会被记成「批次失败」并回退重跑 OCR（本文件的单批识别
            // ocrBatch* 第 366/408 行就是这么处理的，这里必须一致）——重抛才是取消的语义
            cancelAndJoinQuietly(ocrJob)
            // 取消同样要回收第二批裁剪图：它不属于任何缓存，不回收就等 GC，下一批还要再分配一批
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "ppOcrV6: 失败", e)
            // 必须先 join 等第二批 OCR 真正退出，再回收它的裁剪图（见 cancelAndJoinQuietly）
            cancelAndJoinQuietly(ocrJob)
            secondBatch.forEach { if (!it.croppedBitmap.isRecycled) it.croppedBitmap.recycle() }
            return BatchOutcome.NotApplicable
        }
    }

    // ========== 单批识别（PP-OCRv5 / v6）==========

    /** 识别单批：OCR → TextRegionMerger 合并 → TextBlockInfo（原 `incrementalPPOcrV5.recognizeBatch`）。 */
    private suspend fun recognizePpBatchV5(
        batch: List<CroppedTextLine>,
        lang: PPOcrV5Engine.RecLang,
    ): List<TextBlockInfo> {
        val crops = batch.map { it.croppedBitmap }
        val rects = batch.map { it.rect }
        val angles = batch.map { it.angle }
        val centers = batch.map { PointF(it.centerX, it.centerY) }
        val recResults = try {
            withContext(Dispatchers.IO) {
                ops.recognizeV5(host.context, crops, lang)
            }
        } catch (e: java.io.FileNotFoundException) {
            crops.forEach { it.recycle() }
            host.onError(host.context.getString(R.string.error_rec_model_load_failed, e.message))
            host.onBallState(BallStateManager.State.Error)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消（用户停止翻译 / 新任务取代第二批 OCR）：不是模型错误，直接重抛不显示错误
            crops.forEach { it.recycle() }
            throw e
        } catch (e: Exception) {
            crops.forEach { it.recycle() }
            host.onError(host.context.getString(R.string.error_rec_model_exception, e.message))
            host.onBallState(BallStateManager.State.Error)
            throw e
        }
        // 释放裁剪图片
        crops.forEach { it.recycle() }
        // TextRegionMerger 识别后合并
        val mergedInput = PPOcrV5Engine.recResultsToTextLines(recResults, rects, angles, centers)
        TextRegionMerger.refreshParams(host.context)
        val allMerged = TextRegionMerger.merge(
            mergedInput.map { it.toTextRegion() }, verticalDirection = config.textDirection
        )
        // 合并后内容过滤
        val (mergedRegions, contentDiscarded) = PPOcrPostProcessing.filterMergedRegions(allMerged)
        LogCollector.d(
            TAG,
            "recognizePpBatchV5: ${mergedInput.size} 行 → ${allMerged.size} 合并 → 内容丢弃${contentDiscarded.size} → ${mergedRegions.size} 输出"
        )
        return mergedRegions.map { it.toTextBlockInfo() }.filter { it.text.isNotBlank() }
    }

    /** 识别单批：OCR → TextRegionMerger 合并 → TextBlockInfo（原 `incrementalPPOcrV6.recognizeBatch`）。 */
    private suspend fun recognizePpBatchV6(batch: List<CroppedTextLine>): List<TextBlockInfo> {
        val crops = batch.map { it.croppedBitmap }
        val rects = batch.map { it.rect }
        val angles = batch.map { it.angle }
        val centers = batch.map { PointF(it.centerX, it.centerY) }
        val recResults = try {
            withContext(Dispatchers.IO) {
                ops.recognizeV6(host.context, crops)
            }
        } catch (e: java.io.FileNotFoundException) {
            crops.forEach { it.recycle() }
            host.onError(host.context.getString(R.string.error_rec_model_load_failed, e.message))
            host.onBallState(BallStateManager.State.Error)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            crops.forEach { it.recycle() }
            throw e
        } catch (e: Exception) {
            crops.forEach { it.recycle() }
            host.onError(host.context.getString(R.string.error_rec_model_exception, e.message))
            host.onBallState(BallStateManager.State.Error)
            throw e
        }
        crops.forEach { it.recycle() }
        val mergedInput = PPOcrV6Engine.recResultsToTextLines(recResults, rects, angles, centers)
        TextRegionMerger.refreshParams(host.context)
        val allMerged = TextRegionMerger.merge(
            mergedInput.map { it.toTextRegion() }, verticalDirection = config.textDirection
        )
        val (mergedRegions, contentDiscarded) = PPOcrPostProcessing.filterMergedRegions(allMerged)
        LogCollector.d(
            TAG,
            "recognizePpBatchV6: ${mergedInput.size} 行 → ${allMerged.size} 合并 → 内容丢弃${contentDiscarded.size} → ${mergedRegions.size} 输出"
        )
        return mergedRegions.map { it.toTextBlockInfo() }.filter { it.text.isNotBlank() }
    }

    /** 本次 [run] 的文本缓存累计统计（两条路线各自持有实例，由面板相加）。 */
    val cacheStats = TextCacheStats()

    // ========== 批次内翻译（含文本级缓存）==========

    /**
     * 翻译一批气泡（原 `incrementalTranslateBubbles`）：先查文本级缓存（精确 + 模糊），
     * 未命中的才走翻译 API。
     *
     * **public**：截屏翻译的普通路径（Hy-MT2 流式）也要调它。
     */
    suspend fun translateWithCache(
        bubbles: List<BubbleRegion>,
        forceContext: Boolean = false,
        onPartialBubbles: (List<TranslatedBubble>) -> Unit = {},
    ): List<TranslatedBubble> {
        if (bubbles.isEmpty()) return emptyList()
        // 用户已停止翻译：OCR 等耗时段结束后立即终止，避免继续走翻译/渲染残留进度条
        if (host.isCancelled()) throw TranslationCancelledException()

        // 缓存统计按「单次调用」累计：一条路线里两批各调一次，面板侧再相加
        var candidates = 0
        var cacheHits = 0
        val cache = host.textCache()
        LogCollector.d(
            TAG,
            "translateWithCache: ${bubbles.size} bubbles, forceContext=$forceContext, cacheSize=${cache.size()}"
        )

        // 文本级缓存：先精确匹配（快速路径），再模糊匹配（编辑距离）
        val fromCache = mutableListOf<TranslatedBubble>()
        val needTranslation = mutableListOf<BubbleRegion>()

        for (bubble in bubbles) {
            val combinedText = bubble.texts.map { TranslateUtils.cleanOcrText(it) }
                .filter { it.isNotBlank() }.joinToString("")
            if (combinedText.isBlank()) continue
            candidates++   // 有文字可判定的气泡，无论后面走缓存还是 API

            // 精确匹配
            val exactMatch = cache.findExact(combinedText, combinedText.hashCode())
            if (exactMatch != null) {
                cacheHits++
                fromCache.add(bubble.toTranslated(exactMatch.translation, isInMemoryCache = true))
                // 更新时间
                cache.remove(exactMatch)
                cache.add(exactMatch.copy(translatedAt = System.currentTimeMillis()))
                LogCollector.d(
                    TAG,
                    "Text cache hit (exact): '${combinedText.take(20)}' → '${exactMatch.translation.take(20)}'"
                )
            } else {
                // 模糊匹配：编辑距离自适应阈值
                val fuzzyMatch = cache.findFuzzyMatch(combinedText)
                if (fuzzyMatch != null) {
                    cacheHits++
                    fromCache.add(bubble.toTranslated(fuzzyMatch.translation, fromCache = true))
                    cache.remove(fuzzyMatch)
                    cache.add(fuzzyMatch.copy(translatedAt = System.currentTimeMillis()))
                    LogCollector.d(
                        TAG,
                        "Text cache hit (fuzzy): '${combinedText.take(20)}' ~ '${fuzzyMatch.ocrText.take(20)}' → '${fuzzyMatch.translation.take(20)}'"
                    )
                } else {
                    needTranslation.add(bubble)
                }
            }
        }

        if (needTranslation.isEmpty()) {
            cacheStats.candidates += candidates
            cacheStats.hits += cacheHits
            LogCollector.d(
                TAG,
                "translateWithCache: ${bubbles.size} 个气泡 -> $candidates 个有文字，全部命中文本缓存（0 次 API）；" +
                    "命中 ${fromCache.size}/${candidates}（精确+模糊）"
            )
            return fromCache
        }

        LogCollector.d(
            TAG,
            "translateWithCache: ${bubbles.size} 个气泡 -> $candidates 个有文字，缓存命中 $cacheHits，" +
                "需调 API ${needTranslation.size}"
        )
        // 用户已停止翻译：不重新显示「正在翻译」进度（避免取消后进度条残留/跳动）
        if (host.isCancelled()) throw TranslationCancelledException()
        host.onProgress(R.string.manga_translating)

        // 用 translateBubbles 走和手动翻译完全相同的路径
        val results = translateBubbles(needTranslation, forceContext, onPartialBubbles)

        // 缓存翻译结果
        for (result in results) {
            val textHash = result.originalText.hashCode()
            cache.add(
                RegionCacheManager.TranslatedRegion(
                    ocrText = result.originalText,
                    ocrTextHash = textHash,
                    translation = result.translatedText
                )
            )
            LogCollector.d(
                TAG,
                "Cached bubble: '${result.originalText.take(20)}' → '${result.translatedText.take(20)}'"
            )
        }
        cacheStats.candidates += candidates
        cacheStats.hits += cacheHits
        return fromCache + results
    }

    /** 调底层翻译（原 `MangaFloatingService.translateBubbles` 包装）。 */
    private suspend fun translateBubbles(
        bubbles: List<BubbleRegion>,
        forceContext: Boolean = false,
        onPartialBubbles: (List<TranslatedBubble>) -> Unit = {},
    ): List<TranslatedBubble> {
        val translator = host.translator ?: throw RuntimeException("Translation API not initialized")
        return TranslateUtils.translateBubbles(
            translator, bubbles, config.sourceLang, config.targetLang, config.prefs,
            host.contextHistory(), forceContext,
            onPhase = { phase ->
                when (phase) {
                    "prefill" -> host.onProgress(R.string.manga_reading)
                    "generate" -> host.onProgress(R.string.manga_translating)
                }
            },
            onPartialBubbles = onPartialBubbles,
            isCancelled = { host.isCancelled() }  // 用户停止翻译 → waitForResult 立即解除等待，不再卡 35s
        )
    }

    /** 把 OCR 文本映射成渲染用气泡（原 `incrementalTranslateBubbles` 内的 TranslatedBubble 构造）。 */
    private fun BubbleRegion.toTranslated(
        translated: String,
        isInMemoryCache: Boolean = false,
        fromCache: Boolean = false,
    ): TranslatedBubble = TranslatedBubble(
        rect = rect,
        originalText = texts.map { TranslateUtils.cleanOcrText(it) }.filter { it.isNotBlank() }.joinToString(""),
        translatedText = translated,
        backgroundColor = Color.TRANSPARENT,
        fontSize = fontSize,
        direction = direction,
        angle = angle,
        centerX = centerX,
        centerY = centerY,
        fromCache = fromCache,
        isInMemoryCache = isInMemoryCache,
    )

    /** TextRegionGroup → TextBlockInfo（原两条 PP 路线 `recognizeBatch` 末尾的映射，逐字保留）。 */
    private fun TextRegionGroup.toTextBlockInfo(): TextBlockInfo = TextBlockInfo(
        text = texts.joinToString("\n"),
        boundingBox = rect,
        cornerPoints = null,
        isVertical = direction == TextDirection.VERTICAL_RL || direction == TextDirection.VERTICAL_LR,
        angle = angle,
        centerX = center.x,
        centerY = center.y
    )
}
