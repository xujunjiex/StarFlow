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
data class ScreenshotData(val fullBitmap: Bitmap, val croppedBitmap: Bitmap?)

/**
 * 截图管理器 - 解耦截图生产者和消费者
 * 从 ScreenShotAccessibilityService 中提取，支持多种截图方式
 */
object ScreenshotManager {

    private const val TAG = "ScreenshotManager"

    private val _screenshotFlow = MutableSharedFlow<ScreenshotData>(extraBufferCapacity = 1)
    val screenshotFlow = _screenshotFlow.asSharedFlow()

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
    fun emitScreenshot(data: ScreenshotData): Boolean {
        val accepted = _screenshotFlow.tryEmit(data)
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
        val x = (cropRect.left.toInt() + offset.x).coerceIn(0, source.width - 1)
        val y = (cropRect.top.toInt() + offset.y).coerceIn(0, source.height - 1)
        val w = cropRect.width().toInt().coerceAtMost(source.width - x)
        val h = cropRect.height().toInt().coerceAtMost(source.height - y)

        if (w <= 0 || h <= 0) {
            LogCollector.w(
                TAG,
                "cropBitmap: 裁剪区域无效（w=$w h=$h，源 ${source.width}x${source.height}，" +
                    "crop=$cropRect offset=$offset）→ 返回 null"
            )
            return null
        }
        return Bitmap.createBitmap(source, x, y, w, h)
    }
}
