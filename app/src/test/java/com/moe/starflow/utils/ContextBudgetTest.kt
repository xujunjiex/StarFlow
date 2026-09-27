package com.moe.starflow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedList

/**
 * `ContextBudget` 纯逻辑回归守卫（纯 JVM，无需 Robolectric）：
 * 预算解析、按 token 裁剪、本地模型预算、本地历史文本拼接。
 *
 * 这些是「上下文从按轮数改成按 token 预算」的核心口径，改动前先看这里的用例在锁什么。
 */
class ContextBudgetTest {

    // ───────────────────── 预算解析 ─────────────────────

    @Test
    fun parseBudget_validValues() {
        assertEquals(4096, ContextBudget.parseBudget("4096"))
        assertEquals(32768, ContextBudget.parseBudget("32768"))
        assertEquals(131072, ContextBudget.parseBudget("131072"))
    }

    @Test
    fun parseBudget_invalidFallsBackToDefault() {
        assertEquals(ContextBudget.DEFAULT_TOKEN_BUDGET, ContextBudget.parseBudget(null))
        assertEquals(ContextBudget.DEFAULT_TOKEN_BUDGET, ContextBudget.parseBudget(""))
        assertEquals(ContextBudget.DEFAULT_TOKEN_BUDGET, ContextBudget.parseBudget("abc"))
        assertEquals(ContextBudget.DEFAULT_TOKEN_BUDGET, ContextBudget.parseBudget("0"))
        assertEquals(ContextBudget.DEFAULT_TOKEN_BUDGET, ContextBudget.parseBudget("-100"))
    }

    @Test
    fun parseBudget_isClampedToDeclaredRange() {
        // 老用户手改 prefs / 跨版本残留的怪值不能把内存里的历史撑爆
        assertEquals(ContextBudget.MIN_TOKEN_BUDGET, ContextBudget.parseBudget("1"))
        assertEquals(ContextBudget.MAX_TOKEN_BUDGET, ContextBudget.parseBudget("99999999"))
    }

    @Test
    fun defaultBudgetIs32k() {
        assertEquals(32768, ContextBudget.DEFAULT_TOKEN_BUDGET)
    }

    // ───────────────────── 按 token 裁剪 ─────────────────────

    private fun cjk(n: Int): String = "字".repeat(n)   // 1 token / 字

    @Test
    fun trim_emptyHistory() {
        assertTrue(ContextBudget.trim(emptyList(), 1024).isEmpty())
    }

    @Test
    fun trim_allFitsKeepsOrder() {
        val history = listOf(
            "原文一" to "译文一",
            "原文二" to "译文二",
            "原文三" to "译文三",
        )
        // 每轮 6 token，预算 100 → 全留下，且顺序仍是 旧 → 新
        assertEquals(history, ContextBudget.trim(history, 100))
    }

    @Test
    fun trim_dropsOldestUntilItFits() {
        // 每轮 (src 4 + tgt 4) = 8 token；预算 20 → 只能留最近 2 轮
        val h1 = cjk(4) to cjk(4)
        val h2 = cjk(4) to cjk(4)
        val h3 = cjk(4) to cjk(4)
        val kept = ContextBudget.trim(listOf(h1, h2, h3), 20)
        assertEquals(2, kept.size)
        assertEquals(listOf(h2, h3), kept)
    }

    @Test
    fun trim_keepsContiguousNewestWindow() {
        // 中间一轮特别大：不能「跳过它、带上更旧的小轮」——时间线断了比少几轮更糟
        val small1 = cjk(2) to cjk(2)      // 4
        val huge = cjk(50) to cjk(50)      // 100
        val small2 = cjk(2) to cjk(2)      // 4
        val kept = ContextBudget.trim(listOf(small1, huge, small2), 20)
        assertEquals(listOf(small2), kept)
    }

    @Test
    fun trim_oversizedNewestTurnYieldsEmpty() {
        // 预算是硬上限：最新一轮就超预算 → 宁可不带上下文（本地模型超 ctx 会闪退）
        val kept = ContextBudget.trim(listOf(cjk(100) to cjk(100)), 20)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun trim_exactBoundaryIsKept() {
        val pair = cjk(5) to cjk(5)   // 恰好 10 token
        assertEquals(listOf(pair), ContextBudget.trim(listOf(pair), 10))
        assertTrue(ContextBudget.trim(listOf(pair), 9).isEmpty())
    }

    @Test
    fun trim_zeroOrNegativeBudgetIsEmpty() {
        val history = listOf("a" to "b")
        assertTrue(ContextBudget.trim(history, 0).isEmpty())
        assertTrue(ContextBudget.trim(history, -5).isEmpty())
    }

    @Test
    fun trim_respectsMaxTurnsBackstop() {
        val history = (1..300).map { "a$it" to "b$it" }
        // 预算足够，但按条数的兜底上限 200 轮必须生效
        assertEquals(ContextBudget.MAX_TURNS, ContextBudget.trim(history, 1_000_000).size)
        assertEquals(3, ContextBudget.trim(history, 1_000_000, maxTurns = 3).size)
        assertEquals(0, ContextBudget.trim(history, 1_000_000, maxTurns = 0).size)
    }

    @Test
    fun trim_doesNotMutateInput() {
        val history = LinkedList(listOf(cjk(10) to cjk(10), cjk(1) to cjk(1)))
        ContextBudget.trim(history, 5)
        assertEquals(2, history.size)
    }

    // ───────────────────── 原地裁剪（服务侧 LinkedList） ─────────────────────

    @Test
    fun trimInPlace_keepsNewestAndReportsCount() {
        val history = LinkedList(listOf(cjk(4) to cjk(4), cjk(4) to cjk(4), cjk(4) to cjk(4)))
        val kept = ContextBudget.trimInPlace(history, 20)
        assertEquals(2, kept)
        assertEquals(2, history.size)
        assertEquals(cjk(4), history.first.first)
        assertEquals(cjk(4), history.last.first)
    }

    @Test
    fun trimInPlace_noopWhenEverythingFits() {
        val history = LinkedList(listOf("a" to "b"))
        assertEquals(1, ContextBudget.trimInPlace(history, 1000))
        assertEquals(1, history.size)
    }

    @Test
    fun trimInPlace_clearsWhenBudgetZero() {
        val history = LinkedList(listOf("a" to "b"))
        assertEquals(0, ContextBudget.trimInPlace(history, 0))
        assertTrue(history.isEmpty())
    }

    // ───────────────────── 本地模型预算 ─────────────────────

    @Test
    fun localBudget_usesMaxTokensAsReserve() {
        // ctx 4096 − maxTokens 2048 = 2048（导入模型的默认组合）
        assertEquals(2048, ContextBudget.localBudgetTokens(4096, 2048))
    }

    @Test
    fun localBudget_fallsBackToQuarterWhenMaxTokensUnusable() {
        // 内置 Hy-MT2 默认 ctx 2048 / max 4096（max ≥ ctx，不可用）→ 预留 ctx/4 = 512
        assertEquals(1536, ContextBudget.localBudgetTokens(2048, 4096))
        assertEquals(3072, ContextBudget.localBudgetTokens(4096, 0))
        assertEquals(3072, ContextBudget.localBudgetTokens(4096, -1))
    }

    @Test
    fun localBudget_invalidContextSizeIsZero() {
        assertEquals(0, ContextBudget.localBudgetTokens(0, 512))
        assertEquals(0, ContextBudget.localBudgetTokens(-1, 512))
    }

    @Test
    fun localHistoryBudget_subtractsCurrentText() {
        // 预算 2048 − 原文 100 token − 固定开销 16 = 1932
        assertEquals(1932, ContextBudget.localHistoryBudget(4096, 2048, cjk(100)))
    }

    @Test
    fun localHistoryBudget_neverNegative() {
        // 原文本身就快把 ctx 占满 → 历史预算 0（不带上下文也要把这一句翻出来）
        assertEquals(0, ContextBudget.localHistoryBudget(2048, 512, cjk(5000)))
    }

    // ───────────────────── 本地历史文本拼接 ─────────────────────

    @Test
    fun localContextBlock_emptyHistoryIsBlank() {
        assertEquals("", ContextBudget.localContextBlock(emptyList(), 1024))
    }

    @Test
    fun localContextBlock_emptyWhenNothingFits() {
        val history = listOf(cjk(100) to cjk(100))
        assertEquals("", ContextBudget.localContextBlock(history, 20))
    }

    @Test
    fun localContextBlock_shapeAndBudgetBehavior() {
        val h1 = "原文一" to "译文一"
        val h2 = "原文二" to "译文二"
        val h3 = "原文三" to "译文三"
        val block = ContextBudget.localContextBlock(listOf(h1, h2, h3), 100)
        val lines = block.split('\n')
        assertEquals(ContextBudget.LOCAL_CONTEXT_HEADER, lines[0])
        assertEquals("原文一", lines[1])
        assertEquals("译文一", lines[2])
        assertEquals("原文二", lines[3])
        assertEquals(ContextBudget.LOCAL_CURRENT_HEADER, lines[lines.size - 2])
        // 末尾必须为空串：块以「当前」标记 + 换行结尾，调用方直接接原文
        assertEquals("", lines.last())
        // 标记是中英双语的，不只中文用户/模型看得懂
        assertTrue(block.contains("Context"))
        assertTrue(block.contains("当前"))
    }

    @Test
    fun localContextBlock_usesSameBudgetTrimAsNetworkPath() {
        // 每轮 6 token；预算 10 → 只有最新一轮进块（与 trim 的口径完全一致）
        val h1 = "一二三四" to "五六七八"
        val h2 = "九十甲乙" to "丙丁戊己"
        val block = ContextBudget.localContextBlock(listOf(h1, h2), 10)
        assertFalse(block.contains("一二三四"))
        assertTrue(block.contains("九十甲乙"))
    }

    @Test
    fun localContextBlock_appendsCurrentHeaderForCaller() {
        val block = ContextBudget.localContextBlock(listOf("src" to "tgt"), 100)
        // 调用方拼法：block + 原文 → 原文落在「当前」标记之后
        val combined = block + "本轮原文"
        assertTrue(combined.endsWith("本轮原文"))
        assertTrue(combined.indexOf(ContextBudget.LOCAL_CURRENT_HEADER) < combined.indexOf("本轮原文"))
    }
}
