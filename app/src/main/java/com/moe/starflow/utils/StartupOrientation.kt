package com.moe.starflow.utils

import android.app.Activity
import android.content.pm.ActivityInfo

/** 手机保留竖屏主页；平板跟随系统，避免启动时强制进入竖屏兼容窗口。 */
object StartupOrientation {
    fun apply(activity: Activity) {
        if (activity.resources.configuration.smallestScreenWidthDp < 600) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
