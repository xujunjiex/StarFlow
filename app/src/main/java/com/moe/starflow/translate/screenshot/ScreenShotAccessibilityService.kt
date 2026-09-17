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
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.moe.starflow.translate.TranslationStatusOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ScreenShotAccessibilityService: AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusOverlay: TranslationStatusOverlay
    private val eventHandler = AccessibilityEventHandler {
        ScreenshotManager.notifyEventTrigger(it)
    }.also { ScreenshotManager.registerEventHandler(it) }

    // 当用户点击悬浮球时调用此方法
    fun takeScreenshot(mRectF: RectF?, offset: Point) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  // Android 11及以上
            takeScreenshotImpl(mRectF, offset)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotImpl(mRectF: RectF?, offset: Point) {
        try {
            takeScreenshot(
                DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val fullBitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, true)

                            Log.d("ASSOFFSET", "x:"+offset.x+"  y:"+offset.y)
                            // cropBitmap 裁不出有效区域时返回 null（不再退化成返回 fullBitmap 本身）
                            val croppedBitmap = if (mRectF != null && fullBitmap != null) {
                                ScreenshotManager.cropBitmap(fullBitmap, mRectF, offset)
                            } else {
                                null
                            }

                            //使用sharedflow，发送截图完成信号以及全屏+裁剪bitmap
                            fullBitmap?.let { fb ->
                                ScreenshotManager.emitScreenshot(ScreenshotData(fb, croppedBitmap))
                            }

                        } catch (e: Exception) {
                            Log.e("Screenshot", "Error processing screenshot", e)
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val errorText = when (errorCode){
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "截图失败：内部错误"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "截图失败：无无障碍权限"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图频率过高，已自动降速"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "截图失败：无效显示器"
                            else -> "截图失败：未知错误 $errorCode"
                        }
                        Log.e("Screenshot", "onFailure: errorCode=$errorCode, $errorText")
                        showToast(errorText)
                    }
                }
            )
        } catch (e: Exception) {
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
        Log.d("CONNECT", "Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { eventHandler.onEvent(it) }
    }

    override fun onInterrupt() {
        // 处理中断
    }

    override fun onDestroy() {
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