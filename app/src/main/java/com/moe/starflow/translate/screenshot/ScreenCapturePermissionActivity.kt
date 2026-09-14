package com.moe.starflow.translate.screenshot
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import com.moe.starflow.R

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.app.Activity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.moe.starflow.manga.MangaFloatingService
import com.moe.starflow.translate.BroadcastAction
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.ServiceUtils
import com.moe.starflow.utils.UiUtils

/**
 * 截图权限请求 Activity
 * 透明 Activity，弹出系统授权弹窗后立即关闭
 */
class ScreenCapturePermissionActivity : Activity() {
    companion object {
        private const val TAG = "ScreenCapturePermission"
        private const val REQUEST_CODE = 222
        const val EXTRA_SERVICE_TYPE = "service_type"  // "game" or "manga"

        /**
         * 启动权限请求
         * @param context 上下文
         * @param serviceType 要启动的服务类型: "game" 或 "manga"
         */
        fun start(context: Context, serviceType: String) {
            val intent = Intent(context, ScreenCapturePermissionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(EXTRA_SERVICE_TYPE, serviceType)
            context.startActivity(intent)
        }
    }

    private var serviceType: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceType = intent.getStringExtra(EXTRA_SERVICE_TYPE) ?: ""

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                MediaProjectionIntentHolder.set(data)
                LogCollector.d(TAG, "Permission granted, target: $serviceType")
                // 通知已运行的服务初始化 Shooter
                if (ServiceUtils.isServiceRunning(this, MangaFloatingService::class.java)) {
                    startService(Intent(this, MangaFloatingService::class.java).apply {
                        putExtra("PERMISSION_RESULT", true)
                    })
                }
                if (ServiceUtils.isServiceRunning(this, FloatingBallService::class.java)) {
                    startService(Intent(this, FloatingBallService::class.java).apply {
                        putExtra("PERMISSION_RESULT", true)
                    })
                }
                // 启动用户请求的服务
                when (serviceType) {
                    "game" -> {
                        if (!ServiceUtils.isServiceRunning(this, FloatingBallService::class.java)) {
                            startService(Intent(this, FloatingBallService::class.java))
                            UiUtils.showToast(this, getString(R.string.toast_game_started), isShort = true)
                            LogCollector.d(TAG, "FloatingBallService started after permission grant")
                        }
                    }
                    "manga" -> {
                        if (!ServiceUtils.isServiceRunning(this, MangaFloatingService::class.java)) {
                            androidx.core.content.ContextCompat.startForegroundService(
                                this, Intent(this, MangaFloatingService::class.java)
                            )
                            UiUtils.showToast(this, getString(R.string.manga_started), isShort = true)
                            LogCollector.d(TAG, "MangaFloatingService started after permission grant")
                        }
                    }
                }
            } else {
                LogCollector.w(TAG, "Permission denied for: $serviceType")
                UiUtils.showToast(this, getString(R.string.toast_need_capture_permission), isShort = true)
                // 发送停止广播确保按钮状态正确
                when (serviceType) {
                    "game" -> {
                        LocalBroadcastManager.getInstance(this)
                            .sendBroadcast(Intent(BroadcastAction.ACTION_FLOATING_BALL_SERVICE_STOPPED))
                    }
                    "manga" -> {
                        LocalBroadcastManager.getInstance(this)
                            .sendBroadcast(Intent(BroadcastAction.ACTION_MANGA_SERVICE_STOPPED))
                    }
                }
            }
        }

        finish()
    }
}
