package com.moe.starflow.translate.screenshot

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.moe.starflow.utils.DisplaySize

/** 服务配置回调可能不包含 180° 翻转；监听 display 本身并核对完整几何。 */
class DisplayGeometryMonitor(private val context: Context, private val onChanged: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var current = DisplaySize.geometry(context)
    private var closed = false
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && !closed) onChanged()
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || closed) return
            check()
        }
    }

    init { manager.registerDisplayListener(listener, Handler(Looper.getMainLooper())) }

    fun check() {
        if (closed) return
        val next = DisplaySize.geometry(context)
        if (next != current) {
            current = next
            onChanged()
        }
    }

    override fun close() {
        closed = true
        manager.unregisterDisplayListener(listener)
    }
}
