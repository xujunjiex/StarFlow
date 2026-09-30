package com.moe.starflow.mangaimport.data

import android.system.OsConstants.S_ISDIR
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * RAR / 7z 解压（libarchive JNI）。
 *
 * 为什么需要它：Android 没有内置解 RAR 的能力，而 `.cbr` 就是 RAR（多数还是 RAR5，
 * 纯 Java 的 junrar 支持不完整）。库与参考项目 Kototoro 同款
 * （`me.zhanghai.android.libarchive`，底层是 libarchive，顺带覆盖 7z/tar）。
 *
 * **设计：导入时一次性解压成目录**，而不是每次读页都去扫压缩包 ——
 * rar/7z 没有 zip 那样的中央目录，随机读一个条目要从头顺序扫，翻一页扫一遍整包不可接受；
 * 而漫画导入本来就是「复制进 app 目录」的策略，解压成目录后阅读链路与文件夹导入**完全同一条**
 * （连分章都白拿：解压保留了子目录结构）。
 *
 * 进度按**已消耗的压缩包字节数 / 包大小**算 —— 顺序解压没法预知总条目数，但字节数是实时的，
 * 能给出确定进度条（与 zip 复制那条链路观感一致）。
 */
object LibArchiveExtractor {

    private const val TAG = "LibArchiveExtract"
    private const val BUFFER_SIZE = 64 * 1024
    private const val REPORT_INTERVAL_MS = 80L

    /**
     * **解压炸弹防线**（包是用户从第三方拿来的，不能假定善意）。
     *
     * 之前这里**什么上限都没有**：不数条目、不累计字节、也不看剩余空间 ——
     * 一个精心构造的 rar/7z 能把 `/data` 直接填满（失败路径虽然会 `deleteRecursively`，
     * 但填满那一刻系统已经卡死了），而"几百万个空条目"也能把导入无限拖住。
     */
    /** 累计解压字节上限 = max(包大小 × 本系数, [MIN_TOTAL_BUDGET_BYTES])。正常漫画包倍率≈1。 */
    private const val MAX_EXPANSION_RATIO = 200L
    /** 上限的下限：小包也要给足空间，避免正常漫画被误拦。 */
    private const val MIN_TOTAL_BUDGET_BYTES = 512L * 1024 * 1024
    /** 条目数上限。 */
    private const val MAX_ENTRIES = 200_000
    /** 开工前要求的最小剩余空间（不足就直接拒绝，别等写满盘）。 */
    private const val MIN_FREE_BYTES = 50L * 1024 * 1024

    /**
     * 把 [archive] 解压到 [destDir]（保留包内相对路径），返回解压出的文件数。
     *
     * @param filter 只解压返回 true 的条目（漫画只取图片，缩略图/说明文本一律跳过省磁盘）
     * @param onProgress (已消耗字节, 包总大小)，在 IO 线程回调
     */
    suspend fun extract(
        archive: File,
        destDir: File,
        filter: (String) -> Boolean = { true },
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        val totalBytes = archive.length()
        destDir.mkdirs()
        // ⚠️ 先看剩余空间（见上面的「解压炸弹防线」）：不够就当面拒绝，
        //    而不是写到一半把 /data 填满（那时系统层面已经很难恢复了）
        val freeBytes = runCatching { destDir.usableSpace }.getOrDefault(0L)
        val freeNeeded = maxOf(totalBytes, MIN_FREE_BYTES)
        if (freeBytes in 1 until freeNeeded) {
            LogCollector.e(
                TAG,
                "剩余空间不足，拒绝解压: 可用 ${freeBytes / 1024 / 1024}MB，" +
                    "需要至少 ${freeNeeded / 1024 / 1024}MB（${archive.name}）",
            )
            error("存储空间不足，无法解压 ${archive.name}")
        }
        val expansionBudget = maxOf(totalBytes * MAX_EXPANSION_RATIO, MIN_TOTAL_BUDGET_BYTES)
        val rootPath = destDir.canonicalPath
        val counting = CountingInputStream(archive.inputStream().buffered())
        var extracted = 0
        var entryCount = 0
        var writtenBytes = 0L
        var lastReport = 0L
        var handle = 0L
        try {
            handle = open(counting)
            var entry: Long
            while (Archive.readNextHeader(handle).also { entry = it } != 0L) {
                coroutineContext.ensureActive()
                if (++entryCount > MAX_ENTRIES) {
                    LogCollector.e(TAG, "条目数超过上限 $MAX_ENTRIES，判定为异常包: ${archive.name}")
                    error("压缩包条目过多，已中止（${archive.name}）")
                }
                val name = entryName(entry) ?: continue
                val relative = name.replace('\\', '/').trimStart('/')
                if (relative.isEmpty()) continue
                val target = File(destDir, relative)
                // 防路径穿越：包内条目不许写到目标目录之外
                // ⚠️ 必须带**路径分隔符**比较：裸前缀比较会放过 `../pages_evil/x.jpg`
                // （canonicalPath = `<...>/pages_evil/x.jpg`，而 rootPath = `<...>/pages` → startsWith 为真）
                if (!target.canonicalPath.startsWith(rootPath + File.separator)) {
                    LogCollector.w(TAG, "跳过越界条目: $relative")
                    continue
                }
                if (stat(entry).isDir()) {
                    if (!target.exists()) target.mkdirs()
                    // 目录条目没有数据段，但要继续读下一个 header
                    drainData(handle, null)
                    continue
                }
                if (!filter(relative)) {
                    drainData(handle, null)
                    continue
                }
                target.parentFile?.mkdirs()
                val written = FileOutputStream(target).use { output -> drainData(handle, output) }
                writtenBytes += written
                if (writtenBytes > expansionBudget) {
                    LogCollector.e(
                        TAG,
                        "解压总量超过上限 ${expansionBudget / 1024 / 1024}MB" +
                            "（包 ${totalBytes / 1024 / 1024}MB），判定为解压炸弹: ${archive.name}",
                    )
                    error("解压后体积异常，已中止（${archive.name}）")
                }
                extracted++
                val now = System.currentTimeMillis()
                if (now - lastReport >= REPORT_INTERVAL_MS) {
                    lastReport = now
                    onProgress(counting.count, totalBytes)
                }
            }
            onProgress(counting.count, totalBytes)
            if (extracted == 0) LogCollector.w(TAG, "包内没有可解压的图片: ${archive.name}")
            extracted
        } finally {
            if (handle != 0L) runCatching { Archive.free(handle) }
            runCatching { counting.close() }
        }
    }

    /** 建一个 libarchive 读句柄：全格式全过滤器 + UTF-8 文件名优先。 */
    private fun open(input: InputStream): Long {
        val archive = Archive.readNew()
        var ok = false
        try {
            Archive.setCharset(archive, StandardCharsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readSetCallbackData(archive, null)
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            Archive.readSetReadCallback<Any?>(archive) { _, _ ->
                buffer.clear()
                val n = input.read(buffer.array())
                if (n == -1) {
                    null
                } else {
                    buffer.limit(n)
                    buffer
                }
            }
            Archive.readSetSkipCallback<Any?>(archive) { _, _, request -> input.skip(request) }
            Archive.readOpen1(archive)
            ok = true
            return archive
        } finally {
            if (!ok) runCatching { Archive.free(archive) }
        }
    }

    /**
     * 读干一个条目的数据段。[output] 为 null 时只丢弃（跳过的条目也必须读完，
     * 否则下一个 `readNextHeader` 会读到上一个条目的数据里）。
     *
     * @return 实际写出去的字节数（调用方累计它来防解压炸弹）
     */
    private suspend fun drainData(handle: Long, output: java.io.OutputStream?): Long {
        var written = 0L
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            Archive.readData(handle, buffer)
            buffer.flip()
            if (!buffer.hasRemaining()) break
            if (output != null) {
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                output.write(bytes)
                written += bytes.size
            }
            buffer.clear()
        }
        return written
    }

    private fun stat(entry: Long) = ArchiveEntry.stat(entry)

    private fun ArchiveEntry.StructStat.isDir(): Boolean = S_ISDIR(stMode)

    /**
     * 条目名：优先 libarchive 给的 UTF-8 名（RAR5 / 7z 都有）；拿不到就按原始字节解 ——
     * 先试严格 UTF-8，失败退回 GBK（老 RAR4 里的中文名是本地代码页）。
     */
    private fun entryName(entry: Long): String? {
        ArchiveEntry.pathnameUtf8(entry)?.takeIf { it.isNotEmpty() }?.let { return it }
        val raw = ArchiveEntry.pathname(entry) ?: return null
        if (raw.isEmpty()) return null
        return decodeName(raw)
    }

    private fun decodeName(bytes: ByteArray): String {
        val strict = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            try {
                String(bytes, Charset.forName("GBK"))
            } catch (e2: Exception) {
                String(bytes, StandardCharsets.UTF_8)
            }
        }
    }

    /** 统计已从压缩包读走的字节数（进度用）。 */
    private class CountingInputStream(private val delegate: InputStream) : FilterInputStream(delegate) {
        var count: Long = 0L
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = delegate.skip(n).also { if (it > 0) count += it }
    }
}
