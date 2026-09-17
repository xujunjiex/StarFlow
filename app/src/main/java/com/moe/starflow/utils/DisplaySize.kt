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

    /** 上一次取值是否来自「已布局窗口 + Display 读数一致」（两者相符才可信）。 */
    @Volatile
    var isReliable: Boolean = false
        private set

    /**
     * 由持有窗口的一方上报（当前是 `CropView.onSizeChanged`）。
     *
     * ⚠️ **窗口尺寸不等于屏幕尺寸**，即使窗口原点为 (0,0)。实测（竖屏）：
     * 真实屏幕 1220x2712，而 `MATCH_PARENT` 的 overlay 窗口只有 **1220x2660** ——
     * 系统会让窗口避开底部手势条/导航栏，但原点仍是 0。
     *
     * 把这种窗口尺寸当屏幕尺寸用，会让 VirtualDisplay 与全屏浮层都按 2660 建：
     * 帧成了「2712 高的屏幕缩放进 2660」，再摆到 2712 高的屏幕上 →
     * **整幅译文纵向压缩并偏上**，正是「回退全屏后位置错误」的来源。
     *
     * 因此这里**不再由上报方声明"是否铺满"**（窗口原点为 0 判不出铺满，曾据此误判），
     * 改为在 [size] 里与 Display 读数取较大者 —— 真实显示必然 ≥ 任何窗口。
     */
    /**
     * 由 `onConfigurationChanged` 上报**当前朝向**。
     *
     * ⚠️ 这是转屏后进程里**唯一新鲜**的方向信号：`newConfig.orientation` 随配置变更更新
     * （`resources.configuration` 那个是冻结值），而 `Display` 系列与「已布局窗口」
     * （框选窗移除后不再上报）都停在旧方向。
     *
     * 不传像素只传朝向：像素值本就能从任一来源拿到（只是可能方向旧），
     * 而**方向**才是它们都答不出的那一个问题。取值时用朝向校正宽高配对即可。
     */
    fun reportOrientation(isLandscape: Boolean) {
        if (landscape == isLandscape) return
        landscape = isLandscape
        LogCollector.d(TAG, "朝向上报: ${if (isLandscape) "横屏" else "竖屏"}")
    }

    /** null = 未知（尚未收到配置变更） */
    @Volatile
    private var landscape: Boolean? = null

    fun reportLaidOutSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val old = laidOut
        if (old.x == w && old.y == h) return
        laidOut = Point(w, h)
        LogCollector.d(TAG, "已布局窗口几何更新: ${old.x}x${old.y} → ${w}x$h")
    }

    /**
     * 当前**显示**几何（物理像素，含系统栏区域）。
     *
     * ⚠️ 两个来源都可能坏，且**不能逐轴取最大值**：
     * - 已布局窗口：能反映当前方向（进程冻不掉），但可能被系统缩小（避开手势条/导航栏）
     * - Display API：可能是冻结的旧方向，但不会凭空变小
     *
     * 逐轴取 max 在两者**方向不一致**时会拼出一个既不竖也不横的尺寸，
     * 而且拿不到新方向（转屏后 cropRect 已清空、CropView 已移除 → 窗口读数陈旧，
     * Display API 又冻结在旧方向，两者一起指向旧方向 → max 仍是旧方向）。
     * 实测后果：设备已横屏而帧仍是竖屏 1220x2712 → OCR 跑在转了 90° 的图上 → 识别 0 结果。
     *
     * 因此按**平面尺寸**（长边×短边）二选一：取面积较大的那个来源整组采用，
     * 保证宽高配对比值来自同一来源、方向自洽。
     */
    fun size(ctx: Context): Point {
        val laid = laidOut
        val legacy = legacySize(ctx)

        // 选像素来源：优先「已布局窗口」（当前方向、但可能被系统缩小），
        // 其次 Display 读数。两者都可能方向陈旧 —— 方向由 orientationOf() 统一校正。
        val base = if (laid.x > 0 && laid.y > 0) laid else legacy
        if (base.x <= 0 || base.y <= 0) {
            isReliable = false
            return base
        }
        val oriented = orientationOf(base)
        isReliable = (oriented === base)
        return Point(oriented.x, oriented.y)
    }

    /**
     * 按已知朝向校正宽高配对。
     *
     * 像素值可能来自方向陈旧的来源（转屏后 Display 冻结、窗口读数停在旧方向），
     * 但**长边与短边的长度**是对的 —— 只需按朝向重新配对，即可得到当前方向的几何。
     * 朝向未知时原样返回。
     */
    private fun orientationOf(p: Point): Point {
        val want = landscape ?: return p
        val isLandscape = p.x > p.y
        if (isLandscape == want) return p
        return Point(maxOf(p.x, p.y), minOf(p.x, p.y))
    }

    /**
     * 日志用：把一组像素尺寸描述成「横屏/竖屏 WxH」。
     * 各处几何日志统一用它，排查时一眼能看出朝向（比裸的 `1220x2712` 强）。
     */
    fun describe(w: Int, h: Int): String =
        "${if (w > h) "横屏" else "竖屏"} ${w}x$h"

    /** 当前屏幕几何的可读描述（含是否可信）。 */
    fun describeCurrent(ctx: Context): String {
        val p = size(ctx)
        return describe(p.x, p.y) + "可靠=$isReliable"
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
