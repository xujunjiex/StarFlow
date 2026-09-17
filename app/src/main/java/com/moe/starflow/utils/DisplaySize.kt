package com.moe.starflow.utils

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

/**
 * 屏幕几何取值的**唯一入口**。
 *
 * ## 为什么不能直接用 Display API
 *
 * `<service>` 没有 Activity 的 window，进程里的 `Display` 系列取值会被冻结在
 * **进程初始化时的方向**（用户实测：设备已横屏后，`getRealSize` 仍返回竖屏
 * 1220x2712，而同一时刻 `onConfigurationChanged` 收到的 config 已经是横屏）。
 * 拿冻结值去建 VirtualDisplay / 定框选窗口尺寸，就是两套坐标系 → 框选位置与识别
 * 位置错位。`dumpsys display` 里 `mBaseDisplayInfo` 与 `mOverrideDisplayInfo` 互相
 * 矛盾（base 竖屏 rotation 0 / override 横屏 rotation 1），也印证了这一点。
 *
 * ## 唯一可信来源：已布局窗口的几何
 *
 * 窗口尺寸是 WMS 在**当前**配置下真布局出来的，进程冻不掉它。overlay 窗口用
 * MATCH_PARENT 时拿到的就是当前显示几何。由 `CropView.onSizeChanged` 上报
 * （[reportLaidOutSize]），此处缓存供截图管线等无窗口方使用。
 *
 * 尚未上报时退回 Display API 的值（首帧 / 窗口未建立），并记 [isReliable] = false，
 * 调用方据此知道自己拿到的是「可能被冻结」的猜测值。
 */
object DisplaySize {

    private const val TAG = "DisplaySize"

    /** 已布局窗口上报的几何；0 表示尚未上报。 */
    @Volatile
    private var laidOut: Point = Point(0, 0)

    /** 上报的窗口是否铺满整个 display（由上报方声明）。只有它才能直接当屏幕尺寸用。 */
    @Volatile
    private var laidOutIsFullDisplay = false

    /** 上一次取值是否来自「铺满 display 的窗口」（true = WMS 真值）。 */
    @Volatile
    var isReliable: Boolean = false
        private set

    /**
     * 由持有窗口的一方上报（当前是 `CropView.onSizeChanged`）。
     *
     * ⚠️ [fullDisplay] 必须由上报方如实声明「这个窗口是否铺满了整个 display」。
     * 窗口**不等于**屏幕：系统可能因刘海/手势条/状态栏把窗口缩小（实测本机竖屏
     * display 1220x2712 而框选窗口只有 1220x2660）。把这种窗口的尺寸当屏幕尺寸用，
     * 会让 VirtualDisplay 与全屏浮层都按 2660 建 —— 帧变成「整屏缩放进 2660」，
     * 再摆到 2712 高的屏幕上 → 纵向 2% 压缩 + 整体偏上，正是「回退全屏后位置错误」的来源。
     */
    fun reportLaidOutSize(w: Int, h: Int, fullDisplay: Boolean) {
        if (w <= 0 || h <= 0) return
        val old = laidOut
        val changed = old.x != w || old.y != h || laidOutIsFullDisplay != fullDisplay
        if (!changed) return
        laidOut = Point(w, h)
        laidOutIsFullDisplay = fullDisplay
        LogCollector.d(TAG, "已布局窗口几何更新: ${old.x}x${old.y} → ${w}x$h（铺满display=$fullDisplay）")
    }

    /**
     * 当前屏幕几何（物理像素，含系统栏区域）。
     *
     * 优先用**铺满 display 的窗口**几何（WMS 在当前配置下真布局出来的，进程冻不掉）。
     * 窗口没铺满时不能直接采信 —— 真实屏幕尺寸**必然 ≥ 任何窗口**，
     * 故取「窗口几何」与「Display API 读数」中较大者（按面积）。
     */
    fun size(ctx: Context): Point {
        val laid = laidOut
        val legacy = legacySize(ctx)
        if (laid.x > 0 && laid.y > 0) {
            if (laidOutIsFullDisplay) {
                isReliable = true
                return laid
            }
            // 窗口没铺满：取较大的那个（真实屏幕不可能比窗口小）
            val w = maxOf(laid.x, legacy.x)
            val h = maxOf(laid.y, legacy.y)
            isReliable = (w == laid.x && h == laid.y)
            return Point(w, h)
        }
        isReliable = false
        return legacy
    }

    /** 强制读 Display API（诊断用，绕过已布局读数）。 */
    fun legacySize(ctx: Context): Point {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return Point(0, 0)

        // 优先 currentWindowMetrics：走 Binder 到 WMS，比 defaultDisplay 更可能反映当前状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val b = wm.currentWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) return Point(b.width(), b.height())
            } catch (_: Throwable) {
                // 某些 ROM 在 Service context 上会抛，退回 getRealSize
            }
        }

        val p = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return p
    }
}
