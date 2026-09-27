package com.moe.starflow.novel.translate

import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.LogCollector
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

    fun cancel() {}

    val name: String get() = ""
}

/** 把既有 [TranslationTextAPI] 适配成 [NovelTextTranslator]。 */
class TranslationTextApiAdapter(private val api: TranslationTextAPI) : NovelTextTranslator {

    override val name: String get() = api.modelName.ifEmpty { api::class.java.simpleName }

    override fun translate(
        prompt: String,
        sourceLang: String,
        targetLang: String,
        callback: (TranslationResult) -> Unit,
    ) = api.getTranslation(prompt, sourceLang, targetLang, callback)

    override fun cancel() = api.cancelTranslation()
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

    /** 一次请求；失败/取消返回 null。 */
    private suspend fun requestOnce(
        prompt: String,
        sourceLang: String,
        targetLang: String,
        onError: (String) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            var resumed = false
            translator.translate(prompt, sourceLang, targetLang) { result ->
                if (resumed) return@translate
                resumed = true
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
            }
            cont.invokeOnCancellation {
                // 取消时通知底层中断在途请求（网络 API 取消后回调不会再来，必须主动中断）
                runCatching { translator.cancel() }
            }
        }
    }
}
