package com.moe.starflow.llamacpp

import translationapi.llamacpp.LlamaCppNative

/**
 * 提示词拼装：两条通道
 *
 * 1. **Hy-MT2 通道**（内置 Hy-MT2 / 任何 vocab 里带 `hy_User`/`hy_Assistant` 角色标记的 GGUF）：
 *    沿用老的 `HyMt2Prompt` 语义 —— 指令放 system 段、原文放 user 段，由桥接补角色标记，
 *    并靠「固定指令前缀」做前缀 KV 缓存（跨翻译只 prefill 一次）。
 * 2. **通用通道**（任意 instruct GGUF）：用模型**自带的 Jinja 模板**渲染 system + user，
 *    渲染失败时回退 ChatML。前缀缓存用「渲染后文本中原文之前的部分」。
 */
object LlamaCppPrompt {

    // ───────────────────── Hy-MT2 通道 ─────────────────────

    /** 替换 {target_lang} / {source_text} 占位符。 */
    fun buildHy(template: String, targetName: String, sourceText: String): String =
        template
            .replace("{target_lang}", targetName)
            .replace("{source_text}", sourceText)

    /** 固定指令前缀（不含待翻译文本）：模板中 {source_text} 之前、{target_lang} 替换后的部分。 */
    fun buildHyPrefix(template: String, targetName: String): String =
        template
            .replace("{target_lang}", targetName)
            .substringBefore("{source_text}")

    // ───────────────────── 通用通道 ─────────────────────

    data class Rendered(val prompt: String, val prefix: String)

    /**
     * 单段翻译的通用 prompt：system = 模型参数里的 systemPrompt；user = 指令 + 原文。
     * prefix = 渲染结果里原文之前的部分（原文在渲染结果中出现时才有值）。
     */
    fun buildGeneric(
        handle: Long,
        params: LlamaCppParams,
        targetName: String,
        sourceText: String,
    ): Rendered {
        val instr = params.promptTemplate
            .replace("{target_lang}", targetName)
            .substringBefore("{source_text}")
        val userText = (instr + sourceText).trim()
        val rendered = renderChat(
            handle = handle,
            systemPrompt = params.systemPrompt,
            roles = intArrayOf(0),
            contents = arrayOf(userText),
            enableThinking = params.enableThinking,
        )
        val prefix = if (sourceText.isNotEmpty() && rendered.contains(sourceText)) {
            rendered.substringBefore(sourceText)
        } else ""
        return Rendered(rendered, prefix)
    }

    /**
     * 多轮对话的通用 prompt：system + 历史 user/assistant（+ 本轮 user），末尾补 assistant 开始标记。
     */
    fun buildGenericChat(
        handle: Long,
        systemPrompt: String,
        roles: IntArray,
        contents: Array<String>,
        enableThinking: Boolean,
    ): String = renderChat(handle, systemPrompt, roles, contents, enableThinking)

    /**
     * 走模型自带 Jinja 模板（nativeFormatChat）；模型没有模板或渲染异常（返回空串）时回退 ChatML。
     * 关思考时在 assistant 段预填一个空 `<think></think>`，等价于 Qwen3 的 enable_thinking=false。
     */
    private fun renderChat(
        handle: Long,
        systemPrompt: String,
        roles: IntArray,
        contents: Array<String>,
        enableThinking: Boolean,
    ): String {
        val viaTemplate = runCatching {
            LlamaCppNative.nativeFormatChat(
                handle = handle,
                systemPrompt = systemPrompt,
                roles = roles,
                contents = contents,
                addAssistant = true,
                enableThinking = enableThinking,
            )
        }.getOrNull()
        if (!viaTemplate.isNullOrBlank()) return viaTemplate
        return chatmlFallback(systemPrompt, roles, contents, enableThinking)
    }

    /** 通用 ChatML 兜底（模型没带 chat_template 时用）。 */
    fun chatmlFallback(
        systemPrompt: String,
        roles: IntArray,
        contents: Array<String>,
        enableThinking: Boolean,
    ): String = buildString {
        if (systemPrompt.isNotBlank()) {
            append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
        }
        for (i in contents.indices) {
            val role = if (roles.getOrNull(i) == 1) "assistant" else "user"
            append("<|im_start|>").append(role).append('\n')
            append(contents[i]).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
        if (!enableThinking) append("<think>\n\n</think>\n\n")
    }
}
