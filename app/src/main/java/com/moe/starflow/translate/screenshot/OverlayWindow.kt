package com.moe.starflow.translate.screenshot

import android.os.Build
import android.view.WindowManager

/**
 * overlay 窗口参数的统一入口。
 *
 * 存在的意义：窗口的坐标口径（(0,0) 锚在哪里）由 flags + 挖孔模式共同决定，
 * 此前这些参数散落在两个服务的多处 `LayoutParams().apply { ... }` 里各写一份，
 * 一旦某处漏了某个 flag，就产生**第二套坐标系**——而两套坐标系之间的差值随
 * 设备/朝向变化，只能用 offset 去补，补错方向就换个符号再试（历史横屏偏移反复的根因）。
 *
 * 口径收在这里，由构造保证一致，不再依赖每处手写相同。
 */
object OverlayWindow {

    /** 截图和 OCR 坐标是物理像素，不允许 BitmapDrawable 按设备密度隐式缩放。 */
    fun setBitmapInPixels(view: android.widget.ImageView, bitmap: android.graphics.Bitmap) {
        bitmap.density = android.graphics.Bitmap.DENSITY_NONE
        view.scaleType = android.widget.ImageView.ScaleType.CENTER
        view.setImageBitmap(bitmap)
    }

    private var displayContext: android.content.Context? = null

    /** 不继承 Activity 的竖屏/分屏兼容配置；一个 display 共用一个窗口 Context。 */
    @Synchronized
    fun context(context: android.content.Context): android.content.Context {
        displayContext?.let { return it }
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return app
        val dm = app.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return app
        return app.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            .also { displayContext = it }
    }

    /** 坐标映射窗口均以整块 display 为布局空间；框选窗口再指定自己的矩形。 */
    fun fullDisplayParams(): WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = android.graphics.PixelFormat.RGBA_8888
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        // display 像素的 x 总是从左计数，不能随系统 RTL 把 START 解释成右边。
        gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        applyFullDisplayCutout(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
    }

    /**
     * 让窗口覆盖**整个 display**（含刘海区），使「窗口坐标」与「显示坐标」重合。
     *
     * ## 为什么必须这么做
     *
     * 默认挖孔模式下窗口会**避开刘海**：横屏实测 display 2712x1220，而框选窗口只有
     * 2574x1220、原点落在 (138,0)。于是同一次框选产生两套坐标 —— `cropRect` 是
     * **窗口局部**坐标，结果浮层的 x/y 却按**显示原点**解释，相差整整一个刘海宽度，
     * 译文整体偏移。竖屏刘海在顶部、窗口横向铺满，两套坐标恰好重合，所以只坏横屏。
     *
     * 让窗口覆盖整屏后，两套坐标系重合，`crop.left` 可直接使用，**不需要任何补偿**。
     * （反过来的做法——保留坐标系分裂再加 offset——永远在追一个随设备变化的差值。）
     *
     * ## API 分级（重要）
     *
     * `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 是 **API 30** 新增，在 API < 30 上
     * **引用即抛 `IllegalArgumentException`**。本项目 minSdk = 29，因此必须分级：
     * - API ≥ 30：`ALWAYS`（无条件覆盖）
     * - API 29：`SHORT_EDGES`（API 28+）。物理刘海/挖孔都在**短边**上，
     *   该模式允许内容进入短边刘海区，横竖屏都成立，是 29 上能拿到的最接近语义。
     */
    fun applyFullDisplayCutout(lp: WindowManager.LayoutParams) {
        lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}
