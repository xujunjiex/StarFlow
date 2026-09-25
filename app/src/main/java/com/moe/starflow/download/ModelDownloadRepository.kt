package com.moe.starflow.download
import com.moe.starflow.translate.widget.*

import android.content.Context
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ModelDownloadRepository private constructor(private val context: Context) {

    private val stateMutex = Mutex()
    private val stateMap = ConcurrentHashMap<ModelKey, DownloadState>()
    private val modelListCached = MutableStateFlow<List<ModelInfo>>(emptyList())

    private val _snapshot = MutableStateFlow(DownloadSnapshot(emptyMap(), emptySet()))
    fun observe(): StateFlow<DownloadSnapshot> = _snapshot.asStateFlow()

    data class DownloadSnapshot(
        val states: Map<ModelKey, DownloadState>,
        val activeNotifications: Set<ModelKey>
    )

    fun getState(modelKey: ModelKey): DownloadState = stateMap[modelKey] ?: DownloadState.Idle

    fun getModelInfo(modelKey: ModelKey): ModelInfo? =
        modelListCached.value.firstOrNull { it.modelKey == modelKey }

    fun getBrowserUrl(modelKey: ModelKey): String? =
        getModelInfo(modelKey)?.browserUrl

    /**
     * 检查模型是否已完整下载（所有文件都有非空 target）。
     * 用于替代旧的 Download_NLLB 布尔标记（新下载架构不再写该标记）。
     */
    fun isFullyDownloaded(modelKey: ModelKey): Boolean {
        val info = getModelInfo(modelKey) ?: return false
        val baseDir = baseDirFor(modelKey)
        return info.files.all { fileInfo ->
            val t = File(baseDir, fileInfo.fileName)
            t.exists() && t.length() > 0
        }
    }

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            stateMutex.withLock {
                for (key in ModelKey.values()) {
                    stateMap[key] = computeStateFromDisk(key)
                }
                emitSnapshot()
            }
        }
    }

    /**
     * 从磁盘重新计算单个模型的状态。
     *
     * 用于页面进入时识别「目录里已有文件但状态还是 Idle/Partial」的模型：
     * 例如下载完成后状态未更新、或文件由外部放入。Running/Paused/Done 保持原样不打断。
     */
    suspend fun refreshFromDisk(modelKey: ModelKey) = updateState(modelKey) {
        val current = stateMap[modelKey]
        if (current is DownloadState.Running || current is DownloadState.Paused || current is DownloadState.Done) {
            current
        } else {
            computeStateFromDisk(modelKey)
        }
    }

    /**
     * 根据磁盘上的 target/.part 文件计算模型状态（Idle / Partial / Done）。
     *
     * ⚠️ 多文件模型必须**所有文件**都有完整 target 才算 Done，不能只检查第一个文件
     * （否则只下载了第一个文件就被误判为「已下载」——曾导致强退后 NLLB 显示已完成）。
     */
    private fun computeStateFromDisk(modelKey: ModelKey): DownloadState {
        val info = getModelInfo(modelKey)
        val files = info?.files.orEmpty()
        val baseDir = baseDirFor(modelKey)

        if (files.isNotEmpty()) {
            val allComplete = files.all { fileInfo ->
                val t = File(baseDir, fileInfo.fileName)
                t.exists() && t.length() > 0
            }
            if (allComplete) return DownloadState.Done

            // 没有任何文件被下载（无 target 也无 .part）→ Idle
            val anyPartial = files.any { fileInfo ->
                val t = File(baseDir, fileInfo.fileName)
                val p = File(baseDir, fileInfo.fileName + ".part")
                (t.exists() && t.length() > 0) || (p.exists() && p.length() > 0)
            }
            if (!anyPartial) return DownloadState.Idle

            // 部分完成：用 computeFileProgress 计算进度（含 .part）
            val p = computeFileProgress(modelKey)
                ?: return DownloadState.Partial(0L, 0L, 0, 1, "", 0L, 0L)
            return DownloadState.Partial(
                p.downloadedBytes, p.totalBytes,
                p.currentFileIndex, p.currentFileCount, p.currentFileName,
                p.currentFileBytesDownloaded, p.currentFileTotalBytes
            )
        }

        // 无模型信息时退回只检查第一个文件（旧行为）
        val targetFile = targetFileFor(modelKey)
        val partFile = partFileFor(modelKey)
        return when {
            targetFile.exists() && targetFile.length() > 0 -> DownloadState.Done
            partFile.exists() && partFile.length() > 0 -> DownloadState.Partial(
                partFile.length(), 0L, 0, 1, "", partFile.length(), 0L
            )
            else -> DownloadState.Idle
        }
    }

    suspend fun markRunning(
        modelKey: ModelKey,
        currentFileIndex: Int = 0,
        currentFileCount: Int = 1,
        currentFileName: String = "",
        bytesDownloaded: Long = 0
    ) = updateState(modelKey) {
        val info = getModelInfo(modelKey)
        val totalBytes = info?.files?.sumOf { it.fileSize } ?: 0L
        val currentFileTotal = info?.files?.getOrNull(currentFileIndex)?.fileSize ?: 0L
        DownloadState.Running(
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            speedBytesPerSec = 0,
            currentFileIndex = currentFileIndex,
            currentFileCount = currentFileCount,
            currentFileName = currentFileName,
            currentFileProgress = 0,
            currentFileBytesDownloaded = 0,
            currentFileTotalBytes = currentFileTotal
        )
    }

    suspend fun markPartial(modelKey: ModelKey) = updateState(modelKey) {
        val p = computeFileProgress(modelKey)
            ?: return@updateState DownloadState.Partial(0L, 0L, 0, 1, "", 0L, 0L)
        DownloadState.Partial(
            p.downloadedBytes, p.totalBytes,
            p.currentFileIndex, p.currentFileCount, p.currentFileName,
            p.currentFileBytesDownloaded, p.currentFileTotalBytes
        )
    }

    suspend fun markPaused(modelKey: ModelKey) = updateState(modelKey) {
        val p = computeFileProgress(modelKey)
            ?: return@updateState DownloadState.Paused(0L, 0L, 0, 1, "", 0L, 0L)
        DownloadState.Paused(
            p.downloadedBytes, p.totalBytes,
            p.currentFileIndex, p.currentFileCount, p.currentFileName,
            p.currentFileBytesDownloaded, p.currentFileTotalBytes
        )
    }

    suspend fun markDone(modelKey: ModelKey) = updateState(modelKey) { DownloadState.Done }

    private data class FileProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val currentFileIndex: Int,
        val currentFileCount: Int,
        val currentFileName: String,
        val currentFileBytesDownloaded: Long,
        val currentFileTotalBytes: Long
    )

    /**
     * 计算模型当前下载进度（含 .part 文件）。
     *
     * 已完成的目标文件计入 completedBytes；第一个「未完成」的文件（target 不存在，
     * 但有 .part 或尚未开始）作为当前文件返回。返回 null 当 modelInfo 不存在或 files 为空。
     */
    private fun computeFileProgress(modelKey: ModelKey): FileProgress? {
        val info = getModelInfo(modelKey) ?: return null
        val files = info.files
        if (files.isEmpty()) return null
        val baseDir = targetFileFor(modelKey).parentFile
        val totalBytes = files.sumOf { it.fileSize }
        var completedBytes = 0L

        for ((i, fileInfo) in files.withIndex()) {
            val target = File(baseDir, fileInfo.fileName)
            val part = File(baseDir, fileInfo.fileName + ".part")
            if (target.exists() && target.length() > 0) {
                completedBytes += target.length()
            } else {
                val partBytes = if (part.exists() && part.length() > 0) part.length() else 0L
                completedBytes += partBytes
                return FileProgress(
                    downloadedBytes = completedBytes,
                    totalBytes = totalBytes,
                    currentFileIndex = i,
                    currentFileCount = files.size,
                    currentFileName = fileInfo.fileName,
                    currentFileBytesDownloaded = partBytes,
                    currentFileTotalBytes = fileInfo.fileSize
                )
            }
        }
        // 全部完成（Paused/Partial 理论不会出现，防御）
        return FileProgress(
            downloadedBytes = completedBytes,
            totalBytes = totalBytes,
            currentFileIndex = files.size - 1,
            currentFileCount = files.size,
            currentFileName = files.last().fileName,
            currentFileBytesDownloaded = 0L,
            currentFileTotalBytes = files.last().fileSize
        )
    }

    suspend fun updateProgress(
        modelKey: ModelKey,
        bytes: Long,
        total: Long,
        speed: Long,
        currentFileIndex: Int,
        currentFileCount: Int,
        currentFileName: String,
        currentFileProgress: Int,
        currentFileBytesDownloaded: Long,
        currentFileTotalBytes: Long
    ) = updateState(modelKey) {
        // 取消/暂停后，迟到的 onProgress 回调不应把 Paused 复活成 Running
        val current = stateMap[modelKey]
        if (current !is DownloadState.Running) {
            current ?: DownloadState.Idle
        } else {
            DownloadState.Running(
                bytesDownloaded = bytes,
                totalBytes = total,
                speedBytesPerSec = speed,
                currentFileIndex = currentFileIndex,
                currentFileCount = currentFileCount,
                currentFileName = currentFileName,
                currentFileProgress = currentFileProgress,
                currentFileBytesDownloaded = currentFileBytesDownloaded,
                currentFileTotalBytes = currentFileTotalBytes
            )
        }
    }

    suspend fun cancelDownload(modelKey: ModelKey) = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            val info = getModelInfo(modelKey) ?: return@withContext
            queuedKeys.remove(modelKey)  // 取消排队中的下载，避免当前完成后又被自动重启
            for (fileInfo in info.files) {
                val partFile = File(targetFileFor(modelKey).parentFile, fileInfo.fileName + ".part")
                if (partFile.exists()) {
                    partFile.delete()
                    LogCollector.d(TAG, "Cancelled: deleted .part for ${fileInfo.fileName}")
                }
            }
            stateMap[modelKey] = DownloadState.Idle
            emitSnapshot()
        }
    }

    suspend fun deleteDownload(modelKey: ModelKey) = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            val info = getModelInfo(modelKey) ?: return@withContext
            queuedKeys.remove(modelKey)
            for (fileInfo in info.files) {
                val target = File(targetFileFor(modelKey).parentFile, fileInfo.fileName)
                if (target.exists()) target.delete()
                val part = File(targetFileFor(modelKey).parentFile, fileInfo.fileName + ".part")
                if (part.exists()) part.delete()
                // ⚠️ 必须一并删掉重打标标记 `<file>.retagged`：它记的是**重打标后**的 MD5，
                //    留着的话下次下载完 verifyFile 会拿它校验刚下好的官方原件 → 判损坏 → 删 →
                //    重下 → 死循环；即便侥幸过了，ensureRetagged 也会以为文件已改好而跳过改写。
                com.moe.starflow.llamacpp.LlamaCppPaths.retagMarker(target).delete()
            }
            stateMap[modelKey] = DownloadState.Idle
            emitSnapshot()
        }
    }

    /** Test-only: clear all state */
    fun clearAll() {
        stateMap.clear()
    }

    /**
     * Load model metadata from the bundled assets JSON.
     * Must be called after getInstance() and before any download.
     */
    suspend fun loadModelList() {
        val json = context.assets.open("models/downloadinfo.json")
            .bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(json)
        val modelsArray = root.getJSONArray("models")
        val models = mutableListOf<ModelInfo>()
        for (i in 0 until modelsArray.length()) {
            val modelObj = modelsArray.getJSONObject(i)
            val keyStr = modelObj.optString("model_key", "")
            val modelKey = try {
                ModelKey.valueOf(keyStr)
            } catch (e: IllegalArgumentException) {
                LogCollector.w(TAG, "Unknown model_key: $keyStr, skip")
                continue
            }
            val browserUrl = modelObj.optString("browser_url", "")
            val filesArray = modelObj.getJSONArray("files")
            val files = mutableListOf<FileInfo>()
            for (j in 0 until filesArray.length()) {
                val fileObj = filesArray.getJSONObject(j)
                val fileName = fileObj.optString("file_name", "")
                if (fileName.isEmpty()) continue
                files.add(FileInfo(
                    fileName = fileName,
                    downloadUrl = fileObj.optString("download_url", ""),
                    fileSize = fileObj.optLong("file_size", 0L),
                    checksum = fileObj.optString("checksum", "")
                ))
            }
            models.add(ModelInfo(modelKey, browserUrl, files))
        }
        modelListCached.value = models
        LogCollector.d(TAG, "loadModelList: loaded ${models.size} models")
    }

    private suspend fun updateState(modelKey: ModelKey, transform: () -> DownloadState) {
        stateMutex.withLock {
            stateMap[modelKey] = transform()
            emitSnapshot()
        }
    }

    private fun emitSnapshot() {
        _snapshot.value = DownloadSnapshot(
            stateMap.toMap(),
            stateMap.filter { it.value is DownloadState.Running }.keys.toSet()
        )
    }

    private val queuedKeys = ConcurrentHashMap.newKeySet<ModelKey>()

    /**
     * 如果该 modelKey 当前不是 Running，将其加入待处理队列。
     * 返回 true 表示需要调用方启动下载；返回 false 表示已在下载中（已自动入队，当前完成后会再次启动）。
     */
    suspend fun enqueueDownload(modelKey: ModelKey): Boolean {
        stateMutex.withLock {
            val current = stateMap[modelKey]
            return if (current is DownloadState.Running) {
                queuedKeys.add(modelKey)
                false
            } else {
                true
            }
        }
    }

    fun dequeueNext(): ModelKey? {
        // 跳过已取消/已删除（Idle）的排队项，避免自动重启被取消的下载
        val iterator = queuedKeys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (stateMap[key] is DownloadState.Idle) {
                iterator.remove()
                continue
            }
            iterator.remove()
            return key
        }
        return null
    }

    /**
     * Single-file models return the expected target file path.
     * Multi-file models return a sentinel path (Use fileName from FileInfo instead).
     */
    private fun targetFileFor(modelKey: ModelKey): File {
        val baseDir = baseDirFor(modelKey)
        if (!baseDir.exists()) baseDir.mkdirs()
        val firstName = getModelInfo(modelKey)?.files?.firstOrNull()?.fileName ?: "model.onnx"
        return File(baseDir, firstName)
    }

    private fun partFileFor(modelKey: ModelKey): File {
        val t = targetFileFor(modelKey)
        return File(t.parentFile, t.name + ".part")
    }

    private fun baseDirFor(modelKey: ModelKey): File = when (modelKey) {
        ModelKey.NLLB_GROUP -> File(context.getExternalFilesDir(null), "models")
        ModelKey.HY_MT2_GROUP -> File(context.getExternalFilesDir(null), "models")
        ModelKey.HY_MT2_Q4_KM -> File(context.getExternalFilesDir(null), "models")
        ModelKey.MANGA_OCR_GROUP -> File(context.getExternalFilesDir(null), "manga_ocr_download")
        ModelKey.RT_DETR_V2 -> File(context.getExternalFilesDir(null), "rt_detr")
        ModelKey.PP_OCR_V6_MEDIUM_DET, ModelKey.PP_OCR_V6_MEDIUM_REC ->
            File(context.getExternalFilesDir(null), "ppocrv6")
        ModelKey.PP_OCR_V5_DET, ModelKey.PP_OCR_V5_REC_ZH,
        ModelKey.PP_OCR_V5_REC_EN, ModelKey.PP_OCR_V5_REC_KO,
        ModelKey.PP_OCR_V5_REC_RU -> File(context.getExternalFilesDir(null), "ppocrv5")
    }

    /** 返回某模型的目标文件（未下载时为期望路径；仅用于已完整下载的模型）。 */
    fun targetFilePath(modelKey: ModelKey): File = targetFileFor(modelKey)

    companion object {
        private const val TAG = "ModelDownloadRepo"

        @Volatile
        private var INSTANCE: ModelDownloadRepository? = null

        fun getInstance(context: Context): ModelDownloadRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModelDownloadRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
