package com.moe.starflow.translate.screenshot
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import com.moe.starflow.utils.LogCollector

/**
 * MediaProjection 截图提供者。
 *
 * 坐标对齐由 Shooter.ensureBufferOrientation() 保证：
 * 屏幕旋转后自动重建 ImageReader + resize VirtualDisplay，buffer 始终
 * 与当前屏幕方向一致，无需额外坐标变换。
 */
class MediaProjectionProvider(private val context: Context) : ScreenshotProvider {
    companion object {
        private const val TAG = "MediaProjectionProvider"
    }

    private val shooter = Shooter(context)
    private var isInitialized = false
    val frameSeq: Long get() = shooter.frameSeq
    val lastFrameSeqChangeTime: Long get() = shooter.lastFrameSeqChangeTime

    override fun isAvailable(): Boolean = MediaProjectionIntentHolder.intent != null
    override fun needsPermission(): Boolean = true

    fun ensureInitialized(): Boolean {
        if (isInitialized && shooter.ready) return true
        val intent = MediaProjectionIntentHolder.intent ?: run {
            LogCollector.w(TAG, "No MediaProjection intent available")
            return false
        }
        LogCollector.d(TAG, "Initializing Shooter with stored intent")
        isInitialized = shooter.init(intent)
        if (!isInitialized) {
            LogCollector.w(TAG, "Shooter init failed, clearing stored intent")
            MediaProjectionIntentHolder.clear()
        }
        LogCollector.d(TAG, "Shooter init result: $isInitialized")
        return isInitialized
    }

    override suspend fun takeScreenshot(cropRect: RectF?, offset: Point): Bitmap? {
        if (!ensureInitialized()) {
            LogCollector.w(TAG, "Not initialized, cannot take screenshot")
            return null
        }

        LogCollector.d(TAG, "Taking screenshot, cropRect=$cropRect")
        val fullBitmap = shooter.shot() ?: run {
            LogCollector.w(TAG, "Shooter returned null bitmap")
            return null
        }
        LogCollector.d(TAG, "Full screenshot: ${fullBitmap.width}x${fullBitmap.height}")

        // ⚠️ 裁不出有效区域时 cropBitmap 返回 null（不再退化成返回 fullBitmap 本身）。
        // 全屏图仍要发出去 —— 宁可按全屏翻译，也不要发出一个「看似裁剪过、实为原图」的对象
        // 让下游的 full/cropped 前提失效。
        return if (cropRect != null) {
            val cropped = ScreenshotManager.cropBitmap(fullBitmap, cropRect, offset)
            if (cropped != null) {
                LogCollector.d(TAG, "Cropped screenshot: ${cropped.width}x${cropped.height}")
                cropped
            } else {
                LogCollector.w(TAG, "裁剪区域无效，改为返回全屏图")
                fullBitmap
            }
        } else {
            fullBitmap
        }
    }

    override fun release() {
        shooter.release()
        isInitialized = false
    }
}
