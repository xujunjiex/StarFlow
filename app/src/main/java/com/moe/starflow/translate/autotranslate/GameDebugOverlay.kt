package com.moe.starflow.translate.autotranslate
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.R

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 游戏翻译调试浮窗 —— 状态面板 + 可折叠日志面板。
 * 仅在 Game_Translate_Debug_View=true 时创建。
 *
 * - 状态窗口：点击可切换日志窗口的显示/隐藏
 * - 日志窗口：独立浮窗，展示最近日志记录（相同消息自动合并显示次数）
 */
class GameDebugOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // 状态窗口
    private var statusView: TextView? = null
    private var statusAdded = false
    private var statusParams: WindowManager.LayoutParams? = null

    // 日志窗口（独立，可折叠）
    private var logView: TextView? = null
    private var logAdded = false
    private var logVisible = false

    // 日志数据：Pair<消息, 出现次数>
    private val logs = mutableListOf<Pair<String, Int>>()
    private val maxLogs = 20
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var lastLogMessage = ""  // 用于去重

    // 当前状态文本（用于刷新 hint 后缀）
    private var currentStatusText = ""

    companion object {
        private const val TEXT_SIZE_SP = 11f
        private const val PADDING_DP = 10
        private const val BG_COLOR = 0xCC000000.toInt()  // 80% 黑色
        private const val TEXT_COLOR = Color.GREEN
        private const val HINT_COLOR = "#666666"
        private const val LOG_WINDOW_Y_OFFSET = 120  // 日志窗口在状态窗口下方的偏移 dp
    }

    @SuppressLint("SetTextI18n")
    fun show() {
        if (statusAdded) return

        val paddingPx = dpToPx(PADDING_DP.toFloat())

        val textView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            setTextColor(TEXT_COLOR)
            setBackgroundColor(BG_COLOR)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            text = "【空闲】等待截图..."
            com.moe.starflow.utils.FontSync.apply(this)
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE  // 可点击
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        try {
            // 点击状态窗口切换日志
            textView.setOnClickListener { toggleLogWindow() }

            windowManager.addView(textView, params)
            statusView = textView
            statusParams = params
            statusAdded = true
        } catch (e: Exception) {
            statusView = null
            statusParams = null
            statusAdded = false
        }
    }

    fun hide() {
        hideLogWindow()
        if (!statusAdded) return
        try {
            statusView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        statusView = null
        statusParams = null
        statusAdded = false
    }

    /**
     * 切换日志窗口显示/隐藏
     */
    fun toggleLogWindow() {
        if (logVisible) {
            hideLogWindow()
        } else {
            showLogWindow()
        }
        refreshStatusHint()
    }

    fun isLogVisible(): Boolean = logVisible

    private fun showLogWindow() {
        if (logAdded) return
        val paddingPx = dpToPx(PADDING_DP.toFloat())
        val yOffsetPx = dpToPx(LOG_WINDOW_Y_OFFSET.toFloat())

        val textView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            setTextColor(TEXT_COLOR)
            setBackgroundColor(BG_COLOR)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            text = "暂无日志"
            com.moe.starflow.utils.FontSync.apply(this)
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = yOffsetPx
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        try {
            windowManager.addView(textView, params)
            logView = textView
            logAdded = true
            logVisible = true
            refreshLogDisplay()
        } catch (e: Exception) {
            logView = null
            logAdded = false
            logVisible = false
        }
    }

    private fun hideLogWindow() {
        if (!logAdded) return
        try {
            logView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        logView = null
        logAdded = false
        logVisible = false
    }

    /**
     * 刷新状态窗口底部的 hint 提示
     */
    @SuppressLint("SetTextI18n")
    private fun refreshStatusHint() {
        val view = statusView ?: return
        val hint = if (logVisible) context.getString(R.string.debug_log_collapse) else context.getString(R.string.debug_log_expand)
        view.text = "$currentStatusText\n$hint"
    }

    /**
     * 添加一条日志记录，自动去重（连续相同消息合并为 ×N）。
     */
    @SuppressLint("SetTextI18n")
    fun addLog(message: String) {
        if (message == lastLogMessage && logs.isNotEmpty()) {
            val last = logs.last()
            logs[logs.size - 1] = last.copy(second = last.second + 1)
        } else {
            val timestamp = timeFormat.format(Date())
            logs.add("$timestamp $message" to 1)
            if (logs.size > maxLogs) {
                logs.removeAt(0)
            }
            lastLogMessage = message
        }
        if (logVisible) {
            refreshLogDisplay()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshLogDisplay() {
        val view = logView ?: return
        if (logs.isEmpty()) {
            view.text = context.getString(R.string.debug_no_logs)
            return
        }
        val sb = StringBuilder()
        sb.appendLine("──── 日志 (最近${logs.size}条) ────")
        for ((entry, count) in logs) {
            if (count > 1) {
                sb.appendLine("$entry ×$count")
            } else {
                sb.appendLine(entry)
            }
        }
        view.text = sb.toString().trimEnd()
    }

    /**
     * 更新状态窗口信息。必须在主线程调用。
     */
    @SuppressLint("SetTextI18n")
    fun update(
        status: String,
        ocrEngine: String = "",
        similarity: Float = -1f,
        cacheSource: String = "",
        elapsedMs: Long = -1L,
        diffRatio: Float = -1f
    ) {
        if (!statusAdded) return

        // 记录到日志（带去重）
        val logParts = mutableListOf(status)
        if (diffRatio >= 0f) logParts.add("diff=${"%.6f".format(diffRatio)}")
        if (similarity >= 0f) logParts.add("sim=${"%.2f".format(similarity)}")
        if (elapsedMs >= 0L) logParts.add("${elapsedMs}ms")
        addLog(logParts.joinToString(" "))

        // 构建状态窗口文本
        val sb = StringBuilder()
        sb.appendLine(status)
        if (ocrEngine.isNotEmpty()) sb.appendLine("OCR: $ocrEngine")
        if (diffRatio >= 0f) sb.appendLine("像素差异: ${"%.4f%%".format(diffRatio * 100)}")
        if (similarity >= 0f) sb.appendLine("文字相似度: ${"%.2f".format(similarity)}")
        if (cacheSource.isNotEmpty()) sb.appendLine("缓存: $cacheSource")
        if (elapsedMs >= 0L) sb.appendLine("耗时: ${elapsedMs}ms")

        currentStatusText = sb.toString().trimEnd()
        refreshStatusHint()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp,
            context.resources.displayMetrics
        ).toInt()
    }
}
