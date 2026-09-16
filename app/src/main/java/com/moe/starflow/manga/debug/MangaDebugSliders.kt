package com.moe.starflow.manga.debug
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.state.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.engine.*
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.moe.starflow.manga.types.MergeParams
import com.moe.starflow.manga.config.PPOcrDefault
import com.moe.starflow.manga.merge.TextRegionMerger
import com.moe.starflow.utils.CustomPreference
import kotlin.math.roundToInt

/**
 * 调试参数滑块面板构建器：读取/写入 prefs 中的调试参数，构建可交互的滑块 UI。
 * 依赖 prefs + context 通过参数注入，不持有任何服务引用。
 *
 * 两个面板（v5 / v6）共用下面这套控件构造器：
 * 「滑块行」的控件接线、尺寸、配色只在 [addSlider] 等私有函数里写一份，
 * 公开入口只保留**刻度 + prefs key**（每个参数一条 [SliderSpec]）。
 *
 * 回归守卫见 `MangaDebugSlidersTest`。
 */
@SuppressLint("SetTextI18n")
object MangaDebugSliders {

    // =======================================================================
    // 滑块刻度 —— 位置 ↔ 取值
    // =======================================================================

    /**
     * 线性刻度：滑块位置 `k ∈ [0, max]` ↔ 取值 `lo + k * step`。
     *
     * ⚠️ **不用「两个互逆函数」而用 (lo, step, max) 三元组，是因为散写的公式出过一串问题：**
     * - `min_height` 默认 30 落在位置 11，而位置 11 代表 31 —— 加载显示 30、一碰变 31
     * - `max_candidates` 默认 1000 → 1006；`rec_batch_num` 默认 6 → 5
     * - `box_thresh` 默认 0.3 → 0.2991（100 格跨 0.49，0.3 不落在格点上）
     * - `limit_side_len` 的 snap 写法**不是单射**：100 个位置里有两个代表同一个值（拖了没反应）
     *
     * 由三元组导出映射后，这两条性质是构造性保证的：
     * 1. **默认值精确往返** —— `toValue(toSeek(d)) == d`
     * 2. **单射** —— `step > 0` 保证每个位置代表不同的值
     *
     * 加参数时先算好 `(hi - lo) / step` 与 `(default - lo) / step` 是不是整数，
     * 然后把该参数登记进 [SCALE_REGISTRY] —— `MangaDebugSlidersTest` 会逐位置验证上面两条性质。
     */
    internal class LinearScale(val lo: Float, val step: Float, val max: Int) {
        init {
            require(step > 0f) { "step 必须为正，否则刻度不是单射" }
            require(max > 0) { "max 必须为正" }
        }

        fun toSeek(value: Float): Int = ((value - lo) / step).roundToInt().coerceIn(0, max)
        fun toValue(seek: Int): Float = lo + seek * step
        /** 整数取值的参数（min_height / max_candidates / rec_batch_num） */
        fun toIntValue(seek: Int): Int = toValue(seek).roundToInt()
    }

    // 两面板共用
    private val SCALE_UNCLIP = LinearScale(1.0f, 0.02f, 100)          // 1.0~3.0，默认 1.6 → 30
    private val SCALE_TEXT_SCORE = LinearScale(0.1f, 0.008f, 100)     // 0.1~0.9，默认 0.5 → 50
    private val SCALE_RATIO = LinearScale(0.3f, 0.005f, 100)          // 0.3~0.8，默认 0.6 → 60
    private val SCALE_GAP = LinearScale(0.5f, 0.05f, 90)              // 0.5~5.0，默认 1.5 → 20
    private val SCALE_LIMIT_SIDE = LinearScale(64f, 8f, 248)          // 64~2048，默认 1200 → 142
    // v5 专有
    private val SCALE_BOX_V5 = LinearScale(0.01f, 0.005f, 98)         // 0.01~0.50，默认 0.3 → 58
    // v6 专有
    private val SCALE_DET_THRESH_V6 = LinearScale(0.1f, 0.004f, 100)  // 0.1~0.5，默认 0.3 → 50
    private val SCALE_BOX_V6 = LinearScale(0f, 0.01f, 100)            // 0~1，默认 0.5 → 50
    private val SCALE_BATCH = LinearScale(1f, 1f, 11)                 // 1~12，默认 6 → 5
    private val SCALE_MIN_HEIGHT = LinearScale(10f, 1f, 190)          // 10~200，默认 30 → 20
    private val SCALE_MAX_CANDIDATES = LinearScale(50f, 1f, 1950)     // 50~2000，默认 1000 → 950

    /**
     * 刻度登记表 —— **测试缝**。
     *
     * 新增滑块参数时**必须**在此登记，否则 `MangaDebugSlidersTest` 覆盖不到它。
     * 登记表逐项验证两条性质（都是历史上真出过问题的）：
     * 1. **默认值精确落在格点上** —— 否则加载时滑块位置与显示值不符，用户一碰参数就跳
     * 2. **位置 → 取值是单射** —— 否则有两个位置代表同一个值（拖了没反应）
     *
     * ⚠️ UI 层面**无法**用 `SeekBar.setProgress(k, true)` 模拟用户拖动 —— 那个参数是 `animate`
     * 不是 `fromUser`，监听器拿到的是 `fromUser=false`，`save` 分支根本不执行。
     * 真实拖动只能走触摸事件（见 `MangaDebugSlidersTest.dragTo`）。
     */
    internal data class ScaleEntry(val name: String, val scale: LinearScale, val default: Float)

    internal val SCALE_REGISTRY: List<ScaleEntry> = listOf(
        ScaleEntry("ppocr_det_box_thresh", SCALE_BOX_V5, PPOcrDefault.DET_BOX_THRESH_V5),
        ScaleEntry("ppocr_det_unclip_ratio", SCALE_UNCLIP, PPOcrDefault.DET_UNCLIP_RATIO),
        ScaleEntry("ppocr_text_score_thresh", SCALE_TEXT_SCORE, PPOcrDefault.TEXT_SCORE_THRESH),
        ScaleEntry("ppocr_large_box_ratio", SCALE_RATIO, PPOcrDefault.LARGE_BOX_RATIO),
        ScaleEntry("merge_discard_gap", SCALE_GAP, MergeParams.DISCARD_CONNECTION_GAP_DEFAULT),
        ScaleEntry("ppocr_limit_side_len", SCALE_LIMIT_SIDE, PPOcrDefault.LIMIT_SIDE_LEN.toFloat()),
        ScaleEntry("ppocrv6_det_thresh", SCALE_DET_THRESH_V6, PPOcrDefault.V6_DET_THRESH),
        ScaleEntry("ppocrv6_det_box_thresh", SCALE_BOX_V6, PPOcrDefault.DET_BOX_THRESH_V6),
        ScaleEntry("ppocrv6_rec_batch_num", SCALE_BATCH, PPOcrDefault.V6_REC_BATCH_NUM.toFloat()),
        ScaleEntry("ppocrv6_min_height", SCALE_MIN_HEIGHT, PPOcrDefault.V6_MIN_HEIGHT.toFloat()),
        ScaleEntry("ppocrv6_max_candidates", SCALE_MAX_CANDIDATES, PPOcrDefault.V6_MAX_CANDIDATES.toFloat())
    )

    // ── 共用配色 ──
    private val COLOR_TEXT = Color.WHITE
    private val COLOR_PANEL_BG = Color.argb(200, 30, 30, 30)
    private val COLOR_RESET_BG = Color.argb(150, 100, 100, 100)
    /** limit_type 选中态（绿） */
    private val COLOR_ACCENT = Color.argb(255, 76, 175, 80)
    /** limit_type 未选中态（灰） */
    private val COLOR_DIM = Color.argb(150, 200, 200, 200)

    /** 滑块条高度 */
    private const val SEEK_BAR_HEIGHT_DP = 24

    // =======================================================================
    // 公开入口
    // =======================================================================

    fun createPPOcrParamSlidersView(prefs: CustomPreference, context: Context): View {
        val dp = context.resources.displayMetrics.density

        // 默认值统一从 PPOcrDefault 取（单一来源）。改默认值时只动 PPOcrParams.kt，
        // 但**必须**同时确认新默认值落在刻度格点上（见 LinearScale 注释）。
        val DEF_BOX = PPOcrDefault.DET_BOX_THRESH_V5
        val DEF_UNCLIP = PPOcrDefault.DET_UNCLIP_RATIO
        val DEF_TEXT = PPOcrDefault.TEXT_SCORE_THRESH
        val DEF_LARGE_ENABLED = PPOcrDefault.LARGE_BOX_ENABLED
        val DEF_LARGE_RATIO = PPOcrDefault.LARGE_BOX_RATIO
        val DEF_LIMIT_SIDE_V5 = PPOcrDefault.LIMIT_SIDE_LEN
        val DEF_LIMIT_TYPE_V5 = PPOcrDefault.LIMIT_TYPE
        val DEF_GAP = MergeParams.DISCARD_CONNECTION_GAP_DEFAULT

        val box = SCALE_BOX_V5
        val unclip = SCALE_UNCLIP
        val textScore = SCALE_TEXT_SCORE
        val ratio = SCALE_RATIO
        val gap = SCALE_GAP
        val limitSide = SCALE_LIMIT_SIDE

        val refreshEngine: (Context) -> Unit = { PPOcrV5Engine.refreshParams(it) }
        val refreshMerge: (Context) -> Unit = { TextRegionMerger.refreshParams(it) }

        val outerPanel = panelContainer(context, dp)

        // ── 第一行：3 个滑块 ──
        val row1 = sliderRow(context, dp, topMarginDp = 0)
        val boxRef = addSlider(context, dp, row1, refreshEngine, SliderSpec(
            name = "检测置信度",
            max = box.max,
            seekInit = box.toSeek(prefs.getFloat("ppocr_det_box_thresh", DEF_BOX)),
            defaultSeek = box.toSeek(DEF_BOX),
            formatValue = { k -> String.format("%.2f", box.toValue(k)) },
            save = { k -> prefs.setFloat("ppocr_det_box_thresh", box.toValue(k)) }
        ))
        val unclipRef = addSlider(context, dp, row1, refreshEngine, SliderSpec(
            name = "扩展比例",
            max = unclip.max,
            seekInit = unclip.toSeek(prefs.getFloat("ppocr_det_unclip_ratio", DEF_UNCLIP)),
            defaultSeek = unclip.toSeek(DEF_UNCLIP),
            formatValue = { k -> String.format("%.1f", unclip.toValue(k)) },
            save = { k -> prefs.setFloat("ppocr_det_unclip_ratio", unclip.toValue(k)) }
        ))
        val textRef = addSlider(context, dp, row1, refreshEngine, SliderSpec(
            name = "识别置信度",
            max = textScore.max,
            seekInit = textScore.toSeek(prefs.getFloat("ppocr_text_score_thresh", DEF_TEXT)),
            defaultSeek = textScore.toSeek(DEF_TEXT),
            formatValue = { k -> String.format("%.2f", textScore.toValue(k)) },
            save = { k -> prefs.setFloat("ppocr_text_score_thresh", textScore.toValue(k)) }
        ))
        outerPanel.addView(row1)

        // ── 第二行：limit_side_len + limit_type（max 推荐，避免细长框选被强制放大）──
        val rowLimitV5 = sliderRow(context, dp)
        val lslV5Ref = addSlider(context, dp, rowLimitV5, refreshEngine, SliderSpec(
            name = "limit_side_len",
            max = limitSide.max,
            seekInit = limitSide.toSeek(prefs.getInt("ppocr_limit_side_len", DEF_LIMIT_SIDE_V5).toFloat()),
            defaultSeek = limitSide.toSeek(DEF_LIMIT_SIDE_V5.toFloat()),
            formatValue = { k -> "${limitSide.toIntValue(k)}" },
            save = { k -> prefs.setInt("ppocr_limit_side_len", limitSide.toIntValue(k)) }
        ))
        val ltV5 = addLimitTypeToggle(
            context, dp, rowLimitV5,
            current = prefs.getString("ppocr_limit_type", DEF_LIMIT_TYPE_V5)
        ) { picked ->
            prefs.setString("ppocr_limit_type", picked)
            PPOcrV5Engine.refreshParams(context)
        }
        outerPanel.addView(rowLimitV5)

        // ── 合并参数行：merge_discard_gap ──
        val rowMerge = sliderRow(context, dp)
        val gapRef = addSlider(context, dp, rowMerge, refreshMerge, SliderSpec(
            name = "merge_gap",
            max = gap.max,
            seekInit = gap.toSeek(prefs.getFloat("merge_discard_gap", DEF_GAP)),
            defaultSeek = gap.toSeek(DEF_GAP),
            formatValue = { k -> String.format("%.1f", gap.toValue(k)) },
            save = { k -> prefs.setFloat("merge_discard_gap", gap.toValue(k)) },
            // 唯一一个不刷新 OCR 引擎、改刷合并参数的滑块
            refresh = refreshMerge
        ))
        outerPanel.addView(rowMerge)

        // ── 第三行：大框过滤开关 + 比例滑块 ──
        val row2 = sliderRow(context, dp, gravity = Gravity.CENTER_VERTICAL)
        val largeBoxToggle = addSwitch(context, dp, row2, "large_box", labelPaddingEndDp = 4,
            checked = prefs.getBoolean("ppocr_large_box_enabled", DEF_LARGE_ENABLED)
        ) { isChecked ->
            prefs.setBoolean("ppocr_large_box_enabled", isChecked)
            PPOcrV5Engine.refreshParams(context)
        }
        val ratioRef = addInlineSlider(
            context, dp, row2,
            initialLabel = ratioText(prefs.getFloat("ppocr_large_box_ratio", DEF_LARGE_RATIO)),
            labelPaddingStartDp = 8, labelPaddingEndDp = 4,
            max = ratio.max,
            seekInit = ratio.toSeek(prefs.getFloat("ppocr_large_box_ratio", DEF_LARGE_RATIO)),
            defaultSeek = ratio.toSeek(DEF_LARGE_RATIO),
            defaultLabel = ratioText(DEF_LARGE_RATIO),
            labelFor = { k -> ratioText(ratio.toValue(k)) },
            save = { k -> prefs.setFloat("ppocr_large_box_ratio", ratio.toValue(k)) },
            refresh = refreshEngine
        )
        outerPanel.addView(row2)

        // ── 第四行：恢复默认按钮 ──
        val row3 = sliderRow(context, dp, gravity = Gravity.CENTER)
        row3.addView(addResetButton(context, dp) {
            // 重置 SharedPreferences
            prefs.setFloat("ppocr_det_box_thresh", DEF_BOX)
            prefs.setFloat("ppocr_det_unclip_ratio", DEF_UNCLIP)
            prefs.setFloat("ppocr_text_score_thresh", DEF_TEXT)
            prefs.setBoolean("ppocr_large_box_enabled", DEF_LARGE_ENABLED)
            prefs.setFloat("ppocr_large_box_ratio", DEF_LARGE_RATIO)
            prefs.setInt("ppocr_limit_side_len", DEF_LIMIT_SIDE_V5)
            prefs.setString("ppocr_limit_type", DEF_LIMIT_TYPE_V5)
            PPOcrV5Engine.refreshParams(context)

            // 更新 UI（滑块位置在构造时就把默认值换算好了，这里无需再算）
            boxRef.resetToDefault()
            unclipRef.resetToDefault()
            textRef.resetToDefault()
            lslV5Ref.resetToDefault()
            largeBoxToggle.isChecked = DEF_LARGE_ENABLED
            ratioRef.resetToDefault()
            ltV5.highlight(DEF_LIMIT_TYPE_V5)

            // 重置合并参数（仅距离门控 1 个滑块）
            TextRegionMerger.resetParams(context)
            gapRef.resetToDefault()
        })
        outerPanel.addView(row3)

        return outerPanel
    }

    /**
     * 创建 PP-OCRv6 参数滑块视图（v6 独立参数，ppocrv6_ 前缀 prefs）
     */
    fun createPPOcrV6ParamSlidersView(prefs: CustomPreference, context: Context): View {
        val dp = context.resources.displayMetrics.density

        // 默认值统一从 PPOcrDefault 取（单一来源，与 PPOcrV6Engine.refreshParams 一致）
        val DEF_DET_THRESH = PPOcrDefault.V6_DET_THRESH
        val DEF_BOX = PPOcrDefault.DET_BOX_THRESH_V6
        val DEF_UNCLIP = PPOcrDefault.DET_UNCLIP_RATIO
        val DEF_TEXT = PPOcrDefault.TEXT_SCORE_THRESH
        val DEF_BATCH = PPOcrDefault.V6_REC_BATCH_NUM
        val DEF_LARGE_ENABLED = PPOcrDefault.LARGE_BOX_ENABLED
        val DEF_LARGE_RATIO = PPOcrDefault.LARGE_BOX_RATIO
        val DEF_GAP = MergeParams.DISCARD_CONNECTION_GAP_DEFAULT
        val DEF_LIMIT_SIDE = PPOcrDefault.LIMIT_SIDE_LEN
        val DEF_LIMIT_TYPE = PPOcrDefault.LIMIT_TYPE
        val DEF_USE_DILATION = PPOcrDefault.V6_USE_DILATION
        val DEF_MAX_CANDIDATES = PPOcrDefault.V6_MAX_CANDIDATES
        val DEF_MIN_HEIGHT = PPOcrDefault.V6_MIN_HEIGHT

        val detThresh = SCALE_DET_THRESH_V6
        val box = SCALE_BOX_V6
        val unclip = SCALE_UNCLIP
        val textScore = SCALE_TEXT_SCORE
        val batch = SCALE_BATCH
        val ratio = SCALE_RATIO
        val gap = SCALE_GAP
        val limitSide = SCALE_LIMIT_SIDE
        val minHeight = SCALE_MIN_HEIGHT
        val maxCand = SCALE_MAX_CANDIDATES

        val refreshEngine: (Context) -> Unit = { PPOcrV6Engine.refreshParams(it) }
        val refreshMerge: (Context) -> Unit = { TextRegionMerger.refreshParams(it) }

        val outerPanel = panelContainer(context, dp)

        // ── Det ──
        outerPanel.addView(sectionTitle(context, dp, "── Det ──"))
        val row1 = sliderRow(context, dp, topMarginDp = 0)
        val threshRef = addSlider(context, dp, row1, refreshEngine, SliderSpec(
            name = "thresh",
            max = detThresh.max,
            seekInit = detThresh.toSeek(prefs.getFloat("ppocrv6_det_thresh", DEF_DET_THRESH)),
            defaultSeek = detThresh.toSeek(DEF_DET_THRESH),
            formatValue = { k -> String.format("%.2f", detThresh.toValue(k)) },
            save = { k -> prefs.setFloat("ppocrv6_det_thresh", detThresh.toValue(k)) }
        ))
        val boxRef = addSlider(context, dp, row1, refreshEngine, SliderSpec(
            name = "box_thresh",
            max = box.max,
            seekInit = box.toSeek(prefs.getFloat("ppocrv6_det_box_thresh", DEF_BOX)),
            defaultSeek = box.toSeek(DEF_BOX),
            formatValue = { k -> String.format("%.2f", box.toValue(k)) },
            save = { k -> prefs.setFloat("ppocrv6_det_box_thresh", box.toValue(k)) }
        ))
        val unclipRef = addSlider(context, dp, row1, refreshEngine, SliderSpec(
            name = "unclip_ratio",
            max = unclip.max,
            seekInit = unclip.toSeek(prefs.getFloat("ppocrv6_det_unclip_ratio", DEF_UNCLIP)),
            defaultSeek = unclip.toSeek(DEF_UNCLIP),
            formatValue = { k -> String.format("%.1f", unclip.toValue(k)) },
            save = { k -> prefs.setFloat("ppocrv6_det_unclip_ratio", unclip.toValue(k)) }
        ))
        outerPanel.addView(row1)

        // ── Rec ──
        outerPanel.addView(sectionTitle(context, dp, "── Rec ──"))
        val row2 = sliderRow(context, dp)
        val textRef = addSlider(context, dp, row2, refreshEngine, SliderSpec(
            name = "text_score",
            max = textScore.max,
            seekInit = textScore.toSeek(prefs.getFloat("ppocrv6_text_score", DEF_TEXT)),
            defaultSeek = textScore.toSeek(DEF_TEXT),
            formatValue = { k -> String.format("%.2f", textScore.toValue(k)) },
            save = { k -> prefs.setFloat("ppocrv6_text_score", textScore.toValue(k)) }
        ))
        val batchRef = addSlider(context, dp, row2, refreshEngine, SliderSpec(
            name = "rec_batch_num",
            max = batch.max,
            seekInit = batch.toSeek(prefs.getInt("ppocrv6_rec_batch_num", DEF_BATCH).toFloat()),
            defaultSeek = batch.toSeek(DEF_BATCH.toFloat()),
            formatValue = { k -> "${batch.toIntValue(k)}" },
            save = { k -> prefs.setInt("ppocrv6_rec_batch_num", batch.toIntValue(k)) }
        ))
        outerPanel.addView(row2)

        // ── limit_side_len + limit_type ──
        val rowLimit = sliderRow(context, dp)
        val lslRef = addSlider(context, dp, rowLimit, refreshEngine, SliderSpec(
            name = "limit_side_len",
            max = limitSide.max,
            seekInit = limitSide.toSeek(prefs.getInt("ppocrv6_limit_side_len", DEF_LIMIT_SIDE).toFloat()),
            defaultSeek = limitSide.toSeek(DEF_LIMIT_SIDE.toFloat()),
            formatValue = { k -> "${limitSide.toIntValue(k)}" },
            save = { k -> prefs.setInt("ppocrv6_limit_side_len", limitSide.toIntValue(k)) }
        ))
        val lt = addLimitTypeToggle(
            context, dp, rowLimit,
            current = prefs.getString("ppocrv6_limit_type", DEF_LIMIT_TYPE)
        ) { picked ->
            prefs.setString("ppocrv6_limit_type", picked)
            PPOcrV6Engine.refreshParams(context)
        }
        outerPanel.addView(rowLimit)

        // ── min_height ──
        val rowFilter = sliderRow(context, dp)
        val mhRef = addSlider(context, dp, rowFilter, refreshEngine, SliderSpec(
            name = "min_height",
            max = minHeight.max,
            seekInit = minHeight.toSeek(prefs.getInt("ppocrv6_min_height", DEF_MIN_HEIGHT).toFloat()),
            defaultSeek = minHeight.toSeek(DEF_MIN_HEIGHT.toFloat()),
            formatValue = { k -> "${minHeight.toIntValue(k)}" },
            save = { k -> prefs.setInt("ppocrv6_min_height", minHeight.toIntValue(k)) }
        ))
        outerPanel.addView(rowFilter)

        // ── max_candidates ──
        val rowCand = sliderRow(context, dp)
        val mcRef = addSlider(context, dp, rowCand, refreshEngine, SliderSpec(
            name = "max_candidates",
            max = maxCand.max,
            seekInit = maxCand.toSeek(prefs.getInt("ppocrv6_max_candidates", DEF_MAX_CANDIDATES).toFloat()),
            defaultSeek = maxCand.toSeek(DEF_MAX_CANDIDATES.toFloat()),
            formatValue = { k -> "${maxCand.toIntValue(k)}" },
            save = { k -> prefs.setInt("ppocrv6_max_candidates", maxCand.toIntValue(k)) }
        ))
        outerPanel.addView(rowCand)

        // use_dilation 开关（Det 最后一个）
        val rowDil = sliderRow(context, dp, gravity = Gravity.CENTER_VERTICAL)
        addSwitch(context, dp, rowDil, "use_dilation", labelPaddingEndDp = 4,
            checked = prefs.getBoolean("ppocrv6_use_dilation", DEF_USE_DILATION)
        ) { isChecked ->
            prefs.setBoolean("ppocrv6_use_dilation", isChecked)
            PPOcrV6Engine.refreshParams(context)
        }
        outerPanel.addView(rowDil)

        // ── App ──
        outerPanel.addView(sectionTitle(context, dp, "── App ──"))
        // 注意：v6 的 merge_gap 是**横排内联**滑块（标签在左），与 v5 的竖排列不同
        val mergeRow = sliderRow(context, dp, gravity = Gravity.CENTER_VERTICAL)
        val gapRef = addInlineSlider(
            context, dp, mergeRow,
            initialLabel = "merge_gap",
            labelPaddingStartDp = 0, labelPaddingEndDp = 4,
            max = gap.max,
            seekInit = gap.toSeek(prefs.getFloat("merge_discard_gap", DEF_GAP)),
            defaultSeek = gap.toSeek(DEF_GAP),
            defaultLabel = "merge_gap ${String.format("%.1f", DEF_GAP)}",
            labelFor = { k -> "merge_gap ${String.format("%.1f", gap.toValue(k))}" },
            save = { k -> prefs.setFloat("merge_discard_gap", gap.toValue(k)) },
            // 唯一一个不刷新 OCR 引擎、改刷合并参数的滑块
            refresh = refreshMerge
        )
        outerPanel.addView(mergeRow)

        // 大框过滤开关
        val row3 = sliderRow(context, dp, gravity = Gravity.CENTER_VERTICAL)
        val largeBoxToggle = addSwitch(context, dp, row3, "large_box", labelPaddingEndDp = 2,
            checked = prefs.getBoolean("ppocrv6_large_box_enabled", DEF_LARGE_ENABLED)
        ) { isChecked ->
            prefs.setBoolean("ppocrv6_large_box_enabled", isChecked)
            PPOcrV6Engine.refreshParams(context)
        }
        outerPanel.addView(row3)

        // 大框丢弃比例滑块
        val row4 = sliderRow(context, dp, gravity = Gravity.CENTER_VERTICAL)
        val ratioRef = addInlineSlider(
            context, dp, row4,
            initialLabel = ratioText(prefs.getFloat("ppocrv6_large_box_ratio", DEF_LARGE_RATIO)),
            labelPaddingStartDp = 0, labelPaddingEndDp = 4,
            max = ratio.max,
            seekInit = ratio.toSeek(prefs.getFloat("ppocrv6_large_box_ratio", DEF_LARGE_RATIO)),
            defaultSeek = ratio.toSeek(DEF_LARGE_RATIO),
            defaultLabel = ratioText(DEF_LARGE_RATIO),
            labelFor = { k -> ratioText(ratio.toValue(k)) },
            save = { k -> prefs.setFloat("ppocrv6_large_box_ratio", ratio.toValue(k)) },
            refresh = refreshEngine
        )
        outerPanel.addView(row4)

        // 恢复默认按钮
        val row5 = sliderRow(context, dp, gravity = Gravity.CENTER)
        row5.addView(addResetButton(context, dp) {
            // 重置 SharedPreferences
            prefs.setFloat("ppocrv6_det_thresh", DEF_DET_THRESH)
            prefs.setFloat("ppocrv6_det_box_thresh", DEF_BOX)
            prefs.setFloat("ppocrv6_det_unclip_ratio", DEF_UNCLIP)
            prefs.setFloat("ppocrv6_text_score", DEF_TEXT)
            prefs.setInt("ppocrv6_rec_batch_num", DEF_BATCH)
            prefs.setBoolean("ppocrv6_large_box_enabled", DEF_LARGE_ENABLED)
            prefs.setFloat("ppocrv6_large_box_ratio", DEF_LARGE_RATIO)
            prefs.setInt("ppocrv6_limit_side_len", DEF_LIMIT_SIDE)
            prefs.setString("ppocrv6_limit_type", DEF_LIMIT_TYPE)
            prefs.setBoolean("ppocrv6_use_dilation", DEF_USE_DILATION)
            prefs.setInt("ppocrv6_max_candidates", DEF_MAX_CANDIDATES)
            prefs.setInt("ppocrv6_min_height", DEF_MIN_HEIGHT)
            PPOcrV6Engine.refreshParams(context)

            // 更新 UI（滑块位置在构造时就把默认值换算好了，这里无需再算）
            threshRef.resetToDefault()
            boxRef.resetToDefault()
            unclipRef.resetToDefault()
            textRef.resetToDefault()
            batchRef.resetToDefault()
            lslRef.resetToDefault()
            mhRef.resetToDefault()
            mcRef.resetToDefault()
            largeBoxToggle.isChecked = DEF_LARGE_ENABLED
            ratioRef.resetToDefault()
            lt.highlight(DEF_LIMIT_TYPE)

            TextRegionMerger.resetParams(context)
            gapRef.resetToDefault()
        })
        outerPanel.addView(row5)

        // 包裹 ScrollView：内容过多可滚动，避免遮挡全屏（限高屏幕的 50%）
        return android.widget.ScrollView(context).apply {
            addView(outerPanel)
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.5).toInt()
            )
        }
    }

    // =======================================================================
    // 控件构造（两个面板共用）
    // =======================================================================

    /** 一个调试滑块的完整描述：刻度 + prefs key + 标签格式 */
    private class SliderSpec(
        val name: String,
        /** SeekBar.max（位置刻度上限，与 [LinearScale.max] 一致） */
        val max: Int,
        /** 初始位置（由当前 pref 值经刻度换算而来） */
        val seekInit: Int,
        /** 「恢复默认」时滑块应回到的位置 */
        val defaultSeek: Int,
        /** 滑块位置 → 标签值文本 */
        val formatValue: (Int) -> String,
        /** 写 prefs（不负责刷新引擎） */
        val save: (Int) -> Unit,
        /** null = 用面板默认的刷新目标（PPOcrV5/V6Engine） */
        val refresh: ((Context) -> Unit)? = null
    )

    /** 已接线的滑块，供「恢复默认」复位 */
    private class SliderRef(
        private val label: TextView,
        private val seekBar: SeekBar,
        private val defaultSeek: Int,
        private val defaultLabelText: String
    ) {
        /** 只改 UI；prefs 由调用方在重置时统一写入 */
        fun resetToDefault() {
            seekBar.progress = defaultSeek
            label.text = defaultLabelText
        }
    }

    /** 外层面板：竖排、半透明深底 */
    private fun panelContainer(context: Context, dp: Float): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setBackgroundColor(COLOR_PANEL_BG)
        }

    /** 滑块行容器。[gravity] 传 -1 表示不设置（保持 LinearLayout 默认） */
    private fun sliderRow(
        context: Context,
        dp: Float,
        topMarginDp: Int = 4,
        gravity: Int = -1
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        if (gravity != -1) this.gravity = gravity
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (topMarginDp * dp).toInt() }
    }

    /** 分组标题（v6 面板的「── Det ──」） */
    private fun sectionTitle(context: Context, dp: Float, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(COLOR_ACCENT)
            textSize = 11f
            setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
        }

    /**
     * 造一个「标签 + 滑块」竖排组接到 [row] 上（等分宽度）。
     * 只负责控件接线与尺寸；刻度换算由 [spec] 提供。
     */
    private fun addSlider(
        context: Context,
        dp: Float,
        row: LinearLayout,
        defaultRefresh: (Context) -> Unit,
        spec: SliderSpec
    ): SliderRef {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val label = TextView(context).apply {
            text = "${spec.name}\n${spec.formatValue(spec.seekInit)}"
            setTextColor(COLOR_TEXT)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
        }

        val seekBar = SeekBar(context).apply {
            max = spec.max
            progress = spec.seekInit
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (SEEK_BAR_HEIGHT_DP * dp).toInt()
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        label.text = "${spec.name}\n${spec.formatValue(progress)}"
                        spec.save(progress)
                        (spec.refresh ?: defaultRefresh)(context)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val ref = SliderRef(
            label, seekBar, spec.defaultSeek,
            "${spec.name}\n${spec.formatValue(spec.defaultSeek)}"
        )
        column.addView(label)
        column.addView(seekBar)
        row.addView(column)
        return ref
    }

    /** 大框丢弃比例的标签文本（v5/v6 共用格式） */
    private fun ratioText(raw: Float) = "ratio ${String.format("%.0f%%", raw * 100)}"

    /**
     * 横排内联滑块：标签在左、滑块占满剩余宽度（与 [addSlider] 的竖排「标签在上」不同）。
     * 两个面板里的 large_box_ratio 与 v6 的 merge_gap 走这条。
     */
    private fun addInlineSlider(
        context: Context,
        dp: Float,
        row: LinearLayout,
        initialLabel: String,
        labelPaddingStartDp: Int,
        labelPaddingEndDp: Int,
        max: Int,
        seekInit: Int,
        defaultSeek: Int,
        defaultLabel: String,
        labelFor: (Int) -> String,
        save: (Int) -> Unit,
        refresh: (Context) -> Unit
    ): SliderRef {
        val label = TextView(context).apply {
            text = initialLabel
            setTextColor(COLOR_TEXT)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding((labelPaddingStartDp * dp).toInt(), 0, (labelPaddingEndDp * dp).toInt(), 0)
        }
        val seekBar = SeekBar(context).apply {
            this.max = max
            progress = seekInit
            layoutParams = LinearLayout.LayoutParams(0, (SEEK_BAR_HEIGHT_DP * dp).toInt(), 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        label.text = labelFor(progress)
                        save(progress)
                        refresh(context)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        row.addView(label)
        row.addView(seekBar)
        return SliderRef(label, seekBar, defaultSeek, defaultLabel)
    }

    /** 「标签 + 开关」横排 */
    private fun addSwitch(
        context: Context,
        dp: Float,
        row: LinearLayout,
        labelText: String,
        labelPaddingEndDp: Int,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ): Switch {
        val label = TextView(context).apply {
            text = labelText
            setTextColor(COLOR_TEXT)
            textSize = 11f
            setPadding(0, 0, (labelPaddingEndDp * dp).toInt(), 0)
        }
        val toggle = Switch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onCheckedChange(isChecked) }
        }
        row.addView(label)
        row.addView(toggle)
        return toggle
    }

    /**
     * limit_type 的 min / max 双按钮组（互斥高亮）。
     * 点击时写 pref 由 [onPick] 决定；高亮由本函数自己维护。
     */
    private class LimitTypeToggle(val minBtn: TextView, val maxBtn: TextView) {
        /** [current] 为选中的取值（"min" / "max"） */
        fun highlight(current: String) {
            minBtn.setTextColor(if (current == "min") COLOR_ACCENT else COLOR_DIM)
            maxBtn.setTextColor(if (current == "max") COLOR_ACCENT else COLOR_DIM)
        }
    }

    private fun addLimitTypeToggle(
        context: Context,
        dp: Float,
        row: LinearLayout,
        current: String,
        onPick: (String) -> Unit
    ): LimitTypeToggle {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
        }
        val label = TextView(context).apply {
            text = "limit_type"
            setTextColor(COLOR_TEXT)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun button(text: String) = TextView(context).apply {
            this.text = text
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding((4 * dp).toInt(), 2, (4 * dp).toInt(), 2)
            isClickable = true
            isFocusable = true
        }
        val minBtn = button("min")
        val maxBtn = button("max")
        val toggle = LimitTypeToggle(minBtn, maxBtn)

        minBtn.setOnClickListener {
            onPick("min")
            toggle.highlight("min")
        }
        maxBtn.setOnClickListener {
            onPick("max")
            toggle.highlight("max")
        }
        toggle.highlight(current)

        btnRow.addView(minBtn)
        btnRow.addView(maxBtn)
        group.addView(label)
        group.addView(btnRow)
        row.addView(group)
        return toggle
    }

    /** 「恢复默认」按钮；[onClick] 为重置 body */
    private fun addResetButton(context: Context, dp: Float, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = "恢复默认"
            setTextColor(COLOR_TEXT)
            textSize = 12f
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            setBackgroundColor(COLOR_RESET_BG)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
}
