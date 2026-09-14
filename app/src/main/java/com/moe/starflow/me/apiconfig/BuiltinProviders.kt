package com.moe.starflow.me.apiconfig
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import com.moe.starflow.R

/**
 * 内置 OpenAI 兼容 API 提供商定义
 *
 * 内置 API 始终存在，用户只能修改 API Key、提示词和模型选择。
 * 名称、URL 等核心配置不可修改。
 */
object BuiltinProviders {

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
        OpenAIProviderConfig(
            name = "火山引擎",
            nameRes = R.string.provider_volc,
            apiKey = "",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            modelName = "doubao-seed-2-0-pro-260215",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = listOf(
                "doubao-seed-2-0-pro-260215",
                "doubao-seed-2-0-lite-260428",
                "doubao-seed-2-0-mini-260428"
            ),
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT,
            defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT,
            selectedModelIndex = 0,
            consoleUrl = "https://console.volcengine.com/ark",
            continuationType = OpenAIProviderConfig.CONTINUATION_STANDARD
        ),
        OpenAIProviderConfig(
            name = "智谱AI",
            nameRes = R.string.provider_zhipu,
            apiKey = "",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            modelName = "glm-4-flash-250414",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = listOf(
                "glm-5",
                "glm-4.7",
                "glm-4.7-flash",
                "glm-4-flash-250414"
            ),
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT_JSON,
            defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT_JSON,
            selectedModelIndex = 0,
            consoleUrl = "https://open.bigmodel.cn/",
            continuationType = OpenAIProviderConfig.CONTINUATION_JSON
        ),
        OpenAIProviderConfig(
            name = "DeepSeek",
            nameRes = R.string.provider_deepseek,
            apiKey = "",
            baseUrl = "https://api.deepseek.com",
            modelName = "deepseek-v4-pro",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = listOf("deepseek-v4-pro", "deepseek-v4-flash"),
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT,
            defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT,
            selectedModelIndex = 0,
            consoleUrl = "https://platform.deepseek.com/",
            continuationType = OpenAIProviderConfig.CONTINUATION_PREFIX,
            // DeepSeek 推理模型默认会思考：默认强制关闭，保持翻译速度（原代码总是发 thinking:disabled）
            thinkingMode = OpenAIProviderConfig.THINKING_FORCE_DISABLED
        ),
        OpenAIProviderConfig(
            name = "通义千问",
            nameRes = R.string.provider_qwen,
            apiKey = "",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            modelName = "qwen3.7-plus",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            userPrompt = DEFAULT_USER_PROMPT,
            providerType = OpenAIProviderConfig.PROVIDER_TYPE_BUILTIN,
            models = listOf(
                "qwen3.7-max",
                "qwen3.7-plus",
                "qwen3.6-plus",
                "qwen3.6-flash"
            ),
            defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT,
            defaultUserPrompt = DEFAULT_USER_PROMPT,
            defaultMangaSystemPrompt = DEFAULT_MANGA_SYSTEM_PROMPT,
            defaultMangaUserPrompt = DEFAULT_MANGA_USER_PROMPT,
            selectedModelIndex = 0,
            consoleUrl = "https://dashscope.console.aliyun.com/",
            continuationType = OpenAIProviderConfig.CONTINUATION_PARTIAL
        )
    )
}
