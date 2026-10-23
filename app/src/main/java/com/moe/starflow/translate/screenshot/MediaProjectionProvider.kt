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

    override suspend fun takeScreenshot(cropRect: RectF?, offset: Point, requestId: Long): Bitmap? {
        val crop = cropRect?.let { RectF(it) }
        val frame = captureFrame() ?: return null
        val data = ScreenshotManager.createData(frame, crop, Point(offset), requestId)
        if (data.mapping == null) {
            data.recycle()
            return null
        }
        return data.croppedBitmap?.also { data.fullBitmap.recycle() } ?: data.fullBitmap
    }

    suspend fun captureFrame(): CapturedScreen? = if (ensureInitialized()) shooter.captureFrame() else null

    override fun release() {
        shooter.release()
        isInitialized = false
    }
}
