package com.moe.starflow.novel.reader

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.moe.starflow.R
import com.moe.starflow.novel.translate.NovelQuota
import com.moe.starflow.novel.translate.NovelTranslateMode

/**
 * 小说阅读器的偏好读写。**收敛成一处**，避免键名散落各处写错。
 *
 * ⚠️ 背景 / 翻页动画 / 旋转 存的是与漫画阅读器同一份 `SharedPreferences`（[PREFS_NAME]），
 * 那几个键更是直接共用 —— 用户在漫画里把背景调成黑色，切到小说也该是黑的。
 * 两套存储会让「同一个软件」这个前提当场破功。
 *
 * ⚠️ 但**字号是小说自己一份**（[fontSizeSp]）：漫画的字号是按气泡图尺寸推的
 * （它还有「自动字号」），与正文排版没有关系；共用会让「恢复默认字号」连带改掉漫画，
 * 而正文默认 16sp 明显偏小。排版参数（行距/段距/边距/间距）本来就是小说独有的。
 */
object NovelPanelStyle {

    /** 与 `MangaReaderActivity` 同一个 prefs 文件。 */
    const val PREFS_NAME = "manga_reader"

    // ===== 上下浮层需要避开的尺寸 =====

    /**
     * 上下浮层占掉的纵向空间（摸清后写死，供默认值参考）：
     * 顶部：返回/菜单圆形钮 34~74dp，章节胶囊 38~64dp → 留 80dp 就够。
     * 底部：胶囊 10~58dp → 80dp 也够；右下翻译浮层组在 62~114dp，只压住**最右一行的末端**。
     */
    private const val CHROME_TOP_DP = 80
    private const val CHROME_BOTTOM_DP = 80

    // ===== 字号（小说独立一份） =====

    const val FONT_SIZE_MIN = 12f
    const val FONT_SIZE_MAX = 34f
    const val FONT_SIZE_DEFAULT = 20f

    private const val KEY_FONT_SIZE = "novel_font_size"

    fun fontSizeSp(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_FONT_SIZE, FONT_SIZE_DEFAULT).coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)

    fun setFontSizeSp(prefs: SharedPreferences, sp: Float) =
        prefs.edit().putFloat(KEY_FONT_SIZE, sp.coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)).apply()

    // ===== 背景 / 翻页动画（键与漫画共用） =====

    private const val KEY_BG = "reader_background"
    private const val KEY_ANIM = "reader_animation"

    /** 翻页动画：0 无 / 1 滑动 / 2 覆盖 / 3 仿真（折页）。**四项与漫画一一对应**。 */
    const val ANIM_NONE = 0
    const val ANIM_SLIDE = 1
    const val ANIM_COVER = 2
    const val ANIM_SIMULATION = 3

    fun animation(prefs: SharedPreferences): Int = prefs.getInt(KEY_ANIM, ANIM_SLIDE).coerceIn(0, 3)

    fun setAnimation(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_ANIM, v.coerceIn(0, 3)).apply()

    /**
     * 阅读背景：0 默认 / 1 浅 / 2 深 / 3 白 / 4 黑 / 5 自动。
     * 值域与 `MangaReaderActivity` 完全一致。
     */
    fun background(prefs: SharedPreferences): Int = prefs.getInt(KEY_BG, 0).coerceIn(0, 5)

    fun setBackground(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_BG, v.coerceIn(0, 5)).apply()

    /** 系统是否深色。⚠️ 读 `Resources.getSystem()`：不受 app 强制日夜主题影响（与漫画一致）。 */
    fun isSystemDark(): Boolean =
        (android.content.res.Resources.getSystem().configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isDarkBackground(bg: Int): Boolean = when (bg) {
        2, 4 -> true
        3 -> false
        1 -> false
        else -> isSystemDark()
    }

    fun isDarkBackground(context: Context, prefs: SharedPreferences): Boolean =
        isDarkBackground(background(prefs))

    /** 背景色值。与漫画的 `resolveBgColor()` 同一套取值。 */
    fun backgroundColor(bg: Int): Int = when (bg) {
        1 -> 0xFFF0F0EE.toInt()
        2 -> 0xFF18181C.toInt()
        3 -> android.graphics.Color.WHITE
        4 -> android.graphics.Color.BLACK
        5 -> if (isSystemDark()) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        else -> if (isSystemDark()) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }

    // ===== 排版间距 =====

    /** 行距倍率 ×10。范围 1.0× ~ 2.2×：再小中文会挤，再大就散架了。 */
    const val LINE_SPACING_MIN = 10
    const val LINE_SPACING_MAX = 22

    /** 默认 1.4×（用户要求"默认行间距缩小一点点"，原 1.5×）。 */
    const val LINE_SPACING_DEFAULT = 14

    /** 缩小默认之前的那一档 —— 迁移时用它认出"用户其实没动过行距"。 */
    private const val LINE_SPACING_DEFAULT_LEGACY = 15

    /**
     * 段间距 dp（默认 25，用户明确要求）。
     *
     * ⚠️ **区间是固定的：[PARA_SPACING_ABS_MIN]..[PARA_SPACING_MAX]，与字号/行距无关。**
     * 从前上限还会跟着「段距必须大于行距」的动态下限一起抬，那套已经删掉了（见 [paragraphSpacingDp]）。
     */
    const val PARA_SPACING_MAX = 48
    const val PARA_SPACING_DEFAULT = 25

    /** 段距的绝对下限。与面板 `sb_para_spacing` 的 min 一致。 */
    const val PARA_SPACING_ABS_MIN = 6

    const val SIDE_PADDING_MIN = 16
    const val SIDE_PADDING_MAX = 64
    const val SIDE_PADDING_DEFAULT = 20

    const val VERTICAL_PADDING_MIN = 16
    const val VERTICAL_PADDING_MAX = 160

    private const val KEY_LINE_SPACING = "novel_line_spacing"
    private const val KEY_PARA_SPACING = "novel_paragraph_spacing"
    private const val KEY_PADDING = "novel_reader_padding"
    private const val KEY_TOP_PADDING = "novel_reader_top_padding"
    private const val KEY_BOTTOM_PADDING = "novel_reader_bottom_padding"
    private const val KEY_READER_MODE_VERSION = "novel_reader_mode_version"
    private const val KEY_READER_MODE = "novel_reader_mode"
    private const val READER_MODE_VERSION = 1

    /** 行距默认值变更的一次性迁移（见 [migrateLineSpacing]）。 */
    private const val KEY_LINE_SPACING_VERSION = "novel_line_spacing_version"
    private const val LINE_SPACING_VERSION = 1

    fun lineSpacingStep(prefs: SharedPreferences): Int {
        migrateLineSpacing(prefs)
        return prefs.getInt(KEY_LINE_SPACING, LINE_SPACING_DEFAULT)
            .coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX)
    }

    /**
     * 默认行距 1.5× → 1.4× 的一次性迁移。
     *
     * ⚠️ **只改 [LINE_SPACING_DEFAULT] 对已经用过的人无效**：prefs 里早存着旧默认值，
     * 他们会觉得"改了没反应"。所以这里把「还停在旧默认值」的人一起挪到新默认，
     * 自己调过行距的（不等于旧默认）一律不动。
     */
    private fun migrateLineSpacing(prefs: SharedPreferences) {
        if (prefs.getInt(KEY_LINE_SPACING_VERSION, 0) >= LINE_SPACING_VERSION) return
        val cur = prefs.getInt(KEY_LINE_SPACING, LINE_SPACING_DEFAULT_LEGACY)
        prefs.edit().putInt(KEY_LINE_SPACING_VERSION, LINE_SPACING_VERSION).apply {
            if (cur == LINE_SPACING_DEFAULT_LEGACY) putInt(KEY_LINE_SPACING, LINE_SPACING_DEFAULT)
        }.apply()
    }

    fun lineSpacingLabel(step: Int): String = String.format(java.util.Locale.US, "%.1f×", step / 10f)

    /**
     * 段落间距 dp。
     *
     * ⚠️ **只夹在固定的 [PARA_SPACING_ABS_MIN]..[PARA_SPACING_MAX] 之间，与字号、行距无关。**
     *
     * 这里以前还跟着一条「段距必须始终大于行距（按视觉空隙算）」的**动态下限**，于是：
     * 用户调一下字号或行距，**段距的滑块自己就跳走了** —— 面板把 SeekBar 的 min/max 按
     * 当前字号/行距动态算，而 SeekBar 会把 progress 夹到新 min，用户完全不知道为什么
     * 自己设的段距变了（用户报的「调字号或行距时段间距的进度条也会一起变化」）。
     *
     * 取舍：行距拉得很大时，段距若设得小，段与段的空档可能看着和行间一样密。那是用户
     * 自己看得见、拖一下就能改的事，**不该由系统偷偷改他设的值**。
     */
    fun paragraphSpacingDp(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_PARA_SPACING, PARA_SPACING_DEFAULT)
            .coerceIn(PARA_SPACING_ABS_MIN, PARA_SPACING_MAX)

    fun setParagraphSpacingDp(prefs: SharedPreferences, v: Int) {
        prefs.edit()
            .putInt(KEY_PARA_SPACING, v.coerceIn(PARA_SPACING_ABS_MIN, PARA_SPACING_MAX))
            .apply()
    }

    /** 改行距。**不再顺手改段距**（段距只认用户自己设的值，见 [paragraphSpacingDp]）。 */
    fun setLineSpacingStep(prefs: SharedPreferences, v: Int) {
        prefs.edit()
            .putInt(KEY_LINE_SPACING, v.coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX))
            .apply()
    }

    fun paddingDp(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_PADDING, SIDE_PADDING_DEFAULT).coerceIn(SIDE_PADDING_MIN, SIDE_PADDING_MAX)

    fun setPaddingDp(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_PADDING, v.coerceIn(SIDE_PADDING_MIN, SIDE_PADDING_MAX)).apply()

    fun topPaddingDp(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_TOP_PADDING, verticalPaddingDefaultDp())
            .coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)

    fun bottomPaddingDp(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_BOTTOM_PADDING, verticalPaddingDefaultDp())
            .coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)

    fun setTopPaddingDp(prefs: SharedPreferences, v: Int) = prefs.edit()
        .putInt(KEY_TOP_PADDING, v.coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)).apply()

    fun setBottomPaddingDp(prefs: SharedPreferences, v: Int) = prefs.edit()
        .putInt(KEY_BOTTOM_PADDING, v.coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)).apply()

    /** 上下间距的默认值：**上下取同一个数**（用户明确要求），且要够避开上下浮层。 */
    fun verticalPaddingDefaultDp(): Int =
        maxOf(CHROME_TOP_DP, CHROME_BOTTOM_DP).coerceIn(VERTICAL_PADDING_MIN, VERTICAL_PADDING_MAX)

    /**
     * **保持段落完整**：分页不在段落中间切断（放不下的段整段挪到下一页）。
     *
     * ⚠️ **默认关闭**（用户明确要求）：关掉时按间距值把页面填满，段落可能被切断；
     * 代价是「底部可能剩不到一段的空白」，收益是「页面不留空」。开关就在排版面板里。
     */
    private const val KEY_KEEP_PARAGRAPHS_WHOLE = "novel_keep_paragraphs_whole"

    fun keepParagraphsWhole(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_KEEP_PARAGRAPHS_WHOLE, false)

    fun setKeepParagraphsWhole(prefs: SharedPreferences, v: Boolean) =
        prefs.edit().putBoolean(KEY_KEEP_PARAGRAPHS_WHOLE, v).apply()

    // ===== 恢复默认 =====

    /** 恢复默认：字号与全部间距回到默认值。 */
    fun resetTypography(prefs: SharedPreferences) {
        val vertical = verticalPaddingDefaultDp()
        prefs.edit()
            .putBoolean(KEY_KEEP_PARAGRAPHS_WHOLE, false)
            .putFloat(KEY_FONT_SIZE, FONT_SIZE_DEFAULT)
            .putInt(KEY_LINE_SPACING, LINE_SPACING_DEFAULT)
            .putInt(KEY_PARA_SPACING, PARA_SPACING_DEFAULT)
            .putInt(KEY_PADDING, SIDE_PADDING_DEFAULT)
            .putInt(KEY_TOP_PADDING, vertical)
            .putInt(KEY_BOTTOM_PADDING, vertical)
            .apply()
    }

    // ===== 阅读模式 =====

    /** 阅读模式：0 左右翻页 / 1 上下翻页 / 2 连续滚动。 */
    const val READER_PAGED = 0
    const val READER_VERTICAL = 1
    const val READER_SCROLL = 2

    private const val KEY_DISPLAY_MODE = "novel_display_mode"

    fun readerMode(prefs: SharedPreferences): Int {
        migrateReaderMode(prefs)
        return prefs.getInt(KEY_READER_MODE, READER_PAGED).coerceIn(0, 2)
    }

    fun setReaderMode(prefs: SharedPreferences, v: Int) {
        prefs.edit()
            .putInt(KEY_READER_MODE, v.coerceIn(0, 2))
            .putInt(KEY_READER_MODE_VERSION, READER_MODE_VERSION)
            .apply()
    }

    /**
     * 阅读模式取值的一次性迁移。
     *
     * 引入「上下翻页」之前只有 `0 分页 / 1 滚动`；现在 1 变成了「上下翻页」、滚动挪到 2。
     * 不迁移的话，用户原来选的「滚动」会**静默变成「上下翻页」**，他只会觉得"滚动模式坏了"。
     */
    private fun migrateReaderMode(prefs: SharedPreferences) {
        if (prefs.getInt(KEY_READER_MODE_VERSION, 0) >= READER_MODE_VERSION) return
        val old = prefs.getInt(KEY_READER_MODE, READER_PAGED)
        val migrated = when (old) {
            1 -> READER_SCROLL
            2 -> READER_VERTICAL
            else -> READER_PAGED
        }
        prefs.edit()
            .putInt(KEY_READER_MODE, migrated)
            .putInt(KEY_READER_MODE_VERSION, READER_MODE_VERSION)
            .apply()
    }

    fun displayMode(prefs: SharedPreferences): NovelDisplayMode =
        NovelDisplayModeCodec.fromPref(prefs.getString(KEY_DISPLAY_MODE, null))

    fun setDisplayMode(prefs: SharedPreferences, mode: NovelDisplayMode) =
        prefs.edit().putString(KEY_DISPLAY_MODE, NovelDisplayModeCodec.toPref(mode)).apply()

    /**
     * 用当前偏好拼出排版参数。dp → px 在这里换算，调用方只管传 density。
     */
    fun textStyle(context: Context, prefs: SharedPreferences): NovelTextStyle {
        val density = context.resources.displayMetrics.scaledDensity
        val densityDpi = context.resources.displayMetrics.density
        val fontSp = fontSizeSp(prefs)
        // 双语模式下段间距要拉大一截：一段双语是「原文 + 换行 + 译文」两行，行间空隙就是普通
        // 行距，段间距若只比它大一点点，读者分不清"这行译文属于上一行原文还是再上一段"
        // （用户报的「好区分译文到底属于谁」）。一对双语算一个段落块，块之间留出明显空档。
        val paraFactor = if (displayMode(prefs) == NovelDisplayMode.BILINGUAL) {
            BILINGUAL_PARA_SPACING_FACTOR
        } else {
            1f
        }
        return NovelTextStyle(
            fontSizePx = fontSp * density,
            lineSpacingMultiplier = lineSpacingStep(prefs) / 10f,
            paragraphSpacingPx = paragraphSpacingDp(prefs) * densityDpi * paraFactor,
            paddingPx = paddingDp(prefs) * densityDpi,
            topPaddingPx = topPaddingDp(prefs) * densityDpi,
            bottomPaddingPx = bottomPaddingDp(prefs) * densityDpi,
            keepParagraphsWhole = keepParagraphsWhole(prefs),
        )
    }

    /**
     * 双语模式下段间距的放大倍率（见 [textStyle]）。
     *
     * 双语一段 = 原文行 + 译文行（行间只是一个普通行距），段与段之间按这个倍率拉开，
     * 「译文属于谁」一眼可辨。⚠️ 只作用于**排版**（分页与绘制共用同一份 style），
     * 不改用户设置里的那个 dp 值 —— 面板上显示的还是他设的数字。
     */
    const val BILINGUAL_PARA_SPACING_FACTOR = 1.8f

    /**
     * 正文文字颜色：跟随阅读背景深浅。
     *
     * 背景是深色时用浅灰而不是纯白 —— 纯白压在纯黑上对比过强，长段落读久了刺眼。
     */
    fun textColor(bg: Int): Int = if (isDarkBackground(bg)) 0xFFD8D8DC.toInt() else 0xFF1A1A1A.toInt()

    // ===== 翻译 =====

    /** 每批段数：默认 3，范围 1–10（用户明确要求：一次翻一批）。 */
    const val BATCH_MIN = 1
    const val BATCH_MAX = 10
    const val BATCH_DEFAULT = 3

    const val DEBOUNCE_MIN = 200
    const val DEBOUNCE_MAX = 2000

    private const val KEY_TRANSLATE_MODE = "novel_translate_mode"
    private const val KEY_DEBOUNCE = "novel_translate_debounce"

    /**
     * ⚠️ 新键，**不复用**旧的 `novel_translate_ahead`：那个存的是**章数**（默认 3、范围 1–10），
     * 直接读过来会被当成「向后翻 3 批」，语义完全不同。
     */
    private const val KEY_AHEAD_BATCHES = "novel_translate_ahead_batches"
    private const val KEY_BATCH = "novel_translate_batch"

    /**
     * 翻译模式。**底层仍存 0/1/2 的 int**（键没变，老用户的「自动」不会被读成手动，
     * 也不会因为 `getString` 读 int 键而抛 ClassCastException），对外只暴露枚举。
     */
    fun translateMode(prefs: SharedPreferences): NovelTranslateMode =
        when (prefs.getInt(KEY_TRANSLATE_MODE, 0).coerceIn(0, 2)) {
            1 -> NovelTranslateMode.AUTO
            2 -> NovelTranslateMode.AHEAD
            else -> NovelTranslateMode.MANUAL
        }

    fun setTranslateMode(prefs: SharedPreferences, mode: NovelTranslateMode) =
        prefs.edit().putInt(
            KEY_TRANSLATE_MODE,
            when (mode) {
                NovelTranslateMode.MANUAL -> 0
                NovelTranslateMode.AUTO -> 1
                NovelTranslateMode.AHEAD -> 2
            },
        ).apply()

    fun debounceMs(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_DEBOUNCE, 500).coerceIn(DEBOUNCE_MIN, DEBOUNCE_MAX)

    fun setDebounceMs(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_DEBOUNCE, v.coerceIn(DEBOUNCE_MIN, DEBOUNCE_MAX)).apply()

    /** 增量模式下自动向后翻的**批**数：默认 5，范围 2–10。 */
    fun aheadBatches(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_AHEAD_BATCHES, NovelQuota.DEFAULT).coerceIn(NovelQuota.MIN, NovelQuota.MAX)

    fun setAheadBatches(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_AHEAD_BATCHES, v.coerceIn(NovelQuota.MIN, NovelQuota.MAX)).apply()

    /** 每批段数：默认 3，范围 1–10。 */
    fun batchSize(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_BATCH, BATCH_DEFAULT).coerceIn(BATCH_MIN, BATCH_MAX)

    fun setBatchSize(prefs: SharedPreferences, v: Int) =
        prefs.edit().putInt(KEY_BATCH, v.coerceIn(BATCH_MIN, BATCH_MAX)).apply()

    fun displayModeLabel(context: Context, mode: NovelDisplayMode): String = context.getString(
        when (mode) {
            NovelDisplayMode.ORIGINAL -> R.string.novel_display_original
            NovelDisplayMode.BILINGUAL -> R.string.novel_display_bilingual
            else -> R.string.novel_display_translated
        }
    )
}
