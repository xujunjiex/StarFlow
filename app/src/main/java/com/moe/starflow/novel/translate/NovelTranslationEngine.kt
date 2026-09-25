package com.moe.starflow.novel.translate

import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 一章的翻译进度快照（累积：每次 emit 带上目前已拿到的全部译文）。 */
data class NovelChapterProgress(
    val chapterIndex: Int,
    val translations: Map<Int, String>,
    val isComplete: Boolean,
)

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

    fun translateChapter(
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        sourceLang: String,
        targetLang: String,
        batchSize: Int = NovelTranslationBatch.DEFAULT_BATCH_PARAGRAPHS,
    ): Flow<NovelChapterProgress> = callbackFlow {
        val units = paragraphs
            .filter { it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank() }
            .map { NovelUnit(it.index, it.originalText) }

        if (units.isEmpty()) {
            trySend(NovelChapterProgress(chapterIndex, emptyMap(), isComplete = true))
            close()
            return@callbackFlow
        }

        val batches = NovelTranslationBatch.buildBatches(units, batchSize)
        val accumulated = linkedMapOf<Int, String>()

        val worker = launch {
            var doneCount = 0
            for (batch in batches) {
                val prompt = buildPromptText(units, batch)
                var parsed = requestAndParse(prompt, batch, sourceLang, targetLang)

                // 整批失败 → 逐段重试一次（内容审查常只针对其中一段）
                if (parsed.isEmpty() && batch.size > 1) {
                    LogCollector.w(TAG, "整批失败（${batch.size} 段），降级逐段重试")
                    for (pi in batch) {
                        parsed = parsed + requestAndParse(
                            buildPromptText(units, listOf(pi)),
                            listOf(pi),
                            sourceLang,
                            targetLang,
                        )
                    }
                }

                accumulated.putAll(parsed)
                doneCount += batch.size
                trySend(
                    NovelChapterProgress(
                        chapterIndex = chapterIndex,
                        translations = accumulated.toMap(),
                        isComplete = doneCount >= units.size,
                    )
                )
            }
            // 收尾：确保完成态一定发出（全失败也要发，否则上层一直等）
            trySend(NovelChapterProgress(chapterIndex, accumulated.toMap(), isComplete = true))
            close()
        }
        awaitClose { worker.cancel() }
    }

    private fun buildPromptText(units: List<NovelUnit>, batch: List<Int>): String =
        "$NUMBERING_INSTRUCTION\n${NovelTranslationBatch.buildPrompt(units, batch)}"

    private suspend fun requestAndParse(
        prompt: String,
        batch: List<Int>,
        sourceLang: String,
        targetLang: String,
    ): Map<Int, String> {
        val reply = requestOnce(prompt, sourceLang, targetLang) ?: return emptyMap()
        return NovelTranslationBatch.parseTolerant(reply, batch)
    }

    /** 一次请求；失败/取消返回 null。 */
    private suspend fun requestOnce(
        prompt: String,
        sourceLang: String,
        targetLang: String,
    ): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            var resumed = false
            translator.translate(prompt, sourceLang, targetLang) { result ->
                if (resumed) return@translate
                resumed = true
                if (cont.isActive) {
                    cont.resume(if (result is TranslationResult.Success) result.translatedText else null)
                }
            }
            cont.invokeOnCancellation {
                // 取消时通知底层中断在途请求（网络 API 取消后回调不会再来，必须主动中断）
                runCatching { translator.cancel() }
            }
        }
    }
}
