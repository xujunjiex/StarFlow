package com.moe.starflow.translate.batch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** 后台批量翻译任务属于哪个阅读器（通知点击要回到对应界面）。 */
enum class TranslationJobKind { MANGA, NOVEL }

/** 一章后台翻译任务的状态。 */
enum class ChapterJobState {
    /** 已提交、等待调度。 */
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    CANCELLED
}

/** 通知栏动作。 */
enum class JobAction { PAUSE, RESUME, CANCEL }

/**
 * 通知栏/前台服务用的**任务快照**（跨书、跨模块汇总）。纯数据，不带 Android 引用。
 *
 * @param bookId 书 id（漫画=ImportedManga.id，小说=ImportedNovel.id）
 * @param chapterIndex 章在书里的下标（点通知回到这一章要用）
 * @param startPage 漫画用：该章第一页（点通知直接跳过去）；小说恒 0
 */
data class ActiveChapterJob(
    val kind: TranslationJobKind,
    val bookId: Long,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterLabel: String,
    val total: Int,
    val done: Int,
    val state: ChapterJobState,
    val startPage: Int = 0,
) {
    /**
     * 通知 id 的稳定来源（同一本书同一章恒等）。
     *
     * ⚠️ 两点硬要求（2026-09-28 审查实测）：
     * ① **不能落在前台服务汇总通知那一段**（`NOTIFICATION_ID_SERVICE = 9998`）：旧公式下
     *    bookId=322/ch=16 正好是 9998 → 章通知会把汇总通知顶掉；
     * ② **跨书不能撞**：旧公式 `bookId*31 + chapterIndex` 让 book1/ch31 与 book2/ch0 都得 62
     *    → 两条章通知互相覆盖 / 服务的 `shown - alive` 差集把别人的通知 cancel 掉。
     * 现在用 64 位混合 + 高位掩码，并把结果整体挪出汇总 id 附近的小数值区。
     */
    val notificationId: Int
        get() {
            // ⚠️ 不能用"乘以同一个基数再相加"的线性公式：bookId 每 +1 等于 chapterIndex +31，
            // 于是 book1/ch31 与 book2/ch0 必然同 id（审查实测撞了）。这里用**互质权重 + 显式区间**
            // 构造：bookId 权重 10_000 > chapterIndex 的上限 → 在 bookId < 100_000、chapterIndex < 10_000
            // 这个真实取值域内是**单射**；再统一挪到 10000 以上，避开汇总通知 9998 与系统常用小 id。
            val base = bookId.coerceIn(0L, 99_999L) * 10_000L + chapterIndex.coerceIn(0, 9_999)
            return (10_000L + base * 2L + kind.ordinal).toInt()
        }
}

/**
 * 「谁在跑批量翻译」的提供者。漫画侧是 `ReaderTranslationHub`，小说侧是小说自己的宿主。
 *
 * ⚠️ 收口成一个接口的原因：通知栏与前台服务**只认这一份快照**，
 * 不然每加一个阅读器就要再抄一遍通知/暂停/取消的代码（`ModelDownloadService` 那套已经够长了）。
 */
interface ChapterJobSource {
    /** 稳定 key（一个模块一个）。 */
    val key: String

    /** 当前任务快照（调用方在主线程外读）。 */
    fun snapshot(): List<ActiveChapterJob>

    /** 处理通知栏动作；返回 true = 已处理（用于前台服务判断要不要清通知）。 */
    fun onAction(action: JobAction, job: ActiveChapterJob): Boolean
}

/**
 * 批量翻译任务的**全局注册表**：前台服务与通知栏的唯一数据源。
 *
 * 生命周期：各宿主在自己的任务状态 flow 变化时调 [notifyChanged]；
 * 服务 collect [activeJobs]，没有任何活动任务时自己 `stopSelf`。
 */
object TranslationJobRegistry {

    private val sources = CopyOnWriteArrayList<ChapterJobSource>()

    private val _activeJobs = MutableStateFlow<List<ActiveChapterJob>>(emptyList())
    val activeJobs: StateFlow<List<ActiveChapterJob>> = _activeJobs.asStateFlow()

    private val _hasActiveJobs = MutableStateFlow(false)
    val hasActiveJobs: StateFlow<Boolean> = _hasActiveJobs.asStateFlow()

    fun register(source: ChapterJobSource) {
        if (sources.none { it.key == source.key }) sources += source
        notifyChanged()
    }

    fun unregister(key: String) {
        sources.removeAll { it.key == key }
        notifyChanged()
    }

    /** 任务状态变了 → 重新聚合（宿主在 collect 到自己的 flow 变化时调用）。 */
    fun notifyChanged() {
        val snapshot = sources.flatMap { runCatching { it.snapshot() }.getOrDefault(emptyList()) }
        _activeJobs.value = snapshot
        _hasActiveJobs.value = snapshot.any {
            it.state == ChapterJobState.RUNNING || it.state == ChapterJobState.PAUSED ||
                it.state == ChapterJobState.QUEUED
        }
    }

    /** 转发通知栏动作给对应宿主。 */
    fun dispatch(action: JobAction, job: ActiveChapterJob): Boolean {
        var handled = false
        sources.forEach { source ->
            if (runCatching { source.onAction(action, job) }.getOrDefault(false)) handled = true
        }
        notifyChanged()
        return handled
    }
}
