package com.moe.starflow.manga.config

import android.content.Context
import androidx.preference.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 译文文本规则守卫：省略号归一化（硬保证）+ 用户替换表。
 *
 * 归一化那几条来自真实反馈：模型把一条编号译文写成三行
 * ```
 * [1] .
 * .
 * .
 * ```
 * `NUMBERED_TRANSLATION_REGEX` 的 `[\s\S]*?` 会**跨行**抓走 → 译文真的是 `".\n.\n."`，
 * 横排就是三行各一个点、竖排（`\n` 占一格）更难看，还白占 overlay 空间把字号压小。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationTextRulesTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(ctx)

    @Before
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    // ---------- ① 省略号归一化 ----------

    @Test
    fun normalize_threeLinesToOneRun() {
        assertEquals("...", TranslationTextRules.normalizeEllipsis(".\n.\n."))
    }

    @Test
    fun normalize_sixDotsAcrossTwoLines() {
        assertEquals("......", TranslationTextRules.normalizeEllipsis("...\n..."))
    }

    /** 点串之间夹空行也要接上（模型偶尔会多空一行）。 */
    @Test
    fun normalize_blankLineBetweenDots() {
        assertEquals("...", TranslationTextRules.normalizeEllipsis(".\n\n.\n."))
        assertEquals("..", TranslationTextRules.normalizeEllipsis(".\n\n."))
    }

    @Test
    fun normalize_ideographicPeriods() {
        assertEquals("。。", TranslationTextRules.normalizeEllipsis("。\n。"))
        assertEquals("……", TranslationTextRules.normalizeEllipsis("…\n…"))
    }

    /** ⚠️ 只删「点与点之间」的换行：正文换行 + 下一行行首的点是正常排版，不能动。 */
    @Test
    fun normalize_keepsNewlineBeforeDots() {
        val text = "正文\n..."
        assertEquals(text, TranslationTextRules.normalizeEllipsis(text))
    }

    @Test
    fun normalize_keepsNewlineBetweenWords() {
        val text = "第一句。\n第二句。"
        assertEquals(text, TranslationTextRules.normalizeEllipsis(text))
    }

    @Test
    fun normalize_noNewlineIsUntouched() {
        assertEquals("......", TranslationTextRules.normalizeEllipsis("......"))
        assertEquals("", TranslationTextRules.normalizeEllipsis(""))
    }

    // ---------- ② 替换表 ----------

    @Test
    fun apply_rulesRunInOrder() {
        val rules = listOf(ReplacementRule("a", "b"), ReplacementRule("b", "c"))
        assertEquals("c", TranslationTextRules.apply("a", rules))
    }

    @Test
    fun apply_emptyToDeletesContent() {
        assertEquals("ab", TranslationTextRules.apply("a...b", listOf(ReplacementRule("...", ""))))
    }

    /** 空 `from` 必须跳过：`replace("", x)` 会在每个字符之间插内容，把译文撑爆。 */
    @Test
    fun apply_skipsEmptyFrom() {
        assertEquals("ab", TranslationTextRules.apply("ab", listOf(ReplacementRule("", "X"))))
    }

    @Test
    fun apply_noRulesIsIdentity() {
        assertEquals("...", TranslationTextRules.apply("...", emptyList()))
        assertEquals("", TranslationTextRules.apply("", listOf(ReplacementRule("a", "b"))))
    }

    /** 用户的原始诉求：识别到省略号 → 按配置换成英文句号（且要先把三行接回来）。 */
    @Test
    fun process_normalizesThenReplaces() {
        val rules = listOf(ReplacementRule("...", "."))
        assertEquals(".", TranslationTextRules.process(".\n.\n.", rules))
        assertEquals("......", TranslationTextRules.process("...\n...", emptyList()))
    }

    @Test
    fun process_isIdempotentForNormalize() {
        val once = TranslationTextRules.process(".\n.\n.", emptyList())
        assertEquals(once, TranslationTextRules.process(once, emptyList()))
    }

    // ---------- ③ 持久化 ----------

    @Test
    fun saveThenLoad_roundTrip() {
        val rules = listOf(ReplacementRule("...", "."), ReplacementRule("~", ""))
        TranslationTextRules.save(prefs(), rules)
        assertEquals(rules, TranslationTextRules.load(prefs()))
    }

    @Test
    fun load_emptyWhenUnset() {
        assertEquals(emptyList<ReplacementRule>(), TranslationTextRules.load(prefs()))
    }

    @Test
    fun fromJson_dropsEmptyFromAndBadJson() {
        assertEquals(
            listOf(ReplacementRule("a", "b")),
            TranslationTextRules.fromJson("""[{"from":"a","to":"b"},{"from":"","to":"x"}]""")
        )
        assertEquals(emptyList<ReplacementRule>(), TranslationTextRules.fromJson("not json"))
        assertEquals(emptyList<ReplacementRule>(), TranslationTextRules.fromJson(null))
    }

    @Test
    fun toJson_capsRuleCount() {
        val many = (0 until TranslationTextRules.MAX_RULES + 10)
            .map { ReplacementRule("f$it", "t$it") }
        val parsed = TranslationTextRules.fromJson(TranslationTextRules.toJson(many))
        assertEquals(TranslationTextRules.MAX_RULES, parsed.size)
    }

    /** 反向也要封顶：手改/恢复的备份 JSON 不受 toJson 约束。 */
    @Test
    fun fromJson_alsoCapsRuleCount() {
        val json = (0 until TranslationTextRules.MAX_RULES + 10)
            .joinToString(",", "[", "]") { """{"from":"f$it","to":"t$it"}""" }
        assertEquals(TranslationTextRules.MAX_RULES, TranslationTextRules.fromJson(json).size)
    }

    /** 渲染是热路径：同串必须命中记忆化，不能每次渲染都解析一遍。 */
    @Test
    fun load_memoizesByRawString() {
        TranslationTextRules.save(prefs(), listOf(ReplacementRule("...", ".")))
        val first = TranslationTextRules.load(prefs())
        val second = TranslationTextRules.load(prefs())
        assertEquals(first, second)

        // 改了内容必须立刻反映（记忆化的 key 是原始串）
        TranslationTextRules.save(prefs(), listOf(ReplacementRule("~", "")))
        assertEquals(listOf(ReplacementRule("~", "")), TranslationTextRules.load(prefs()))
    }
}
