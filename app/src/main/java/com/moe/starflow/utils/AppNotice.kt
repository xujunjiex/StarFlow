package com.moe.starflow.utils

import android.content.Context
import com.moe.starflow.translate.TranslationStatusOverlay

/**
 * **全应用统一的用户提示出口**（截屏翻译那套顶部系统浮层 `TranslationStatusOverlay`）。
 *
 * 用户口径（2026-10）：「阅读器/书架所有的提示报错信息全部使用顶部系统弹窗系统，用截屏翻译
 * 那个同款（支持调节显示位置、时间的那个）」。
 *
 * 位置与时长不用调用方管：浮层自己读 `Status_Position` / `Status_Duration`（个性化设置页）；
 * 总开关 `status_overlay_enabled` 关掉、或**没有「显示在其他应用上层」权限**时浮层会**静默失败**
 * （既不显示也不报错）—— 所以这两条判据必须由本出口统一兜底成系统 Toast。
 *
 * ⚠️ **本文件是全应用唯一允许调用 `UiUtils.showToast` 的地方**（守卫 `ReaderNoticeOutletTest`）。
 * 新加提示请调 [show]，不要自己弹 Toast：少了兜底判据，用户看到的就是「点了毫无反馈」。
 */
object AppNotice {

    /** 总开关键（与个性化设置页 `status_overlay_enabled` 同一份默认 prefs）。 */
    const val KEY_OVERLAY_ENABLED = "status_overlay_enabled"

    /** 「短时报错」的驻留时长（[Style.BRIEF_ERROR]）。比普通提示长一点：够读完，又不赖在屏幕上。 */
    private const val BRIEF_ERROR_MS = 3200L

    /** 提示样式。 */
    enum class Style {
        /**
         * 进度/状态：**替换**最顶部那条芯片（截屏翻译的「检测中…」同款用法）。
         * `autoDismiss = false` 时**常驻**，直到被结果/失败替换 —— 用于「正在超分…」这类进行中状态。
         */
        PROGRESS,

        /** 普通提示：堆叠一条、到点自动消失。 */
        INFO,

        /** 报错：红底芯片、可点击复制；默认**常驻**到被下一条提示替换（翻译失败就是它）。 */
        ERROR,

        /**
         * 报错 + 限时自动消失 + 扛得住 `dismiss()` 清屏。
         *
         * 用于「说完就关掉页面/关掉弹窗」的报错（本地文件丢失、导出失败这类）：用 [ERROR] 的话
         * 那条红芯片是 `TYPE_APPLICATION_OVERLAY` 系统窗口，宿主没了它仍会挂在别的应用上方，
         * 而且 `onDestroy` 那次清屏还会把刚发出去的内容吃掉。
         */
        BRIEF_ERROR,
    }

    /**
     * 浮层现在**能不能真的画出来**：总开关 + 「显示在其他应用上层」权限，两个都满足才行。
     *
     * 有些场景需要提前分流（例如导出进度只在浮层可用时挂常驻芯片），所以单独暴露。
     */
    fun canUseOverlay(context: Context): Boolean = try {
        val app = context.applicationContext
        CustomPreference.getInstance(app).getSharedPreferences()
            .getBoolean(KEY_OVERLAY_ENABLED, true) && TranslationStatusOverlay.canDraw(app)
    } catch (e: Exception) {
        // prefs 读失败之类的极端情况：当作画不出来，退回 Toast（宁可丑，不可静默）
        false
    }

    /**
     * 显示一条提示。
     *
     * @param clearFirst 先清空已有芯片再显示。`show()` 是**追加**一条、不替换顶部，
     *   所以「结果/报错要顶掉常驻的『正在…』芯片」时必须传 true（否则两条一起挂着）。
     * @param autoDismiss 只有 [Style.PROGRESS] 用得上：false = 常驻到被替换。
     */
    fun show(
        context: Context,
        text: String,
        style: Style = Style.INFO,
        autoDismiss: Boolean = true,
        clearFirst: Boolean = false,
    ) {
        val app = context.applicationContext
        if (!canUseOverlay(app)) {
            UiUtils.showToast(app, text)
            return
        }
        val overlay = TranslationStatusOverlay.getInstance(app)
        if (clearFirst) overlay.dismiss()
        when (style) {
            Style.PROGRESS -> overlay.showImmediate(text, autoDismiss = autoDismiss)
            Style.INFO -> overlay.show(text)
            Style.ERROR -> overlay.showError(text)
            Style.BRIEF_ERROR -> overlay.showError(text, autoDismissMs = BRIEF_ERROR_MS, sticky = true)
        }
    }
}
