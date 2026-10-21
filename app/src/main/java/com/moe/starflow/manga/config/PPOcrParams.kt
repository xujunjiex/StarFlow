/*
 * 集中管理 PP-OCRv5/v6 用户可调参数的「默认值 + prefs key」单一来源。
 *
 * 之前默认值散落在三处：
 *   - MangaFloatingService 调试面板（UI 显示）
 *   - PPOcrV5Engine / PPOcrV6Engine.refreshParams（OCR 引擎兜底）
 *   - strings.xml FAQ 描述（用户文档）
 * 任意一处不同步都会出现"UI 显示 1200 但引擎兜底走 736"的不一致 bug（v0.9.2 修复）。
 *
 * 所有可调参数都集中在这里：默认值 + prefs key + read/write 工具方法。
 * 三处调用方（引擎、UI、reset）都从这里取，IDE grep 可一次找全，避免再次写散。
 */
package com.moe.starflow.manga.config
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import com.moe.starflow.utils.CustomPreference

/**
 * 参数 key 常量（v5/v6 各一套，避免重复硬编码）。
 * 命名规则：ppocr_<name>（v5 用）+ ppocrv6_<name>（v6 用）。
 * v5 和 v6 各自读各自的 key，互不影响（共享 key 字符串不等于"参数同步变化"）。
 */
object PPOcrKey {
    // ── v5 prefs key（无前缀，PPOcrV5Engine 用）──
    // 部分 v6 参数（如 box_thresh/unclip_ratio/limit_*）也用同名 v5 key 存储，但
    // v5 改这些 pref 不会"同步"到 v6——v6 只是用同样的 key 字符串存自己的值。
    const val DET_BOX_THRESH = "ppocr_det_box_thresh"
    const val DET_UNCLIP_RATIO = "ppocr_det_unclip_ratio"
    const val TEXT_SCORE_THRESH = "ppocr_text_score_thresh"
    const val LARGE_BOX_ENABLED = "ppocr_large_box_enabled"
    const val LARGE_BOX_RATIO = "ppocr_large_box_ratio"
    const val LIMIT_SIDE_LEN = "ppocr_limit_side_len"
    const val LIMIT_TYPE = "ppocr_limit_type"

    // ── v6 prefs key（V6_ 前缀，PPOcrV6Engine 用）──
    // v5 完全不使用这些 key（v5 没用到 V6_ 参数如 max_candidates/use_dilation 等）。
    const val V6_DET_THRESH = "ppocrv6_det_thresh"
    const val V6_DET_BOX_THRESH = "ppocrv6_det_box_thresh"
    const val V6_DET_UNCLIP_RATIO = "ppocrv6_det_unclip_ratio"
    const val V6_TEXT_SCORE = "ppocrv6_text_score"               // 注意：v6 用 _score 后缀，与 v5 key 不同
    const val V6_LIMIT_SIDE_LEN = "ppocrv6_limit_side_len"
    const val V6_LIMIT_TYPE = "ppocrv6_limit_type"
    const val V6_LARGE_BOX_ENABLED = "ppocrv6_large_box_enabled"
    const val V6_LARGE_BOX_RATIO = "ppocrv6_large_box_ratio"
    const val V6_REC_BATCH_NUM = "ppocrv6_rec_batch_num"
    const val V6_USE_DILATION = "ppocrv6_use_dilation"
    const val V6_SCORE_MODE = "ppocrv6_score_mode"
    const val V6_MAX_CANDIDATES = "ppocrv6_max_candidates"
    const val V6_MIN_HEIGHT = "ppocrv6_min_height"
    const val V6_WIDTH_HEIGHT_RATIO = "ppocrv6_width_height_ratio"
}

/**
 * 默认值单一来源（v5 + v6）。
 * 三处调用方（UI 显示、引擎兜底、reset）都从这里取 — 改一处即可。
 *
 * 注意事项：
 * - 命名不带 _V5/_V6 后缀的常量（如 DET_BOX_THRESH_V5/DET_BOX_THRESH_V6），
 *   表示 v5 / v6 两个引擎各自的默认值，可能相同也可能不同。
 * - 如果要让两个引擎用不同值，明确拆成两个常量（如 DET_BOX_THRESH_V5 / DET_BOX_THRESH_V6）。
 * - 改默认值时同时检查 PPOcrKey + prefs 文件是否需要迁移（已存 prefs 不会被新默认值覆盖）。
 */
object PPOcrDefault {
    // ── v5 引擎默认值（无 V6_ 前缀的就是 v5 的，v6 各自有同名变体）──
    /** Det.box_thresh：v5 默认值 */
    const val DET_BOX_THRESH_V5 = 0.3f
    /** Det.unclip_ratio：v5 默认值（v6 默认值同） */
    const val DET_UNCLIP_RATIO = 1.6f
    /** Global.text_score：v5 默认值（v6 默认值同） */
    const val TEXT_SCORE_THRESH = 0.5f
    /** Det.limit_side_len：v5 默认值（v6 默认值同） */
    const val LIMIT_SIDE_LEN = 1200
    /** Det.limit_type：v5 默认值（v6 默认值同） */
    const val LIMIT_TYPE = "max"
    /** LargeBox 开关：v5 默认值（v6 默认值同） */
    const val LARGE_BOX_ENABLED = false
    const val LARGE_BOX_RATIO = 0.6f

    // ── v6 引擎默认值（V6_ 前缀）──
    /** Det.thresh：v6 独有 */
    const val V6_DET_THRESH = 0.3f
    /** Det.box_thresh：v6 默认值（与 v5 不同：v5 是 0.3，v6 是 0.5） */
    const val DET_BOX_THRESH_V6 = 0.5f
    /** Rec.rec_batch_num：v6 独有 */
    const val V6_REC_BATCH_NUM = 6
    /** Det.use_dilation：v6 独有 */
    const val V6_USE_DILATION = true
    /** Det.score_mode：v6 独有 */
    const val V6_SCORE_MODE = "fast"
    /** Det.max_candidates：v6 独有 */
    const val V6_MAX_CANDIDATES = 500
    /** Global.min_height：v6 独有 */
    const val V6_MIN_HEIGHT = 30
    /** Global.width_height_ratio：v6 独有 */
    const val V6_WIDTH_HEIGHT_RATIO = -1f

    // ── 滑块范围（与默认值配套使用）──
    const val LIMIT_SIDE_MIN = 64
    const val LIMIT_SIDE_MAX = 2048
    /** seek 转 px 时 snap 到 20 像素倍数（保证默认值可往返） */
    const val LIMIT_SIDE_SNAP = 20
}

/**
 * 统一读写 prefs 的薄封装。
 * 所有"读 prefs + 走默认"逻辑集中这里，引擎和 UI 都用它。
 */
object PPOcrPrefs {

    // ── 共享参数 ──
    fun boxThreshV5(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.DET_BOX_THRESH, PPOcrDefault.DET_BOX_THRESH_V5)
    fun boxThreshV6(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.V6_DET_BOX_THRESH, PPOcrDefault.DET_BOX_THRESH_V6)
    fun unclipRatio(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.DET_UNCLIP_RATIO, PPOcrDefault.DET_UNCLIP_RATIO)
    fun textScoreV5(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.TEXT_SCORE_THRESH, PPOcrDefault.TEXT_SCORE_THRESH)
    fun limitSideLen(prefs: CustomPreference) =
        prefs.getInt(PPOcrKey.LIMIT_SIDE_LEN, PPOcrDefault.LIMIT_SIDE_LEN)
    fun limitSideLenV6(prefs: CustomPreference) =
        prefs.getInt(PPOcrKey.V6_LIMIT_SIDE_LEN, PPOcrDefault.LIMIT_SIDE_LEN)
    fun limitType(prefs: CustomPreference) =
        prefs.getString(PPOcrKey.LIMIT_TYPE, PPOcrDefault.LIMIT_TYPE) ?: PPOcrDefault.LIMIT_TYPE
    fun limitTypeV6(prefs: CustomPreference) =
        prefs.getString(PPOcrKey.V6_LIMIT_TYPE, PPOcrDefault.LIMIT_TYPE) ?: PPOcrDefault.LIMIT_TYPE
    fun largeBoxEnabled(prefs: CustomPreference) =
        prefs.getBoolean(PPOcrKey.LARGE_BOX_ENABLED, PPOcrDefault.LARGE_BOX_ENABLED)
    fun largeBoxEnabledV6(prefs: CustomPreference) =
        prefs.getBoolean(PPOcrKey.V6_LARGE_BOX_ENABLED, PPOcrDefault.LARGE_BOX_ENABLED)
    fun largeBoxRatio(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.LARGE_BOX_RATIO, PPOcrDefault.LARGE_BOX_RATIO)
    fun largeBoxRatioV6(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.V6_LARGE_BOX_RATIO, PPOcrDefault.LARGE_BOX_RATIO)

    // ── v6 独有参数 ──
    fun detThreshV6(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.V6_DET_THRESH, PPOcrDefault.V6_DET_THRESH)
    fun unclipRatioV6(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.V6_DET_UNCLIP_RATIO, PPOcrDefault.DET_UNCLIP_RATIO)
    fun textScoreV6(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.V6_TEXT_SCORE, PPOcrDefault.TEXT_SCORE_THRESH)
    fun recBatchNumV6(prefs: CustomPreference) =
        prefs.getInt(PPOcrKey.V6_REC_BATCH_NUM, PPOcrDefault.V6_REC_BATCH_NUM)
    fun useDilationV6(prefs: CustomPreference) =
        prefs.getBoolean(PPOcrKey.V6_USE_DILATION, PPOcrDefault.V6_USE_DILATION)
    fun scoreModeV6(prefs: CustomPreference) =
        prefs.getString(PPOcrKey.V6_SCORE_MODE, PPOcrDefault.V6_SCORE_MODE) ?: PPOcrDefault.V6_SCORE_MODE
    fun maxCandidatesV6(prefs: CustomPreference) =
        prefs.getInt(PPOcrKey.V6_MAX_CANDIDATES, PPOcrDefault.V6_MAX_CANDIDATES)
    fun minHeightV6(prefs: CustomPreference) =
        prefs.getInt(PPOcrKey.V6_MIN_HEIGHT, PPOcrDefault.V6_MIN_HEIGHT)
    fun widthHeightRatioV6(prefs: CustomPreference) =
        prefs.getFloat(PPOcrKey.V6_WIDTH_HEIGHT_RATIO, PPOcrDefault.V6_WIDTH_HEIGHT_RATIO)
}
