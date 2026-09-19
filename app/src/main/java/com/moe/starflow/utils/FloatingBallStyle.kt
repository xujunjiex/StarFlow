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

package com.moe.starflow.utils

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import com.moe.starflow.R
import kotlin.math.roundToInt

/**
 * 悬浮球（游戏 / 漫画**共用**）的**大小**与**透明度**。
 *
 * 设置页「个性化设置 → 悬浮球」里的「悬浮球大小与透明度」面板写这里定义的两个 prefs；
 * 两个 Service（[com.moe.starflow.translate.FloatingBallService] /
 * [com.moe.starflow.manga.MangaFloatingService]）建球时与收到偏好变更时都调 [apply]，
 * 所以改完立刻生效、不用重启服务。
 *
 * - **大小**：整数百分比，基准是 `floatball_layout.xml` 里的 65dp
 * - **透明度**：整数百分比，作用于悬浮球**根视图**（图标 + 错误圈一起淡出）
 *
 * ⚠️ 两个模式共用同一份设置（用户要求"游戏漫画悬浮窗"一起调），没有 Icon_Game/Icon_Comic
 * 那样的分家键 —— 需要分家时再按模式加后缀键，别默默只改一边。
 */
object FloatingBallStyle {

    /** 大小百分比（int prefs）。 */
    const val KEY_SIZE = "Floating_Ball_Size"

    /** 透明度百分比（int prefs）。 */
    const val KEY_ALPHA = "Floating_Ball_Alpha"

    const val MIN_SIZE_PERCENT = 50
    const val MAX_SIZE_PERCENT = 200
    const val DEFAULT_SIZE_PERCENT = 100

    const val MIN_ALPHA_PERCENT = 10
    const val MAX_ALPHA_PERCENT = 100
    const val DEFAULT_ALPHA_PERCENT = 100

    /** 基准边长（dp）：与 `floatball_layout.xml` 的 icon / error_ring 保持一致。 */
    const val BASE_SIZE_DP = 65

    /** Service 的偏好监听用这个集合判断"是不是悬浮球外观改了"。 */
    val KEYS: Set<String> = setOf(KEY_SIZE, KEY_ALPHA)

    fun sizePercent(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_SIZE, DEFAULT_SIZE_PERCENT).coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)

    fun alphaPercent(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_ALPHA, DEFAULT_ALPHA_PERCENT).coerceIn(MIN_ALPHA_PERCENT, MAX_ALPHA_PERCENT)

    /** 百分比 → 边长（px）。 */
    fun sizePx(context: Context, percent: Int): Int {
        val density = context.resources.displayMetrics.density
        return (BASE_SIZE_DP * percent / 100f * density).roundToInt()
    }

    /** 百分比 → `View.alpha`（0.1f..1.0f）。 */
    fun alpha(percent: Int): Float = percent / 100f

    fun sizePx(context: Context, prefs: SharedPreferences): Int = sizePx(context, sizePercent(prefs))

    fun alpha(prefs: SharedPreferences): Float = alpha(alphaPercent(prefs))

    /**
     * 把大小 + 透明度应用到已 inflate 的悬浮球视图。
     *
     * ⚠️ 大小改的是 icon / error_ring 的 **layoutParams**，不是 `scaleX/scaleY`：
     * 点击脉冲、双击反馈、长按反馈动画用的就是 scale（结束回到 1f），尺寸若也走 scale
     * 会被动画收尾那一下抹掉（用户设的大小"点了球就没了"）。
     */
    fun apply(view: View, prefs: SharedPreferences) {
        val sizePx = sizePx(view.context, prefs)
        for (id in intArrayOf(R.id.floating_ball_icon, R.id.floating_ball_error_ring)) {
            val child = view.findViewById<View>(id) ?: continue
            val lp = child.layoutParams ?: continue
            if (lp.width != sizePx || lp.height != sizePx) {
                lp.width = sizePx
                lp.height = sizePx
                child.layoutParams = lp
            }
        }
        val target = alpha(prefs)
        if (view.alpha != target) view.alpha = target
    }

    /**
     * 尺寸变了之后把窗口位置夹回屏幕内。返回 true 表示位置被改过（调用方需 `updateViewLayout`）。
     *
     * ⚠️ 必需：窗口 x/y 是**左上角**、球只向右下生长 —— 从 100% 调到 200% 时，原本贴右/下边的球会
     * 有一部分留在屏外；更糟的是转屏（横屏 x=2000 → 竖屏宽 1080）后可能**整球都在屏外**，
     * 用户再也摸不到那颗球（拖都拖不回来，只能去系统设置里关悬浮窗）。
     *
     * @param sizePx 当前球的边长（px，= [sizePx] 的结果；窗口是 WRAP_CONTENT，尺寸即球尺寸）
     */
    fun clampIntoDisplay(
        params: android.view.WindowManager.LayoutParams?,
        sizePx: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (params == null || screenWidth <= 0 || screenHeight <= 0) return false
        val maxX = (screenWidth - sizePx).coerceAtLeast(0)
        val maxY = (screenHeight - sizePx).coerceAtLeast(0)
        val nx = params.x.coerceIn(0, maxX)
        val ny = params.y.coerceIn(0, maxY)
        if (nx == params.x && ny == params.y) return false
        params.x = nx
        params.y = ny
        return true
    }
}
