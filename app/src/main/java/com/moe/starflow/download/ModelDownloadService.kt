package com.moe.starflow.download
import com.moe.starflow.translate.widget.*

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ModelDownloadService : LifecycleService() {

    private val activeJobs = ConcurrentHashMap<ModelKey, Job>()
    private val lifecycleMutex = Mutex()
    private val repo by lazy { ModelDownloadRepository.getInstance(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    private var isForegroundStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundIfNeeded(intent)

        val action = intent?.action ?: return START_NOT_STICKY
        val modelKey = intent.getStringExtra(EXTRA_MODEL_KEY)
            ?.let { ModelKey.valueOf(it) } ?: return START_NOT_STICKY

        when (action) {
            ACTION_START_DOWNLOAD -> {
                lifecycleScope.launch {
                    if (repo.enqueueDownload(modelKey)) {
                        startDownload(modelKey = modelKey, isResume = false)
                    } else {
                        LogCollector.d(TAG, "$modelKey 已在队列中，等待当前下载完成后自动启动")
                    }
                }
            }
            ACTION_RESUME_DOWNLOAD -> {
                lifecycleScope.launch {
                    if (repo.enqueueDownload(modelKey)) {
                        startDownload(modelKey = modelKey, isResume = true)
                    } else {
                        LogCollector.d(TAG, "$modelKey 已在队列中，等待当前下载完成后自动启动")
                    }
                }
            }
            ACTION_PAUSE_DOWNLOAD -> lifecycleScope.launch { pauseDownload(modelKey) }
            ACTION_CANCEL_DOWNLOAD -> lifecycleScope.launch { cancelDownload(modelKey) }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(modelKey: ModelKey, isResume: Boolean) {
        activeJobs.computeIfAbsent(modelKey) {
            lifecycleScope.launch(Dispatchers.IO) {
                val modelInfo = repo.getModelInfo(modelKey)
                val firstFileName = modelInfo?.files?.firstOrNull()?.fileName ?: ""
                val fileCount = modelInfo?.files?.size ?: 1
                repo.markRunning(
                    modelKey = modelKey,
                    currentFileIndex = 0,
                    currentFileCount = fileCount,
                    currentFileName = firstFileName
                )
                updateNotification(modelKey, repo.getState(modelKey))
                downloadModelInternal(modelKey, isResume)
            }
        }
    }

    private suspend fun pauseDownload(modelKey: ModelKey) {
        lifecycleMutex.withLock {
            activeJobs.remove(modelKey)?.cancel()
            repo.markPaused(modelKey)
            updateNotification(modelKey, repo.getState(modelKey))
            checkStopSelf()
        }
    }

    private suspend fun cancelDownload(modelKey: ModelKey) {
        lifecycleMutex.withLock {
            activeJobs.remove(modelKey)?.cancel()
            repo.cancelDownload(modelKey)
            clearNotification(modelKey)
            checkStopSelf()
        }
    }

    private fun checkStopSelf() {
        if (activeJobs.isEmpty()) stopSelf()
    }

    private fun startForegroundIfNeeded(intent: Intent?) {
        if (isForegroundStarted) return
        isForegroundStarted = true
        try {
            val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(getString(R.string.dl_notify_title))
                .setContentText(getString(R.string.dl_notify_preparing))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(NOTIFICATION_ID_SERVICE, placeholder)
        } catch (e: Exception) {
            LogCollector.e(TAG, "startForeground failed", e)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.dl_notify_title),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(modelKey: ModelKey, state: DownloadState) {

        val (title, text) = when (state) {
            is DownloadState.Running -> {
                val pct = if (state.totalBytes > 0) (state.bytesDownloaded * 100 / state.totalBytes).toInt() else 0
                val fileLabel = if (state.currentFileCount > 1) {
                    "${state.currentFileIndex + 1}/${state.currentFileCount}: ${state.currentFileProgress}%"
                } else {
                    "$pct%"
                }
                getString(R.string.dl_downloading, modelKey.displayName(), fileLabel) to
                    "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)} · ${formatSpeed(state.speedBytesPerSec)}"
            }
            is DownloadState.Paused -> getString(R.string.dl_paused, modelKey.displayName()) to
                formatPausedOrPartialBody(
                    state.currentFileCount,
                    state.currentFileIndex,
                    state.currentFileBytesDownloaded,
                    state.currentFileTotalBytes,
                    state.bytesDownloaded,
                    state.totalBytes
                )
            is DownloadState.Partial -> getString(R.string.dl_partial, modelKey.displayName()) to
                formatPausedOrPartialBody(
                    state.currentFileCount,
                    state.currentFileIndex,
                    state.currentFileBytesDownloaded,
                    state.currentFileTotalBytes,
                    state.bytesDownloaded,
                    state.totalBytes
                )
            is DownloadState.Done -> getString(R.string.dl_done, modelKey.displayName()) to ""
            DownloadState.Idle -> "" to ""
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(state !is DownloadState.Done)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            androidx.core.app.NotificationManagerCompat.from(this).run {
                notify(NOTIFICATION_ID_BASE + modelKey.stableId, builder.build())
                notify(NOTIFICATION_ID_SERVICE, buildAggregateNotification())
            }
        } catch (e: SecurityException) {
            LogCollector.w(TAG, "通知权限缺失，无法更新通知: ${e.message}")
        }
    }

    private fun buildAggregateNotification(): android.app.Notification {
        val runningCount = activeJobs.size
        val firstRunning = activeJobs.keys.firstOrNull()
        val text = if (firstRunning != null) {
            getString(R.string.dl_multiple, firstRunning.displayName(), runningCount)
        } else {
            getString(R.string.dl_notify_title)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.dl_notify_title))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun clearNotification(modelKey: ModelKey) {
        try {
            androidx.core.app.NotificationManagerCompat.from(this).run {
                cancel(NOTIFICATION_ID_BASE + modelKey.stableId)
                notify(NOTIFICATION_ID_SERVICE, buildAggregateNotification())
            }
        } catch (e: SecurityException) {
            // 忽略
        }
    }

    private fun ModelKey.displayName(): String = when (this) {
        ModelKey.NLLB_GROUP -> "NLLB"
        ModelKey.HY_MT2_GROUP -> "Hy-MT2"
        ModelKey.MANGA_OCR_GROUP -> "manga-ocr"
        ModelKey.RT_DETR_V2 -> "RT-DETR-V2"
        ModelKey.PP_OCR_V5_DET -> "PP-OCRv5 DET"
        ModelKey.PP_OCR_V5_REC_ZH -> "PP-OCRv5 REC ZH"
        ModelKey.PP_OCR_V5_REC_EN -> "PP-OCRv5 REC EN"
        ModelKey.PP_OCR_V5_REC_KO -> "PP-OCRv5 REC KO"
        ModelKey.PP_OCR_V5_REC_RU -> "PP-OCRv5 REC RU"
        ModelKey.PP_OCR_V6_MEDIUM_DET -> "PP-OCRv6 DET (medium)"
        ModelKey.PP_OCR_V6_MEDIUM_REC -> "PP-OCRv6 REC (medium)"
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
    }

    private fun formatSpeed(bytesPerSec: Long): String =
        String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))

    /** 多文件显示当前文件进度（1/3: 50.0MB/100.0MB），单文件显示聚合进度 */
    private fun formatPausedOrPartialBody(
        currentFileCount: Int,
        currentFileIndex: Int,
        currentFileBytesDownloaded: Long,
        currentFileTotalBytes: Long,
        bytesDownloaded: Long,
        totalBytes: Long
    ): String = if (currentFileCount > 1) {
        "${currentFileIndex + 1}/${currentFileCount}: ${formatBytes(currentFileBytesDownloaded)}/${formatBytes(currentFileTotalBytes)}"
    } else {
        "${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
    }

    internal suspend fun downloadModelInternal(modelKey: ModelKey, isResume: Boolean) {
        try {
            val modelInfo = repo.getModelInfo(modelKey) ?: run {
                LogCollector.e(TAG, "ModelInfo not found: $modelKey")
                return
            }
            if (modelInfo.files.size == 1) {
                downloadSingleFile(modelKey, modelInfo.files[0])
            } else {
                downloadMultiFile(modelKey, modelInfo.files)
            }
        } catch (e: CancellationException) {
            // propagate
        } finally {
            activeJobs.remove(modelKey)
            clearNotification(modelKey)
            val next = repo.dequeueNext()
            if (next != null) {
                LogCollector.d(TAG, "启动队列中的下一个下载: $next")
                startDownload(modelKey = next, isResume = true)
            }
            checkStopSelf()
        }
    }

    // -- Download Logic -- //

    private suspend fun downloadSingleFile(
        modelKey: ModelKey,
        fileInfo: FileInfo,
        fileIndex: Int = 0,
        fileCount: Int = 1,
        priorBytes: Long = 0L,
        totalBytes: Long = fileInfo.fileSize
    ) {
        val destFile = targetFileFor(modelKey, fileInfo.fileName)
        if (verifyFile(destFile, fileInfo) == VerifyResult.COMPLETE) {
            LogCollector.d(TAG, "${fileInfo.fileName} 已下载且校验通过，跳过")
            // ⚠️ 调用方 startDownload 已经写了 Running，这里直接 return 会让状态**永久卡在「下载中 0%」**
            // （refreshFromDisk 有意保留 Running/Paused，不会自愈）。文件既然校验通过，就是 Done。
            // 多文件由 downloadMultiFile 在全部文件完成后统一 markDone，这里只处理单文件。
            if (fileCount == 1) {
                repo.markDone(modelKey)
                updateNotification(modelKey, repo.getState(modelKey))
                UiUtils.showToast(this, getString(R.string.model_downloaded), isShort = true)
            }
            return
        }

        repo.markRunning(
            modelKey = modelKey,
            currentFileIndex = fileIndex,
            currentFileCount = fileCount,
            currentFileName = fileInfo.fileName,
            bytesDownloaded = priorBytes
        )
        updateNotification(modelKey, repo.getState(modelKey))

        val result = ModelDownloadManager.downloadModel(
            context = applicationContext,
            url = fileInfo.downloadUrl,
            checksum = fileInfo.checksum,
            destFile = destFile,
            onProgress = object : ModelDownloadManager.ProgressCallback {
                override fun onProgress(bytesRead: Long, fileTotalBytes: Long, speed: Float) {
                    // bytesRead / fileTotalBytes 是当前文件的进度；totalBytes 是整体总大小
                    val currentFilePct = if (fileTotalBytes > 0) (bytesRead * 100 / fileTotalBytes).toInt() else 0
                    val aggregateBytes = priorBytes + bytesRead
                    lifecycleScope.launch {
                        repo.updateProgress(
                            modelKey = modelKey,
                            bytes = aggregateBytes,
                            total = totalBytes,
                            speed = (speed * 1024 * 1024).toLong(),
                            currentFileIndex = fileIndex,
                            currentFileCount = fileCount,
                            currentFileName = fileInfo.fileName,
                            currentFileProgress = currentFilePct,
                            currentFileBytesDownloaded = bytesRead,
                            currentFileTotalBytes = fileTotalBytes
                        )
                        updateNotification(modelKey, repo.getState(modelKey))
                    }
                }
            }
        )

        if (result.isSuccess) {
            // downloadModel 内部已按服务器 Content-Length + MD5 校验，文件有效。
            // 本地 GGUF 再多一步：1.25-bit 类型重打标（42→43，幂等）
            retagIfGguf(destFile)
            if (fileCount == 1) {
                // 单文件模型：下载完成立即标记 Done。
                // 否则状态会停在 Running，只有重启 initialize() 才能识别成 Done。
                repo.markDone(modelKey)
                updateNotification(modelKey, repo.getState(modelKey))
                UiUtils.showToast(this, getString(R.string.model_download_success), isShort = false)
            }
            // 多文件模型由 downloadMultiFile 在全部文件完成后统一 markDone
        } else {
            repo.markPartial(modelKey)
            updateNotification(modelKey, repo.getState(modelKey))
        }
    }

    private suspend fun downloadMultiFile(
        modelKey: ModelKey,
        files: List<FileInfo>
    ) {
        var completedBytes = 0L
        val totalBytes = files.sumOf { it.fileSize }
        val fileCount = files.size

        for ((index, fileInfo) in files.withIndex()) {
            if (!kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive!!) return

            val destFile = targetFileFor(modelKey, fileInfo.fileName)
            if (verifyFile(destFile, fileInfo) == VerifyResult.COMPLETE) {
                LogCollector.d(TAG, "${fileInfo.fileName} 已下载且校验通过，跳过")
                completedBytes += destFile.length()
                continue
            }

            downloadSingleFile(
                modelKey = modelKey,
                fileInfo = fileInfo,
                fileIndex = index,
                fileCount = fileCount,
                priorBytes = completedBytes,
                totalBytes = totalBytes
            )

            val state = repo.getState(modelKey)
            if (state !is DownloadState.Running && state !is DownloadState.Paused) {
                return
            }

            if (verifyFile(destFile, fileInfo) == VerifyResult.COMPLETE) {
                completedBytes += destFile.length()
            } else {
                return
            }
        }

        val allComplete = files.all { verifyFile(targetFileFor(modelKey, it.fileName), it) == VerifyResult.COMPLETE }
        if (allComplete) {
            repo.markDone(modelKey)
            updateNotification(modelKey, repo.getState(modelKey))
            UiUtils.showToast(this, getString(R.string.model_download_success), isShort = false)
        } else {
            LogCollector.e(TAG, "整体完整性检查失败：部分文件损坏")
            repo.markPartial(modelKey)
            updateNotification(modelKey, repo.getState(modelKey))
        }
    }

    /**
     * 校验已下载文件。
     *
     * 以 MD5 校验和为准，**不按 fileSize 精确匹配**：JSON 里的 file_size 可能过时或与
     * 服务器实际文件不一致（曾导致已下载完成的文件被误删、重复下载死循环）。
     * 下载本身（ModelDownloadManager）已经按服务器 Content-Length 校验过完整性，
     * 所以这里只需确认文件非空 + 校验和一致（有校验和时）。
     */
    private fun verifyFile(file: File, fileInfo: FileInfo): VerifyResult {
        if (!file.exists()) return VerifyResult.MISSING
        if (file.length() <= 0) {
            file.delete()
            return VerifyResult.DAMAGED
        }
        // ⚠️ 本地 GGUF 会被「1.25-bit 类型重打标」改写（张量类型 42→43，见 patches/README.md），
        //    文件 MD5 随之改变：有 <file>.retagged 标记时按标记里的 MD5 校验，
        //    否则会把已经改好的模型误判成 DAMAGED 并删掉（然后重复下载 → 死循环）。
        val retaggedMd5 = com.moe.starflow.llamacpp.GgufTypeRetag.retaggedMd5(file)
        val expected = retaggedMd5 ?: fileInfo.checksum
        if (expected.isNotEmpty() && !ChecksumHelper.verifyChecksum(file, expected)) {
            file.delete()
            return VerifyResult.DAMAGED
        }
        return VerifyResult.COMPLETE
    }

    /**
     * GGUF 模型下载完成后做量化类型重打标（幂等；只有含 42 号张量的 1.25-bit 文件会被改写），
     * 并把新的 MD5 同步进 LlamaCpp 模型清单。
     */
    private fun retagIfGguf(file: File) {
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return
        if (!com.moe.starflow.llamacpp.GgufTypeRetag.ensureRetagged(file)) return
        val md5 = com.moe.starflow.llamacpp.GgufTypeRetag.retaggedMd5(file)
        LogCollector.d(TAG, "GGUF 重打标完成：${file.name} md5=$md5")
        if (md5 != null) {
            com.moe.starflow.llamacpp.LlamaCppModelStore.init(applicationContext)
            com.moe.starflow.llamacpp.LlamaCppModelStore.builtinHymt2()?.let { m ->
                if (m.fileName == file.name) {
                    com.moe.starflow.llamacpp.LlamaCppModelStore.markRetagged(m.id, md5)
                }
            }
        }
    }

    private fun targetFileFor(modelKey: ModelKey, fileName: String): File {
        val baseDir = baseDirFor(modelKey)
        if (!baseDir.exists()) baseDir.mkdirs()
        return File(baseDir, fileName)
    }

    private fun baseDirFor(modelKey: ModelKey): File = when (modelKey) {
        ModelKey.NLLB_GROUP -> File(applicationContext.getExternalFilesDir(null), "models")
        ModelKey.HY_MT2_GROUP -> File(applicationContext.getExternalFilesDir(null), "models")
        ModelKey.MANGA_OCR_GROUP -> File(applicationContext.getExternalFilesDir(null), "manga_ocr_download")
        ModelKey.RT_DETR_V2 -> File(applicationContext.getExternalFilesDir(null), "rt_detr")
        ModelKey.PP_OCR_V6_MEDIUM_DET, ModelKey.PP_OCR_V6_MEDIUM_REC ->
            File(applicationContext.getExternalFilesDir(null), "ppocrv6")
        ModelKey.PP_OCR_V5_DET, ModelKey.PP_OCR_V5_REC_ZH,
        ModelKey.PP_OCR_V5_REC_EN, ModelKey.PP_OCR_V5_REC_KO,
        ModelKey.PP_OCR_V5_REC_RU -> File(applicationContext.getExternalFilesDir(null), "ppocrv5")
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        const val ACTION_START_DOWNLOAD = "com.moe.starflow.action.START_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.moe.starflow.action.RESUME_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.moe.starflow.action.PAUSE_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.moe.starflow.action.CANCEL_DOWNLOAD"
        const val EXTRA_MODEL_KEY = "model_key"
        const val NOTIFICATION_ID_SERVICE = 9999
        const val NOTIFICATION_ID_BASE = 1000
        const val CHANNEL_ID = "model_download"

        fun startDownload(context: Context, modelKey: ModelKey, isResume: Boolean) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = if (isResume) ACTION_RESUME_DOWNLOAD else ACTION_START_DOWNLOAD
                putExtra(EXTRA_MODEL_KEY, modelKey.name)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                LogCollector.e(TAG, "Failed to start service", e)
            }
        }

        fun pauseDownload(context: Context, modelKey: ModelKey) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
                putExtra(EXTRA_MODEL_KEY, modelKey.name)
            }
            context.startService(intent)
        }

        fun cancelDownload(context: Context, modelKey: ModelKey) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_MODEL_KEY, modelKey.name)
            }
            context.startService(intent)
        }
    }
}