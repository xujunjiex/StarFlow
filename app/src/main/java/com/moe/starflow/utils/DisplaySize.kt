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
        // ⚠️ 窗口的宽高关系**就是**当前朝向，用它覆盖闩锁。
        // 不覆盖的话：进程内横屏过一次后闩锁永久为 true，之后竖屏下会把窗口读数
        // 1080x2400 **对调**成 2400x1080 → 每次几何比对都判「变了」→ 框选后必被清掉
        // （实测：时有时无，取决于本进程启动后有没有转过屏）。
        landscape = w > h
        val old = laidOut
        if (old.x == w && old.y == h) return
        laidOut = Point(w, h)
        LogCollector.d(TAG, "已布局窗口几何更新: ${old.x}x${old.y} → ${w}x$h")
    }

    /**
     * 当前**显示**几何（物理像素，含系统栏区域）。
     *
     * 决策规则抽在 [resolve]（纯函数，可纯 JVM 测），这里只负责拿两个来源。
     */
    fun size(ctx: Context): Point {
        val legacy = legacySize(ctx)
        val r = resolve(laidOut.x, laidOut.y, legacy.x, legacy.y, landscape)
        // 结果与 Display 读数一致时才可信（不一致说明窗口被系统缩小、由 max 补齐过）
        isReliable = (r[0] == legacy.x && r[1] == legacy.y)
        return Point(r[0], r[1])
    }

    /**
     * **纯函数**：由「已布局窗口 / Display 读数 / 已知朝向」推出显示尺寸。
     *
     * 单独抽出来的唯一目的是**可测**：`size()` 需要 Context/WindowManager，本机
     * Robolectric 取屏幕尺寸不可靠，而这条规则历史上出过两次方向相关的回归
     * （逐轴取 max 拼出畸形尺寸、闩锁把竖屏窗口对调），都只能靠直接测规则才发现。
     *
     * 规则：
     * 1. 无窗口读数 ⇒ 用 Display 读数，按已知朝向配对
     * 2. 有窗口读数 ⇒ **朝向以窗口为准**（WMS 真布局，比"最后一次回调说了什么"可信）
     * 3. 屏幕 ≥ 任何窗口 ⇒ 逐轴取大，但 Display 读数先按窗口方向配对（否则 max 会拼出畸形尺寸）
     *
     * @param laid    已布局窗口尺寸（0,0 = 尚无）
     * @param legacy  Display 读数
     * @param orientationIsLandscape 已知朝向（null = 未知）
     */
    fun resolve(
        laidW: Int, laidH: Int, legacyW: Int, legacyH: Int, orientationIsLandscape: Boolean?
    ): IntArray {
        if (laidW <= 0 || laidH <= 0) {
            return alignTo(legacyW, legacyH, orientationIsLandscape)
        }
        val laidLandscape = laidW > laidH
        val legacy = alignTo(legacyW, legacyH, laidLandscape)
        return intArrayOf(maxOf(laidW, legacy[0]), maxOf(laidH, legacy[1]))
    }

    /** 把尺寸配成指定朝向（长边与短边重新配对）；朝向未知时原样返回。 */
    private fun alignTo(w: Int, h: Int, asLandscape: Boolean?): IntArray {
        if (asLandscape == null || w <= 0 || h <= 0) return intArrayOf(w, h)
        if ((w > h) == asLandscape) return intArrayOf(w, h)
        // ⚠️ 要横屏 → (长, 短)；要竖屏 → (短, 长)。
        // 曾两种情况都返回 (max, min) —— 那对「要竖屏」是错的（结果仍是横屏形状），
        // 随后逐轴 max 会把两个方向的分量撞成相等的**正方形**（实测 1080x2400 vs 2400x1080
        // → 2400x2400），拿它去建 VirtualDisplay 帧就是废的。
        return if (asLandscape) {
            intArrayOf(maxOf(w, h), minOf(w, h))
        } else {
            intArrayOf(minOf(w, h), maxOf(w, h))
        }
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
