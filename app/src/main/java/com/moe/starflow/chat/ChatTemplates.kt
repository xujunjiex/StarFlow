package com.moe.starflow.chat
import com.moe.starflow.translate.widget.*

import androidx.annotation.StringRes
import com.moe.starflow.R

/**
 * 一套翻译提示词模板。
 *
 * ⚠️ label / variableHints 走字符串资源（中英各一份）——之前是中文硬编码，
 * 英文界面下会泄漏中文。zh / en 是模板正文本身：弹层中英并列展示供用户复制，
 * 两种语言都是有意的内容，不进资源。
 */
data class ChatTemplate(
    val id: String,
    @StringRes val labelRes: Int,
    val zh: String,
    val en: String,
    /** 占位符 → 说明文案资源 id */
    val variableHints: List<Pair<String, Int>>
)

object ChatTemplates {

    /** 对话默认系统提示词：自由聊天助手 */
    const val DEFAULT_SYSTEM = "你是一个乐于助人的 AI 助手，请直接回答用户的问题。"

    private val HINT_SOURCE = "{source_text}" to R.string.chat_hint_source_text
    private val HINT_TARGET = "{target_lang}" to R.string.chat_hint_target_lang

    /** 7 套官方翻译模板（桌面端 MODES 移植） */
    val all: List<ChatTemplate> = listOf(
        ChatTemplate(
            id = "default", labelRes = R.string.chat_tpl_default,
            zh = "将以下文本翻译为 {target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{source_text}",
            en = "Translate the following text into {target_lang}. Note that you should only output the translated result without any additional explanation:\n\n{source_text}",
            variableHints = listOf(HINT_SOURCE, HINT_TARGET)
        ),
        ChatTemplate(
            id = "terminology", labelRes = R.string.chat_tpl_terminology,
            zh = "参考下面的翻译：\n{glossary}\n将以下文本翻译为 {target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{source_text}",
            en = "Reference the following translations:\n{glossary}\nTranslate the following text into {target_lang}. Note that you must ONLY output the translated result without any additional explanation:\n\n{source_text}",
            variableHints = listOf(
                "{glossary}" to R.string.chat_hint_glossary,
                HINT_SOURCE, HINT_TARGET
            )
        ),
        ChatTemplate(
            id = "style", labelRes = R.string.chat_tpl_style,
            zh = "请将以下文本翻译为 {target_lang}。\n注意翻译的风格要严格符合【{target_style}】\n\n{source_text}",
            en = "Please translate the following text into {target_lang}. Note that the translation style must strictly conform to [{target_style}]:\n\n{source_text}",
            variableHints = listOf(
                "{target_style}" to R.string.chat_hint_target_style,
                HINT_SOURCE, HINT_TARGET
            )
        ),
        ChatTemplate(
            id = "personalization", labelRes = R.string.chat_tpl_personalization,
            zh = "【待翻译文本】\n{source_text}\n\n【翻译任务】\n{prefs}",
            en = "[Source Text]\n{source_text}\n\n[Translation Tasks]\n{prefs}",
            variableHints = listOf(
                "{prefs}" to R.string.chat_hint_prefs,
                HINT_SOURCE
            )
        ),
        ChatTemplate(
            id = "delimiters", labelRes = R.string.chat_tpl_delimiters,
            zh = "请将以下文本准确翻译为 {target_lang}。\n你必须在译文中保留等量的分隔符，绝对不可遗漏、转义或翻译该符号，并注意分隔符的位置。\n\n{source_text}",
            en = "Please accurately translate the following text into {target_lang}.\nYou must retain the exact same number of delimiters in the translation. Strictly do not omit, escape, or translate these symbols, and pay close attention to their placement.\n\n{source_text}",
            variableHints = listOf(HINT_SOURCE, HINT_TARGET)
        ),
        ChatTemplate(
            id = "structured1", labelRes = R.string.chat_tpl_structured1,
            zh = "# 任务目标\n将下方 {source_text} 中的 {format_type} 格式数据翻译为 {target_lang}。\n\n# 严格约束\n1. 结构锁定：绝对保持原有的 {format_type} 数据结构、缩进和层级完全不变。\n2. 选择性翻译：仅翻译面向用户展示的可见文本内容。\n3. 禁止修改：严禁翻译或更改任何代码标签、键名 (Key)、变量占位符（如 {{var}}、\${var}、%s、%d 等）或代码属性。\n\n# 数据输入\n{source_text}",
            en = "### Task\nTranslate the user-facing text within the following {format_type} data into {target_lang}.\n\n### Strict Rules\n1. Structure Preservation: You MUST preserve the original {format_type} data structure, nesting, hierarchy, and indentation exactly as they are.\n2. Selective Translation: Translate ONLY the visible, user-facing text content/values.\n3. Strict Non-Translation: NEVER translate or alter code tags, keys, properties, object names, or variable placeholders. Leave them exactly in their original English/code form.\n\n### Source Data\n{source_text}",
            variableHints = listOf(
                "{format_type}" to R.string.chat_hint_format_type,
                HINT_SOURCE, HINT_TARGET
            )
        ),
        ChatTemplate(
            id = "structured2", labelRes = R.string.chat_tpl_structured2,
            zh = "【背景信息】\n{background_text}\n\n请结合背景信息将以下文本翻译为 {target_lang}。\n\n【待翻译文本】\n{source_text}",
            en = "[Background Information]\n{background_text}\n\nPlease translate the following text into {target_lang}, taking the provided background information into consideration.\n\n[Source Text]\n{source_text}",
            variableHints = listOf(
                "{background_text}" to R.string.chat_hint_background_text,
                HINT_SOURCE, HINT_TARGET
            )
        )
    )
}
