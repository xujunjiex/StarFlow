package com.moe.starflow.utils

import com.moe.starflow.translate.ContextAwareTranslation
import com.moe.starflow.translate.TranslationTextAPI

/**
 * AI 上下文（历史翻译对）的 **token 预算** 与 **裁剪** —— 全项目唯一实现。
 *
 * ### 为什么不再按「轮数」保留
 * 轮数和 token 完全不成比例：一页 34 个气泡的漫画批量翻译，**单轮**就能吃掉几千 token；
 * 而两句短对话只有几十。按轮数保留时用户看到的「5 轮」在两种场景下差两个数量级。
 * 改成 token 预算后，档位直接对应请求体大小 —— 也就是直接对应速度与花费。
 *
 * ### 预算来源（两种，刻意互不干扰）
 * - **网络 API（OpenAI 兼容）**：用户设置 [KEY_TOKEN_BUDGET]（4K / 8K / … / 128K，默认 32K）
 * - **本地 LlamaCpp**：**不受设置影响**，用模型自己的上下文长度（`contextSize − maxTokens`），
 *   见 [localBudgetTokens] —— 塞进超过 `n_ctx` 的 prompt 会被 native 拒绝（`__PROMPT_TOO_LONG__`），
 *   更糟时直接 ggml_abort 闪退
 * - **NLLB 等纯机器翻译**：不接上下文（没有提示词通道），见各自类注释
 *
 * ### 裁剪只有一个实现
 * 原先 `FloatingBallService` 与 `TranslateUtils` 各写一份 `while (size > max) removeFirst()`，
 * 口径已经分叉。现在两处都只调本对象的 [trim] / [trimInPlace] / [applyTo]。
 * 另外保留**按条数的兜底上限** [MAX_TURNS]（200 轮），防止历史无界增长。
 *
 * 本对象里的 [parseBudget] / [trim] / [localBudgetTokens] / [localContextBlock] 都是纯函数，
 * 单测见 `app/src/test/java/com/moe/starflow/utils/ContextBudgetTest.kt`。
 */
object ContextBudget {

    /** 设置项 key：上下文长度（token），ListPreference，值存字符串（见 res/xml/personalization.xml） */
    const val KEY_TOKEN_BUDGET = "ctx_token_budget"

    /** 默认上下文长度：32K token */
    const val DEFAULT_TOKEN_BUDGET = 32 * 1024

    /** 档位下限 / 上限（与 `ctx_token_budget_values` 的两端一致，越界值按此收敛） */
    const val MIN_TOKEN_BUDGET = 4 * 1024
    const val MAX_TOKEN_BUDGET = 128 * 1024

    /** 按条数的兜底上限：即使 token 预算极大，历史也不会无界增长 */
    const val MAX_TURNS = 200

    /** 本地模型的历史区起止标记（**中英双语**：两类模型都看得懂，也便于用户排查日志） */
    const val LOCAL_CONTEXT_HEADER = "[Context/前文]"
    const val LOCAL_CURRENT_HEADER = "[Current/当前]"

    /**
     * 解析设置里的 token 预算（存的是字符串）。
     *
     * 非法值（null / 空 / 非数字 / ≤0）→ [DEFAULT_TOKEN_BUDGET]；
     * 超出 [MIN_TOKEN_BUDGET]~[MAX_TOKEN_BUDGET] 的值向区间收敛 —— 老用户手改 prefs
     * 或跨版本残留的怪值不该把内存里的历史撑爆。
     */
    fun parseBudget(raw: String?): Int {
        val v = raw?.trim()?.toIntOrNull() ?: return DEFAULT_TOKEN_BUDGET
        if (v <= 0) return DEFAULT_TOKEN_BUDGET
        return v.coerceIn(MIN_TOKEN_BUDGET, MAX_TOKEN_BUDGET)
    }

    /** 从 prefs 读上下文预算（每次现读，设置改完下一次翻译即生效）。 */
    fun budgetOf(prefs: CustomPreference): Int =
        parseBudget(prefs.getString(KEY_TOKEN_BUDGET, DEFAULT_TOKEN_BUDGET.toString()))

    /**
     * 按 token 预算裁剪历史：**从最新往前累加**，装不下的最旧轮直接丢。
     *
     * 「装不下就停」而不是「跳过继续往前找」：结果必须是一段**连续的最新窗口**
     * （跳过某一轮却带上更旧的一轮，模型看到的时间线是断的，比少给几轮更糟）。
     *
     * ⚠️ 预算是**硬上限**：最新一轮本身就超预算时返回空表 —— 宁可不带上下文，
     * 也不能让本地模型的 prompt 超 `n_ctx`（会闪退）。
     *
     * @param budgetTokens 历史可用的 token 预算（≤0 → 空表）
     * @param maxTurns 按条数的兜底上限（默认 [MAX_TURNS]）
     * @return 按时间顺序（旧 → 新）排列的新列表，原列表不被修改
     */
    fun trim(
        history: List<Pair<String, String>>,
        budgetTokens: Int,
        maxTurns: Int = MAX_TURNS,
    ): List<Pair<String, String>> {
        if (history.isEmpty() || budgetTokens <= 0 || maxTurns <= 0) return emptyList()
        val kept = ArrayList<Pair<String, String>>(minOf(history.size, maxTurns))
        var used = 0
        for (i in history.indices.reversed()) {
            if (kept.size >= maxTurns) break
            val pair = history[i]
            val cost = TokenEstimator.estimatePair(pair.first, pair.second)
            if (used + cost > budgetTokens) break
            used += cost
            kept.add(pair)
        }
        kept.reverse()
        return kept
    }

    /**
     * [trim] 的原地版本：把 [history] 裁到预算内并返回保留的轮数。
     * 供服务侧的 `LinkedList` 使用（历史在内存里也必须是有界的）。
     */
    fun trimInPlace(
        history: MutableList<Pair<String, String>>,
        budgetTokens: Int,
        maxTurns: Int = MAX_TURNS,
    ): Int {
        val kept = trim(history, budgetTokens, maxTurns)
        if (kept.size != history.size) {
            history.clear()
            history.addAll(kept)
        }
        return kept.size
    }

    /**
     * 本地 LlamaCpp 模型可用于**历史**的 token 预算。
     *
     * = 模型上下文长度 − 预留生成空间。预留取该模型的 `maxTokens`（译文长度上限）；
     * `maxTokens` 取不到或不合理（≤0 / ≥ ctx，例如内置 Hy-MT2 默认 ctx 2048、max 4096）
     * 时退回 **ctx 的 1/4**。
     */
    fun localBudgetTokens(contextSize: Int, maxTokens: Int): Int {
        if (contextSize <= 0) return 0
        val reserve = if (maxTokens in 1 until contextSize) maxTokens else contextSize / 4
        return (contextSize - reserve).coerceAtLeast(0)
    }

    /**
     * 本地模型**本次真正**能分给历史的预算 = [localBudgetTokens] − 本轮原文与指令占用的 token。
     *
     * 为什么还要减一次：`localBudgetTokens` 只预留了「生成空间」，而**原文本身**（含模板指令）
     * 也要占 `n_ctx`。不减的话，原文长时 history + 原文一起把 ctx 顶穿 → native 直接返回
     * `__PROMPT_TOO_LONG__`（翻译失败）；长原文时更该做的是**不带上下文**把这一句翻出来。
     */
    fun localHistoryBudget(contextSize: Int, maxTokens: Int, currentText: String): Int {
        val base = localBudgetTokens(contextSize, maxTokens)
        if (base <= 0) return 0
        return (base - TokenEstimator.estimate(currentText) - LOCAL_BLOCK_OVERHEAD_TOKENS).coerceAtLeast(0)
    }

    /** 本地历史块的固定开销：两个标记行（`[Context/前文]` / `[Current/当前]`）的粗估。 */
    private const val LOCAL_BLOCK_OVERHEAD_TOKENS = 16

    /**
     * 把历史拼成一段**前缀文本**，直接接在待翻译原文之前（本地引擎用，见 `LlamaCppTranslation`）。
     *
     * 形态（`[Current/当前]` 之后由调用方接原文）：
     * ```
     * [Context/前文]
     * 原文1
     * 译文1
     * [Current/当前]
     * ```
     * 历史为空或全部超预算时返回空串（调用方原样使用原文）。
     */
    fun localContextBlock(history: List<Pair<String, String>>, budgetTokens: Int): String {
        val kept = trim(history, budgetTokens)
        if (kept.isEmpty()) return ""
        return buildString {
            append(LOCAL_CONTEXT_HEADER).append('\n')
            for ((src, tgt) in kept) {
                append(src.trim()).append('\n')
                append(tgt.trim()).append('\n')
            }
            append(LOCAL_CURRENT_HEADER).append('\n')
        }
    }

    /**
     * **写上下文的唯一入口**：把历史推给支持上下文的引擎（[ContextAwareTranslation]）。
     *
     * 网络 API 由引擎内部按 [budgetOf] 的预算裁剪；本地 LlamaCpp **刻意忽略这个预算**，
     * 改用自己的模型 ctx（预算作为参数传入只是让引擎知道"设置里写的是多少"，便于日志）。
     * 不支持的引擎（NLLB / 机器翻译 / 用户自建 API）直接跳过。
     */
    fun applyTo(
        translator: TranslationTextAPI?,
        history: List<Pair<String, String>>,
        enabled: Boolean,
        prefs: CustomPreference,
    ) {
        val aware = translator as? ContextAwareTranslation ?: return
        aware.updateContext(history, enabled, budgetOf(prefs))
    }

    /**
     * 清空引擎里已推入的上下文（服务销毁 / 新会话开始时用）。
     *
     * ⚠️ 必须显式清：`LlamaCppTranslation` 是**进程级共享实例**（`LlamaCppSharedHolder`），
     * 服务重启后还是同一个对象 —— 不清的话上一个会话的历史会接着被当上下文用。
     */
    fun clear(translator: TranslationTextAPI?) {
        val aware = translator as? ContextAwareTranslation ?: return
        aware.updateContext(emptyList(), false, DEFAULT_TOKEN_BUDGET)
    }
}
