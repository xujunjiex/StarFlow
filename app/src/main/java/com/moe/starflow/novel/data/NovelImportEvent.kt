package com.moe.starflow.novel.data

/**
 * 小说导入的失败原因。
 *
 * ⚠️ 与漫画的 `ImportFailureReason` **分开定义**：那边的取值
 * （`NOT_ARCHIVE` / `DIRECTORY`…）全是压缩包与文件夹语境，套到小说上
 * （压缩包坏了 / 没有可读章节 / 空文件 / DRM 加密）会给出对不上的提示文案。
 */
enum class NovelImportFailureReason {
    /** 不是受支持的格式，或压缩包损坏。 */
    NOT_ARCHIVE,

    /** SAF 权限被撤销 / 文件读不了。 */
    UNREADABLE,

    /** 解析出来一个可读章节都没有。 */
    NO_TEXT_CHAPTER,

    /** 空文件。 */
    EMPTY,

    /** DRM 加密的 EPUB（加密条目指向正文文档）。 */
    ENCRYPTED,

    UNKNOWN,
}

/**
 * 小说导入结果事件。
 *
 * 与漫画同理：**不直接弹窗**，而是攒在 `NovelImportManager.events` 里由 Fragment 取走
 * （drain）—— 这样旋转/切页导致 View 销毁期间产生的事件不会丢，也不会重复弹。
 */
sealed interface NovelImportEvent {

    /** 导入成功但一章都没有（条目已入库，只提示）。 */
    data class NoChapters(val title: String) : NovelImportEvent

    /** 导入失败，没有产生条目。 */
    data class Failed(val title: String, val reason: NovelImportFailureReason) : NovelImportEvent

    /**
     * 选错了书架：用户在小说的入口选了漫画文件（[isManga] = true），
     * 或反过来（在漫画入口选了文本文件，[isManga] = false）。
     */
    data class WrongFormat(val title: String, val isManga: Boolean) : NovelImportEvent
}

/**
 * 用户在小说的导入入口选了「不是小说」的文件。
 *
 * [isManga] 决定提示文案：是漫画 → 「请到漫画书架导入」；两者都不是 → 「不是受支持的格式」。
 */
class NovelWrongFormatException(val isManga: Boolean) :
    IllegalStateException(if (isManga) "WRONG_FORMAT_MANGA" else "WRONG_FORMAT_NOT_TEXT")
