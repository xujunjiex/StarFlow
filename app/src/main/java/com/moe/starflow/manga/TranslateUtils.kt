package com.moe.starflow.manga
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.graphics.Color
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.moe.starflow.llamacpp.LlamaCppTranslation
import translationapi.openaitranslation.OpenAITranslation
import java.util.LinkedList
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 漫画翻译工具类，从 MangaFloatingService 抽取的静态翻译方法。
 * 所有方法均为纯函数，不依赖 Service 实例。
 */
object TranslateUtils {

    private const val TAG = "TranslateUtils"

    /** 由 StarFlowApplication.onCreate 注入（对齐 LogCollector.init），用于本地化错误消息。纯 object 无构造 Context。 */
    private var appContext: Context? = null
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // 带编号翻译结果的行匹配：`[N] 文本`（N 可为任意数字，前缀 [N]、N.、N、 也兼容）。两个解析器共用，避免行为漂移。
    private val NUMBERED_TRANSLATION_REGEX = Regex("""\[(\d+)]\s*([\s\S]*?)(?=\[\d+]|$)""")

    /**
     * 降级按行拆时用的行首编号前缀（`[N]` / `[N` / `N.` / `N、`）。
     *
     * ⚠️ **不要写回 `^\[?\d+]?[.、\s]*`**：`\[?` 与 `]?` 各自独立可选，合起来就能匹配
     * 「一个裸数字开头的行」，于是把正文里的数字整行吃掉 —— 页码气泡（OCR 出 `330`）恰好是
     * 每批的第 1 条时，这一行被丢掉 → 结果少一条 → 补齐空串 → **整批译文前移一位、末条空白**。
     * 2026-09-25 用户日志实测：火山(standard)/DeepSeek(prefix) 三家同一页稳定复现，
     * 智谱(json，按数组下标) 不受影响。裸数字行必须原样保留。
     */
    private val LINE_MARKER_PREFIX = Regex("""^(?:\[\d+]?|\d+[.、])\s*""")

    // 批量翻译超时策略：
    //  - 网络 API：请求发出起 35s 内必须返回（无响应即超时）
    //  - 本地引擎（Hy-MT2）：不设总时长（本地总会翻完），改为「30s 无任何新输出」的卡死看门狗
    private const val API_TIMEOUT_MS = 35_000L
    private const val LOCAL_STALL_TIMEOUT_MS = 30_000L

    // ========== 对外入口 ==========

    /**
     * 翻译全部气泡。自动判断使用 AI 批量翻译还是机器逐个翻译。
     */
    suspend fun translateBubbles(
        translator: TranslationTextAPI,
        bubbles: List<BubbleRegion>,
        sourceLang: String,
        targetLang: String,
        prefs: CustomPreference,
        contextHistory: LinkedList<Pair<String, String>> = LinkedList(),
        forceContext: Boolean = false,
        onPhase: (String) -> Unit = {},
        onPartialBubbles: (List<TranslatedBubble>) -> Unit = {},
        /** 用户停止翻译的检测回调：返回 true 时立即解除等待并中止翻译（修复网络 API cancel 后回调永不触发导致卡死） */
        isCancelled: (() -> Boolean)? = null
    ): List<TranslatedBubble> {
        LogCollector.d(TAG, "translateBubbles: ${bubbles.size} bubbles, translator=${translator.javaClass.simpleName}")

        // 准备气泡数据：清理文本，过滤空的，分离「不需要翻译」的气泡（纯符号 / 纯数字页码）
        val preparedBubbles = mutableListOf<Pair<BubbleRegion, String>>()
        val passthroughBubbles = mutableListOf<TranslatedBubble>()

        for (bubble in bubbles) {
            val cleaned = bubble.texts.map { cleanOcrText(it) }.filter { it.isNotBlank() }
            if (cleaned.isEmpty()) continue
            val combinedText = cleaned.joinToString("")

            // 纯符号与纯数字（页码 "330"）都原文回填、**不进翻译批次**：
            // 页码本身翻不出内容，却要占掉批量编号里的一位 —— 而模型把它原样返回成"纯数字行"时，
            // 正是 2026-09-25 那批「整批译文前移一位 + 末条空白」的触发源。从源头掐掉最稳。
            val reason = when {
                isSymbolOnlyText(combinedText) -> "symbol-only"
                isNumericOnlyText(combinedText) -> "numeric"
                else -> null
            }
            if (reason != null) {
                LogCollector.d(TAG, "translateBubbles: skipping $reason: '$combinedText'")
                passthroughBubbles.add(TranslatedBubble(
                    rect = bubble.rect,
                    originalText = combinedText,
                    translatedText = combinedText,
                    backgroundColor = Color.TRANSPARENT,
                    fontSize = bubble.fontSize,
                    direction = bubble.direction,
                    angle = bubble.angle,
                    centerX = bubble.centerX,
                    centerY = bubble.centerY
                ))
            } else {
                preparedBubbles.add(bubble to combinedText)
            }
        }
        if (preparedBubbles.isEmpty()) return passthroughBubbles

        // AI 翻译（OpenAI 兼容 / 本地 Hy-MT2）用批量请求，其余机器翻译用逐个请求
        // Hy-MT2 走批量：输入 [N] 编号，模型按官方默认模板只输出译文并保持 [N] 编号，管线按编号解析
        val isAI = translator is OpenAITranslation
                || translator.javaClass.simpleName.contains("Custom")
                || translator is LlamaCppTranslation

        val translatedResults = if (isAI && (preparedBubbles.size > 1 || translator is LlamaCppTranslation)) {
            // Hy-MT2 即使只有 1 个气泡也走批量编号+流式路径：统一享受「30s 无输出卡死检测」，避免 sequential 的 30s 总时限误杀
            translateBubblesBatch(translator, preparedBubbles, sourceLang, targetLang, prefs, contextHistory, forceContext, onPhase, onPartialBubbles, isCancelled)
        } else {
            translateBubblesSequential(translator, preparedBubbles, sourceLang, targetLang)
        }

        // 译文后处理：只做**点串归一化**（`.\n.\n.` → `...`，硬保证；顺序路径的译文没走
        // parseNumberedTranslations，所以这里兜一次，幂等）。
        // ⚠️ 用户「译文替换表」**不在这里**：它在渲染时套用（OverlayRenderer）—— 译文只存文本、
        // overlay 后期才画，所以改规则只需重新渲染，不必重翻 API。
        //
        // ⚠️ **空译文绝不能进结果**：解析错位、模型漏给、机器翻译返回空串都会产出 `""`，
        // 渲染出来就是「识别到了但没翻」（用户看到的空白格）。回退原文至少让用户看见"这句没翻"，
        // 而不是一片空白；调用方（管道/阅读器）也据此把这条当作"没译出来"。
        val blankCount = (passthroughBubbles + translatedResults).count { it.translatedText.isBlank() }
        if (blankCount > 0) {
            LogCollector.w(TAG, "translateBubbles: $blankCount/${preparedBubbles.size} 条译文为空，已回退原文")
        }
        return (passthroughBubbles + translatedResults).map { bubble ->
            val normalized = TranslationTextRules.normalizeEllipsis(bubble.translatedText)
            val out = normalized.ifBlank { bubble.originalText }
            if (out == bubble.translatedText) bubble else bubble.copy(translatedText = out)
        }
    }

    // ========== AI 批量翻译 ==========

    /**
     * AI 翻译：所有气泡合并为一次请求，用编号分隔
     */
    private suspend fun translateBubblesBatch(
        translator: TranslationTextAPI,
        bubbles: List<Pair<BubbleRegion, String>>,
        sourceLang: String,
        targetLang: String,
        prefs: CustomPreference,
        contextHistory: LinkedList<Pair<String, String>> = LinkedList(),
        forceContext: Boolean = false,
        onPhase: (String) -> Unit = {},
        onPartialBubbles: (List<TranslatedBubble>) -> Unit = {},
        isCancelled: (() -> Boolean)? = null
    ): List<TranslatedBubble> = withContext(Dispatchers.IO) {
        LogCollector.d(TAG, "translateBubblesBatch: ${bubbles.size} bubbles, forceContext=$forceContext")

        // 构建带编号的文本
        val numberedText = bubbles.mapIndexed { index, (_, text) ->
            "[${index + 1}] $text"
        }.joinToString("\n")

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultText: String? = null
        var errorMsg: String? = null

        // 分批渲染强制开启上下文（仅批次间传递）；正常漫画翻译不使用上下文
        val currentContextEnabled = forceContext
        val currentContextMaxCount = try {
            prefs.getString("game_context_count", "5").toIntOrNull() ?: 5
        } catch (e: Exception) { 5 }

        // 更新 AI 上下文（仅 OpenAI 兼容 API）
        (translator as? OpenAITranslation)?.updateContext(
            if (currentContextEnabled) contextHistory.toList() else emptyList(),
            currentContextEnabled
        )

        // 等待翻译结果。超时策略：
        //  - 网络 API：请求发出起 API_TIMEOUT_MS 内必须返回（无响应即超时）
        //  - 本地引擎（Hy-MT2）：不设总时长（本地总会翻完），由看门狗按「30s 无新输出」判卡死——有输出就一直等
        val isLocalEngine = translator is LlamaCppTranslation
        val lastProgressAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancelledByUser = java.util.concurrent.atomic.AtomicBoolean(false)
        val translationFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val contRef = java.util.concurrent.atomic.AtomicReference<CancellableContinuation<Unit>?>(null)

        // 看门狗：① 用户停止翻译（isCancelled）→ 立即解除等待，中止在途请求；
        // ② 本地引擎 30s 无任何新输出 → 判卡死解除阻塞（网络引擎由 withTimeoutOrNull 兜底）
        val watchdogJob = launch {
            while (!translationFinished.get()) {
                delay(200)
                if (isCancelled?.invoke() == true) {
                    cancelledByUser.set(true)
                    translationFinished.set(true)
                    translator.cancelTranslation()  // 中止引擎 / 取消 HTTP
                    // 强制结束等待：网络 API 取消后回调永不触发，必须主动 resume 才能解除 isProcessing 阻塞
                    contRef.getAndSet(null)?.let { if (it.isActive) it.resume(Unit) }  // 原子取并置空：看门狗/回调竞争时只 resume 一次
                    break
                }
                if (isLocalEngine && System.currentTimeMillis() - lastProgressAt.get() > LOCAL_STALL_TIMEOUT_MS) {
                    timedOut.set(true)
                    translationFinished.set(true)
                    translator.cancelTranslation()  // 中止引擎，释放 g_mutex
                    // 强制结束等待：即使 native 彻底卡死不回调，也解除阻塞向上抛错
                    contRef.getAndSet(null)?.let { if (it.isActive) it.resume(Unit) }  // 原子取并置空：看门狗/回调竞争时只 resume 一次
                    break
                }
            }
        }

        val waitForResult: suspend () -> Unit = {
            suspendCancellableCoroutine { cont ->
                contRef.set(cont)
                cont.invokeOnCancellation {
                    // 协程被取消时尝试主动取消翻译任务
                    translator.cancelTranslation()
                }
                var lastRenderedCount = 0
                translator.getTranslationStreaming(
                    numberedText,
                    sourceLang,
                    targetLang,
                    onPhase = { phase ->
                        if (isLocalEngine) lastProgressAt.set(System.currentTimeMillis())
                        onPhase(phase)
                    },
                    onPartial = { partialText ->
                        if (isLocalEngine) lastProgressAt.set(System.currentTimeMillis())
                        // 流式：解析已完整的气泡条目并回调渲染（每完成一个气泡渲染一次）
                        val completed = parseNumberedTranslationsPartial(partialText)
                        if (completed.size > lastRenderedCount && completed.isNotEmpty()) {
                            lastRenderedCount = completed.size
                            // 按 [N] 编号匹配气泡；越界/幻觉编号（如超出输入条数）跳过，防止 IndexOutOfBounds 与错位
                            val partialBubbles = completed.mapNotNull { (number, translated) ->
                                val index = number - 1
                                if (index !in bubbles.indices) return@mapNotNull null
                                val (bubble, originalText) = bubbles[index]
                                TranslatedBubble(
                                    rect = bubble.rect,
                                    originalText = originalText,
                                    translatedText = translated,
                                    backgroundColor = Color.TRANSPARENT,
                                    fontSize = bubble.fontSize,
                                    direction = bubble.direction,
                                    angle = bubble.angle,
                                    centerX = bubble.centerX,
                                    centerY = bubble.centerY
                                )
                            }
                            if (partialBubbles.isNotEmpty()) onPartialBubbles(partialBubbles)
                        }
                    },
                    callback = { result ->
                        translationFinished.set(true)
                        watchdogJob?.cancel()
                        when (result) {
                            is TranslationResult.Success -> {
                                resultText = result.translatedText
                            }
                            is TranslationResult.Error -> {
                                errorMsg = result.error.message ?: "Unknown error"
                            }
                        }
                        // 原子取并置空：与看门狗竞争时只 resume 一次（避免 Already resumed）
                        contRef.getAndSet(null)?.let { if (it.isActive) it.resume(Unit) }
                    }
                )
            }
        }
        val completed = if (isLocalEngine) waitForResult() else withTimeoutOrNull(API_TIMEOUT_MS) { waitForResult() }
        watchdogJob.cancel()
        if (cancelledByUser.get()) {
            // 用户停止翻译：抛专用异常向上（不能用 CancellationException，会取消整个 collector 协程）。
            // 上层按类型识别「已取消」：不保存、不报错、isProcessing 复位
            throw TranslationCancelledException()
        }
        if (timedOut.get()) {
            translator.cancelTranslation()
            val stallSec = (LOCAL_STALL_TIMEOUT_MS / 1000).toInt()
            throw RuntimeException(
                appContext?.getString(R.string.error_local_engine_stall, stallSec)
                    ?: "Local engine not responding (no output for $stallSec s)"
            )
        }
        if (completed == null) {
            translator.cancelTranslation()
            throw RuntimeException("AI batch translation timeout (${API_TIMEOUT_MS / 1000}s)")
        }
        if (errorMsg != null) {
            throw RuntimeException("AI batch translation failed: $errorMsg")
        }

        // 按编号解析结果（支持 JSON 格式和编号格式）
        val result = resultText!!.trim()
        val translations = if (result.startsWith("{")) {
            parseJsonTranslations(result, bubbles.size)
        } else {
            parseNumberedTranslations(result, bubbles.size)
        }
        LogCollector.d(TAG, "translateBubblesBatch: parsed ${translations.size} translations")

        // 更新 AI 上下文历史（仅 OpenAI 兼容 API）
        if (currentContextEnabled && translations.isNotEmpty()) {
            val sourceText = bubbles.map { it.second }.joinToString("\n")
            val translatedText = translations.joinToString("\n")
            contextHistory.addLast(Pair(sourceText, translatedText))
            while (contextHistory.size > currentContextMaxCount) {
                contextHistory.removeFirst()
            }
            LogCollector.d(TAG, "上下文已更新: ${contextHistory.size}/$currentContextMaxCount 轮")
        }

        // 输出翻译结果
        for (i in translations.indices) {
            val (_, original) = bubbles[i]
            val translated = translations[i]
            LogCollector.d(TAG, "翻译结果[$i]: orig='$original' → trans='$translated'")
        }

        bubbles.mapIndexed { index, (bubble, originalText) ->
            if (abs(bubble.angle) > 0.5f) {
                LogCollector.d(TAG, "TranslatedBubble[$index]: angle=${bubble.angle}, cx=${bubble.centerX}, cy=${bubble.centerY}, text='${originalText.take(15)}'")
            }
            TranslatedBubble(
                rect = bubble.rect,
                originalText = originalText,
                translatedText = translations.getOrElse(index) { originalText },
                backgroundColor = Color.TRANSPARENT,
                fontSize = bubble.fontSize,
                direction = bubble.direction,
                angle = bubble.angle,
                centerX = bubble.centerX,
                centerY = bubble.centerY
            )
        }
    }

    // ========== 机器逐个翻译 ==========

    /**
     * 机器翻译：逐个气泡请求
     */
    private suspend fun translateBubblesSequential(
        translator: TranslationTextAPI,
        bubbles: List<Pair<BubbleRegion, String>>,
        sourceLang: String,
        targetLang: String
    ): List<TranslatedBubble> = coroutineScope {
        LogCollector.d(TAG, "translateBubblesConcurrent: ${bubbles.size} bubbles, concurrent")

        val deferreds = bubbles.map { (bubble, combinedText) ->
            async(Dispatchers.IO) {
                LogCollector.d(TAG, "translateBubblesConcurrent: translating '$combinedText'")

                val latch = java.util.concurrent.CountDownLatch(1)
                var successResult: TranslatedBubble? = null
                var errorMsg: String? = null

                translator.getTranslation(
                    combinedText,
                    sourceLang,
                    targetLang
                ) { result ->
                    when (result) {
                        is TranslationResult.Success -> {
                            LogCollector.d(TAG, "translateBubblesConcurrent: SUCCESS for '$combinedText'")
                            successResult = TranslatedBubble(
                                rect = bubble.rect,
                                originalText = combinedText,
                                translatedText = result.translatedText,
                                backgroundColor = Color.TRANSPARENT,
                                fontSize = bubble.fontSize,
                                direction = bubble.direction,
                                angle = bubble.angle,
                                centerX = bubble.centerX,
                                centerY = bubble.centerY
                            )
                        }
                        is TranslationResult.Error -> {
                            errorMsg = result.error.message ?: "Unknown error"
                            LogCollector.e(TAG, "translateBubblesConcurrent: ERROR: $errorMsg")
                        }
                    }
                    latch.countDown()
                }

                val completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!completed) {
                    // 超时：主动取消翻译任务。Hy-MT2 等本地引擎取消后解码循环提前退出，释放占用的引擎，
                    // 否则该线程会继续生成并阻塞后续所有翻译。
                    translator.cancelTranslation()
                    errorMsg = "Translation timeout (30s)"
                }

                Pair(successResult, errorMsg)
            }
        }

        val allResults = deferreds.awaitAll()
        val results = mutableListOf<TranslatedBubble>()
        val errors = mutableListOf<String>()

        for ((successResult, errorMsg) in allResults) {
            if (successResult != null) {
                results.add(successResult)
            } else if (errorMsg != null) {
                errors.add(errorMsg)
            }
        }

        LogCollector.d(TAG, "translateBubblesConcurrent: ${results.size} successful out of ${bubbles.size}")
        if (results.isEmpty() && bubbles.isNotEmpty()) {
            val errorDetail = errors.distinct().joinToString("; ")
            throw RuntimeException("All bubbles failed to translate: $errorDetail")
        }
        results
    }

    // ========== 结果解析 ==========

    /**
     * 解析 JSON 格式的翻译结果
     * 支持格式:
     *   1. {"translations": ["译文1", "译文2"]}
     *   2. ["译文1", "译文2"]
     *   3. [{"translations": ["译文1"]}, ...] (模型可能返回的混合格式)
     */
    fun parseJsonTranslations(text: String, expectedCount: Int): List<String> {
        return try {
            val results = mutableListOf<String>()

            // 尝试解析为 JSON 对象 {"translations": [...]}
            try {
                val jsonObject = org.json.JSONObject(text)
                val translations = jsonObject.getJSONArray("translations")
                for (i in 0 until translations.length().coerceAtMost(expectedCount)) {
                    results.add(translations.getString(i))
                }
            } catch (_: Exception) {
                // 尝试解析为 JSON 数组
                val jsonArray = org.json.JSONArray(text)
                for (i in 0 until jsonArray.length().coerceAtMost(expectedCount)) {
                    val item = jsonArray.get(i)
                    when (item) {
                        is String -> results.add(item)
                        is org.json.JSONObject -> {
                            // 处理 {"translations": ["译文"]} 格式的数组元素
                            if (item.has("translations")) {
                                val arr = item.getJSONArray("translations")
                                if (arr.length() > 0) results.add(arr.getString(0))
                            }
                        }
                        // 跳过数字等其他类型（如 [2], [3]）
                    }
                }
            }

            // 补齐不足的部分
            while (results.size < expectedCount) {
                results.add("")
            }
            results.take(expectedCount)
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to parse JSON translations: ${text.take(200)}", e)
            List(expectedCount) { "" }
        }
    }

    /**
     * 解析带编号的翻译结果
     * 支持格式: "[1] 翻译文本" 或 "1. 翻译文本" 或 "1、翻译文本"
     *
     * ⚠️ 先做点串归一化：`[\s\S]*?` 抓的是**跨行**内容，模型把一条译文写成
     * `[1] .` / `.` / `.` 三行时，这条译文就真的是 `".\n.\n."`（用户看到的「每个点占一行」）。
     *
     * 三条分支，按可靠性从高到低：
     * 1. **编号齐全**（模型连 `[1]` 一起复述）→ 按位置取。
     * 2. **编号从 `[2]` 起**（续写 prefill `"[1] "` 被服务端吞掉，content 里没有 `[1]`，
     *    火山 standard / DeepSeek prefix / 千问 partial 都是这个形态）→ **第一个标记之前的正文
     *    就是第 1 条**，按**编号**对位重建。⚠️ 绝不能在这里退化成「按行拆」：只要正文里有以
     *    数字开头的行（页码 `330`），按行拆就会掉行 → 整批前移 + 末尾空串（2026-09-25 日志实证）。
     *    编号有跳号时该号留空，由调用方回退原文 —— 也远好于让整批错位。
     * 3. **一个标记都没有**（模型没按格式答）→ 才按行拆，且只用 [LINE_MARKER_PREFIX] 剥真编号。
     */
    fun parseNumberedTranslations(text: String, expectedCount: Int): List<String> {
        val normalized = TranslationTextRules.normalizeEllipsis(text)
        val matches = NUMBERED_TRANSLATION_REGEX.findAll(normalized).toList()

        // 分支 1：编号齐全 → 直接按位置取
        if (matches.size >= expectedCount) {
            return matches.take(expectedCount).map { it.groupValues[2].trim() }
        }

        // 分支 2：有标记但不满条数（典型：prefill 吃掉了 [1]）→ 按编号对位
        val firstMarker = matches.firstOrNull()
        if (firstMarker != null) {
            val byNumber = HashMap<Int, String>(expectedCount)
            normalized.substring(0, firstMarker.range.first).trim()
                .takeIf { it.isNotEmpty() }
                ?.let { byNumber[1] = it }
            matches.forEach { m ->
                m.groupValues[1].toIntOrNull()?.let { byNumber[it] = m.groupValues[2].trim() }
            }
            val out = (1..expectedCount).map { byNumber[it].orEmpty() }
            if (out.any { it.isBlank() }) {
                LogCollector.w(
                    TAG,
                    "parseNumberedTranslations: 期望 $expectedCount 条，编号 " +
                        "${matches.mapNotNull { it.groupValues[1].toIntOrNull() }} 中缺号/空条目，原文片段=${normalized.take(120)}"
                )
            }
            return out
        }

        // 分支 3：完全没有编号 → 按行拆（降级）
        val results = mutableListOf<String>()
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotBlank() }
        for (line in lines) {
            val cleaned = line.replace(LINE_MARKER_PREFIX, "").trim()
            if (cleaned.isNotBlank()) {
                results.add(cleaned)
            }
        }
        if (results.size != expectedCount) {
            LogCollector.w(
                TAG,
                "parseNumberedTranslations: 无编号格式，解析 ${results.size} 条、期望 $expectedCount 条，" +
                    "原文片段=${normalized.take(120)}"
            )
        }

        // 补齐不足的部分
        while (results.size < expectedCount) {
            results.add("")
        }
        return results.take(expectedCount)
    }

    /**
     * 解析带编号翻译结果中「已完整」的条目（仅流式显示用）。
     * 一条目后跟有下一个 [N] 才视为完整；最后一条可能仍在生成中，跳过不渲染。
     * 返回 (编号, 文本) 对，调用方按编号匹配气泡，可跳过越界/幻觉编号，防止错位与崩溃。
     *
     * 同 [parseNumberedTranslations]：先归一化点串，避免流式过程中闪出「每行一个点」。
     */
    fun parseNumberedTranslationsPartial(text: String): List<Pair<Int, String>> {
        val normalized = TranslationTextRules.normalizeEllipsis(text)
        val results = mutableListOf<Pair<Int, String>>()
        val matches = NUMBERED_TRANSLATION_REGEX.findAll(normalized).toList()
        for (idx in 0 until matches.size - 1) {
            val number = matches[idx].groupValues[1].toIntOrNull()
            val content = matches[idx].groupValues[2].trim()
            if (number != null) results.add(number to content)
        }
        return results
    }

    // ========== 文字处理 ==========

    /**
     * 清理 OCR 文本：移除换行符，合并空格。
     */
    fun cleanOcrText(text: String): String {
        return text
            .replace(Regex("[\\n\\r]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 检查文本是否为「纯数字」（页码 / 编号 / 年份这类），这类气泡**不需要也翻不出内容**。
     *
     * 除了省一次请求，更重要的是**保住批量编号的对位**：模型对纯数字条目的译文就是原样回一个
     * 数字行（`330`），而按行降级解析时这种行最容易被当成编号前缀吃掉 → 整批译文前移一位、
     * 末条空白。这类气泡直接从批次里摘掉（原文回填），比事后修补稳。
     *
     * 判据：去掉空白后必须**至少有一个数字**，其余只能是数字或数字里常见的分隔符
     * （`.,-/–—:·`）。所以 `100%`、`$100`、`3F`、`第 3 话` 都不算，仍会送去翻译。
     */
    fun isNumericOnlyText(text: String): Boolean {
        val stripped = text.filterNot { it.isWhitespace() }
        if (stripped.isEmpty()) return false
        var digits = 0
        for (ch in stripped) {
            when {
                ch.isDigit() -> digits++
                ch == '.' || ch == ',' || ch == '-' || ch == '/' || ch == '–' ||
                    ch == '—' || ch == ':' || ch == '·' -> Unit
                else -> return false
            }
        }
        return digits > 0
    }

    /**
     * 检查文本是否仅包含符号/标点（不含实际文字内容）。
     * 用于过滤漫画中的纯符号表达，避免提交给翻译模型导致文字被缩小。
     */
    fun isSymbolOnlyText(text: String): Boolean {
        if (text.isBlank()) return true
        val stripped = text.replace(Regex("\\s+"), "")
        if (stripped.isEmpty()) return true
        return stripped.all { ch ->
            val type = Character.getType(ch).toByte()
            type == Character.START_PUNCTUATION ||
            type == Character.END_PUNCTUATION ||
            type == Character.DASH_PUNCTUATION ||
            type == Character.OTHER_PUNCTUATION ||
            type == Character.MATH_SYMBOL ||
            type == Character.CURRENCY_SYMBOL ||
            type == Character.MODIFIER_SYMBOL ||
            type == Character.OTHER_SYMBOL ||
            ch == '♡' || ch == '♥' || ch == '♪' || ch == '♫' ||
            ch == '〜' || ch == '～' || ch == '…' || ch == '─'
        }
    }

    /**
     * 构建翻译器显示名（调试信息：引擎 + det/ocr 组合 + 分批/自由文字开关 + PP-OCRv5 参数）。
     * 从 MangaFloatingService 阶段 4b 提取（纯函数，参数化无 Service 依赖）。
     */
    fun buildTranslatorDisplayName(
        translator: TranslationTextAPI?,
        detEngine: DetEngine,
        ocrEngine: OcrEngine,
        prefs: android.content.SharedPreferences
    ): String {
        val apiName = translator?.javaClass?.simpleName ?: "Unknown"
        val model = translator?.modelName ?: ""
        val apiStr = if (model.isNotEmpty()) "$apiName($model)" else apiName

        val det = when (detEngine) {
            DetEngine.MLKIT -> "MLKit"
            DetEngine.RT_DETR_V2 -> "RT-DETR"
            DetEngine.PP_OCR_V5 -> "PP-OCRv5"
            DetEngine.PP_OCR_V6 -> "PP-OCRv6"
        }
        val ocr = when (ocrEngine) {
            OcrEngine.MLKit -> "MLKit"
            OcrEngine.MangaOcr -> "manga-ocr"
            OcrEngine.PPOcrV5 -> "PP-OCRv5"
            OcrEngine.PPOcrV6 -> "PP-OCRv6"
        }

        val parts = mutableListOf(apiStr, "$det+$ocr")

        // 分批翻译：开关打开 + 支持的组合（RT-DETR+manga-ocr 或 PP-OCRv5/v6 独立）
        val incrementalEnabled = prefs.getBoolean("Incremental_Render", true)
        val isRTDetrMangaOcr = detEngine == DetEngine.RT_DETR_V2 && ocrEngine == OcrEngine.MangaOcr
        val isPPOcrV5Standalone = detEngine == DetEngine.PP_OCR_V5 && ocrEngine == OcrEngine.PPOcrV5
        val isPPOcrV6Standalone2 = detEngine == DetEngine.PP_OCR_V6 && ocrEngine == OcrEngine.PPOcrV6
        if (incrementalEnabled && (isRTDetrMangaOcr || isPPOcrV5Standalone || isPPOcrV6Standalone2)) {
            parts.add("分批✓")
        } else if (incrementalEnabled) {
            parts.add("分批✗")  // 开关打开但组合不支持
        }

        // 自由文字：开关打开 + 检测器是 RT-DETR-V2
        val keepTextFreeEnabled = prefs.getBoolean(MangaModeConfig.KEY_KEEP_TEXT_FREE, true)
        if (keepTextFreeEnabled && detEngine == DetEngine.RT_DETR_V2) {
            parts.add("自由文字✓")
        } else if (keepTextFreeEnabled) {
            parts.add("自由文字✗")  // 开关打开但检测器不是 RT-DETR
        }

        // PP-OCRv5 参数（仅当检测器或识别器为 PP-OCRv5 时显示）
        if (detEngine == DetEngine.PP_OCR_V5 || ocrEngine == OcrEngine.PPOcrV5) {
            val boxThresh = prefs.getFloat("ppocr_det_box_thresh", 0.3f)
            val unclipRatio = prefs.getFloat("ppocr_det_unclip_ratio", 1.6f)
            val textScore = prefs.getFloat("ppocr_text_score_thresh", 0.5f)
            parts.add("box=%.2f unclip=%.1f score=%.2f".format(boxThresh, unclipRatio, textScore))
        }

        return parts.joinToString(" | ")
    }
}

/**
 * 用户主动停止翻译的专用异常。
 * 用独立类型而非普通 RuntimeException：漫画 collector 据此可靠识别「已取消」，
 * 不依赖 translationCancelled 标志（该标志可能被下一次翻译抢先重置导致竞态误报错）。
 */
class TranslationCancelledException : Exception("翻译已取消")
