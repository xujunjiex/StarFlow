package com.moe.starflow.chat
import com.moe.starflow.translate.widget.*

import android.content.Context
import com.moe.starflow.me.apiconfig.ConfigurationStorage
import com.moe.starflow.R
import com.moe.starflow.translate.TranslationResult
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import translationapi.openaitranslation.OpenAITranslation

/**
 * OpenAI 兼容 API 对话引擎：system = 默认助手提示，历史轮次经 updateContext 传入，
 * 当前输入经 userPrompt 模板（"{usesourcetext}" 透传）。复用现有 OpenAI 客户端。
 */
class OpenAIChatEngine(context: Context, prefs: CustomPreference) : ChatEngine {

    private val translator: OpenAITranslation = run {
        val providers = ConfigurationStorage.loadAllProviders(prefs)
        val idx = prefs.getInt("OpenAI_Selected_Provider", 0)
        val p = providers.getOrNull(idx) ?: throw IllegalStateException(context.getString(R.string.error_no_openai_config))
        OpenAITranslation(
            apiKey = p.apiKey,
            baseUrl = p.baseUrl,
            model = p.modelName,
            systemPrompt = ChatTemplates.DEFAULT_SYSTEM,
            userPrompt = "{usesourcetext}",
            autoAppendPath = p.autoAppendPath,
            thinkingMode = p.thinkingMode
        )
    }

    override fun chat(
        history: List<ChatMessage>,
        input: String,
        onPhase: (String) -> Unit,
        onPartial: (String) -> Unit,
        callback: (ChatResult) -> Unit
    ) {
        // 历史 → (user, assistant) 对（OpenAI 上下文机制）
        val pairs = buildPairs(history)
        LogCollector.d(TAG, "OpenAI chat 发送: system='${ChatTemplates.DEFAULT_SYSTEM.take(60)}' 历史${pairs.size}轮")
        LogCollector.d(TAG, "OpenAI chat 消息: " + pairs.flatMap { listOf("user:${it.first.take(80)}", "assistant:${it.second.take(80)}") }
            .joinToString(" | ") + " → 当前user:'${input.take(80)}'")
        translator.updateContext(pairs, pairs.isNotEmpty())
        translator.getTranslationStreaming(
            input, "zh", "zh",
            onPhase = onPhase,
            onPartial = onPartial
        ) { result ->
            when (result) {
                is TranslationResult.Success -> callback(ChatResult.Success(result.translatedText))
                is TranslationResult.Error -> callback(ChatResult.Error(result.error))
            }
        }
    }

    override fun cancel() { translator.cancelTranslation() }

    override fun release() { translator.release() }

    companion object {
        private const val TAG = "OpenAIChatEngine"
    }

    private fun buildPairs(history: List<ChatMessage>): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        var pendingUser: String? = null
        for (m in history) {
            when (m.role) {
                ChatRole.USER -> pendingUser = m.content
                ChatRole.ASSISTANT -> {
                    val u = pendingUser
                    if (u != null && m.content.isNotBlank()) {
                        pairs.add(u to m.content)
                        pendingUser = null
                    }
                }
            }
        }
        return pairs
    }
}
