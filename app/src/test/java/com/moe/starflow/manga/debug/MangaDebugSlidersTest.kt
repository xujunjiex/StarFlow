package com.moe.starflow.manga.debug

import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.moe.starflow.manga.config.PPOcrDefault
import com.moe.starflow.manga.types.MergeParams
import com.moe.starflow.utils.CustomPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * 调试滑块面板的特征化测试。
 *
 * `MangaDebugSliders` 的两个面板全是手写的控件构造，没有任何 ID 可供查找 —— 这里靠
 * **控件创建顺序**来锁定结构（深度优先遍历得到的 SeekBar/Switch 序列）。
 *
 * 三件事被锁死：
 * 1. 控件结构（SeekBar / Switch 数量）
 * 2. 默认值 → 滑块位置 的换算结果（映射公式改了就红）
 * 3. 「恢复默认」按钮：prefs 回默认值 + 滑块位置回到干净状态
 *
 * ⚠️ `CustomPreference` 是静态单例且持有 `PreferenceManager.getDefaultSharedPreferences`，
 * Robolectric 每个用例新建 Application → 单例会缓存**上一个** Application 的 prefs。
 * 所以每个用例前用反射把单例清空，并且两个 store 都清。
 */
@RunWith(RobolectricTestRunner::class)
class MangaDebugSlidersTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** 与 MangaDebugSliders 里的配色一致（limit_type 选中/未选中） */
    private val COLOR_ACCENT = Color.argb(255, 76, 175, 80)
    private val COLOR_DIM = Color.argb(150, 200, 200, 200)

    @Before
    fun resetPreferenceSingleton() {
        CustomPreference::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit()
        CustomPreference.getInstance(ctx).getSharedPreferences().edit().clear().commit()
    }

    private fun prefs() = CustomPreference.getInstance(ctx)

    // ── 视图树工具（面板无 ID，只能按类型 + 创建顺序取）──

    private fun flatten(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun seekBars(root: View) = flatten(root).filterIsInstance<SeekBar>()
    private fun switches(root: View) = flatten(root).filterIsInstance<Switch>()
    private fun progresses(root: View) = seekBars(root).map { it.progress }

    /**
     * 所有非空文本，按创建顺序。
     * 这是没有 ID 时最强的结构断言：能区分「竖排 标签\n值」与「横排内联标签」两种滑块形态
     * （只看 SeekBar 数量是看不出来的）。
     */
    private fun labels(root: View) = flatten(root)
        .filterIsInstance<TextView>()
        .map { it.text.toString() }
        .filter { it.isNotEmpty() }

    private fun resetButton(root: View): TextView =
        flatten(root).filterIsInstance<TextView>()
            .first { it.text.toString() == "恢复默认" }

    private fun textView(root: View, text: String): TextView =
        flatten(root).filterIsInstance<TextView>().first { it.text.toString() == text }

    /** 面板未 attach 到窗口时 `View.post()` 只把点击入队、永不执行（`performClick` 走的就是这条路），
     *  所以必须挂到一个真实 Activity 上，并在派发触摸后把主 Looper 跑空。 */
    private fun host(panel: View): View {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setContentView(panel)
        return panel
    }

    /** 直接 measure+layout 后再派发触摸：面板未经过真实布局时宽高为 0，`pointInView` 会判定失败 */
    private fun tap(v: View) {
        if (v.width == 0) {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY)
            )
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        }
        val now = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 5f, 5f, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun v5Panel() = host(MangaDebugSliders.createPPOcrParamSlidersView(prefs(), ctx))
    private fun v6Panel() = host(MangaDebugSliders.createPPOcrV6ParamSlidersView(prefs(), ctx))

    // ── 1. 结构 ──

    @Test
    fun v5PanelStructure() {
        val panel = v5Panel()
        assertEquals("v5 SeekBar 数量", 6, seekBars(panel).size)
        assertEquals("v5 Switch 数量", 1, switches(panel).size)
    }

    @Test
    fun v6PanelStructure() {
        val panel = v6Panel()
        assertEquals("v6 SeekBar 数量", 10, seekBars(panel).size)
        assertEquals("v6 Switch 数量", 2, switches(panel).size)
    }

    // ── 2. 默认值 → 滑块位置（映射公式的锁）──

    @Test
    fun v5InitialProgressMatchesDefaultMapping() {
        // 刻度 = lo + k*step，每个默认值都必须精确落在格点上（见 LinearScale 注释）
        // box(0.01+58*0.005=0.3) · unclip(1+30*0.02=1.6) · text(0.1+50*0.008=0.5)
        // limit_side(64+142*8=1200) · gap(0.5+20*0.05=1.5) · ratio(0.3+60*0.005=0.6)
        assertEquals(listOf(58, 30, 50, 142, 20, 60), progresses(v5Panel()))
    }

    @Test
    fun v6InitialProgressMatchesDefaultMapping() {
        // thresh(0.1+50*0.004=0.3) · box(0+50*0.01=0.5) · unclip=1.6 · text=0.5
        // batch(1+5*1=6) · limit_side(64+142*8=1200) · min_height(10+20*1=30)
        // max_candidates(50+950*1=1000) · gap=1.5 · ratio=0.6
        assertEquals(listOf(50, 50, 30, 50, 5, 142, 20, 950, 20, 60), progresses(v6Panel()))
    }

    // ── 2b. 标签序列（锁住布局形态与刻度换算）──
    //
    // v5 的 merge_gap 是竖排（"merge_gap\n1.5"），v6 的是横排内联（初始只有 "merge_gap"）——
    // 两者 SeekBar 数量相同，只有标签序列能分辨。
    // 标签显示的是**滑块位置代表的取值**（不再显示 pref 原始值），所以它同时也是刻度往返的断言。

    @Test
    fun v5LabelSequence() {
        assertEquals(
            listOf(
                "检测置信度\n0.30",
                "扩展比例\n1.6",
                "识别置信度\n0.50",
                "limit_side_len\n1200",
                "limit_type", "min", "max",
                "merge_gap\n1.5",
                "large_box",
                "ratio 60%",
                "恢复默认"
            ),
            labels(v5Panel())
        )
    }

    @Test
    fun v6LabelSequence() {
        assertEquals(
            listOf(
                "── Det ──",
                "thresh\n0.30", "box_thresh\n0.50", "unclip_ratio\n1.6",
                "── Rec ──",
                "text_score\n0.50", "rec_batch_num\n6",
                "limit_side_len\n1200",
                "limit_type", "min", "max",
                "min_height\n30", "max_candidates\n1000",
                "use_dilation",
                "── App ──",
                // v6 的 merge_gap 是横排内联：初始不显示值，拖动后才变成 "merge_gap 1.5"
                "merge_gap",
                "large_box",
                "ratio 60%",
                "恢复默认"
            ),
            labels(v6Panel())
        )
    }

    // ── 2c. 刻度（历史上反复出问题的地方）──
    //
    // 位置与取值不是双射时，加载显示的数字和滑块实际代表的值就对不上，用户一碰参数就跳：
    // 曾经 min_height 默认 30 显示成 31、max_candidates 1000→1006、rec_batch_num 显示 5 而实际是 6、
    // box_thresh 0.3→0.2991，limit_side_len 甚至有两个位置代表同一个值（拖了没反应）。

    /**
     * 全量刻度检查：把每个参数刻度的**每一个位置**都过一遍。
     *
     * 这是覆盖最彻底的一条 —— 逐位置验证「默认值落在格点上」与「单射」。
     * 没走 UI 是因为 **UI 层模拟不了用户拖动**：`SeekBar.setProgress(k, true)` 的第二个参数是
     * `animate` 不是 `fromUser`，监听器收到 `fromUser=false`，`save` 分支根本不执行。
     * （用那个写法写过一组在新旧代码上都通过、实际什么都没测的假测试。）
     * 触摸拖动路径由 [v5TouchDragWritesGridValue] 单独覆盖。
     */
    @Test
    fun everyRegisteredScaleIsExactAndInjective() {
        assertTrue("刻度登记表不应为空", MangaDebugSliders.SCALE_REGISTRY.isNotEmpty())
        for (entry in MangaDebugSliders.SCALE_REGISTRY) {
            val scale = entry.scale
            val seek = scale.toSeek(entry.default)
            assertEquals(
                "${entry.name}：默认值 ${entry.default} 没落在格点上（位置 $seek 代表 ${scale.toValue(seek)}）",
                entry.default, scale.toValue(seek), 1e-5f
            )
            val values = (0..scale.max).map { scale.toValue(it) }
            assertEquals("${entry.name}：刻度不是单射，有两个位置代表同一个值", values.size, values.toSet().size)
            assertEquals("${entry.name}：刻度必须单调递增", values.sorted(), values)
        }
    }

    /**
     * 真实触摸拖动。[fraction] 是目标 x 占滑块宽度的比例。
     * SeekBar 只在 `fromUser=true` 的进度变化里写 prefs，所以只能走触摸事件。
     */
    private fun dragTo(v: View, fraction: Float) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val now = SystemClock.uptimeMillis()
        val x0 = v.width * 0.5f
        val x1 = v.width * fraction
        v.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x0, 10f, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, x1, 10f, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x1, 10f, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 触摸拖动必须：写 prefs、写的是刻度格点上的值、标签与滑块位置同步 */
    @Test
    fun v5TouchDragWritesGridValue() {
        val panel = v5Panel()
        val sb = seekBars(panel)[0]                       // 检测置信度 → ppocr_det_box_thresh
        val scale = MangaDebugSliders.SCALE_REGISTRY
            .first { it.name == "ppocr_det_box_thresh" }.scale

        dragTo(sb, 0.85f)

        val written = prefs().getFloat("ppocr_det_box_thresh", -1f)
        assertNotEquals("拖动必须写 prefs", -1f, written)
        val seek = scale.toSeek(written)
        assertEquals("写入的值必须正好落在某个格点上", scale.toValue(seek), written, 1e-5f)
        assertNotEquals(
            "拖到 85% 应该离开默认位置",
            scale.toSeek(PPOcrDefault.DET_BOX_THRESH_V5), seek
        )
        assertEquals(
            "标签必须与滑块位置同步",
            "检测置信度\n${String.format("%.2f", written)}",
            labels(panel).first { it.startsWith("检测置信度") }
        )
    }

    // ── 3. 恢复默认 ──

    @Test
    fun v5ResetRestoresPrefsAndSliderPositions() {
        val cleanPanel = v5Panel()
        val clean = progresses(cleanPanel)
        val cleanLabels = labels(cleanPanel)

        prefs().setFloat("ppocr_det_box_thresh", 0.45f)
        prefs().setFloat("ppocr_det_unclip_ratio", 2.8f)
        prefs().setFloat("ppocr_text_score_thresh", 0.85f)
        prefs().setInt("ppocr_limit_side_len", 2000)
        prefs().setString("ppocr_limit_type", "min")
        prefs().setBoolean("ppocr_large_box_enabled", true)
        prefs().setFloat("ppocr_large_box_ratio", 0.75f)
        prefs().setFloat("merge_discard_gap", 4.0f)

        val dirty = v5Panel()
        assertNotEquals("污染后初始位置应改变", clean, progresses(dirty))

        tap(resetButton(dirty))

        assertEquals("恢复默认后滑块位置应回到干净状态", clean, progresses(dirty))
        assertEquals("恢复默认后标签应回到干净状态", cleanLabels, labels(dirty))
        assertEquals(
            PPOcrDefault.DET_BOX_THRESH_V5,
            prefs().getFloat("ppocr_det_box_thresh", -1f), 1e-6f
        )
        assertEquals(
            PPOcrDefault.DET_UNCLIP_RATIO,
            prefs().getFloat("ppocr_det_unclip_ratio", -1f), 1e-6f
        )
        assertEquals(
            PPOcrDefault.TEXT_SCORE_THRESH,
            prefs().getFloat("ppocr_text_score_thresh", -1f), 1e-6f
        )
        assertEquals(PPOcrDefault.LIMIT_SIDE_LEN, prefs().getInt("ppocr_limit_side_len", -1))
        assertEquals(PPOcrDefault.LIMIT_TYPE, prefs().getString("ppocr_limit_type", "MISSING"))
        assertEquals(
            PPOcrDefault.LARGE_BOX_ENABLED,
            prefs().getBoolean("ppocr_large_box_enabled", true)
        )
        assertEquals(
            PPOcrDefault.LARGE_BOX_RATIO,
            prefs().getFloat("ppocr_large_box_ratio", -1f), 1e-6f
        )
        assertEquals(
            MergeParams.DISCARD_CONNECTION_GAP_DEFAULT,
            prefs().getFloat("merge_discard_gap", -1f), 1e-6f
        )
    }

    @Test
    fun v6ResetRestoresPrefsAndSliderPositions() {
        val cleanPanel = v6Panel()
        val clean = progresses(cleanPanel)
        val cleanLabels = labels(cleanPanel).toMutableList()

        prefs().setFloat("ppocrv6_det_thresh", 0.45f)
        prefs().setFloat("ppocrv6_det_box_thresh", 0.9f)
        prefs().setFloat("ppocrv6_det_unclip_ratio", 2.9f)
        prefs().setFloat("ppocrv6_text_score", 0.85f)
        prefs().setInt("ppocrv6_rec_batch_num", 12)
        prefs().setInt("ppocrv6_limit_side_len", 2000)
        prefs().setString("ppocrv6_limit_type", "min")
        prefs().setBoolean("ppocrv6_use_dilation", false)
        prefs().setInt("ppocrv6_max_candidates", 1900)
        prefs().setInt("ppocrv6_min_height", 180)
        prefs().setBoolean("ppocrv6_large_box_enabled", true)
        prefs().setFloat("ppocrv6_large_box_ratio", 0.75f)
        prefs().setFloat("merge_discard_gap", 4.0f)

        val dirty = v6Panel()
        assertNotEquals("污染后初始位置应改变", clean, progresses(dirty))

        tap(resetButton(dirty))

        assertEquals("恢复默认后滑块位置应回到干净状态", clean, progresses(dirty))
        // 只有一处标签与「干净状态」不同：v6 的 merge_gap 是横排内联，初始不显示值，
        // 而「恢复默认」会把它写成带值的 "merge_gap 1.5"（原实现如此）。
        cleanLabels[15] = "merge_gap 1.5"
        assertEquals("恢复默认后标签（含 merge_gap 这一处固有差异）", cleanLabels, labels(dirty))
        assertEquals(
            PPOcrDefault.V6_DET_THRESH,
            prefs().getFloat("ppocrv6_det_thresh", -1f), 1e-6f
        )
        assertEquals(
            PPOcrDefault.DET_BOX_THRESH_V6,
            prefs().getFloat("ppocrv6_det_box_thresh", -1f), 1e-6f
        )
        assertEquals(
            PPOcrDefault.DET_UNCLIP_RATIO,
            prefs().getFloat("ppocrv6_det_unclip_ratio", -1f), 1e-6f
        )
        assertEquals(
            PPOcrDefault.TEXT_SCORE_THRESH,
            prefs().getFloat("ppocrv6_text_score", -1f), 1e-6f
        )
        assertEquals(PPOcrDefault.V6_REC_BATCH_NUM, prefs().getInt("ppocrv6_rec_batch_num", -1))
        assertEquals(PPOcrDefault.LIMIT_SIDE_LEN, prefs().getInt("ppocrv6_limit_side_len", -1))
        assertEquals(PPOcrDefault.LIMIT_TYPE, prefs().getString("ppocrv6_limit_type", "MISSING"))
        assertEquals(
            PPOcrDefault.V6_USE_DILATION,
            prefs().getBoolean("ppocrv6_use_dilation", false)
        )
        assertEquals(
            PPOcrDefault.V6_MAX_CANDIDATES,
            prefs().getInt("ppocrv6_max_candidates", -1)
        )
        assertEquals(PPOcrDefault.V6_MIN_HEIGHT, prefs().getInt("ppocrv6_min_height", -1))
        assertEquals(
            PPOcrDefault.LARGE_BOX_ENABLED,
            prefs().getBoolean("ppocrv6_large_box_enabled", true)
        )
        assertEquals(
            PPOcrDefault.LARGE_BOX_RATIO,
            prefs().getFloat("ppocrv6_large_box_ratio", -1f), 1e-6f
        )
        assertEquals(
            MergeParams.DISCARD_CONNECTION_GAP_DEFAULT,
            prefs().getFloat("merge_discard_gap", -1f), 1e-6f
        )
    }

    /** v6 面板比 v5 多包一层 ScrollView（内容过多可滚动），v5 直接返回面板本体 */
    @Test
    fun onlyV6WrapsPanelInScrollView() {
        assertTrue("v6 应包 ScrollView", v6Panel() is android.widget.ScrollView)
        assertTrue("v5 不应包 ScrollView", v5Panel() !is android.widget.ScrollView)
    }

    // ── 4. 交互接线（提取成 helper 后最容易接错的就是 pref key 与刷新目标）──

    @Test
    fun v5LimitTypeClickWritesPrefAndSwapsHighlight() {
        val panel = v5Panel()
        val minBtn = textView(panel, "min")
        val maxBtn = textView(panel, "max")

        // 默认 limit_type = "max" → max 高亮、min 灰
        assertEquals("默认 max 应高亮", COLOR_ACCENT, maxBtn.currentTextColor)
        assertEquals("默认 min 应为灰", COLOR_DIM, minBtn.currentTextColor)

        tap(minBtn)

        assertEquals("min", prefs().getString("ppocr_limit_type", "MISSING"))
        assertEquals("点击后 min 应高亮", COLOR_ACCENT, minBtn.currentTextColor)
        assertEquals("点击后 max 应变灰", COLOR_DIM, maxBtn.currentTextColor)
    }

    @Test
    fun v6LimitTypeClickWritesPrefAndSwapsHighlight() {
        val panel = v6Panel()
        val minBtn = textView(panel, "min")
        val maxBtn = textView(panel, "max")

        assertEquals("默认 max 应高亮", COLOR_ACCENT, maxBtn.currentTextColor)
        tap(minBtn)
        assertEquals("min", prefs().getString("ppocrv6_limit_type", "MISSING"))
        assertEquals("点击后 min 应高亮", COLOR_ACCENT, minBtn.currentTextColor)
    }

    @Test
    fun v5LargeBoxSwitchWritesItsPref() {
        val panel = v5Panel()
        val toggle = switches(panel).single()
        assertFalse("默认关闭", toggle.isChecked)

        toggle.isChecked = true
        assertTrue(
            "开关键必须写 ppocr_large_box_enabled",
            prefs().getBoolean("ppocr_large_box_enabled", false)
        )
    }

    /** v6 两个开关各自绑定不同的 pref key —— 提取 helper 后最容易接错的地方 */
    @Test
    fun v6SwitchesWriteTheirOwnPrefs() {
        val panel = v6Panel()
        val toggles = switches(panel)
        val dilation = toggles[0]
        val largeBox = toggles[1]

        assertTrue("use_dilation 默认开", dilation.isChecked)
        assertFalse("large_box 默认关", largeBox.isChecked)

        dilation.isChecked = false
        largeBox.isChecked = true

        assertFalse(
            "use_dilation 开关应写 ppocrv6_use_dilation",
            prefs().getBoolean("ppocrv6_use_dilation", true)
        )
        assertTrue(
            "large_box 开关应写 ppocrv6_large_box_enabled",
            prefs().getBoolean("ppocrv6_large_box_enabled", false)
        )
    }
}
