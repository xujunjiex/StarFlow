package com.moe.starflow.translate

/**
 * 支持「历史上下文」的翻译引擎。
 *
 * 实现方：
 * - `translationapi.openaitranslation.OpenAITranslation`（聚合 AI / OpenAI 兼容 API）
 * - `com.moe.starflow.llamacpp.LlamaCppTranslation`（本地 GGUF）
 *
 * **刻意不实现**：`NLLBTranslation` 等纯机器翻译引擎、`CustomTranslationText`（用户自建 API 的
 * 提示词通道不接历史）—— 它们没有可插入历史的对话结构，塞进去只会污染输入。
 *
 * 调用方只通过 [com.moe.starflow.utils.ContextBudget.applyTo] 写上下文（全项目唯一入口），
 * 不要在各 Service 里各写一份写入逻辑。
 */
interface ContextAwareTranslation {

    /**
     * 更新历史上下文。
     *
     * **预算由各引擎自己说了算**：
     * - OpenAI 兼容 API：用 [budgetTokens]（用户设置的 `ctx_token_budget`）裁剪后存入
     * - 本地 LlamaCpp：**忽略** [budgetTokens]，按模型自己的上下文长度裁剪（用模型 ctx 减预留生成空间）
     *   —— 本地模型超 `n_ctx` 会被 native 拒绝甚至 ggml_abort，必须服从模型而不是设置
     *
     * @param history 完整历史（旧 → 新）
     * @param enabled false 时引擎应丢弃全部历史（不只是不使用），避免内存里留着废数据
     * @param budgetTokens 设置里的 token 预算；≤0 表示调用方未指定（引擎用默认档）。本地模型刻意忽略（见上）
     */
    fun updateContext(history: List<Pair<String, String>>, enabled: Boolean, budgetTokens: Int = 0)
}
