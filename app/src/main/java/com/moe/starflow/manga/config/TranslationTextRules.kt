package com.moe.starflow.manga.config

import android.content.SharedPreferences
import com.moe.starflow.utils.LogCollector
import org.json.JSONArray
import org.json.JSONObject

/** 一条译文替换规则：把译文里的 [from] 换成 [to]（[to] 为空串 = 删掉这段内容）。 */
data class ReplacementRule(val from: String, val to: String)

/**
 * 译文文本规则（**单一来源**）：省略号归一化（硬保证，无开关）+ 用户替换表（可配置）。
 *
 * ### 1. 省略号归一化 [normalizeEllipsis]
 *
 * 真实反馈：「`...` 在竖排里显示成 `.\n.\n.`，每个点占一行，白占 overlay 空间把字号压小」。
 *
 * 根因不在排版，在**模型输出**：批量翻译要求模型按 `[N] 译文` 逐行回答，但它常把一条译文
 * 写成三行：
 * ```
 * [1] .
 * .
 * .
 * ```
 * 而 `NUMBERED_TRANSLATION_REGEX` 用 `[\s\S]*?` 抓的是**跨行**内容 → 这条气泡的译文真的就是
 * `".\n.\n."`。横排渲染 = 三行各一个点；竖排更糟（`\n` 在竖排里占一个空白格，把点串撑成三行）。
 *
 * 所以这里把「**两侧都是点**的换行」直接删掉：`.\n.\n.` → `...`。只删点与点之间的换行，
 * `正文\n...`（点在下一行行首）不动 —— 那是正常换行。
 *
 * ⚠️ 这是**硬保证**：不加开关、不依赖用户配置。配套还有两道防线：
 * - `LayoutEngine.buildVertical` 直接去掉竖排里的换行（竖排是连续竖流，换行只会留空白格）
 * - `LayoutEngine` 的分列/断行不切点串（点串整串进下一列/行）
 *
 * ### 2. 用户替换表 [apply]
 *
 * 漫画翻译结果设置 →「译文替换表」二级面板配置。
 *
 * ⚠️ **在渲染时套用**（`OverlayRenderer.renderOverlay(..., replacementRules)` 里 `process()`），
 * **不是**翻译时：译文只存文本（DB 里的 `translatedText` + 气泡坐标），overlay 是后期画到原图上的，
 * 所以改完规则**重新渲染该页即生效，不需要重翻**（阅读器返回时 `refreshIfRulesChanged` 作废译图缓存）。
 * 别把它挪回 `TranslateUtils.translateBubbles` —— 那样既逼用户重翻，非幂等规则（`a`→`aa`）还会被套两次。
 *
 * [normalizeEllipsis] 幂等，可以随便多跑；[apply] **不幂等**（规则 `a`→`aa` 每跑一次翻一倍），
 * 因此每段译文只应套用**一次**（渲染层每帧都会重建显示文本，但源文本永不被回写，所以安全）。
 */
object TranslationTextRules {

    private const val TAG = "TranslationTextRules"

    /** 替换表在 prefs 里的 key（【译文替换表】二级面板读写的就是它）。 */
    const val KEY_REPLACEMENTS = "Manga_Translation_Replacements"

    /** 规则条数上限：每条规则都要对每段译文做一次 replace，不设上限容易被写成性能陷阱。 */
    const val MAX_RULES = 50

    /**
     * 点串成员：半角句点 / 全角句点 / 表意句号 / 省略号（`…` 与 `⋯`）。
     *
     * **唯一来源** —— 排版断行的「点串不可切」也用这个集合（见 [isEllipsisDot]）。
     */
    const val DOT_CHARS = ".．。…⋯"

    fun isEllipsisDot(c: Char): Boolean = DOT_CHARS.indexOf(c) >= 0

    /**
     * 两侧都是点的换行 → 删掉（把被模型换行拆开的点串接回同一行）。
     *
     * 允许一个或多个连续换行（模型偶尔多空一行）以及换行前后的空格/制表符，全部一并吞掉。
     */
    private val NEWLINE_BETWEEN_DOTS =
        Regex("(?<=[$DOT_CHARS])[ \\t]*(?:\\r?\\n[ \\t]*)+(?=[$DOT_CHARS])")

    fun normalizeEllipsis(text: String): String =
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) text
        else text.replace(NEWLINE_BETWEEN_DOTS, "")

    /**
     * 逐条套用用户规则（按配置顺序，`replace` 全量替换）。
     *
     * `from` 为空的规则跳过 —— `replace("", x)` 会在每个字符之间插内容，把译文撑爆。
     * （纯空格 `from` 由编辑面板在保存时挡掉：它会把译文的空格成片改写，同样属于灾难性输入。）
     */
    fun apply(text: String, rules: List<ReplacementRule>): String {
        if (text.isEmpty() || rules.isEmpty()) return text
        var out = text
        for (r in rules) {
            if (r.from.isEmpty()) continue
            out = out.replace(r.from, r.to)
        }
        return out
    }

    /** 完整后处理：**先修结构**（点串归一行）→ 再套用户替换表。两步都幂等。 */
    fun process(text: String, rules: List<ReplacementRule>): String =
        apply(normalizeEllipsis(text), rules)

    // ===== 持久化（JSON 数组，挂在默认 SharedPreferences 上，与其它设置同一份）=====

    fun load(prefs: SharedPreferences): List<ReplacementRule> =
        loadFromJson(prefs.getString(KEY_REPLACEMENTS, null))

    /**
     * 解析结果记忆化（key = 原始 JSON 串）。
     *
     * ⚠️ 必需：渲染是热路径 —— `getOverlayConfig` 每次渲染都要读规则，而流式/预热会在一页里
     * 反复渲染几十次。同串同结果，没必要每次解析。
     */
    @Synchronized
    private fun loadFromJson(raw: String?): List<ReplacementRule> {
        if (raw == memoRaw) return memoRules
        val parsed = fromJson(raw)
        memoRaw = raw
        memoRules = parsed
        return parsed
    }

    private var memoRaw: String? = null
    private var memoRules: List<ReplacementRule> = emptyList()

    fun save(prefs: SharedPreferences, rules: List<ReplacementRule>) {
        prefs.edit().putString(KEY_REPLACEMENTS, toJson(rules)).apply()
    }

    fun fromJson(json: String?): List<ReplacementRule> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val from = o.optString("from", "")
                // 空 from 不合法（见 apply 的说明）：解析阶段就丢掉，坏数据不进内存
                if (from.isEmpty()) null else ReplacementRule(from, o.optString("to", ""))
            }
            // ⚠️ 也要封顶：toJson 会截断，但手改/恢复的备份 JSON 不会 —— 不封顶就是
            // 「每段译文多跑 N 次 replace」的性能陷阱（渲染是热路径）
            .take(MAX_RULES)
        } catch (e: Exception) {
            LogCollector.w(TAG, "解析替换表失败，按空表处理", e)
            emptyList()
        }
    }

    fun toJson(rules: List<ReplacementRule>): String {
        val arr = JSONArray()
        rules.take(MAX_RULES).forEach { r ->
            if (r.from.isEmpty()) return@forEach
            arr.put(JSONObject().put("from", r.from).put("to", r.to))
        }
        return arr.toString()
    }
}
