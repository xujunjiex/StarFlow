package com.moe.starflow.translate.batch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.moe.starflow.R
import com.moe.starflow.novel.reader.NovelReaderActivity
import com.moe.starflow.utils.LogCollector

/**
 * 阅读器**前台队列**（手动翻一批 / 自动 / 增量）的进度通知。
 *
 * 用户口径 2026-10-03：「整章翻译没有看到通知系统的通知 —— 这个不仅要显示在卡片徽章上，
 * **其他模式的翻译过程相关提示也必须显示在通知系统中**」。
 * 整章任务本来就有通知（`TranslationJobService` 单章一条、多章最多两条，走 [TranslationJobRegistry]）；
 * 缺的是**阅读器自己这条队列**（自动/增量/手动翻一批）—— 这里给它一条。
 *
 * ⚠️ 与整章任务的区别（别合并成一套）：
 * - 这条通知**不依赖前台服务**，也不进 [TranslationJobRegistry]：队列活在阅读器的
 *   `lifecycleScope` 里，**离开阅读器就停**（自动/增量立刻终止是用户明确要求），
 *   所以没有"后台保活"的需求，只要"看得见"。
 * - 因此它没有暂停/取消按钮（队列的暂停入口是打开面板，取消是超长预警/退出阅读器）。
 *   点通知 = 回到这本书的这一章。
 * - id 固定（[NOTIFICATION_ID]，9998 是前台服务的汇总、10000+ 是每章任务），同一时刻只有一条。
 */
object ReaderTranslationNotify {

    private const val TAG = "ReaderTranslationNotify"

    /** ⚠️ 必须避开 `TranslationJobService.NOTIFICATION_ID_SERVICE`(9998) 与每章任务的 10000+。 */
    const val NOTIFICATION_ID = 9997

    /**
     * 更新（或首次显示）这条进度通知。
     *
     * @param line 已经拼好的进度文案（"正在翻译 · 第 3/52 页 · 已翻 12 批"）
     * @param done/total 进度条分子分母（页数；拿不到就传 0/0 → 不画进度条）
     */
    @SuppressLint("MissingPermission")
    fun show(
        context: Context,
        bookTitle: String,
        chapterLabel: String,
        line: String,
        done: Int,
        total: Int,
        novelId: Long,
        chapterIndex: Int,
    ) {
        val nm = NotificationManagerCompat.from(context)
        // 通知被用户关掉时别白构造（也避免部分 ROM 上直接抛 SecurityException）
        if (!nm.areNotificationsEnabled()) return
        // ⚠️ 通道必须先确保存在：Android 8+ 往不存在的通道发通知是**静默丢弃**，
        // 而这条通知不经过前台服务（用户只用自动/增量时没人建通道）
        TranslationNotifyChannel.ensure(context)
        val builder = NotificationCompat.Builder(context, TranslationJobService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reader_translate)
            .setContentTitle(context.getString(R.string.chapter_translate_notify_title, bookTitle, chapterLabel))
            .setContentText(line)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openReaderIntent(context, novelId, chapterIndex))
        if (total > 0) builder.setProgress(total, done.coerceIn(0, total), false)
        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { LogCollector.w(TAG, "阅读器翻译通知显示失败: ${it.message}") }
    }

    /** 队列停下 / 离开阅读器 → 收掉这条通知。 */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun openReaderIntent(context: Context, novelId: Long, chapterIndex: Int): PendingIntent {
        val intent = Intent(context, NovelReaderActivity::class.java).apply {
            putExtra(NovelReaderActivity.EXTRA_NOVEL_ID, novelId)
            val session = com.moe.starflow.library.LibrarySessions.current(context)
            val book = session.database.catalog().book("novel", novelId)
            putExtra("library_location", session.locationId)
            putExtra("library_book_uuid", book?.uuid)
            data = android.net.Uri.parse("starflow://library/${session.locationId}/novel/${book?.uuid}")
            putExtra(NovelReaderActivity.EXTRA_CHAPTER_INDEX, chapterIndex)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
