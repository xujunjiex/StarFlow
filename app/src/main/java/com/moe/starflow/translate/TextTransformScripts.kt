package com.moe.starflow.translate

import com.moe.starflow.R
import com.moe.starflow.utils.CustomPreference

/**
 * 用户自定义「翻译前 / 翻译后」文本处理脚本的设置键与预置模板。
 *
 * 键都写在 `res/xml/personalization.xml` 的「文本预处理与后处理」分类里：
 * - [KEY_ENABLED] 总开关，默认关闭（关闭时翻译路径完全不碰脚本）
 * - [KEY_PRE] 原文脚本：OCR 原文 → 送进翻译引擎之前的文本
 * - [KEY_POST] 译文脚本：翻译引擎返回的译文 → 最终上屏/入库的译文
 *
 * 脚本是 JavaScript 的函数体，形如 `return s;`，参数名固定为 [PARAM_NAME]。
 * 执行器见 [TextTransformer]。
 */
object TextTransformScripts {

    const val KEY_ENABLED = "text_transform_enabled"
    const val KEY_PRE = "text_transform_pre"
    const val KEY_POST = "text_transform_post"

    const val DEFAULT_ENABLED = false

    /** 脚本入参名，预置模板与 `CLAUDE.md` 都按这个名字写。 */
    const val PARAM_NAME = "s"

    /** 预置模板：什么都不做，直接把传入的字符串返回。也是缺省值与空脚本的兜底。 */
    const val IDENTITY_SCRIPT = "return $PARAM_NAME;"

    /**
     * 预置模板正文，顺序必须与 [PRESET_NAME_RES] 一一对应。
     * 第一条就是「什么都不做」，也是脚本为空时的默认值。
     */
    val PRESET_SCRIPTS: List<String> = listOf(
        IDENTITY_SCRIPT,
        "return $PARAM_NAME.replace(/[\\r\\n]+/g, ' ').replace(/ {2,}/g, ' ').trim();",
        "return $PARAM_NAME.replace(/\\s+/g, '');",
        "return $PARAM_NAME.replace(/,/g, '，').replace(/!/g, '！').replace(/\\?/g, '？');",
    )

    /**
     * 预置模板的显示名，顺序必须与 [PRESET_SCRIPTS] 一致（有单测锁两者长度相同）。
     */
    val PRESET_NAME_RES: List<Int> = listOf(
        R.string.text_transform_preset_identity,
        R.string.text_transform_preset_join_lines,
        R.string.text_transform_preset_strip_spaces,
        R.string.text_transform_preset_halfwidth_punctuation,
    )

    fun isEnabled(prefs: CustomPreference): Boolean =
        prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun preScript(prefs: CustomPreference): String =
        normalize(prefs.getString(KEY_PRE, IDENTITY_SCRIPT))

    fun postScript(prefs: CustomPreference): String =
        normalize(prefs.getString(KEY_POST, IDENTITY_SCRIPT))

    /** 空/全空白一律回预置的「什么都不做」，避免把空脚本丢进 JS 引擎当语法错误。 */
    fun normalize(script: String?): String =
        script?.takeIf { it.isNotBlank() } ?: IDENTITY_SCRIPT

    /** 是否就是预置的「什么都不做」：是的话连引擎都不用进（省一次解析）。 */
    fun isIdentity(script: String): Boolean = script.trim() == IDENTITY_SCRIPT
}
