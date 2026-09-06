package com.moe.starflow.mangaimport.data

/**
 * 一部导入的漫画。
 *
 * @param localRoot app 内绝对路径（复制后的），不是 SAF uri
 * @param isArchive true=复制后的 zip/cbz，false=复制后的图片目录
 * @param coverPath 封面缩略图绝对路径 filesDir/covers/<id>.jpg，可为 null
 * @param translatedPath 译文缓存路径（三态切换「译文」态用），阶段一恒为 null
 * @param lastReadPage 断点续读的阅读进度，阶段一仅保存/恢复页号
 */
data class ImportedManga(
    val id: Long,
    val title: String,
    val localRoot: String,
    val isArchive: Boolean,
    val coverPath: String?,
    val pageCount: Int,
    val addedAt: Long,
    val translatedPath: String? = null,
    val lastReadPage: Int = 0
)
