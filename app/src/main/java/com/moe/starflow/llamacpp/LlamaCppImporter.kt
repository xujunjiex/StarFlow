package com.moe.starflow.llamacpp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.moe.starflow.R
import com.moe.starflow.download.ModelDownloadService
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 用户导入本地 GGUF 文件（**应用级后台任务**）。
 *
 * ⚠️ 为什么不是页面级协程：1~3GB 的模型拷贝要几十秒到几分钟，用户完全可能中途离开页面
 * （或旋转屏幕导致 Fragment 重建）。之前跑在 `viewLifecycleOwner.lifecycleScope` 里，
 * 一离开就取消，等于白拷一遍。现在跑在应用级 [scope]，页面只是**观察者**：
 *  - 进度通过 [progress]（StateFlow）暴露，回到页面能接着显示；
 *  - 完成后写模型清单 + Toast + 通知；
 *  - 只有用户显式 [cancel] 才会中止（并删掉 `.part`），离开页面不影响。
 *
 * 流程（每一步先校验再落盘）：
 *  1. 扩展名必须是 `.gguf`；
 *  2. 流式拷贝到 `<modelsDir>/<名字>.part`，同时算 MD5；
 *  3. 校验 GGUF magic + 剩余空间；
 *  4. 与清单已有条目按 **MD5** 去重；
 *  5. 重名追加 ` (2)`，`.part` → 正式名 rename；
 *  6. 1.25-bit 类型重打标（如需）+ 写入模型清单。
 */
object LlamaCppImporter {

    private const val TAG = "LlamaCppImporter"
    private const val PART_SUFFIX = ".part"
    private const val BUFFER = 64 * 1024
    private const val FREE_SPACE_MARGIN = 1.05
    private const val MAX_SIZE_BYTES = 12L * 1024 * 1024 * 1024
    private const val NOTIF_ID = 8801

    /** 应用级作用域：不随任何页面销毁而取消 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ImportProgress(val fileName: String, val percent: Int)

    private val _progress = MutableStateFlow<ImportProgress?>(null)

    /** 当前导入进度；null = 没有在导入 */
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()

    @Volatile private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    sealed interface ImportResult {
        data class Success(val model: LlamaCppModel, val retagged: Boolean) : ImportResult
        data class Duplicate(val existing: LlamaCppModel) : ImportResult
        data class Failed(val reason: String) : ImportResult
    }

    /**
     * 启动导入（**非阻塞**，立刻返回）。
     * @return true = 已开始；false = 已有任务在跑（调用方提示用户等待）
     */
    fun start(context: Context, uri: Uri): Boolean {
        if (isRunning()) {
            LogCollector.d(TAG, "已有导入任务在跑，忽略本次请求")
            return false
        }
        val app = context.applicationContext
        job = scope.launch {
            try {
                val result = importInternal(app, uri) { name, pct ->
                    _progress.value = ImportProgress(name, pct)
                }
                onFinished(app, result)
            } catch (ce: CancellationException) {
                // 用户主动取消：静默（.part 已在 importInternal 里删掉）
                LogCollector.d(TAG, "导入被取消")
                throw ce
            } catch (t: Throwable) {
                LogCollector.e(TAG, "导入异常：${t.message}", t)
                notify(app, app.getString(R.string.llamacpp_import_failed, t.message ?: ""))
                UiUtils.showToast(app, app.getString(R.string.llamacpp_import_failed, t.message ?: ""), isShort = false)
            } finally {
                _progress.value = null
            }
        }
        return true
    }

    /** 用户主动取消导入（离开页面**不**应调用这个）。 */
    fun cancel() {
        job?.cancel()
    }

    private fun onFinished(context: Context, result: ImportResult) {
        val msg = when (result) {
            is ImportResult.Success -> context.getString(R.string.llamacpp_import_completed, result.model.displayName)
            is ImportResult.Duplicate -> context.getString(R.string.llamacpp_import_duplicate, result.existing.displayName)
            is ImportResult.Failed -> context.getString(R.string.llamacpp_import_failed, result.reason)
        }
        LogCollector.d(TAG, "导入结束：$msg")
        UiUtils.showToast(context, msg, isShort = result is ImportResult.Success)
        notify(context, msg)
    }

    /** 完成/失败都用通知兜底：用户此时可能已经离开页面（后台 Toast 在 Android 12+ 会被限制）。 */
    private fun notify(context: Context, text: String) {
        // Android 13+ 需要 POST_NOTIFICATIONS；没有权限就只留 Toast，不抛异常
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            LogCollector.d(TAG, "无通知权限，跳过导入完成通知")
            return
        }
        runCatching {
            ensureChannel(context)
            val n = NotificationCompat.Builder(context, ModelDownloadService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(context.getString(R.string.llamacpp_import_notification_title))
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        }.onFailure { LogCollector.w(TAG, "发送导入通知失败：${it.message}") }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ModelDownloadService.CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ModelDownloadService.CHANNEL_ID,
                    context.getString(R.string.llamacpp_import_notification_title),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    // ───────────────────────── 实际导入 ─────────────────────────

    private suspend fun importInternal(
        context: Context,
        uri: Uri,
        onProgress: (name: String, pct: Int) -> Unit,
    ): ImportResult {
        LlamaCppModelStore.init(context)
        LlamaCppModelStore.ensureLoadedSync()
        val resolver = context.contentResolver

        var displayName = "imported.gguf"
        var sizeHint = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { displayName = it }
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) sizeHint = c.getLong(sizeIdx)
                }
            }
        }.onFailure { LogCollector.w(TAG, "读取 SAF 元信息失败：${it.message}") }

        if (!displayName.endsWith(".gguf", ignoreCase = true)) {
            return ImportResult.Failed(context.getString(R.string.llamacpp_not_gguf))
        }
        if (sizeHint > MAX_SIZE_BYTES) {
            return ImportResult.Failed("> ${MAX_SIZE_BYTES / 1024 / 1024 / 1024}GB")
        }

        val modelsDir = LlamaCppPaths.modelsDir()
        if (sizeHint > 0 && modelsDir.usableSpace < (sizeHint * FREE_SPACE_MARGIN).toLong()) {
            return ImportResult.Failed(
                "need ${sizeHint / 1024 / 1024}MB, free ${modelsDir.usableSpace / 1024 / 1024}MB"
            )
        }

        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
        val partFile = File(modelsDir, "$safeName$PART_SUFFIX")
        if (partFile.exists()) partFile.delete()

        val md5 = try {
            copyAndDigest(context, uri, partFile, sizeHint, safeName, onProgress)
        } catch (ce: CancellationException) {
            partFile.delete()   // 用户取消：不留半成品
            throw ce
        } catch (e: Exception) {
            partFile.delete()
            LogCollector.e(TAG, "导入拷贝失败：${e.message}", e)
            return ImportResult.Failed(e.message ?: "copy failed")
        }

        if (!GgufTypeRetag.looksLikeGguf(partFile)) {
            partFile.delete()
            return ImportResult.Failed("invalid GGUF header")
        }

        // 量化兼容性校验：读不了的量化（如腾讯 2-bit 私有格式）在**导入时**就拦下并说明原因，
        // 而不是等用户切过去、加载到一半再报错
        when (GgufQuantCheck.check(partFile)) {
            GgufQuantCheck.Compat.UNSUPPORTED -> {
                partFile.delete()
                LogCollector.w(TAG, "导入被拒：量化类型不被当前引擎支持（${safeName}）")
                return ImportResult.Failed(context.getString(R.string.llamacpp_error_unsupported_quant))
            }
            else -> Unit
        }

        LlamaCppModelStore.models.value.firstOrNull { it.md5 != null && it.md5.equals(md5, true) }?.let { dup ->
            partFile.delete()
            return ImportResult.Duplicate(dup)
        }

        val finalName = collisionFreeName(modelsDir, safeName)
        val finalFile = File(modelsDir, finalName)
        if (!partFile.renameTo(finalFile)) {
            runCatching { partFile.copyTo(finalFile, overwrite = true); partFile.delete() }
                .onFailure { e ->
                    partFile.delete()
                    return ImportResult.Failed(e.message ?: "rename failed")
                }
        }

        val retagged = GgufTypeRetag.ensureRetagged(finalFile)
        val model = LlamaCppModel(
            id = "imported:$finalName",
            displayName = finalName,
            fileName = finalName,
            sizeBytes = finalFile.length(),
            md5 = md5,
            retaggedMd5 = GgufTypeRetag.retaggedMd5(finalFile),
            source = LlamaCppModelSource.IMPORTED,
            builtinModelKey = null,
            hyProfile = false, // 实际走哪条通道由加载时的 nativeModelInfo(has_hy) 决定
            params = LlamaCppParams.forSource(LlamaCppModelSource.IMPORTED),
        )
        LlamaCppModelStore.upsert(model)
        return ImportResult.Success(model, retagged)
    }

    /** 同名文件已存在时追加 ` (2)`、` (3)`…（与下载流水线风格一致）。 */
    private fun collisionFreeName(dir: File, desired: String): String {
        if (!File(dir, desired).exists()) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (!File(dir, candidate).exists()) return candidate
            i++
        }
    }

    private suspend fun copyAndDigest(
        context: Context,
        uri: Uri,
        dst: File,
        sizeHint: Long,
        displayName: String,
        onProgress: (name: String, pct: Int) -> Unit,
    ): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open input stream")
        val md = MessageDigest.getInstance("MD5")
        var copied = 0L
        var lastPct = -1
        input.use { ins ->
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()   // 取消点：用户 cancel() 时抛出
                    val read = ins.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    md.update(buf, 0, read)
                    copied += read
                    if (sizeHint > 0) {
                        val pct = ((copied * 100.0) / sizeHint).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(displayName, pct)
                        }
                    }
                }
                out.fd.sync()
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
