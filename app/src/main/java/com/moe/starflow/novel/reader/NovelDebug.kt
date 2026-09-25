package com.moe.starflow.novel.reader

import android.util.Log

/**
 * 小说阅读器的临时诊断输出（定位「页面不刷新」类问题用）。
 *
 * ⚠️ **这是排查用的临时开关**：手动抓 logcat 看 `NovelDbg` 标签即可。
 * 问题定位完就把 [ENABLED] 置回 false（或整体删掉）—— 它会在滚动/翻页路径上频繁打日志。
 */
internal object NovelDebug {

    /** 打开后每次 loadChapter / 每页绑定 / 每次滚动都会打一行。 */
    const val ENABLED = true

    private const val TAG = "NovelDbg"

    fun log(message: String) {
        if (ENABLED) Log.i(TAG, message)
    }

    /** 内容指纹：用来判断「绑定到的是不是同一份文本」（只看前 12 个字符 + 长度）。 */
    fun brief(text: String): String {
        if (text.isEmpty()) return "<empty>"
        val head = text.take(12).replace('\n', '⏎')
        return "$head…(len=${text.length})"
    }
}
