package com.moe.starflow.translate.screenshot
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 截图数据
 */
data class ScreenshotData(val fullBitmap: Bitmap, val croppedBitmap: Bitmap?, val mapping: ScreenshotMapping? = null, val requestId: Long = 0) {
    fun belongsToRequest(requestId: Long): Boolean = requestId > 0 && this.requestId == requestId

    fun isCurrent(display: DisplayGeometry): Boolean {
        val map = mapping ?: return false
        val ocr = croppedBitmap ?: fullBitmap
        return map.display == display && fullBitmap.width == display.width && fullBitmap.height == display.height &&
            map.matchesBitmap(ocr.width, ocr.height)
    }

    fun recycle() {
        if (croppedBitmap !== fullBitmap) croppedBitmap?.takeUnless { it.isRecycled }?.recycle()
        if (!fullBitmap.isRecycled) fullBitmap.recycle()
    }
}

enum class ScreenshotTarget { GAME, MANGA }

/**
 * 截图管理器 - 解耦截图生产者和消费者
 * 从 ScreenShotAccessibilityService 中提取，支持多种截图方式
 */
object ScreenshotManager {

    private val requestIds = java.util.concurrent.atomic.AtomicLong()
    fun newRequestId(): Long = requestIds.incrementAndGet()

    private const val TAG = "ScreenshotManager"

    private val _screenshotFlow = MutableSharedFlow<ScreenshotData>(extraBufferCapacity = 1)
    val screenshotFlow = _screenshotFlow.asSharedFlow()
    private val gameScreenshots = MutableSharedFlow<ScreenshotData>(extraBufferCapacity = 1)

    fun screenshotsFor(target: ScreenshotTarget) = when (target) {
        ScreenshotTarget.MANGA -> screenshotFlow
        ScreenshotTarget.GAME -> gameScreenshots.asSharedFlow()
    }

    private val _eventTriggerFlow = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val eventTriggerFlow = _eventTriggerFlow.asSharedFlow()

    /** 框选区域（用于事件过滤） */
    var cropRect: RectF? = null

    /** 事件模式设置 */
    private var eventModeHandler: AccessibilityEventHandler? = null
    fun setEventMode(mode: AccessibilityEventHandler.Mode) {
        eventModeHandler?.setMode(mode)
    }
    fun registerEventHandler(handler: AccessibilityEventHandler) {
        eventModeHandler = handler
    }

    /**
     * 发送截图数据。
     *
     * ⚠️ `tryEmit` 在**没有收集者或缓冲已满**时会静默返回 false，此前直接丢弃 ——
     * 而 `ScreenshotData` 里是两张 `Bitmap`，丢弃即泄漏（自动翻译下检测循环持续 emit，
     * 属无界泄漏）。容量 1 的缓冲在翻译期间（`processMangaScreenshot` 可跑数秒）必然打满。
     * 因此**发出失败时主动回收**，不让它们等 GC。
     *
     * @return true 表示已投递；false 表示被丢弃（此时两张 bitmap 已被回收，调用方不得再使用）
     */
    fun emitScreenshot(data: ScreenshotData, target: ScreenshotTarget = ScreenshotTarget.MANGA): Boolean {
        val stream = if (target == ScreenshotTarget.GAME) gameScreenshots else _screenshotFlow
        val accepted = stream.subscriptionCount.value > 0 && stream.tryEmit(data)
        if (!accepted) {
            LogCollector.w(
                TAG,
                "emitScreenshot 被丢弃（无收集者或缓冲满），回收 " +
                    "full=${data.fullBitmap.width}x${data.fullBitmap.height}" +
                    (data.croppedBitmap?.let { ", cropped=${it.width}x${it.height}" } ?: "")
            )
            // 两者可能是同一实例（见 cropBitmap 的说明），避免二次回收
            val full = data.fullBitmap
            val cropped = data.croppedBitmap
            if (!full.isRecycled) full.recycle()
            if (cropped != null && cropped !== full && !cropped.isRecycled) cropped.recycle()
        }
        return accepted
    }

    /**
     * 通知无障碍事件触发（经 EventHandler 去抖后调用）
     */
    fun notifyEventTrigger(eventType: String) {
        _eventTriggerFlow.tryEmit(eventType)
    }

    /**
     * 裁剪 Bitmap。
     *
     * @return 裁剪结果；**裁不出有效区域时返回 null**（不再返回 `source` 本身）。
     *
     * ⚠️ 旧实现在退化分支（w<=0 || h<=0）**返回 `source` 本身**，于是
     * `croppedBitmap === fullBitmap`。而两个消费端都写着
     * `if (data.croppedBitmap != null) data.fullBitmap.recycle()` ——
     * 它们的前提是「两者必然不同」，这个前提在退化分支被打破，会误回收/二次回收。
     * 返回 null 让调用方走「没裁出东西」的显式分支，前提重新成立。
     */
    fun cropBitmap(source: Bitmap, cropRect: RectF, offset: Point): Bitmap? {
        val bounds = ScreenshotMapping.exactCropBounds(source.width, source.height, cropRect, offset)
        if (bounds == null) {
            LogCollector.w(TAG, "cropBitmap: 拒绝越界/无效区域，源=${source.width}x${source.height} crop=$cropRect offset=$offset")
            return null
        }
        val cropped = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width(), bounds.height())
        // Android 对整图裁剪可能返回原实例，消费端分别持有并回收 full/cropped。
        return if (cropped === source) source.copy(source.config ?: Bitmap.Config.ARGB_8888, false) else cropped
    }

    /** 无效映射保留为 null 交收集端丢弃并结束本轮请求，不能降级成另一种坐标空间。 */
    fun createData(frame: CapturedScreen, crop: RectF?, offset: Point, requestId: Long = 0): ScreenshotData {
        val mapping = ScreenshotMapping.create(frame.display, crop, offset)
        val full = frame.bitmap
        if (mapping == null || full.width != frame.display.width || full.height != frame.display.height) {
            LogCollector.w(TAG, "createData: 帧/框选几何不匹配，丢弃截图")
            return ScreenshotData(full, null, requestId = requestId)
        }
        val cropped = if (crop != null) cropBitmap(full, crop, offset) else null
        if (crop != null && cropped == null) {
            return ScreenshotData(full, null, requestId = requestId)
        }
        return ScreenshotData(full, cropped, mapping, requestId)
    }
}
