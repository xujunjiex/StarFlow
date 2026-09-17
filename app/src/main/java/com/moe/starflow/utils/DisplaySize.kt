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

    /** 已布局窗口上报的真实几何；0 表示尚未上报。 */
    @Volatile
    private var laidOut: Point = Point(0, 0)

    /** 上一次取值是否来自已布局窗口（true = WMS 真值，false = Display API 猜测值）。 */
    @Volatile
    var isReliable: Boolean = false
        private set

    /** 由持有窗口的一方上报（当前是 CropView.onSizeChanged）。 */
    fun reportLaidOutSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val old = laidOut
        if (old.x == w && old.y == h) return
        laidOut = Point(w, h)
        LogCollector.d(TAG, "已布局窗口几何更新: ${old.x}x${old.y} → ${w}x$h")
    }

    /**
     * 当前屏幕几何（物理像素，含系统栏区域）。
     * 有已布局窗口的读数就用它；否则退回 Display API。
     */
    fun size(ctx: Context): Point {
        val laid = laidOut
        if (laid.x > 0 && laid.y > 0) {
            isReliable = true
            return laid
        }
        isReliable = false
        return legacySize(ctx)
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
