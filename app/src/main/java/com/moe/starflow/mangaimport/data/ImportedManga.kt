package com.moe.starflow.mangaimport.data

/**
 * 一部导入的漫画。
 *
 * @param localRoot app 内绝对路径（复制后的），不是 SAF uri
 * @param isArchive true=复制后的 zip/cbz，false=复制后的图片目录
 * @param coverPath 封面缩略图绝对路径 filesDir/covers/<id>_<ts>.jpg，可为 null
 * @param sizeBytes 本地副本总大小（zip=文件大小，目录=图片总大小）
 * @param description 用户添加的简介/备注，可为空
 * @param translatedPath 译文缓存路径（三态切换「译文」态用），阶段一恒为 null
 * @param lastReadPage 断点续读的阅读进度，阶段一仅保存/恢复页号
 * @param lost 该条目本地文件是否已丢失（被手动删除等）。**瞬态标记，不入库**——由书架刷新时
 *             按 `localRoot` 是否存在实时推导；`toJson` 不持久化，加载回的条目恒为 false。
 * @param importing 瞬态：这是「导入中的占位」而非真实条目（还没入库、没本地路径）。
 *                  书架在图片位显示导入进度；占位不可打开、不可多选、不参与「文件丢失」推导。
 *                  `toJson` 不持久化。
 * @param importPhase 瞬态：占位的导入阶段（扫描/复制），仅 [importing] 时有意义。
 * @param importPercent 瞬态：占位的导入进度 0..100（-1=不确定），仅 [importing] 时有意义。
 * @param chapters **章节表**（见 [MangaChapter]）。空表 = 还不知道/单章漫画：
 *                 导入时按「子文件夹 = 一章」算好并持久化；老条目由书架后台一次性回填
 *                 （`MangaChapterMigrator`），阅读器打开时也会按实际文件**重新推导并自愈**。
 */
data class ImportedManga(
    val id: Long,
    val title: String,
    val localRoot: String,
    val isArchive: Boolean,
    val coverPath: String?,
    val pageCount: Int,
    val addedAt: Long,
    val sizeBytes: Long = 0,
    val description: String = "",
    val translatedPath: String? = null,
    val lastReadPage: Int = 0,
    val lost: Boolean = false,
    val importing: Boolean = false,
    val importPhase: ImportPhase? = null,
    val importPercent: Int = -1,
    val chapters: List<MangaChapter> = emptyList(),
    /**
     * 页序版本（见 [MangaPageOrder]）：小于 [MangaPageOrder.CURRENT_VERSION] 表示这本书的译文行
     * 可能还是**旧页序**（章节系统之前）存下的 → 阅读器打开时做一次性重排。
     * 默认 0；老清单没有这个字段，反序列化后即 0。
     */
    val pageOrderVersion: Int = 0,
) {
    /** 章数（至少 1：单章漫画也算一章）。 */
    val chapterCount: Int get() = chapters.size.coerceAtLeast(1)

    /** 第 [page] 页所在的章（没有章节表时按「整本一章」处理）。 */
    fun chapterOfPage(page: Int): MangaChapter? = chapters.firstOrNull { it.containsPage(page) }

    /**
     * 翻译记录的身份指纹（= `addedAt`）。
     *
     * ⚠️ 单一来源：阅读器读记录（`ReaderTranslationController`）与书架删除前的「有没有译文」提示
     * 必须用**同一个值**，否则一处改了另一处会静默失配。漫画 id 是「当前最大 id + 1」，
     * 删除后再导入会复用同一个 id，靠这个指纹区分「同一 id 的不同书」。
     */
    val translationKey: String get() = addedAt.toString()
}
