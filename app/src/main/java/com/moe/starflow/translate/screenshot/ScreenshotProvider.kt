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

/**
 * 截图提供者接口
 * 抽象截图方式，支持 MediaProjection 和 AccessibilityService
 */
interface ScreenshotProvider {
    /**
     * 检查是否可用
     */
    fun isAvailable(): Boolean

    /**
     * 是否需要请求权限（MediaProjection 需要，AccessibilityService 不需要）
     */
    fun needsPermission(): Boolean

    /**
     * 截图
     * @param cropRect 裁剪区域（null 表示全屏）
     * @param offset 裁剪偏移
     * @return Bitmap 或 null（失败时）
     */
    suspend fun takeScreenshot(cropRect: RectF?, offset: Point, requestId: Long = 0): Bitmap?

    /**
     * 释放资源
     */
    fun release()
}
