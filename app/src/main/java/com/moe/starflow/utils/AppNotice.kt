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
 * **进行中的连续状态**（要与别的提示并存、结束才收）用 [showRunning] + [clearRunning]。
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
            toast(app, text)
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

    /**
     * 兜底 Toast（**全应用唯一一处** `UiUtils.showToast`，守卫 `ReaderNoticeOutletTest` 数着它）。
     *
     * 抽成函数是为了让"浮层画不出来"的所有分支都走同一个出口 —— 每处各写一遍
     * `UiUtils.showToast` 会立刻把那道守卫撞红。
     */
    private fun toast(context: Context, text: String) {
        UiUtils.showToast(context, text)
    }

    /**
     * 追加一条**「进行中」提示**：不替换顶部、不自动消失，返回句柄交给 [clearRunning] 精确移除。
     *
     * ## 什么时候用它（用户口径 2026-10）
     *
     * 「超分执行的时候也要有提示信息，而且要和『翻译中…』**一起出现** —— 我们的通知系统
     * 本来就支持同时显示多个通知」。[Style.PROGRESS] 走的是 `showImmediate` = **替换**顶部，
     * 用它就永远看不到"翻译中 + 正在超分"两条并存，所以另开这一个出口。
     *
     * 典型用法：任务开始 → `val id = showRunning(...)`；任务结束（含失败 / 取消）→
     * `clearRunning(context, id)`。**成对使用**，别只发不清。
     *
     * ⚠️ **默认不做 Toast 兜底**（[canUseOverlay] 为假时直接返回 0，既不显示也不报错）：
     * 这是**连续状态**（整章批量时每页都会发一条），浮层开关关掉时保持静默正是那个设置本身的
     * 语义 —— 与翻译进度芯片同一口径（退化成 Toast 就是每页刷一条）。
     * 结果/报错仍然走 [show]，那边带兜底，用户不会"毫无反馈"。
     *
     * @param toastIfUnavailable **用户主动点击**那一路传 true：一次点击只发一条，退化成 Toast
     *   不会刷屏，而静默会变成用户眼里的"点了没反应"（2026-10 真机反馈：点了超分，
     *   因为浮层权限没给，屏幕上**一句话都没有**）。批量/自动那一路保持 false。
     */
    fun showRunning(context: Context, text: String, toastIfUnavailable: Boolean = false): Long {
        val app = context.applicationContext
        if (!canUseOverlay(app)) {
            if (toastIfUnavailable) toast(app, text)
            return 0L
        }
        return TranslationStatusOverlay.getInstance(app).showRunning(text)
    }

    /**
     * 移除 [showRunning] 返回的那一条（**只摘它自己**，并排的「翻译中…」等一条不动）。
     *
     * 句柄为 0、或那条已经不在场（被清屏收走 / 被槽位挤掉）→ 空操作，可以放心重复调。
     */
    fun clearRunning(context: Context, id: Long) {
        if (id == 0L) return
        TranslationStatusOverlay.getInstance(context.applicationContext).removeRunning(id)
    }
}
