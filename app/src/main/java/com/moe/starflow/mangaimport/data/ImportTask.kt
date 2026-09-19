package com.moe.starflow.mangaimport.data

/**
 * 一次导入任务的进度快照（由 [MangaImporter] 的进度回调产出）。
 *
 * 进度口径按「最可靠的已知量」二选一：
 * - 压缩包：字节数（源文件大小可查）→ [copiedBytes]/[totalBytes]
 * - 图片文件夹：文件数（SAF 逐文件查 length 太贵，扫描阶段只数个数）→ [copiedFiles]/[totalFiles]
 *
 * 两者都拿不到（扫描中）→ [percent] = -1，UI 走不确定进度。
 */
data class ImportProgress(
    val phase: ImportPhase,
    val copiedFiles: Int = 0,
    val totalFiles: Int = 0,
    val copiedBytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    /** 0..100；无法确定时 -1。 */
    val percent: Int
        get() = when {
            totalBytes > 0 -> ((copiedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            totalFiles > 0 -> ((copiedFiles * 100) / totalFiles).coerceIn(0, 100)
            else -> -1
        }
}

/** 导入阶段：先枚举/数文件（扫描），再逐个复制。 */
enum class ImportPhase { SCANNING, COPYING }

/**
 * 进行中的导入任务。**纯内存态**：不落盘、不进 SharedPreferences、不随进程存活。
 *
 * 书架用 [toPlaceholder] 把它渲染成占位卡片（图片位置显示进度），导入完成后占位被真实条目
 * 顶替（id 相同 → DiffUtil 原地刷新，不闪）。
 */
data class ImportTask(
    val id: Long,
    val title: String,
    val isArchive: Boolean,
    val addedAt: Long,
    val progress: ImportProgress
) {
    val percent: Int get() = progress.percent

    fun toPlaceholder(): ImportedManga = ImportedManga(
        id = id,
        title = title,
        // 占位没有本地路径：它还没入库，也不该被「文件丢失」判据命中（Fragment 跳过 importing 项）
        localRoot = "",
        isArchive = isArchive,
        coverPath = null,
        pageCount = 0,
        addedAt = addedAt,
        importing = true,
        importPhase = progress.phase,
        importPercent = percent
    )
}

/** 导入失败原因。数据层只给原因，文案由 UI 层映射（避免 data 包依赖具体资源）。 */
enum class ImportFailureReason { NOT_ARCHIVE, UNREADABLE, DIRECTORY, UNKNOWN }

/**
 * 导入结果事件（UI 用内置弹窗消费）。
 * ⚠️ 事件**不直接弹窗**，而是攒在 [ImportManager.events] 里由 Fragment 取走（drain）：
 * 这样旋转/切页导致 View 销毁期间产生的事件不会丢，也不会重复弹。
 */
sealed interface ImportEvent {

    /** 导入成功了，但内容里一张图都没有（照旧入库成 0 页条目，只提示）。 */
    data class NoImages(val title: String, val isArchive: Boolean) : ImportEvent

    /** 导入失败，没有产生书架条目。 */
    data class Failed(val title: String, val reason: ImportFailureReason) : ImportEvent
}
