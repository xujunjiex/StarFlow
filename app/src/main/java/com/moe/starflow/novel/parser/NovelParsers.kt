package com.moe.starflow.novel.parser

import com.moe.starflow.novel.model.NovelFormat

/**
 * 格式 → 解析器 的**唯一映射**。
 *
 * 抽出来是因为它有两个消费方（导入器与章加载仓库），各写一份 `when` 迟早会在新增格式时
 * 只改一处 —— 表现为「导入成功但打开是空的」这种很难定位的问题。
 */
object NovelParsers {

    fun forFormat(format: NovelFormat): NovelParser = when (format) {
        NovelFormat.TXT -> TxtParser
        NovelFormat.EPUB -> EpubParser
        NovelFormat.ZIP_HTML -> ZipHtmlParser
        NovelFormat.HTML -> HtmlParser
    }
}
