package com.moe.starflow.translate.batch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector

/**
 * **翻译进度通知通道**的唯一创建入口（整章任务的 `TranslationJobService` 与阅读器队列的
 * [ReaderTranslationNotify] 及模型下载共用同一条）。
 *
 * ⚠️ **必须两边都调 [ensure]**：Android 8+ 往一个**不存在的通道**发通知是**静默丢弃**
 * （logcat 里只有一句 `No Channel found for pkg=...`）。阅读器队列那条通知**不经过前台服务**，
 * 所以用户只用自动/增量、从没点过「翻译本章」时，通道根本没人建 —— 通知也就永远不出现。
 * 这正是"两条路各自 ensureChannel"之外的第三种漏法。
 *
 * ⚠️ **重要度必须是 `IMPORTANCE_DEFAULT`**：旧通道 `chapter_translation` 建的时候是 `LOW`，
 * 而 Android 的**通道重要度一旦创建就不能由 App 改**（用户说了算）→ LOW 在 MIUI 上既不亮
 * 状态栏图标、也容易被折叠成"不重要通知"，用户报的「整章翻译没看到通知」就是它。
 * 所以这里换了新 id 并把旧通道删掉（一次性、幂等）。换 id 时**别忘了同步改
 * `ReaderTranslationNotifyTest` 里的断言**。
 */
object TranslationNotifyChannel {

    private const val TAG = "TranslationNotifyChannel"

    /** 当前通道 id（`TranslationJobService.CHANNEL_ID` 与它必须一致）。 */
    const val ID = "translation_progress"

    /** 旧通道 id（只用来删；LOW 重要度改不回来，只能换 id）。 */
    private const val LEGACY_ID = "chapter_translation"

    /** 幂等重建；系统保留用户设置，不以进程缓存掩盖缺失的通道。 */
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            ID,
            context.getString(R.string.translate_notify_channel_name),
            // DEFAULT（不是 LOW）：亮状态栏图标 —— 用户口径「看不到通知」多半就是 LOW 被折叠
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setShowBadge(false)
        channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        channel.setSound(null, null)
        channel.enableVibration(false)
        runCatching { nm.createNotificationChannel(channel) }
            .onFailure { LogCollector.w(TAG, "创建翻译通知通道失败: ${it.message}") }
        // 旧通道（LOW）删掉：同一个功能挂两个通道只会让人以为通知坏了
        runCatching { nm.deleteNotificationChannel(LEGACY_ID) }
        // 下载与章节处理复用已有通道，不新增需要用户分别配置的通知类别。
        runCatching { nm.deleteNotificationChannel("model_download") }
    }
}
