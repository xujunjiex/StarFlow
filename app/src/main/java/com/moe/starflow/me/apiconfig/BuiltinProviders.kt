package com.moe.starflow.me.apiconfig
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import androidx.annotation.StringRes
import com.moe.starflow.R

/**
 * 内置 OpenAI 兼容 API 提供商定义
 *
 * 内置 API 始终存在，用户只能修改 API Key、提示词和模型选择。
 * 名称、URL 等核心配置不可修改。
 */
object BuiltinProviders {

    /**
     * 内置厂商工厂。
     *
     * ⚠️ **不要改回直接 `OpenAIProviderConfig(...)` 逐字段手写。**
     * `OpenAIProviderConfig` 同时有 `modelName` 和 `models` + `selectedModelIndex`，
     * 三者必须一致（面板显示 `models[selectedModelIndex]`，引擎调用 `modelName`），
     * 但它们是**三个独立字段**，手写就可能对不上 —— 智谱AI 就曾把默认模型写成
     * `modelName = "glm-4-flash-250414"` 而 `selectedModelIndex = 0`（指向 "glm-5"），
     * 表现为「面板显示 glm-5，实际调用 glm-4-flash-250414」。
     *
     * 这里由 [defaultModelIndex] 一处派生 `modelName` 与 `selectedModelIndex`，
     * 写不出不一致的数据。
     */
    private fun builtinProvider(
        name: String,
        @StringRes nameRes: Int,
        baseUrl: String,
        models: List<String>,
        /** 默认选中的模型在 [models] 中的下标；`modelName` 由它派生 */
        defaultModelIndex: Int = 0,
        consoleUrl: String,
        continuationType: String,
        /** 模型名 → 弹窗标注（如「免费·文本」）；未登记的模型不显示标注 */
        modelLabels: Map<String, String> = emptyMap(),
        defaultMangaSystemPrompt: String = DEFAULT_MANGA_SYSTEM_PROMPT,
        thinkingMode: Int = OpenAIProviderConfig.THINKING_DEFAULT,
        /** true = 不预置模型，用户在配置页手动添加或点「获取模型列表」拉取 */
        supportsModelFetch: Boolean = false
    ): OpenAIProviderConfig {
        // 允许空列表（supportsModelFetch 的厂商不预置），非空时必须给得出默认项
        require(models.isEmpty() || defaultModelIndex in models.indices) {
            "$name 的默认模型下标 $defaultModelIndex 越界（models 共 ${models.size} 项）"
        }
        return OpenAIProviderConfig(
            name = name,
            nameRes = nameRes,
            apiKey = "",
            baseUrl = baseUrl,
            // modelName 唯一真源 = models[defaultModelIndex]，不给手写机会；无预置时为空串
            modelName = models.getOrElse(defaultModelIndex) { "" },
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = models,
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            selectedModelIndex = defaultModelIndex,
            consoleUrl = consoleUrl,
            continuationType = continuationType,
            modelLabels = modelLabels,
            defaultMangaSystemPrompt = defaultMangaSystemPrompt,
            defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT,
            thinkingMode = thinkingMode,
            supportsModelFetch = supportsModelFetch
        )
    }

    internal const val DEFAULT_SYSTEM_PROMPT =
        "你是专业翻译引擎。将用户提供的文本翻译为usetolang。\n" +
        "规则：\n" +
        "1. 只输出译文，不输出解释、标注或附加内容\n" +
        "2. 保持原文格式（换行、标点风格等）\n" +
        "3. 翻译应自然流畅，符合目标语言的表达习惯\n" +
        "4. 专有名词（人名、地名、作品名）保留原文或使用通用译名\n" +
        "5. 如果文本已经是目标语言，原样返回"

    internal const val DEFAULT_USER_PROMPT =
        "将以下文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    // 漫画模式默认提示词（用户自定义 API 未配置漫画 prompt 时的回退值）
    internal const val DEFAULT_MANGA_SYSTEM_PROMPT =
        "你是漫画翻译引擎。逐条翻译以下文本为usetolang，保持每条的[N]编号格式不变。\n" +
        "规则：\n" +
        "1. 只输出译文，不输出解释、标注或附加内容\n" +
        "2. 翻译应口语化、自然，符合漫画对话的语气\n" +
        "3. 保持编号格式：[1] 译文\n" +
        "4. 象声词、感叹词根据目标语言习惯调整\n" +
        "5. 如果文本已经是目标语言，原样返回"

    internal const val DEFAULT_MANGA_USER_PROMPT =
        "将以下文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    // 漫画模式JSON格式提示词（智谱AI结构化输出用）
    private const val DEFAULT_MANGA_SYSTEM_PROMPT_JSON =
        "你是漫画翻译引擎。将每条文本翻译为usetolang后以JSON格式返回：{\"translations\":[\"译文1\",\"译文2\"]}。\n" +
        "规则：\n" +
        "1. 数组顺序与输入编号一致，只输出JSON\n" +
        "2. 翻译应口语化、自然，符合漫画对话的语气\n" +
        "3. 象声词、感叹词根据目标语言习惯调整\n" +
        "4. 如果文本已经是目标语言，原样返回"

    private const val DEFAULT_MANGA_USER_PROMPT_JSON =
        "将以下文本从usefromlang翻译为usetolang：\n\nusesourcetext"

    val providers = listOf(
        builtinProvider(
            name = "火山引擎",
            nameRes = R.string.provider_volc,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            models = listOf(
                "doubao-seed-2-0-pro-260215",
                "doubao-seed-2-0-lite-260428",
                "doubao-seed-2-0-mini-260428"
            ),
            supportsModelFetch = true,
            consoleUrl = "https://console.volcengine.com/ark",
            continuationType = OpenAIProviderConfig.CONTINUATION_STANDARD
        ),
        builtinProvider(
            name = "智谱AI",
            nameRes = R.string.provider_zhipu,
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            // ⚠️ 这份列表的**下标是持久化语义**：老用户存的 selectedModelIndex 会按新下标解释。
            // 改动后越界的下标由 applyMod 归一到 0，且 modelName 与下标一起归一。
            models = listOf(
                "glm-4-flash-250414",   // 免费·文本
                "glm-4.6v-flash",       // 免费·视觉
                "glm-4.7-flash"         // 免费·思考
            ),
            modelLabels = mapOf(
                "glm-4-flash-250414" to "免费·文本",
                "glm-4.6v-flash" to "免费·视觉",
                "glm-4.7-flash" to "免费·思考"
            ),
            supportsModelFetch = true,
            consoleUrl = "https://open.bigmodel.cn/",
            continuationType = OpenAIProviderConfig.CONTINUATION_JSON,
            defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT_JSON
        ),
        builtinProvider(
            name = "DeepSeek",
            nameRes = R.string.provider_deepseek,
            baseUrl = "https://api.deepseek.com",
            // ⚠️ DeepSeek 的模型不预置：模型线变动频繁（曾经的预设已过时），改为用户在配置页
            // 手动添加或点「获取模型列表」从 GET {baseUrl}/models 拉取。
            models = emptyList(),
            supportsModelFetch = true,
            consoleUrl = "https://platform.deepseek.com/",
            continuationType = OpenAIProviderConfig.CONTINUATION_PREFIX,
            // DeepSeek 推理模型默认会思考：默认强制关闭，保持翻译速度（原代码总是发 thinking:disabled）
            thinkingMode = OpenAIProviderConfig.THINKING_FORCE_DISABLED
        ),
        builtinProvider(
            name = "通义千问",
            nameRes = R.string.provider_qwen,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            // 同 DeepSeek：模型线变动频繁，不预置，由用户手动添加或点「获取模型列表」拉取
            models = emptyList(),
            supportsModelFetch = true,
            consoleUrl = "https://dashscope.console.aliyun.com/",
            continuationType = OpenAIProviderConfig.CONTINUATION_PARTIAL
        )
    )
}
