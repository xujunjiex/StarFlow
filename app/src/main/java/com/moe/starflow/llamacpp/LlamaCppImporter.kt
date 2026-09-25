package com.moe.starflow.llamacpp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 用户导入本地 GGUF 文件。
 *
 * 流程（每一步都先校验再落盘，避免"导入成功但用不了"）：
 *  1. 扩展名必须是 `.gguf`；
 *  2. 流式拷贝到 `<modelsDir>/<名字>.part`，同时算 MD5（拷贝期间可取消）；
 *  3. 校验 GGUF magic（防止把别的文件改名 .gguf 混进来）+ 剩余空间；
 *  4. 与清单里已有条目按 **MD5** 去重（同内容不重复占盘）；
 *  5. 重名时追加 ` (2)` 后缀，`.part` → 正式名 rename；
 *  6. 写入模型清单。
 *
 * 对比老方案（萌译只查扩展名、无去重、无空间检查）：这里多了 magic 校验 + MD5 去重 + 空间预检，
 * 且拷贝失败/取消会删掉 `.part`，不留半成品。
 */
object LlamaCppImporter {

    private const val TAG = "LlamaCppImporter"
    private const val PART_SUFFIX = ".part"
    private const val BUFFER = 64 * 1024

    /** 留出 5% 余量，避免刚好把外置存储写满 */
    private const val FREE_SPACE_MARGIN = 1.05

    /** 硬上限：超过这个大小直接拒绝（防止误选磁盘镜像之类把存储写爆） */
    private const val MAX_SIZE_BYTES = 12L * 1024 * 1024 * 1024

    sealed interface ImportResult {
        /** 导入成功 */
        data class Success(val model: LlamaCppModel, val retagged: Boolean) : ImportResult
        /** 同内容模型已在库中（按 MD5 命中） */
        data class Duplicate(val existing: LlamaCppModel) : ImportResult
        /** 校验失败/写入失败/空间不足 */
        data class Failed(val reason: String) : ImportResult
    }

    /**
     * @param onProgress 0..100（仅在能拿到源文件大小时回调）
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        onProgress: (Int) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        LlamaCppModelStore.ensureLoaded()
        val resolver = context.contentResolver

        // 1) 源文件元信息
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
            return@withContext ImportResult.Failed("只支持 .gguf 文件")
        }
        if (sizeHint > MAX_SIZE_BYTES) {
            return@withContext ImportResult.Failed("文件超过 ${MAX_SIZE_BYTES / 1024 / 1024 / 1024}GB 上限")
        }

        val modelsDir = LlamaCppPaths.modelsDir()
        // 2) 空间预检（拷贝要占一份等大的空间）
        if (sizeHint > 0 && modelsDir.usableSpace < (sizeHint * FREE_SPACE_MARGIN).toLong()) {
            return@withContext ImportResult.Failed(
                "存储空间不足：需要约 ${sizeHint / 1024 / 1024}MB，可用 ${modelsDir.usableSpace / 1024 / 1024}MB"
            )
        }

        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
        val partFile = File(modelsDir, "$safeName$PART_SUFFIX")
        if (partFile.exists()) partFile.delete()

        // 3) 流式拷贝 + MD5
        val md5 = runCatching {
            copyAndDigest(resolver.openInputStream(uri), partFile, sizeHint, onProgress)
        }.getOrElse { e ->
            partFile.delete()
            LogCollector.e(TAG, "导入拷贝失败：${e.message}", e)
            return@withContext ImportResult.Failed(e.message ?: "拷贝失败")
        }

        // 4) GGUF magic 校验
        if (!GgufTypeRetag.looksLikeGguf(partFile)) {
            partFile.delete()
            return@withContext ImportResult.Failed("文件不是有效的 GGUF（magic 校验失败）")
        }

        // 5) 按 MD5 去重
        LlamaCppModelStore.models.value.firstOrNull { it.md5 != null && it.md5.equals(md5, true) }?.let { dup ->
            partFile.delete()
            LogCollector.d(TAG, "导入去重命中：${dup.displayName}")
            return@withContext ImportResult.Duplicate(dup)
        }

        // 6) 重名处理 + 落地
        val finalName = collisionFreeName(modelsDir, safeName)
        val finalFile = File(modelsDir, finalName)
        if (!partFile.renameTo(finalFile)) {
            // rename 失败（跨设备/占用）时退化为复制
            runCatching { partFile.copyTo(finalFile, overwrite = true); partFile.delete() }
                .onFailure { e ->
                    partFile.delete()
                    return@withContext ImportResult.Failed("写入模型目录失败：${e.message}")
                }
        }

        // 7) 内置 1.25-bit 类型重打标（导入的若是同款 1.25-bit 文件，同样需要 42→43）
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
            hyProfile = false, // 实际通道在加载时由 nativeModelInfo 的 has_hy 决定
            params = LlamaCppParams.forSource(LlamaCppModelSource.IMPORTED),
        )
        LlamaCppModelStore.upsert(model)
        LogCollector.d(TAG, "导入完成：$finalName（${finalFile.length() / 1024 / 1024}MB, retag=$retagged）")
        ImportResult.Success(model, retagged)
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
        input: java.io.InputStream?,
        dst: File,
        sizeHint: Long,
        onProgress: (Int) -> Unit,
    ): String {
        if (input == null) throw IOException("无法读取所选文件")
        val md = java.security.MessageDigest.getInstance("MD5")
        var copied = 0L
        var lastPct = -1
        input.use { ins ->
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    coroutineContext.ensureActive() // 取消时抛 CancellationException，由上层删 .part
                    val read = ins.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    md.update(buf, 0, read)
                    copied += read
                    if (sizeHint > 0) {
                        val pct = ((copied * 100.0) / sizeHint).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
                out.fd.sync()
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
