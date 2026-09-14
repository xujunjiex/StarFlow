/*
 * Copyright (C) 2026 murangogo
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
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * UI 同步字体（`ui_apply_custom_font`）统一接线点。
 *
 * 自 BaseActivity（5 个 Activity / Service / MangaReaderActivity / MangaViewerActivity）的
 * 原两套重复 Factory2 逻辑收敛而来：
 * - Activity：`install(activity)` 在 `super.onCreate()` **之前**挂 Factory2，inflate 即应用，
 *   覆盖 Fragment / Dialog / 适配器列表项（用 Activity inflater 的都命中）；
 * - Service / 程序化 TextView / 惰性 inflate 列表项：Factory2 覆盖不到，用
 *   `applyToTree(view)` / `apply(textView)` 定点应用。
 * 开关关闭或无字体时全部为空操作。
 */
object FontSync {

    const val KEY_UI_FONT = "ui_apply_custom_font"

    fun isEnabled(ctx: Context): Boolean =
        CustomPreference.getInstance(ctx).getBoolean(KEY_UI_FONT, false)

    fun loadTypeface(ctx: Context): Typeface? =
        com.moe.starflow.manga.render.OverlayRenderer.loadResultTypeface(ctx, CustomPreference.getInstance(ctx))

    /**
     * 给 Activity 的 LayoutInflater 挂 Factory2（inflate 即应用自定义字体）。
     * 必须是 AppCompatActivity 子类且在本类 `super.onCreate()` 之前调用，
     * 否则 AppCompat 已设 factory，`setFactory2` 抛 `IllegalStateException` 被静默吞掉（字体会失效）。
     * 返回是否挂载成功。
     */
    fun install(activity: AppCompatActivity): Boolean {
        if (!isEnabled(activity)) return false
        val typeface = loadTypeface(activity) ?: return false
        return try {
            val delegate = activity.delegate
            androidx.core.view.LayoutInflaterCompat.setFactory2(
                activity.layoutInflater,
                object : android.view.LayoutInflater.Factory2 {
                    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
                        val view = delegate.createView(parent, name, context, attrs)
                        if (view is TextView) view.typeface = typeface
                        return view
                    }
                    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                        onCreateView(null, name, context, attrs)
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 单个 TextView 应用自定义字体（程序化创建的场景）。开关关闭/无字体为空操作。 */
    fun apply(tv: TextView) {
        if (!isEnabled(tv.context)) return
        val typeface = loadTypeface(tv.context) ?: return
        tv.typeface = typeface
    }

    /** 对 view 树递归应用自定义字体（Service 悬浮窗等无 Factory2 的场景）。 */
    fun applyToTree(view: View) {
        if (!isEnabled(view.context)) return
        val typeface = loadTypeface(view.context) ?: return
        fun walk(v: View) {
            if (v is TextView) v.typeface = typeface
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
    }
}