package com.moe.starflow.novel.translate

import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.translate.TranslationRequest
import com.moe.starflow.translate.TranslationRequestSlot
import com.moe.starflow.utils.LogCollector
import translationapi.TranslatorFactory
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 一批的结果：译文表 + **失败原因**（原始报错文本，直接给用户看）。
 *
 * ⚠️ 失败原因**不能吞**：以前只回一句"模型没返回译文"，用户问"为什么没返回"时无从查起 ——
 * HTTP 状态码、异常类型、模型到底回了什么，全都没留下来。
 */
data class NovelBatchResult(
    val translations: Map<Int, String>,
    /** null = 成功。否则是给人看的原始原因（异常类型 + message / 模型返回了什么）。 */
    val error: String? = null,
) {
    val isEmpty: Boolean get() = translations.isEmpty()
}

/**
 * 文本翻译的薄适配层。
 *
 * 存在的理由：[TranslationTextAPI] 是既有重接口（流式、取消、release、modelName、错误文案本地化），
 * 直接假实现成本高、且会把既有实现细节漏进测试。这里只暴露引擎需要的那一小面，
 * 生产侧用 [TranslationTextApiAdapter] 包一层。
 */
fun interface NovelTextTranslator {

    /** [prompt] 是**已拼好的完整请求文本**（含编号与说明），实现方原样送引擎即可。 */
    fun translate(
        prompt: String,
        sourceLang: String,
        targetLang: String,
        callback: (TranslationResult) -> Unit,
    )

    fun start(prompt: String, sourceLang: String, targetLang: String,
        callback: (TranslationResult) -> Unit): TranslationRequest {
        translate(prompt, sourceLang, targetLang, callback)
        return TranslationRequest { cancel() }
    }

    fun cancel() {}

    val name: String get() = ""

    /**
     * 是否**本地离线引擎**（NLLB / LlamaCpp）。
     *
     * ⚠️ 只影响**超时策略**：本地推理单批可以跑几分钟，绝不能加总时长超时（会砍掉正常推理）；
     * 远端 API 必须有终态 —— 回调永远不来时不能让那一批永久挂起（见 [NovelTranslationEngine.requestOnce]）。
     */
    val isLocal: Boolean get() = false

    /**
     * 中止该引擎实例上**所有在飞请求**（真正掐掉 HTTP）。
     *
     * ⚠️ 与 [cancel] 的分工别搞混：
     * - [cancel] = 「**这一次**请求的调用方不要结果了」→ 远端 API **刻意不动它**，
     *   免得连带取消同实例上其它并发批（那些批等的是回调，回调不来就永久挂起）；
     * - 本方法 = 「**这个引擎的全部在途请求都作废**」（退出阅读器 / 停任务）→ 必须真的取消。
     */
    fun abortInFlight() {
        cancel()
    }
}

/** 把既有 [TranslationTextAPI] 适配成 [NovelTextTranslator]。 */
class TranslationTextApiAdapter(private val api: TranslationTextAPI) : NovelTextTranslator {
    private val requests = java.util.concurrent.ConcurrentHashMap.newKeySet<TranslationRequestSlot>()
    override val name: String get() = api.modelName.ifEmpty { api::class.java.simpleName }
    override val isLocal: Boolean get() = TranslatorFactory.isLocal(api)

    override fun translate(prompt: String, sourceLang: String, targetLang: String,
        callback: (TranslationResult) -> Unit) { start(prompt, sourceLang, targetLang, callback) }

    override fun start(prompt: String, sourceLang: String, targetLang: String,
        callback: (TranslationResult) -> Unit): TranslationRequest {
        val slot = TranslationRequestSlot()
        requests.add(slot)
        try {
            slot.attach(api.startTranslation(prompt, sourceLang, targetLang, callback = callback@{ result ->
                if (!requests.remove(slot)) return@callback
                slot.finish()
                callback(result)
            }))
        } catch (e: Exception) {
            requests.remove(slot)
            slot.cancel()
            throw e
        }
        return TranslationRequest { requests.remove(slot); slot.cancel() }
    }

    override fun cancel() { requests.toList().forEach { it.cancel() }; requests.clear() }
    override fun abortInFlight() {
        cancel()
        if (!isLocal) api.cancelTranslation()
    }
}

/**
 * 小说翻译引擎：逐批请求，每批产出一次累积结果。
 *
 * ### 用的是「文本模式」的引擎配置，不是漫画模式
 * 漫画模式会给 OpenAI 兼容接口加 **`[1] ` 续写预填**。小说的编号是**真实 `paraIndex`**
 * （首批可能是 `[0]`、`[5]`……），预填的 `[1] ` 会让模型把第一段回成 `[1] 译文` ——
 * 而批里**恰好存在 paraIndex=1 时就会被当成正确结果接受**，静默错配到别的段落上。
 * 所以这里不借漫画模式，编号规则由 [buildPromptText] 在请求里写明。
 *
 * ### 整批失败降级为逐段重试一次
 * 批量请求可能因其中一段触发内容审查被整体拒绝，逐段重试能把其余段落救回来。
 * 只重试一次，不做无限重试。
 */
class NovelTranslationEngine(private val translator: NovelTextTranslator) {

    private companion object {
        const val TAG = "NovelTranslationEngine"

        /**
         * 编号协议说明。放在请求正文里（而非依赖 provider 的系统提示词）：文本模式的
         * 内置提示词完全不认识 `[N]` 编号，不写明的话模型可能把编号当正文翻掉或整段吞掉。
         *
         * ⚠️ 这是**给模型的指令**，不是给用户的界面文案，所以按项目里
         * `BuiltinProviders` 的先例直接写成 Kotlin 常量，不进 strings.xml。
         */
        const val NUMBERING_INSTRUCTION =
            "逐条翻译下面的段落，保持每条的 [编号] 前缀与顺序不变，只输出译文："

        /**
         * 远端 API 单次请求的**总时长上限**：超时 = 终态失败（报原始原因、释放并发槽位）。
         *
         * ⚠️ 存在的理由（2026-10 真机事故的第二个成因）：`TranslationTextAPI` 是**回调式**接口，
         * 服务端挂住/网关吞连接时回调永远不来，而小说链路原来**没有任何总超时**
         * （项目里的 35s 超时只在漫画/游戏那条 `TranslateUtils` 链上）→ 那一批永久挂起、
         * 并发槽位再也放不出来、界面一直显示「翻译中」。
         *
         * ⚠️ 本地引擎（NLLB / LlamaCpp）**不设总时长**：单批可以跑几分钟，一刀切会把正常推理砍掉。
         * 60s 取的是「比 35s 宽松（小说单批比漫画单气泡长）、又不足以让用户以为卡死」。
         */
        const val REMOTE_REQUEST_TIMEOUT_MS = 60_000L

        /**
         * 超时文案里的固定片段：[NovelTranslationEngine.requestBatch] 靠它把「超时」与
         * 「内容性失败（审查/解析不出编号）」区分开 —— 超时不该降级逐段重试（那会把一次超时
         * 放大成 N 次、每次又等满 60s）。
         */
        const val TIMEOUT_ERROR_MARK = "没有响应"
    }

    /**
     * 翻**一批**（[paraIndexes] 指定的段，来自 `NovelBatchPlanner.nextBatch`）。
     *
     * ⚠️ **它只发一次请求，翻完就返回** —— 这里若偷偷把整章翻完，「点一次翻一批」和
     * 「增量配额 x 批」两个功能就都没法计数了（`NovelTranslationEngineBatchTest` 盯着这条）。
     * 要翻整章走 `NovelTranslationQueue.translateWholeChapter`（它一批批地调这里）。
     */
    suspend fun translateBatch(
        paragraphs: List<NovelParagraph>,
        paraIndexes: List<Int>,
        sourceLang: String,
        targetLang: String,
    ): NovelBatchResult {
        if (paraIndexes.isEmpty()) return NovelBatchResult(emptyMap())
        val units = asUnits(paragraphs)
        val present = units.mapTo(HashSet()) { it.paraIndex }
        val batch = paraIndexes.filter { it in present }
        if (batch.isEmpty()) return NovelBatchResult(emptyMap())
        return requestBatch(units, batch, sourceLang, targetLang)
    }

    /** 中止该引擎实例上**所有在飞请求**（阅读器退出/强制退出用）。 */
    fun abortInFlight() = translator.abortInFlight()

    /** 只保留可翻译的段（TEXT 且非空白），并绑死 `paraIndex`（见 [NovelUnit] 的说明）。 */
    private fun asUnits(paragraphs: List<NovelParagraph>): List<NovelUnit> = paragraphs
        .filter { it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank() }
        .map { NovelUnit(it.index, it.originalText) }

    /**
     * 发一批并解析；整批失败时**降级逐段重试一次**（内容审查常只针对其中一段）。
     *
     * 只重试一次，不做无限重试 —— 内容性失败重试多少次都是同样的结果，
     * 只会把额度烧光并让用户以为卡住了。
     */
    private suspend fun requestBatch(
        units: List<NovelUnit>,
        batch: List<Int>,
        sourceLang: String,
        targetLang: String,
    ): NovelBatchResult {
        var lastError: String? = null
        val parsed = requestAndParse(
            units, buildPromptText(units, batch), batch, sourceLang, targetLang,
            onError = { lastError = it },
        )
        if (parsed.isNotEmpty() || batch.size <= 1) {
            return NovelBatchResult(parsed, if (parsed.isEmpty()) lastError else null)
        }

        // ⚠️ **超时不降级逐段重试**：一次超时会被放大成 N 次（每次都等满 60s），
        // 用户看到「翻译中」好几分钟却什么都没出来。超时本身就是终态 → 直接失败、把原因报上去。
        if (lastError?.contains(TIMEOUT_ERROR_MARK) == true) {
            LogCollector.w(TAG, "整批超时（${batch.size} 段），不再逐段重试：$lastError")
            return NovelBatchResult(emptyMap(), lastError)
        }

        LogCollector.w(TAG, "整批失败（${batch.size} 段），降级逐段重试：$lastError")
        var out = parsed
        for (pi in batch) {
            out = out + requestAndParse(
                units,
                buildPromptText(units, listOf(pi)),
                listOf(pi),
                sourceLang,
                targetLang,
                onError = { lastError = it },
            )
        }
        return NovelBatchResult(out, if (out.isEmpty()) lastError else null)
    }

    private fun buildPromptText(units: List<NovelUnit>, batch: List<Int>): String =
        "$NUMBERING_INSTRUCTION\n${NovelTranslationBatch.buildPrompt(units, batch)}"

    private suspend fun requestAndParse(
        units: List<NovelUnit>,
        prompt: String,
        batch: List<Int>,
        sourceLang: String,
        targetLang: String,
        onError: (String) -> Unit = {},
    ): Map<Int, String> {
        val reply = requestOnce(prompt, sourceLang, targetLang, onError) ?: return emptyMap()
        // ⚠️ 位置兜底要拿到原文才判得出「这条回复像不像译文」：只请求一段时条数一致不带信息，
        // 模型回一句拒绝语会被当成译文写库（永久错误且不会再重试）。见 parseByPosition。
        val sourceOf = { pi: Int -> units.firstOrNull { it.paraIndex == pi }?.text.orEmpty() }
        val parsed = NovelTranslationBatch.parseTolerant(reply, batch, sourceOf)
        if (parsed.isEmpty()) {
            // 请求成功但解析不出编号 —— 用户要的就是"模型到底回了什么"
            onError("返回内容无法解析为编号段落：${reply.trim().take(120)}")
        }
        return parsed
    }

    /**
     * 异常 → 给人看的**原始原因**（保留异常类型与整条 cause 链）。
     *
     * ⚠️ 别只写「翻译失败」：用户要的是"为什么" —— HTTP 429/401、超时、证书、模型名不存在
     * 都长得一样但处置完全不同。
     */
    private fun describe(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(4)
            .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        return chain.ifBlank { e.javaClass.simpleName }
    }

    /**
     * 一次请求；失败/取消返回 null。
     *
     * ⚠️ **远端 API 必须有终态**（[REMOTE_REQUEST_TIMEOUT_MS]）：回调永远不来时原来会**永久挂起** ——
     * 那一批既不入库也不报错，队列的并发槽位再也放不出来，界面一直显示「翻译中」。
     * 本地引擎不设总时长（单批可以跑几分钟）。
     */
    private suspend fun requestOnce(
        prompt: String,
        sourceLang: String,
        targetLang: String,
        onError: (String) -> Unit = {},
    ): String? {
        // 区分「超时」与「回调返回了空」：超时时 withTimeoutOrNull 也返回 null
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val waitForCallback: suspend () -> String? = {
            suspendCancellableCoroutine { cont ->
                val slot = TranslationRequestSlot()
                val resumed = java.util.concurrent.atomic.AtomicBoolean()
                cont.invokeOnCancellation { slot.cancel() }
                slot.attach(translator.start(prompt, sourceLang, targetLang) callback@{ result ->
                    if (!resumed.compareAndSet(false, true)) return@callback
                    slot.finish()
                    answered.set(true)
                    when (result) {
                        is TranslationResult.Success -> {
                            if (cont.isActive) {
                                if (result.translatedText.isBlank()) onError("返回为空（HTTP 200 但内容为空）")
                                cont.resume(result.translatedText.takeIf { it.isNotBlank() })
                            }
                        }
                        is TranslationResult.Error -> {
                            val why = describe(result.error)
                            LogCollector.w(TAG, "请求失败：$why")
                            onError(why)
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                })

            }
        }
        val reply = if (translator.isLocal) {
            withContext(Dispatchers.IO) { waitForCallback() }
        } else {
            withTimeoutOrNull(REMOTE_REQUEST_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { waitForCallback() }
            }
        }
        if (!answered.get()) {
            onError("超过 ${REMOTE_REQUEST_TIMEOUT_MS / 1000}s $TIMEOUT_ERROR_MARK（已中止等待）")
        }
        return reply
    }
}
