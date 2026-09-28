package com.moe.starflow.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.moe.starflow.R

/**
 * **阅读器（漫画 + 小说）所有弹窗的唯一主题入口。**
 *
 * ## 为什么必须是"强制 night 模式的上下文"，而不是"换底 + 刷字色"
 *
 * 阅读器的深浅跟的是**阅读背景**（`reader_background`），**不是系统主题**。
 * 早先的做法是「`window.setBackgroundDrawableResource(bg_dialog_dark)` + 把所有 TextView 刷成浅色」——
 * **它就是错的**：`AlertDialog` 的**面板底是主题给的**（AppCompat/Material 的 `alertDialogTheme`），
 * 画在窗口底**之上**把窗口底整个盖住；系统浅色时面板是白的，而文字被刷成了浅色
 * → **白底白字，完全看不见**（用户 2026-09-28 报的就是这个：
 * 「背景白色文字你也搞成白色我怎么看？？我不希望再出现色彩主题搭配的显示问题！」）。
 *
 * 所以现在：
 * 1. `context(context, dark)` 用 **Configuration 覆盖**把 `uiMode` 钉成 NIGHT_YES / NIGHT_NO，
 *    再用它建弹窗 → 面板、标题、正文、按钮、分割线**全部由框架按该模式给色**，
 *    不存在"底和字来自两个来源"的可能；
 * 2. 窗口底再贴一层本项目的圆角底（`bg_dialog_dark` / `bg_dialog_white`）保持与章节目录弹窗一致；
 * 3. **自定义内容视图**（`dialog_reader_download` / `dialog_page_preview` 这类 XML 里写死颜色的）
 *    走 [tintCustomView]：按**颜色自身的亮度 + 饱和度**决定翻不翻 ——
 *    中性色（#333/#888/#F0F0F0…）按模式翻转，**强调色/错误色（#55AEEA / #CC5555…）保留**
 *    （红字在深色底上本来就读得清，翻成灰反而丢了语义）。
 *
 * ⚠️ 新增弹窗**一律**走 [show]（或 [context] + [style]）；新增自定义内容视图**必须**过 [tintCustomView]。
 * 回归守卫：`ReaderThemeGuardTest`。
 */
object ReaderDialogs {

    private const val TAG = "ReaderDialogs"

    /** 深色模式下的主文字 / 次要文字 / 分割线（与面板 `applyPanelTheme` 同一套值）。 */
    const val LABEL_DARK = 0xFFE2E2E4.toInt()
    const val SUB_DARK = 0xFF9A9A9F.toInt()
    const val DIVIDER_DARK = 0x1AFFFFFF
    const val LABEL_LIGHT = 0xFF333333.toInt()
    const val SUB_LIGHT = 0xFF888888.toInt()
    const val DIVIDER_LIGHT = 0x11000000

    /**
     * 造一个「**以原上下文为 base**、主题照旧、强制 night 模式」的上下文给弹窗用。
     *
     * ## ⚠️ 两条血泪（都是线上崩过的，别再"优化"）
     *
     * 1. **不能 `createConfigurationContext()` 出来的裸 Context**：它拿不到 Activity 注入的 AppCompat 主题
     *    → `AlertDialog.show()` 抛 `You need to use a Theme.AppCompat theme`。
     * 2. **也不能把 `createConfigurationContext()` 的结果当 base**（哪怕再套 `ContextThemeWrapper`）：
     *    那个 Context **不挂在 Activity 的窗口上**，弹窗拿不到 window token
     *    → `WindowManager$BadTokenException: token null is not valid`（顶部胶囊那次崩溃）。
     *
     * ⇒ 唯一正确做法：**base 必须是原上下文（Activity）**，night 只能通过
     * [androidx.appcompat.view.ContextThemeWrapper.applyOverrideConfiguration] 加 ——
     * 它只改 `Resources/Configuration`，**不动 `getSystemService`**，所以窗口 token 保住、主题也保住。
     *
     * 拿不到主题资源时**直接返回原上下文**（`AppCompatActivity` 自己的 context，AppCompat 一定认）。
     */
    fun context(context: Context, dark: Boolean): Context {
        val themeRes = themeResIdOf(context)
        if (themeRes == 0) return context
        val wrapper = androidx.appcompat.view.ContextThemeWrapper(context, themeRes)
        val cfg = Configuration(context.resources.configuration)
        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        // ⚠️ 必须立刻 apply（在 wrapper 的 resources 被使用之前）；失败就退回原上下文
        return runCatching {
            wrapper.applyOverrideConfiguration(cfg)
            wrapper as Context
        }.getOrDefault(context)
    }

    /**
     * 取当前上下文对应的**主题资源 id**：Activity 在 Manifest 里声明的优先，其次 Application 的。
     *
     * ⚠️ 不能只取 `applicationInfo.theme`：Activity 完全可以在 Manifest 里声明另一个主题
     * （本项目阅读器就是这么干的），用错主题同样会让 AppCompat 认不出来。
     */
    private fun themeResIdOf(context: Context): Int {
        val fromActivity = (context as? android.app.Activity)?.let { act ->
            runCatching {
                act.packageManager.getActivityInfo(act.componentName, 0).theme
            }.getOrNull()
        } ?: 0
        if (fromActivity != 0) return fromActivity
        return runCatching { context.applicationInfo.theme }.getOrDefault(0)
    }

    /**
     * 窗口底（圆角底，与章节目录弹窗一致）。
     *
     * ⚠️ 参数是 `android.app.Dialog` 而不是 appcompat 的 `AlertDialog`：
     * 项目里两种弹窗都有（`NovelTocDialog` 用的是 framework 的 `android.app.AlertDialog`，
     * 它并不继承 appcompat 那个），放宽到 `Dialog` 两边都能用。
     */
    fun style(dialog: android.app.Dialog, dark: Boolean) {
        dialog.window?.setBackgroundDrawableResource(
            if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_dialog_white,
        )
    }

    /**
     * 建一个**已经按阅读背景配好主题**的弹窗并显示。
     *
     * ⚠️ **带兜底**：主题/配置这套万一在某机型上出问题（主题认不出、token 拿不到），
     * 一律退回**原上下文**再建一次 —— 宁可这次配色不跟随阅读背景，也**绝不能让用户看到闪退**。
     * 兜底触发会记 E 级日志（排查时能看到是哪一步失败）。
     *
     * 用法与 `AlertDialog.Builder` 一致（链式设置标题/正文/按钮）。
     * 自定义内容视图用 `setView(...)` 传进来后，调用方再对那个 view 调 [tintCustomView]。
     */
    fun show(
        context: Context,
        dark: Boolean,
        configure: AlertDialog.Builder.() -> Unit,
    ): AlertDialog {
        val themed = context(context, dark)
        return try {
            AlertDialog.Builder(themed).apply(configure).create().also {
                it.show()
                style(it, dark)
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "阅读器弹窗构建失败，退回原上下文重试（本次配色不跟阅读背景）", e)
            AlertDialog.Builder(context).apply(configure).create().also {
                it.show()
                style(it, dark)
            }
        }
    }

    /**
     * 自定义内容视图：按**每个颜色的亮度 + 饱和度**决定是否翻转。
     *
     * - **中性色**（饱和度低：黑/灰/白）→ 深色模式翻成浅、浅色模式翻成深（否则就是"白底白字/黑底黑字"）
     * - **强调色/错误色**（饱和度高：#55AEEA 蓝、#CC5555 红、#34C759 绿）→ **保留**，
     *   它们在两种底色上都可读，翻掉反而丢失语义（"删除"不再红）
     * - 只有 1dp 高的分隔线（背景色）同样按中性色规则翻
     */
    fun tintCustomView(root: View?, dark: Boolean) {
        if (root == null) return
        when (root) {
            is TextView -> {
                val c = currentTextColor(root)
                if (isNeutral(c)) root.setTextColor(if (dark) darkenNeutralToLight(c) else lightenNeutralToDark(c))
            }
            is ViewGroup -> {
                // 分隔线：1dp 高的纯色 View（XML 里常写 #F0F0F0，深色下一条亮线很扎眼）
                if (root.height in 1..(3 * root.resources.displayMetrics.density).toInt() && root !is TextView) {
                    val bg = (root.background as? android.graphics.drawable.ColorDrawable)?.color
                    if (bg != null && isNeutral(bg)) {
                        root.setBackgroundColor(if (dark) DIVIDER_DARK else DIVIDER_LIGHT)
                    }
                }
                for (i in 0 until root.childCount) tintCustomView(root.getChildAt(i), dark)
            }
        }
    }

    /** 取 TextView 当前文字色（拿不到就当主文字色）。 */
    private fun currentTextColor(tv: TextView): Int = runCatching { tv.currentTextColor }.getOrDefault(LABEL_LIGHT)

    /** 是否"中性色"（低饱和）：黑白灰属于它，蓝/红/绿不属于。 */
    private fun isNeutral(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        if (max == 0) return true
        return (max - min).toFloat() / max < 0.25f
    }

    /** 中性色 → 深色模式下的可读值：暗色映射成主文字浅色，亮色（分割线）映射成暗底分割线。 */
    private fun darkenNeutralToLight(color: Int): Int = if (luminance(color) < 0.5f) LABEL_DARK else DIVIDER_DARK

    /** 中性色 → 浅色模式下的可读值：亮色映射成主文字深色，暗色（分割线）映射成浅底分割线。 */
    private fun lightenNeutralToDark(color: Int): Int = if (luminance(color) >= 0.5f) LABEL_LIGHT else DIVIDER_LIGHT

    /** 相对亮度（0=黑 1=白）。 */
    private fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f; val g = Color.green(color) / 255f; val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
