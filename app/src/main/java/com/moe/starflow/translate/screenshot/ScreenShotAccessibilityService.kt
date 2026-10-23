/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.starflow.translate.screenshot
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import android.view.Display.DEFAULT_DISPLAY
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.moe.starflow.translate.TranslationStatusOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ScreenShotAccessibilityService: AccessibilityService() {
    companion object {
        private const val TAG = "AccessibilityScreenshot"
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusOverlay: TranslationStatusOverlay
    private val eventHandler = AccessibilityEventHandler {
        ScreenshotManager.notifyEventTrigger(it)
    }.also { ScreenshotManager.registerEventHandler(it) }

    // 当用户点击悬浮球时调用此方法
    fun takeScreenshot(mRectF: RectF?, offset: Point, target: ScreenshotTarget = ScreenshotTarget.MANGA, requestId: Long = 0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  // Android 11及以上
            takeScreenshotImpl(mRectF, offset, target, requestId)
        } else {
            LogCollector.w(TAG, "Screenshot requires API 30; sdk=${Build.VERSION.SDK_INT}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotImpl(mRectF: RectF?, offset: Point, target: ScreenshotTarget, requestId: Long) {
        val requestedGeometry = com.moe.starflow.utils.DisplaySize.geometry(this)
        val requestedCrop = mRectF?.let { RectF(it) }
        val requestedOffset = Point(offset)
        try {
            LogCollector.d(TAG, "request: display=$DEFAULT_DISPLAY crop=$mRectF offset=$offset")
            takeScreenshot(
                DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var fullBitmap: Bitmap? = null
                        var croppedBitmap: Bitmap? = null
                        var delivered = false
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            ) ?: error("Cannot wrap screenshot hardware buffer")
                            val full = try {
                                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                    ?: error("Cannot copy screenshot bitmap")
                            } finally {
                                hardwareBitmap.recycle()
                            }
                            fullBitmap = full
                            // cropBitmap 裁不出有效区域时返回 null（不再退化成返回 fullBitmap 本身）
                            val data = if (requestedGeometry == com.moe.starflow.utils.DisplaySize.geometry(this@ScreenShotAccessibilityService)) {
                                ScreenshotManager.createData(CapturedScreen(full, requestedGeometry), requestedCrop, requestedOffset, requestId)
                            } else ScreenshotData(full, null, requestId = requestId)
                            val cropped = data.croppedBitmap
                            croppedBitmap = cropped

                            //使用sharedflow，发送截图完成信号以及全屏+裁剪bitmap
                            LogCollector.d(TAG, "success: full=${full.width}x${full.height} cropped=${cropped?.let { "${it.width}x${it.height}" }}")
                            ScreenshotManager.emitScreenshot(data, target)
                            delivered = true
                        } catch (e: OutOfMemoryError) {
                            LogCollector.e(TAG, "Screenshot bitmap allocation failed", e)
                            showToast(getString(R.string.error_screenshot_memory))
                        } catch (e: Exception) {
                            LogCollector.e(TAG, "Error processing screenshot", e)
                            showToast(getString(R.string.toast_screenshot_failed_format, e.message.orEmpty()))
                        } finally {
                            if (!delivered) {
                                croppedBitmap?.takeIf { it !== fullBitmap && !it.isRecycled }?.recycle()
                                fullBitmap?.takeIf { !it.isRecycled }?.recycle()
                            }
                            screenshot.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val errorText = when (errorCode){
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> getString(R.string.error_screenshot_internal)
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> getString(R.string.error_screenshot_access)
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> getString(R.string.error_screenshot_interval)
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> getString(R.string.error_screenshot_display)
                            else -> getString(R.string.error_screenshot_code, errorCode)
                        }
                        LogCollector.e(TAG, "onFailure: errorCode=$errorCode, $errorText", null)
                        showToast(errorText)
                    }
                }
            )
        } catch (e: Exception) {
            LogCollector.e(TAG, "Screenshot request failed", e)
            showToast(getString(com.moe.starflow.R.string.toast_screenshot_failed_format, e.toString()))
        }
    }


    fun showToast(message: String) {
        statusOverlay.show(message)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        statusOverlay = TranslationStatusOverlay.getInstance(this)
        AccessibilityServiceManager.setService(this)
        LogCollector.d(TAG, "Service connected: sdk=${Build.VERSION.SDK_INT} capabilities=${serviceInfo?.capabilities}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { eventHandler.onEvent(it) }
    }

    override fun onInterrupt() {
        LogCollector.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        LogCollector.d(TAG, "Service disconnected")
        super.onDestroy()
        // 取消服务
        AccessibilityServiceManager.setService(null)
        // 释放悬浮提示条
        if (::statusOverlay.isInitialized) {
            statusOverlay.release()
        }
        // 取消事件处理
        eventHandler.cancel()
        // 取消所有协程
        serviceScope.cancel()
    }
}
