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

/**
 * AccessibilityService 截图提供者
 * 包装现有的 AccessibilityServiceManager
 */
class AccessibilityProvider(private val target: ScreenshotTarget = ScreenshotTarget.MANGA) : ScreenshotProvider {
    companion object {
        private const val TAG = "AccessibilityProvider"
    }

    override fun isAvailable(): Boolean {
        return AccessibilityServiceManager.getService() != null
    }

    override fun needsPermission(): Boolean {
        return false
    }

    override suspend fun takeScreenshot(cropRect: RectF?, offset: Point, requestId: Long): Bitmap? {
        // AccessibilityService 的截图是异步的，通过 ScreenshotManager.screenshotFlow 返回
        // 这里直接调用，结果会在 flow 中收到
        AccessibilityServiceManager.takeScreenshot(cropRect, offset, target, requestId)
        return null // 实际结果通过 flow 返回
    }

    override fun release() {
        // AccessibilityService 不需要释放
    }
}
