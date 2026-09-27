package com.moe.starflow.novel.reader

import android.content.Context
import com.moe.starflow.R

/**
 * 失败码 → 给人看的说明。
 *
 * ⚠️ 一处映射：以前这类码在 UI 里散写成 `when`，加一个码就要满仓库找。
 * 未知码**原样显示**（别吞掉）—— 排查问题时那个字符串就是线索。
 */
object NovelFailCode {

    /** 模型没返回这一段的译文（当前唯一的码，见 `NovelChapterTranslator.FAIL_CODE_EMPTY`）。 */
    const val TRANSLATE_EMPTY = "TRANSLATE_EMPTY"

    fun label(context: Context, code: String?): String = when (code) {
        TRANSLATE_EMPTY, null, "" -> context.getString(R.string.novel_fail_translate_empty)
        else -> code
    }
}
