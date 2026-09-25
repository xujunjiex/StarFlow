package com.moe.starflow.llamacpp

import com.moe.starflow.utils.LogCollector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * LlamaCpp 本地模型（内置 Hy-MT2 + 用户导入的任意 GGUF）的统一描述符。
 *
 * 与老的 `ModelKey` 枚举的区别：那个是**编译期枚举**，只能描述 `downloadinfo.json` 里预置的模型；
 * 用户导入的模型文件名/大小/MD5 都是运行期才知道的，因此这里用运行期清单（JSON 文件）描述。
 *
 * @param id 稳定标识：内置模型 = "builtin:hymt2"；导入模型 = "imported:<fileName>"
 * @param fileName GGUF 文件名（落在 modelsDir 下，内置与导入同目录）
 * @param sizeBytes 文件字节数（导入/下载完成后写实）
 * @param md5 文件 MD5（小写 hex）。内置模型记的是**重打标前**的官方 MD5，
 *            重打标后的 MD5 记在 [retaggedMd5]
 * @param retaggedMd5 设备端把 1.25-bit 类型 42 改写成 43 之后的 MD5；未重打标为 null
 */
data class LlamaCppModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val md5: String?,
    val retaggedMd5: String? = null,
    val source: LlamaCppModelSource,
    /**
     * 文件所在子目录（相对 `getExternalFilesDir(null)`）：
     *  - 内置 Hy-MT2 = `"models"`（与下载流水线 `ModelDownloadRepository.baseDirFor` 一致）
     *  - 导入的模型 = `"llamacpp"`（LlamaCppPaths.DIR_NAME）
     */
    val dirName: String = LlamaCppPaths.DIR_NAME,
    /** 内置模型对应的下载流水线 key（ModelKey.name）；导入模型为 null */
    val builtinModelKey: String? = null,
    /** 是否含 Hy-MT2 专属角色标记的模型（决定用 Hy-MT2 专用 prompt 通道还是通用模板通道） */
    val hyProfile: Boolean = false,
    val params: LlamaCppParams = LlamaCppParams.forSource(source),
) {
    val absoluteFile: File get() = File(File(LlamaCppPaths.baseDir(), dirName), fileName)
}

enum class LlamaCppModelSource { BUILTIN, IMPORTED }

/**
 * 每个模型一套推理参数（用户已确认：per-model，而不是全局一套）。
 *
 * Hy-MT2 的默认值来自官方推荐（temp 0.7 / top_p 0.6 / top_k 20 / rep 1.05）；
 * 导入的通用 instruct 模型用更保守的默认（temp 0.6 / top_p 0.8），提示词模板由用户按模型风格自己改。
 */
data class LlamaCppParams(
    /** 翻译指令模板，占位符 {target_lang} / {source_text}（与老 HyMt2Params 一致） */
    val promptTemplate: String,
    /** 通用模型的 system 提示词（Hy-MT2 走自己的 chat 结构，不用这个） */
    val systemPrompt: String,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repetitionPenalty: Float,
    val maxTokens: Int,
    val contextSize: Int,
    val threads: Int,
    val batchThreads: Int,
    /** 通用模型：是否让模型开启思考（Qwen3 等）；默认关，翻译更快 */
    val enableThinking: Boolean,
) {
    companion object {
        /**
         * 默认翻译指令模板（**自带完整要求，不依赖 system 段**）。
         *
         * 为什么要把要求写全：内置 Hy-MT2 走的是专用对话格式（指令放 system 段、且它的
         * system/思考开关在 UI 里是隐藏的），如果要求只写在 system 提示词里，它就丢了；
         * 把要求写进模板后，内置与导入模型、两条 prompt 通道都吃同一套要求。
         * `{source_text}` 之前的部分会作为前缀 KV 缓存的 key（见 LlamaCppPrompt.buildHyPrefix）。
         */
        const val DEFAULT_PROMPT =
            "你是一名专业翻译。请把下面的文本翻译成 {target_lang}。\n" +
                "要求：\n" +
                "1、准确、自然，保持原意与语气；\n" +
                "2、尽可能保持原有格式与分段；\n" +
                "3、只输出译文，不要添加解释、标注或引号；\n" +
                "4、如果原文已经是 {target_lang}，请按原样返回。\n\n" +
                "{source_text}"

        /** 旧版默认模板：加载清单时用它判断用户是否改过提示词，改过的尊重用户设置 */
        const val LEGACY_DEFAULT_PROMPT =
            "将以下文本翻译为 {target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{source_text}"

        /**
         * 通用模型的 system 提示词：只留一句角色设定。
         * 详细规则已经在 [DEFAULT_PROMPT] 里（两条通道共用），这里再写一遍会重复。
         */
        const val DEFAULT_SYSTEM_PROMPT = "你是一名专业翻译。"

        /**
         * 默认生成线程数：按设备核心数适配。
         * 1.25-bit 解码是内存带宽瓶颈，超过 6 线程后带宽饱和、层间同步开销反而变慢。
         */
        val defaultThreads: Int
            get() = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

        /** 批量（prefill）线程数：用满设备核心（可手动调小对比速度） */
        val defaultBatchThreads: Int
            get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        fun forSource(source: LlamaCppModelSource): LlamaCppParams =
            if (source == LlamaCppModelSource.BUILTIN) {
                LlamaCppParams(
                    promptTemplate = DEFAULT_PROMPT,
                    systemPrompt = DEFAULT_SYSTEM_PROMPT,
                    temperature = 0.7f,
                    topP = 0.6f,
                    topK = 20,
                    repetitionPenalty = 1.05f,
                    maxTokens = 4096,
                    contextSize = 2048,
                    threads = defaultThreads,
                    batchThreads = defaultBatchThreads,
                    enableThinking = false,
                )
            } else {
                LlamaCppParams(
                    promptTemplate = DEFAULT_PROMPT,
                    systemPrompt = DEFAULT_SYSTEM_PROMPT,
                    temperature = 0.6f,
                    topP = 0.8f,
                    topK = 20,
                    repetitionPenalty = 1.05f,
                    maxTokens = 2048,
                    contextSize = 4096,
                    threads = defaultThreads,
                    batchThreads = defaultBatchThreads,
                    enableThinking = false,
                )
            }
    }
}

/** 模型文件目录与清单文件的单一来源。 */
object LlamaCppPaths {

    /** 模型目录名（getExternalFilesDir(null)/llamacpp） */
    const val DIR_NAME = "llamacpp"

    /** 清单文件名（放内部 filesDir，避免用户/文件管理器误删导致模型「消失」） */
    const val MANIFEST_NAME = "llamacpp_models.json"

    /** 重打标标记后缀：<gguf>.retagged，内容为重打标后的 MD5 */
    const val RETAG_SUFFIX = ".retagged"

    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    fun isInitialized(): Boolean = appContext != null

    /** 应用专属外置根目录（不可用时退回内部 filesDir）。 */
    fun baseDir(): File {
        val ctx = appContext ?: error("LlamaCppPaths.init(context) 未调用")
        return ctx.getExternalFilesDir(null) ?: ctx.filesDir
    }

    /** 导入模型的目录根（内置 Hy-MT2 不在这里，它在下载流水线的 `models/`）。 */
    fun modelsDir(): File {
        return File(baseDir(), DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    fun manifestFile(): File {
        val ctx = appContext ?: error("LlamaCppPaths.init(context) 未调用")
        return File(ctx.filesDir, MANIFEST_NAME)
    }

    fun retagMarker(gguf: File): File = File(gguf.parentFile, gguf.name + RETAG_SUFFIX)
}

// ─────────────────────────── JSON 编解码 ───────────────────────────
// 清单是本地文件，字段少但可能被用户手工改；解析一律容错（坏条目跳过并记日志），
// 不让一个坏条目把整份清单读废。

internal object LlamaCppJson {

    private const val TAG = "LlamaCppJson"

    fun encode(models: List<LlamaCppModel>): String {
        val arr = JSONArray()
        models.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("displayName", m.displayName)
                put("fileName", m.fileName)
                put("sizeBytes", m.sizeBytes)
                put("md5", m.md5 ?: JSONObject.NULL)
                put("retaggedMd5", m.retaggedMd5 ?: JSONObject.NULL)
                put("source", m.source.name)
                put("dirName", m.dirName)
                put("builtinModelKey", m.builtinModelKey ?: JSONObject.NULL)
                put("hyProfile", m.hyProfile)
                put("params", JSONObject().apply {
                    put("promptTemplate", m.params.promptTemplate)
                    put("systemPrompt", m.params.systemPrompt)
                    put("temperature", m.params.temperature.toDouble())
                    put("topP", m.params.topP.toDouble())
                    put("topK", m.params.topK)
                    put("repetitionPenalty", m.params.repetitionPenalty.toDouble())
                    put("maxTokens", m.params.maxTokens)
                    put("contextSize", m.params.contextSize)
                    put("threads", m.params.threads)
                    put("batchThreads", m.params.batchThreads)
                    put("enableThinking", m.params.enableThinking)
                })
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("models", arr)
        }.toString(2)
    }

    fun decode(text: String): List<LlamaCppModel> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<LlamaCppModel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val fileName = o.optString("fileName")
            if (fileName.isBlank()) continue
            val source = runCatching { LlamaCppModelSource.valueOf(o.optString("source")) }
                .getOrDefault(LlamaCppModelSource.IMPORTED)
            val p = o.optJSONObject("params")
            val defaults = LlamaCppParams.forSource(source)
            out += LlamaCppModel(
                id = id,
                displayName = o.optString("displayName").ifBlank { fileName },
                fileName = fileName,
                sizeBytes = o.optLong("sizeBytes", 0L),
                md5 = o.optString("md5").takeIf { it.isNotBlank() && it != "null" },
                retaggedMd5 = o.optString("retaggedMd5").takeIf { it.isNotBlank() && it != "null" },
                source = source,
                dirName = o.optString("dirName").takeIf { it.isNotBlank() }
                    ?: if (source == LlamaCppModelSource.BUILTIN) "models" else LlamaCppPaths.DIR_NAME,
                builtinModelKey = o.optString("builtinModelKey").takeIf { it.isNotBlank() && it != "null" },
                hyProfile = o.optBoolean("hyProfile", false),
                params = LlamaCppParams(
                    promptTemplate = p?.optString("promptTemplate")?.takeIf { it.isNotBlank() } ?: defaults.promptTemplate,
                    systemPrompt = p?.optString("systemPrompt") ?: defaults.systemPrompt,
                    temperature = p?.optDouble("temperature", defaults.temperature.toDouble())?.toFloat() ?: defaults.temperature,
                    topP = p?.optDouble("topP", defaults.topP.toDouble())?.toFloat() ?: defaults.topP,
                    topK = p?.optInt("topK", defaults.topK) ?: defaults.topK,
                    repetitionPenalty = p?.optDouble("repetitionPenalty", defaults.repetitionPenalty.toDouble())?.toFloat()
                        ?: defaults.repetitionPenalty,
                    maxTokens = p?.optInt("maxTokens", defaults.maxTokens) ?: defaults.maxTokens,
                    contextSize = p?.optInt("contextSize", defaults.contextSize) ?: defaults.contextSize,
                    threads = p?.optInt("threads", defaults.threads) ?: defaults.threads,
                    batchThreads = p?.optInt("batchThreads", defaults.batchThreads) ?: defaults.batchThreads,
                    enableThinking = p?.optBoolean("enableThinking", defaults.enableThinking) ?: defaults.enableThinking,
                ),
            )
        }
        LogCollector.d(TAG, "清单解析：${out.size} 个模型")
        return out
    }
}
