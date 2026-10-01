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

        /** 顶部贴边距离（dp）——**截图翻译链路**的口径；阅读器会用 [setTopOffsetDp] 覆盖它。 */
        private const val TOP_OFFSET_DP = 24

        /**
         * 阅读器里提示条的顶距（dp）：**让到顶部章节胶囊下面**。
         *
         * 口径来源（用户 2026-10）：「章节胶囊不要紧贴屏幕顶部，**保证提示在章节胶囊下面**就行了」。
         * 胶囊在 `layout_marginTop=38dp`、高约 26dp → 下沿 ~64dp；提示条从 76dp 起，
         * 三条堆叠（76 / 106 / 136）全部落在胶囊下方。
         * ⚠️ 改胶囊的 marginTop 就要同步改这里 —— 守卫 `ReaderTopPillInsetTest`。
         */
        const val READER_TOP_OFFSET_DP = 76

        /** 全局唯一实例：游戏/漫画/无障碍服务/NLLB 共用同一浮窗，避免多条消息在不同浮窗上重叠 */
        @Volatile
        private var instance: TranslationStatusOverlay? = null

        /**
         * 现在**能不能真的画出来**（缺「显示在其他应用上层」权限时画不出来）。
         *
         * ⚠️ 调用方必须用它兜底成 Toast：权限没给时浮层**静默失败**（既不显示也不报错），
         * 用户看到的就是"点了翻译/暂停，**毫无提示**"（用户报"什么提示都没有"的根因之一）。
         * ⚠️ 必须是 **companion 成员**：本类是 class + 伴生单例，实例方法不能用类名调用。
         */
        fun canDraw(context: Context): Boolean = try {
            android.provider.Settings.canDrawOverlays(context)
        } catch (e: Exception) {
            false
        }
        fun getInstance(context: Context): TranslationStatusOverlay =
            instance ?: synchronized(this) {
                instance ?: TranslationStatusOverlay(context.applicationContext).also { instance = it }
            }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = CustomPreference.getInstance(context)

    private var container: LinearLayout? = null
    private var isShowing = false

    /**
     * 顶距覆盖（dp）：阅读器用它在**不改用户设置**的前提下把提示条压到章节胶囊**下面**。
     *
     * ⚠️ 为什么需要：阅读器顶部有章节页码胶囊，用户口径是「**胶囊在提示条上面**，这样多条信息
     * 堆下来也不会盖住胶囊」。浮层默认顶距 24dp（截图翻译链路的口径，那边没有胶囊），
     * 而胶囊在 10dp 附近 —— 两者必然重叠。阅读器前台时把顶距让到胶囊下面，离开时恢复，
     * 游戏/截屏链路完全不受影响。
     */
    @Volatile
    private var topOffsetOverrideDp: Int? = null

    // 每条消息的自动消失任务
    private val dismissRunnables = HashMap<TextView, Runnable>()
    // 标记为「不随 dismiss 清屏」的 chip（showSticky 用）—— 屏幕方向变化这类提示
    // 必须在随后的「检测中…」清屏中存活，否则用户根本读不到（实测被吃掉）。
    private val stickyChips = HashSet<TextView>()
    // 「进行中」芯片（showRunning 用）→ 句柄 id。**LinkedHashMap**：槽位满时要按**加入顺序**
    // 挤掉最旧的那条，HashMap 的迭代序跟插入序无关，挤谁是随机的。
    private val runningChips = LinkedHashMap<TextView, Long>()
    private val nextRunningId = java.util.concurrent.atomic.AtomicLong(0)
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
                // ⚠️ 这条芯片被**改作他用**了 → 不再属于「进行中」那一类。
                //    不摘的话，它原来那一页超分结束时会 `removeRunning` 把这条**进度**芯片删掉
                //    （表现：一条提示莫名其妙自己消失）。
                runningChips.remove(top)
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
     * 追加一条**「进行中」芯片**：不替换顶部、不自动消失，返回**句柄**供 [removeRunning] 精确移除。
     *
     * ## 为什么不能复用现成的三个入口（用户口径 2026-10）
     *
     * 用户要求「超分执行时也要有提示，而且要和『翻译中…』**一起出现** —— 我们的通知系统
     * 本来就支持同时显示多条」。现成的三条路都做不到这件事：
     *
     * | 入口 | 行为 | 为什么不满足 |
     * |---|---|---|
     * | [showImmediate] | **替换**最顶部一条 | 超分一开始就把「翻译中…」顶掉，永远看不到两条并存 |
     * | [show] | 追加但 `autoDismiss=true` | 超分单页可跑几分钟，提示会先自己消失 |
     * | [showSticky] | 追加、扛得住 `dismiss()` | 但它**也登记进 sticky** → 翻译收尾那次清屏清不掉它 → 会赖在屏幕上 |
     *
     * 所以这里新的语义是「**追加 + 不自动消失 + 可被精确移除**」：既与别的芯片并存，
     * 又能在超分结束时**只摘掉自己**（而不是像以前那样 `dismiss()` 清掉全部 —— 那正是
     * 「和翻译中一起出现」做不到的原因）。
     *
     * ⚠️ **不进 [stickyChips]** 是刻意的：任何一次 `dismiss()`（翻译成功/失败、阅读器
     * `onDestroy` 清屏）都会把它一起收走 —— 这是"调用方忘了 [removeRunning] 也不会永久驻留"
     * 的兜底。反之若登记成 sticky，漏移除就是永久挂着（[showSticky] 的注释里记着同一个坑）。
     *
     * ⚠️ 槽位满时**优先挤掉最旧的一条同类芯片**，绝不先动普通/进度芯片：整章批量翻译时
     * 每页都会起一次超分，若按"挤最旧的"处理就会把并排的「翻译中…」或「正在翻译本章…」抹掉。
     *
     * @return 句柄（> 0），交给 [removeRunning]；浮层总开关关掉 → 0（此时 [removeRunning] 是空操作）。
     */
    fun showRunning(message: String): Long {
        if (!isEnabled()) return 0L
        LogCollector.d(TAG, message)
        val id = nextRunningId.incrementAndGet()
        runOnMainThread {
            val layout = ensureContainer()
            while (layout.childCount >= MAX_SLOTS) {
                val victim = runningChips.keys.firstOrNull()
                    ?: layout.children().firstOrNull { it !in stickyChips }
                    ?: layout.getChildAt(0)
                dismissRunnables.remove(victim)?.let { mainHandler.removeCallbacks(it) }
                stickyChips.remove(victim)
                runningChips.remove(victim)
                layout.removeView(victim)
            }
            val chip = addChip(message, isError = false, autoDismiss = false)
            runningChips[chip] = id
        }
        return id
    }

    /**
     * 移除 [showRunning] 返回的那一条（**只摘它自己**，别的芯片一条不动）。
     *
     * 句柄为 0 / 那条已经不在场（被 `dismiss()` 收走、或被槽位挤掉）→ 空操作。
     */
    fun removeRunning(id: Long) {
        if (id == 0L) return
        runOnMainThread {
            val chip = runningChips.entries.firstOrNull { it.value == id }?.key ?: return@runOnMainThread
            runningChips.remove(chip)
            removeChip(chip)
        }
    }

    /**
     * 显示错误提示（红色背景，可点击复制）。替换最顶部一条。
     *
     * @param autoDismissMs 到点自动消失（毫秒）。**默认 null = 一直挂着**，直到被下一次
     *   `dismiss()` / 别的提示替换 —— 翻译失败这类「等用户读完、点一下复制」的错误就用这个默认值。
     *   阅读器里「说完就关掉页面」的报错（本地文件丢失）必须传值：不传的话那条红芯片是
     *   `TYPE_APPLICATION_OVERLAY` 系统窗口，页面关掉后它会挂在别的应用上，没有任何入口能消掉。
     * @param sticky 登记进 [stickyChips]：`dismiss()` 清屏时**保留**它。阅读器 `onDestroy`
     *   会清一次浮层（防止「检测中…」残留到桌面），不登记的话刚发出去的报错会被那次清屏吃掉。
     */
    fun showError(message: String, autoDismissMs: Long? = null, sticky: Boolean = false) {
        if (!isEnabled()) return
        LogCollector.e(TAG, message)
        runOnMainThread {
            val top = topChip()
            val chip = if (top != null) {
                top.text = message
                // 同 showImmediate：被复用的那条已经变成报错芯片，不再是「进行中」芯片
                runningChips.remove(top)
                top
            } else {
                addChip(message, isError = true, autoDismiss = false)
            }
            if (sticky) stickyChips.add(chip)
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
            // 传了时长就排消失任务：`addChip(autoDismiss = false)` / 复用在顶部的那条都没有计时器，
            // 不补这一句就是「说好 3 秒消失，实际永远挂着」
            if (autoDismissMs != null) rescheduleDismiss(chip, true, autoDismissMs)
        }
    }

    /**
     * 临时覆盖「顶部贴边距离」（dp）；传 null 恢复用户设置/默认值。
     *
     * 阅读器在 `onStart` 传一个大于胶囊下沿的值、`onStop` 传 null（见 MangaReaderActivity）。
     * 已显示的芯片会立刻重排，不必等下一帧。
     */
    fun setTopOffsetDp(dp: Int?) {
        runOnMainThread {
            if (topOffsetOverrideDp == dp) return@runOnMainThread
            topOffsetOverrideDp = dp
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

    /**
     * **测试缝**：当前屏上芯片的文字（按堆叠顺序）。生产逻辑不消费它。
     *
     * 存在的理由：容器是私有的，而「[showRunning] 是**追加**、不是替换」与
     * 「[removeRunning] **只**摘自己那条」这两条恰恰是没法从外部观察的语义
     * （2026-10 用户口径「超分提示要和翻译中一起出现」）—— 靠读源码断言容易漂。
     */
    internal fun debugChipTexts(): List<String> =
        container?.children()?.map { (it as TextView).text.toString() } ?: emptyList()

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
        // 同上：句柄表也要跟着清，否则 removeRunning 会去移除一条早已不在场的 chip
        runningChips.remove(chip)
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
        // 「进行中」芯片**从不登记 sticky**，所以两个分支都会把它们清掉 → 句柄表也一并清空，
        // 免得留下的句柄让调用方以为"还有一条在场"。
        runningChips.clear()
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
                Gravity.TOP -> (((topOffsetOverrideDp ?: TOP_OFFSET_DP)) * context.resources.displayMetrics.density).toInt()
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
