package com.moe.starflow.novel.data

import com.moe.starflow.mangaimport.data.ImportPhase
import com.moe.starflow.novel.model.NovelFormat

/**
 * 一部导入的小说。
 *
 * @param localRoot 复制后的 app 内绝对路径（`novel_import/<id>/<原文件>`）
 * @param addedAt **同时是译文身份指纹**。书籍 id = 清单最大 id + 1，删书后重导会复用同一 id，
 *   靠这个值区分「同一 id 的不同书」。必须单调递增（见 `NovelImporter.freshAddedAt`）。
 * @param lastReadCharOffset 章内字符偏移 —— **排版无关的稳定锚点**，改字号/行距后仍能定位。
 * @param lastReadPage 章内页号，仅作「同一排版下直接命中」的快路径。
 * @param lost 本地文件是否已丢失。**瞬态**，由书架按 `localRoot` 是否存在实时推导，不持久化。
 * @param importing 瞬态：导入占位卡片，不可打开、不参与「文件丢失」推导。
 */
data class ImportedNovel(
    val id: Long,
    val title: String,
    val author: String? = null,
    val localRoot: String,
    val format: NovelFormat,
    val coverPath: String? = null,
    val chapterCount: Int,
    val addedAt: Long,
    val sizeBytes: Long = 0,
    val description: String = "",
    val lastReadChapter: Int = 0,
    val lastReadCharOffset: Int = 0,
    val lastReadPage: Int = 0,
    val lost: Boolean = false,
    val importing: Boolean = false,
    val importPhase: ImportPhase? = null,
    val importPercent: Int = -1,
) {
    /** 译文身份指纹。与 `ImportedManga.translationKey` 同构。 */
    val translationKey: String get() = addedAt.toString()
}
