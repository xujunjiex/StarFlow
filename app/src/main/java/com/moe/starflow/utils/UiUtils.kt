package com.moe.starflow.utils
import com.moe.starflow.translate.widget.*

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast

/**
 * UI 相关工具函数
 */
object UiUtils {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 单行标题统一规范：省略号 + 选中滚动动画。
     *
     * 给标题 TextView 设 singleLine + ellipsize=marquee + 无限重复：
     * - 未选中 → 显示省略号（不溢出、不压图标、不缩字号）；
     * - setSelected(true)（列表行被点选时由业务接线）→ 横向滚动展示完整标题。
     *
     * ⚠️ marquee 需要 singleLine（maxLines=1 不够）；复用 view 前记得先 isSelected=false。
     * 多行描述/正文、按钮文案不套此函数。
     */
    fun marqueeTitle(tv: TextView) {
        tv.setSingleLine(true)
        tv.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        tv.marqueeRepeatLimit = -1  // marquee_forever
    }

    /**
     * 显示 Toast（自动切换到主线程）
     * @param context 上下文
     * @param message 消息内容
     * @param isShort 是否短时显示，默认 true
     */
    fun showToast(context: Context, message: String, isShort: Boolean = true) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 已在主线程，直接显示
            Toast.makeText(context, message, if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        } else {
            // 在后台线程，切换到主线程
            mainHandler.post {
                Toast.makeText(context, message, if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            }
        }
    }
}
