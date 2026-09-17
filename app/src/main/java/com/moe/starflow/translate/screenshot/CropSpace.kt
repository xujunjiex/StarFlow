package com.moe.starflow.translate.screenshot

import com.moe.starflow.manga.render.Box
import kotlin.math.abs

/**
 * 框选窗口的几何：自身尺寸 + 它在**显示**上的原点。
 *
 * ⚠️ 用 int 而不是 `Box`（`manga/render/Box.kt`）：那是排版内核的 Float 区域表示，
 * 与截图坐标是两回事；同名类型混用正是本目录此前反复出错的原因之一。
 */
data class WinGeom(val width: Int, val height: Int, val originX: Int, val originY: Int)

/** 截屏帧的几何。 */
data class FrameGeom(val width: Int, val height: Int)

/**
 * 框选 → 裁剪的坐标换算（**纯函数**）。
 *
 * ## 为什么单独抽出来
 *
 * 此前这段换算散在三个裁剪点里（`MediaProjectionProvider` / `MangaFloatingService` /
 * `ScreenShotAccessibilityService`），各自手写 `crop.left + offset.x`，
 * 没有任何一处能测、也没有任何机制保证三处一致。本目录因此在「横屏偏多少」上反复返工。
 *
 * ## 恒等分支是硬约束
 *
 * 帧尺寸与窗口尺寸相同**且**窗口原点为 0 时，**原样返回**、不做任何取整或换算 ——
 * 这在数学上等价于旧行为，因此「本来正常的设备」（竖屏、无刘海、帧 1:1）**零回归**。
 * `CropSpaceTest` 锁死这条。
 *
 * ## 帧语义尚未定标（重要）
 *
 * VirtualDisplay 输出到底是「整屏等比缩放 + 居中黑边」还是「1:1 取景窗口」，
 * 在本机**尚未实测确认**（只拿到过另一台机器上的一份像素测量，且那份结论后来被更正过）。
 * 两种情况下正确的换算公式不同，猜错就是整体偏移。
 *
 * 因此本函数目前**只实现恒等分支**；尺寸不一致时原样返回并标记 [CropResolution.calibrated]
 * = false，由调用方记日志。**在拿到实测数据之前不得补比例公式。**
 */
object CropSpace {

    /**
     * 换算结果。
     *
     * @param rect        帧坐标系下的裁剪矩形（int，紧贴 `Bitmap.createBitmap` 的入参）
     * @param identical   是否走了恒等分支（未做任何换算）
     * @param calibrated  该结果是否有依据。false = 帧与窗口不同几何但公式未定标，结果是「照旧」
     */
    data class CropResolution(val rect: IntRect, val identical: Boolean, val calibrated: Boolean)

    /** 裁剪矩形（int）。刻意不复用排版内核的 `Box`（Float，另一种语义）。 */
    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        fun isEmpty(): Boolean = width <= 0 || height <= 0
    }

    /** `android.graphics.RectF` → [IntRect]（调用方传 Float，这里只取整，不做换算）。 */
    fun fromRectF(left: Float, top: Float, right: Float, bottom: Float): IntRect =
        IntRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

    /** 排版内核的 [Box] 是另一种语义，此重载只为显式桥接（勿在截图路径反过来用 Box 表示裁剪框）。 */
    fun fromBox(box: Box): IntRect =
        IntRect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())

    /**
     * 把**视图坐标系**的框选矩形换算成**帧坐标系**的裁剪矩形。
     *
     * 视图坐标 = 相对框选窗口左上角；框选窗口在显示上的原点由 [win].originX/Y 给出。
     * 高度/宽度的换算需要知道帧语义，见类注释 —— 未定标时保持原样。
     */
    fun resolveCropRect(crop: IntRect, win: WinGeom, frame: FrameGeom): CropResolution {
        // 退化输入：维持原样（下游会按「裁不出东西」处理并给出明确提示）
        if (win.width <= 0 || win.height <= 0 || frame.width <= 0 || frame.height <= 0) {
            return CropResolution(crop, identical = false, calibrated = false)
        }
        // 帧与窗口同尺寸 ⇒ 帧覆盖的就是窗口那块像素区 ⇒ 两者同为**窗口相对**坐标，
        // 裁剪矩形直接可用。⚠️ 此时窗口原点**无关紧要**：frame(0,0) 就是 window(0,0)。
        // 把 origin 塞进这个判据是错的 —— 会在「同尺寸且窗口不在显示原点」时误判为未定标。
        if (frame.width == win.width && frame.height == win.height) {
            return CropResolution(crop, identical = true, calibrated = true)
        }
        // 帧与窗口几何不同 —— 需要知道「整屏缩放+黑边」还是「1:1 取景」才能定标。
        // 未实测前保持原样，并让调用方记日志（calibrated=false）。
        return CropResolution(crop, identical = false, calibrated = false)
    }

    /** 诊断用：把三元组拼成一行，便于定标（frame / window / origin / 是否恒等）。 */
    fun describe(crop: IntRect, win: WinGeom, frame: FrameGeom, res: CropResolution): String =
        "frame=${frame.width}x${frame.height} window=${win.width}x${win.height} " +
            "origin=(${win.originX},${win.originY}) " +
            "crop=[${crop.left},${crop.top}][${crop.right},${crop.bottom}] " +
            "→ [${res.rect.left},${res.rect.top}][${res.rect.right},${res.rect.bottom}] " +
            "identity=${res.identical} calibrated=${res.calibrated}"

    /** 两个矩形是否在容差内相同（测试与诊断用）。 */
    fun nearlyEquals(a: IntRect, b: IntRect, tolerance: Int = 0): Boolean =
        abs(a.left - b.left) <= tolerance && abs(a.top - b.top) <= tolerance &&
            abs(a.right - b.right) <= tolerance && abs(a.bottom - b.bottom) <= tolerance
}
