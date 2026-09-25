package com.moe.starflow.llamacpp

import com.moe.starflow.utils.LogCollector
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * GGUF 量化类型重打标：把**腾讯 1.25-bit 私有编号**的文件里的张量类型 `42` 改写成 `43`。
 *
 * 背景（详见 `patches/README.md`）：
 *  - 官方分发的 `Hy-MT2-1.8B-1.25Bit.gguf` 里 224 个权重张量写的是 **42**，
 *    实际布局 = `block_stq1_0`（每 256 值 42 字节）；
 *  - 上游 master 把 42 分配给了自己的 `Q2_0`（每 256 值 72 字节），我们的引擎按上游编号，
 *    `STQ1_0` 落在 **43** → 必须把这类文件的 42 改成 43 才能读。
 *
 * ⚠️ **绝不能见到 42 就改**：真正的上游 `Q2_0` 文件也是 42，改了就把好模型改坏。
 *    所以改写前先做 [GgufQuantCheck] 的布局校验，只有判定为 [GgufQuantCheck.Compat.NEEDS_RETAG]
 *    （即「只有把 42 当作 STQ1_0 才能对上文件的实际大小」）才动手。
 *
 * 安全性：
 *  - 只改 tensor info 里的 4 字节类型字段，不动张量数据；幂等；
 *  - 改完写 `<file>.retagged`（内容为重打标后的 MD5）作为标记与校验依据；
 *  - 中途失败不会写标记，下次进入会重新扫描补齐，半改状态可自愈；
 *  - 「已改成 43 但标记没写成」的情况会补写标记（否则重新校验会拿旧 MD5 把文件当损坏删掉）。
 */
object GgufTypeRetag {

    private const val TAG = "GgufTypeRetag"

    /** 腾讯 1.25-bit（STQ1_0）在官方分发的文件里占用的类型号 */
    const val TYPE_LEGACY_1_25BIT = 42

    /** 我们引擎里 STQ1_0 的类型号（与官方 Q2_0 错开） */
    const val TYPE_STQ1_0 = 43

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian
    private const val MAX_METADATA_HEADER = 64L * 1024 * 1024

    /**
     * 确保 [gguf] 已完成重打标。
     *
     * @return true = 文件已是（或刚被改成）本引擎可读的 43 号
     */
    fun ensureRetagged(gguf: File): Boolean {
        if (!gguf.isFile) {
            LogCollector.w(TAG, "ensureRetagged: 文件不存在 ${gguf.absolutePath}")
            return false
        }
        val marker = LlamaCppPaths.retagMarker(gguf)
        if (marker.isFile && marker.readText().isNotBlank()) {
            LogCollector.d(TAG, "ensureRetagged: 已有标记，跳过（${marker.name}）")
            return true
        }

        // 先校验布局，避免把上游 Q2_0（同样是 42 号）的文件改坏
        val compat = GgufQuantCheck.check(gguf)
        if (compat == GgufQuantCheck.Compat.UNSUPPORTED) {
            LogCollector.w(TAG, "ensureRetagged: 量化类型不被当前引擎支持，不做改写")
            return false
        }

        val offsets = runCatching { findTypeFieldOffsets(gguf) }.getOrElse { e ->
            LogCollector.e(TAG, "解析 GGUF 头失败：${e.message}", e)
            return false
        }
        val legacy = offsets.filter { it.second == TYPE_LEGACY_1_25BIT }

        // 补写标记的公共分支：文件已是 43 号但 `<file>.retagged` 没写成（改完瞬间进程被杀），
        // 不补的话重新校验会拿清单里的旧 MD5 比对，把这个正确的文件当损坏删掉。
        fun writeMarkerOnly(): Boolean {
            runCatching {
                val md5 = md5(gguf)
                marker.writeText(md5)
                LogCollector.d(TAG, "ensureRetagged: 已是 43 号但缺标记，补写 md5=$md5")
            }.onFailure { LogCollector.e(TAG, "补写标记失败：${it.message}", it) }
            return true
        }

        if (compat != GgufQuantCheck.Compat.NEEDS_RETAG) {
            // 布局说明不需要重打标（普通 GGUF，或本就是 43 号）：只在「已是 43 号且缺标记」时补标记
            if (legacy.isEmpty() && offsets.any { it.second == TYPE_STQ1_0 }) return writeMarkerOnly()
            LogCollector.d(TAG, "ensureRetagged: 无需重打标（compat=$compat）")
            return false
        }

        if (legacy.isEmpty()) {
            // 极端情况：校验说需要重打标，但扫描不到 42 号（解析差异）→ 当作已改好补标记
            return if (offsets.any { it.second == TYPE_STQ1_0 }) writeMarkerOnly() else false
        }

        LogCollector.d(TAG, "ensureRetagged: 需改写 ${legacy.size} 个张量类型 42→43（${gguf.name}）")
        return runCatching {
            RandomAccessFile(gguf, "rw").use { raf ->
                legacy.forEach { (off, _) ->
                    raf.seek(off)
                    raf.write(byteArrayOf(TYPE_STQ1_0.toByte(), 0, 0, 0)) // 小端 uint32
                }
                raf.fd.sync()
            }
            val newMd5 = md5(gguf)
            marker.writeText(newMd5)
            LogCollector.d(TAG, "ensureRetagged: 完成，retagged md5=$newMd5")
            true
        }.getOrElse { e ->
            LogCollector.e(TAG, "重打标失败：${e.message}", e)
            false
        }
    }

    /** 重打标后的 MD5（无标记返回 null）。 */
    fun retaggedMd5(gguf: File): String? =
        LlamaCppPaths.retagMarker(gguf).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    /**
     * 扫描 GGUF 头部，返回每个 tensor info 里「类型字段」的文件偏移与实际类型值。
     * （实现委托给 [GgufQuantCheck.scan]，只保留一份解析器。）
     */
    fun findTypeFieldOffsets(gguf: File): List<Pair<Long, Int>> =
        GgufQuantCheck.scan(gguf).tensors.map { it.typeFieldOffset to it.type }

    /** 8192 缓冲的流式 MD5（与 ChecksumHelper 一致：小写 hex）。 */
    fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 仅校验文件头是不是 GGUF（导入时用，避免把任意文件改名 .gguf 就入库）。 */
    fun looksLikeGguf(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 24) return@use false
            java.lang.Integer.reverseBytes(raf.readInt()) == GGUF_MAGIC
        }
    }.getOrDefault(false)
}
