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
 * 导入内容的类型：属于哪个书架。
 *
 * 两个书架（漫画 / 小说）各有自己的 `ImportManager`，但共用 [ImportTask] 这类纯数据编排件；
 * 状态栏按这个枚举显示所属书架的任务。
 */
enum class ImportFormat { MANGA, NOVEL }

/**
 * 进行中的导入任务。**纯内存态**：不落盘、不进 SharedPreferences、不随进程存活。
 *
 * 由书架上方状态栏显示进度；任务不加入书目列表。
 */
data class ImportTask(
    val id: Long,
    val title: String,
    val isArchive: Boolean,
    val addedAt: Long,
    val progress: ImportProgress,
    /** 该任务属于哪个书架。**带默认值**，漫画侧既有调用点零改动也能编译。 */
    val format: ImportFormat = ImportFormat.MANGA,
) {
    val percent: Int get() = progress.percent

    /** 兼容旧调用的瞬态模型；当前书架不使用占位条目。 */
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
enum class ImportFailureReason {
    NOT_ARCHIVE,
    ARCHIVE_INVALID,
    SOURCE_ACCESS_DENIED,
    SOURCE_OPEN_FAILED,
    SOURCE_READ_FAILED,
    DESTINATION_WRITE_FAILED,
    STORAGE_INSUFFICIENT,
    ARCHIVE_LIMIT_EXCEEDED,
    DIRECTORY_ACCESS_DENIED,
    DIRECTORY_SCAN_FAILED,
    DIRECTORY_INVALID,
    UNREADABLE,

    /** PDF 需要密码（Android 的 `PdfRenderer` 没有密码接口 → 只能请用户先解密）。 */
    PDF_ENCRYPTED,

    /** PDF 损坏 / 不是有效 PDF / pdfium 不支持。 */
    PDF_INVALID,

    UNKNOWN
}

/**
 * 发生在 SAF 源读取或应用目标写入阶段的失败。保留阶段信息，避免把文件提供器问题误报成
 * 「权限不足或文件移动」；[cause] 会原样保留，供日志与失败弹窗显示系统实际错误。
 */
class ImportOperationException(
    val reason: ImportFailureReason,
    message: String,
    cause: Throwable? = null
) : java.io.IOException(message, cause)

/**
 * PDF 打不开。
 *
 * 单独一个类型而不是复用 `IllegalArgumentException`：后者在 `classifyArchive` 里被归成
 * NOT_ARCHIVE（"不是压缩包"），而 PDF 的问题**不是格式不认识**，是"这个 PDF 本身打不开"，
 * 提示语完全不同。
 */
class PdfImportException(
    val reason: ImportFailureReason,
    message: String
) : IllegalStateException(message)

/**
 * 导入结果事件（UI 用内置弹窗消费）。
 * ⚠️ 事件**不直接弹窗**，而是攒在 [ImportManager.events] 里由 Fragment 取走（drain）：
 * 这样旋转/切页导致 View 销毁期间产生的事件不会丢，也不会重复弹。
 */
sealed interface ImportEvent {

    /** 导入成功了，但内容里一张图都没有（照旧入库成 0 页条目，只提示）。 */
    data class NoImages(val title: String, val isArchive: Boolean) : ImportEvent

    /** 导入失败，没有产生书架条目。 */
    data class Failed(
        val title: String,
        val reason: ImportFailureReason,
        /** 系统异常类型和原始消息；已截断，可能为空。 */
        val detail: String? = null
    ) : ImportEvent
}
