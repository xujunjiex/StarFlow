package com.moe.starflow.translate
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.R

import android.animation.LayoutTransition
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.LinkedList

/**
 * 翻译状态悬浮提示条 — 统一的游戏/漫画翻译状态反馈组件。
 *
 * - 单窗口 + 竖直 LinearLayout 容器：同一时间可显示多条消息，**纵向堆叠**（第 1/2/3 行），不会重叠
 * - 位置、时长从个性化设置读取
 * - 所有消息通过 LogCollector 记录
 * - 错误消息可点击复制
 * - 进度/状态消息（showImmediate）复用最顶部一条；队列消息依次补位
 */
class TranslationStatusOverlay private constructor(private val context: Context) {

    companion object {
        private const val TAG = "StatusOverlay"
        private const val MAX_SLOTS = 3

        /** 全局唯一实例：游戏/漫画/无障碍服务/NLLB 共用同一浮窗，避免多条消息在不同浮窗上重叠 */
        @Volatile
        private var instance: TranslationStatusOverlay? = null

        fun getInstance(context: Context): TranslationStatusOverlay =
            instance ?: synchronized(this) {
                instance ?: TranslationStatusOverlay(context.applicationContext).also { instance = it }
            }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = CustomPreference.getInstance(context)

    private var container: LinearLayout? = null
    private var isShowing = false

    // 每条消息的自动消失任务
    private val dismissRunnables = HashMap<TextView, Runnable>()
    // 标记为「不随 dismiss 清屏」的 chip（showSticky 用）—— 屏幕方向变化这类提示
    // 必须在随后的「检测中…」清屏中存活，否则用户根本读不到（实测被吃掉）。
    private val stickyChips = HashSet<TextView>()
    // 待显示队列（超过 MAX_SLOTS 时排队）
    private val messageQueue = LinkedList<QueuedMessage>()

    private data class QueuedMessage(val text: String, val isError: Boolean)

    // ========== Public API ==========

    /**
     * 队列显示状态提示。多条消息会**同时纵向堆叠**显示（最多 3 条），
     * 超过 3 条排队，前面的消失后补位。
     * 用于：初始化信息、启停提示等用户需要看到每一条的消息。
     */
    fun show(message: String) {
        if (!isEnabled()) return
        LogCollector.d(TAG, message)
        runOnMainThread {
            if (activeCount() >= MAX_SLOTS) {
                messageQueue.add(QueuedMessage(message, isError = false))
            } else {
                addChip(message, isError = false, autoDismiss = true)
            }
        }
    }

    /**
     * 覆盖显示状态提示（替换最顶部一条，其他堆叠消息保留）。
     * 用于：状态进度、模型切换等只关心最新状态的消息。
     */
    fun showImmediate(message: String, autoDismiss: Boolean = true) {
        if (!isEnabled()) return
        LogCollector.d(TAG, message)
        runOnMainThread {
            val top = topChip()
            if (top != null) {
                top.text = message
                top.background = createRoundedBackground(Color.argb(150, 0, 0, 0))
                top.isClickable = false
                top.setOnClickListener(null)
                rescheduleDismiss(top, autoDismiss)
                // 确保窗口已附着：窗口可能已被系统移除而 isShowing 仍为 true（回归修复）
                addToWindowIfNeeded()
            } else {
                addChip(message, isError = false, autoDismiss = autoDismiss)
            }
        }
    }

    /**
     * 显示一条**能扛住清屏**的普通提示（到点仍会自动消失）。
     *
     * 用于「屏幕方向变化」这类需要用户读到的通知。两条失效路径都要避开：
     * - [showImmediate] 会**替换**顶部 chip → 几秒后的「检测中…」把提示顶掉
     * - `dismiss()` 会**清空全部** chip → 每轮翻译收尾的 `dismissProgressOverlay()` 把提示吃掉
     *
     * 因此：走普通堆叠（有自己的消失计时，不会永久驻留）+ 登记进 stickyChips
     * （在存活期内不被 `dismiss()` 清掉）。**不要**改成 `autoDismiss = false`——
     * 那样没有消失计时，会永久挂在屏幕上。
     */
    fun showSticky(message: String) {
        if (!isEnabled()) return
        LogCollector.d(TAG, message)
        runOnMainThread {
            // ⚠️ **绝不排队**：排队的消息要等前面消失才显示，而中途任何一次
            // `dismiss()`（每轮翻译收尾都会调）会把队列一并清掉 —— 提示就此消失，
            // 用户永远看不到（实测：发出后 10 秒仍无 "Overlay added to window"）。
            // 槽位满时挤掉一个**非 sticky** 的旧 chip 腾地方；全是 sticky 就挤最旧的。
            val layout = container
            while (layout != null && layout.childCount >= MAX_SLOTS) {
                val victim = (0 until layout.childCount)
                    .map { layout.getChildAt(it) }
                    .firstOrNull { it !in stickyChips }
                    ?: layout.getChildAt(0)
                dismissRunnables.remove(victim)?.let { mainHandler.removeCallbacks(it) }
                stickyChips.remove(victim)
                layout.removeView(victim)
            }
            // ⚠️ autoDismiss 必须为 **true**：为 false 时 rescheduleDismiss 直接 return，
            // 压根不排消失任务；而 dismiss() 又保留 sticky —— 两者叠加会让提示**永久驻留**，
            // 且后续同类提示复到同一位置、用户看着"没变化"（实测：转屏两次，第二条被当成旧的）。
            // 正确语义 = 普通提示的自动消失 + 存活期内不被翻译收尾的清屏吃掉。
            stickyChips.add(addChip(message, isError = false, autoDismiss = true))
        }
    }

    /**
     * 显示错误提示（红色背景，可点击复制）。替换最顶部一条。
     */
    fun showError(message: String) {
        if (!isEnabled()) return
        LogCollector.e(TAG, message)
        runOnMainThread {
            val top = topChip()
            val chip = if (top != null) {
                top.text = message
                top
            } else {
                addChip(message, isError = true, autoDismiss = false)
            }
            chip.background = createRoundedBackground(Color.argb(150, 180, 0, 0))
            chip.isClickable = true
            chip.setOnClickListener {
                copyToClipboard(message)
                chip.text = context.getString(R.string.toast_copied)
                chip.background = createRoundedBackground(Color.argb(150, 0, 120, 0))
                chip.isClickable = false
                rescheduleDismiss(chip, true, 1000L)
            }
            // 确保窗口已附着（与 showImmediate 同理）
            addToWindowIfNeeded()
        }
    }

    /**
     * 更新提示文字（不重置计时器）。用于：进度提示的实时更新。
     */
    fun update(message: String) {
        if (!isEnabled()) return
        runOnMainThread {
            topChip()?.let {
                it.text = message
                // 确保窗口已附着
                addToWindowIfNeeded()
            }
        }
    }

    /**
     * 手动关闭所有提示。
     */
    fun dismiss() {
        runOnMainThread {
            // ⚠️ 保留 sticky 提示：进度类消息的收尾清屏（每轮翻译结束都会调）会把
            // 几秒前发的「屏幕方向已变化…」一起抹掉 —— 那条恰恰是用户唯一需要读到的。
            removeAllChips(keepSticky = true)
            messageQueue.clear()
        }
    }

    /**
     * 释放资源（Service 销毁时调用）。
     */
    fun release() {
        runOnMainThread {
            removeAllChips()
            messageQueue.clear()
            container = null
        }
    }

    // ========== Internal ==========

    private fun activeCount(): Int = container?.childCount ?: 0

    /** 容器当前的所有子 View（保持顺序）。 */
    private fun android.view.ViewGroup.children(): List<android.view.View> =
        (0 until childCount).map { getChildAt(it) }

    private fun topChip(): TextView? = container?.getChildAt(0) as? TextView

    private fun ensureContainer(): LinearLayout {
        container?.let { return it }
        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 出现（淡入）/ 移动（兄弟项位移）/ 消失（淡出）动画
            layoutTransition = LayoutTransition().apply {
                setDuration(220L)
            }
        }
        container = linearLayout
        addToWindowIfNeeded()
        return linearLayout
    }

    private fun createChip(): TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        val hPad = (10 * context.resources.displayMetrics.density).toInt()
        val vPad = (4 * context.resources.displayMetrics.density).toInt()
        setPadding(hPad, vPad, hPad, vPad)
        gravity = Gravity.CENTER
    }

    /** 圆角半透明背景 */
    private fun createRoundedBackground(color: Int): GradientDrawable {
        val radius = (12 * context.resources.displayMetrics.density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }
    }

    private fun addChip(message: String, isError: Boolean, autoDismiss: Boolean): TextView {
        val layout = ensureContainer()
        val chip = createChip()
        chip.text = message
        chip.background = createRoundedBackground(
            if (isError) Color.argb(150, 180, 0, 0)
            else Color.argb(150, 0, 0, 0)
        )
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (layout.childCount > 0) {
            lp.topMargin = (6 * context.resources.displayMetrics.density).toInt()
        }
        layout.addView(chip, lp)
        // ⚠️ **必须直接调**，不能只靠 `layout.post {}`：
        // `View.post()` 在 View 未 attach 到窗口时**不会执行**，而是排队等 attach。
        // 而 `ensureContainer()` 新建的容器必然未 attach → 那个 runnable 永远不跑 →
        // 窗口永远加不上 → 提示不可见。直到**别的**状态消息（showImmediate 在已有 chip 时
        // 是直接调 addToWindowIfNeeded 的）把容器 attach 上去，排队的 post 才一起补跑，
        // 提示这时才"延迟出现"（实测：框选后转屏的提示要等下次翻译才显示）。
        addToWindowIfNeeded()
        layout.post { addToWindowIfNeeded() }  // 内容变化后再刷一次布局
        if (autoDismiss) rescheduleDismiss(chip, true)
        return chip
    }

    private fun rescheduleDismiss(chip: TextView, enabled: Boolean, customDurationMs: Long? = null) {
        dismissRunnables.remove(chip)?.let { mainHandler.removeCallbacks(it) }
        if (!enabled) return
        val duration = customDurationMs ?: getDurationMs()
        val runnable = Runnable {
            removeChip(chip)
        }
        dismissRunnables[chip] = runnable
        mainHandler.postDelayed(runnable, duration)
    }

    private fun removeChip(chip: TextView) {
        val layout = container ?: return
        layout.removeView(chip)
        // ⚠️ 同步清理身份集合：不清的话它永久增长，且 showSticky 选「牺牲者」时
        // 用 `it !in stickyChips` 判断会认错对象（已消失的 chip 仍被认为在场）
        stickyChips.remove(chip)
        dismissRunnables.remove(chip)?.let { mainHandler.removeCallbacks(it) }
        if (layout.childCount == 0) {
            removeFromWindow()
        } else {
            layout.post { addToWindowIfNeeded() }
        }
        // 队列补位
        if (messageQueue.isNotEmpty() && layout.childCount < MAX_SLOTS) {
            val next = messageQueue.poll()
            if (next != null) addChip(next.text, next.isError, autoDismiss = true)
        }
    }

    /**
     * @param keepSticky true = 保留 [stickyChips]（进度类收尾清屏用）；
     *                   false = 全清（release / 用户主动关闭用）
     */
    private fun removeAllChips(keepSticky: Boolean = false) {
        val layout = container ?: return
        dismissRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        dismissRunnables.clear()
        if (keepSticky && stickyChips.isNotEmpty()) {
            // ⚠️ 保留 sticky：**先记下要留的，再整体清空，最后按原顺序加回**。
            // 不要写「逐个 removeView 再 addView」——那要求每个 chip 的 parent 恰是本 layout，
            // 不满足时 removeView 被跳过、addView 便抛
            // `IllegalStateException: specified child already has a parent`（实测崩溃）。
            // 也不能复用旧 LayoutParams：removeView 后需重新给，否则间距/顺序丢失。
            val keep = layout.children()
                .filter { it in stickyChips }
                .map { it to (it.layoutParams as? android.widget.LinearLayout.LayoutParams) }
            layout.removeAllViews()
            keep.forEachIndexed { idx, (chip, lp) ->
                val params = lp?.let {
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (idx == 0) 0 else it.topMargin }
                }
                if (params != null) layout.addView(chip, params) else layout.addView(chip)
                // ⚠️ **必须重启消失计时**：上面 clear() 已经把它的回调摘掉了，
                // 不补回来这个 chip 就再也不会消失（实测「提示一直不消失」）。
                rescheduleDismiss(chip as TextView, enabled = true)
            }
            if (layout.childCount == 0) removeFromWindow()
        } else {
            stickyChips.clear()
            layout.removeAllViews()
            removeFromWindow()
        }
    }

    private fun isEnabled(): Boolean = prefs.getBoolean("status_overlay_enabled", true)

    private fun getPosition(): Int {
        return when (prefs.getString("Status_Position", "top")) {
            "center" -> Gravity.CENTER
            "bottom" -> Gravity.BOTTOM
            else -> Gravity.TOP
        }
    }

    private fun getDurationMs(): Long {
        return prefs.getString("Status_Duration", "2000").toLongOrNull() ?: 2000L
    }

    private fun getViewParams(): WindowManager.LayoutParams {
        val position = getPosition()
        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = position or Gravity.CENTER_HORIZONTAL
            y = when (position) {
                Gravity.TOP -> (24 * context.resources.displayMetrics.density).toInt()
                Gravity.BOTTOM -> (80 * context.resources.displayMetrics.density).toInt()
                else -> 0
            }
        }
    }

    private fun addToWindowIfNeeded() {
        val layout = container ?: return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (isShowing) {
            try {
                wm.updateViewLayout(layout, getViewParams())
            } catch (e: Exception) {
                // isShowing 可能已过期（窗口被系统移除），尝试重新添加
                LogCollector.w(TAG, "updateViewLayout 失败，尝试重新添加窗口: ${e.message}")
                try {
                    wm.addView(layout, getViewParams())
                    LogCollector.d(TAG, "Overlay re-added to window")
                } catch (e2: Exception) {
                    LogCollector.e(TAG, "Failed to re-add overlay", e2)
                }
            }
        } else {
            try {
                wm.addView(layout, getViewParams())
                isShowing = true
                LogCollector.d(TAG, "Overlay added to window")
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to add overlay", e)
            }
        }
    }

    private fun removeFromWindow() {
        if (!isShowing) return
        val layout = container ?: return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeView(layout)
        } catch (_: Exception) {}
        isShowing = false
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("translation_error", text))
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to copy to clipboard", e)
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
