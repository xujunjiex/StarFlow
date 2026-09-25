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

    /**
     * 确保 [gguf] 已完成重打标（幂等）。判定看**文件内容**（还有没有 42 号张量），不看标记文件。
     *
     * ⚠️ 返回值不是「这个文件能不能加载」，只是「这次是否经手/确认了 STQ1_0」：
     * `false` 既可能是「普通量化（Q4_K_M 之类）本来就不需要」，也可能是「不支持」或解析失败。
     * 调用方要判断可加载性请用 [GgufQuantCheck.check]。
     *
     * @return true = 文件是 43 号（刚改写完，或已改好且标记齐备）
     */
    fun ensureRetagged(gguf: File): Boolean =
        ensureRetagged(gguf, GgufQuantCheck.analyze(gguf))

    /**
     * 同上，但复用调用方已经做过的头部扫描 [analysis]。
     *
     * 加载路径「先 check 再重打标」本是两次解析同一文件（头部要读整段元数据，含 12 万个
     * vocab token），所以那边改成 `analyze` + 本重载，一次搞定。
     *
     * `internal`：[GgufQuantCheck.Analysis] 本身是 internal，跨包调用方（下载流水线）走上面的
     * 一参版即可。
     */
    internal fun ensureRetagged(gguf: File, analysis: GgufQuantCheck.Analysis): Boolean {
        if (!gguf.isFile) {
            LogCollector.w(TAG, "ensureRetagged: 文件不存在 ${gguf.absolutePath}")
            return false
        }
        val marker = LlamaCppPaths.retagMarker(gguf)
        val marked = marker.isFile && marker.readText().isNotBlank()

        // ⚠️ 判定依据必须是「文件里还有没有 42 号张量」，**不能**是「标记文件在不在」。
        //    删掉预设模型再重新下载时，gguf 被换成未重打标的官方原件，而 `<file>.retagged`
        //    仍留在磁盘上（下载侧只删 gguf 与 .part）→ 凭标记跳过会把 42 号文件当成本引擎可读，
        //    加载出垃圾输出或直接失败。头部扫描顺带就有（调用方已算），所以不靠标记省这一步。
        val offsets = analysis.offsets
        val legacyOffsets = offsets.filter { it.second == TYPE_LEGACY_1_25BIT }
        // 解析失败（文件正在写、被截断）时 offsets 为空，与「解析成功且没有 42 号」长得一模一样，
        // 靠 compat==UNKNOWN 区分 —— 清残留标记只能在**确定结论**下做。
        val parsedDefinitively = analysis.compat != GgufQuantCheck.Compat.UNKNOWN

        /** 清掉「描述的是另一个文件」的残留标记，否则下载侧会拿它的 MD5 把当前文件判成损坏。 */
        fun clearStaleMarker() {
            if (!marked || !parsedDefinitively) return
            if (runCatching { marker.delete() }.getOrDefault(false)) {
                LogCollector.w(TAG, "ensureRetagged: 残留标记与文件不符，已清除（${marker.name}）")
            }
        }

        // 补写标记：文件已是 43 号但 `<file>.retagged` 没写成（改完瞬间进程被杀）。
        // 不补的话下载侧会拿官方 MD5 校验这个正确的文件、把它当损坏删掉。
        fun writeMarkerOnly(): Boolean = runCatching {
            val newMd5 = md5(gguf)
            marker.writeText(newMd5)
            LogCollector.d(TAG, "ensureRetagged: 已是 43 号但缺标记，补写 md5=$newMd5")
            true
        }.getOrElse { e ->
            LogCollector.e(TAG, "补写标记失败：${e.message}", e)
            false
        }

        if (legacyOffsets.isEmpty()) {
            if (offsets.any { it.second == TYPE_STQ1_0 }) {
                // 已是 43 号：标记齐备（说明上次改写完整落盘）就直接跳过，缺标记则补写
                if (marked) {
                    LogCollector.d(TAG, "ensureRetagged: 已是 43 号且标记齐备，跳过（${marker.name}）")
                    return true
                }
                return writeMarkerOnly()
            }
            // 既没有 42 也没有 43：本来就不需要重打标（Q4_K_M 等标准量化）。
            // 此时若还留着标记，它描述的是**另一个文件** → 清掉，否则下载侧会拿它的 MD5
            // 把当前文件判成损坏并删掉（删完重下 → 死循环）
            clearStaleMarker()
            LogCollector.d(TAG, "ensureRetagged: 无需重打标（${gguf.name}）")
            return false
        }

        // 有 42 号：先看布局，避免把上游 Q2_0（同样是 42 号，但 72 字节/256）改坏
        if (analysis.compat != GgufQuantCheck.Compat.NEEDS_RETAG) {
            if (analysis.compat == GgufQuantCheck.Compat.UNSUPPORTED) {
                LogCollector.w(TAG, "ensureRetagged: 量化类型不被当前引擎支持，不做改写")
            } else {
                LogCollector.d(TAG, "ensureRetagged: 42 号不是 STQ1_0 布局（compat=${analysis.compat}），不改写")
            }
            clearStaleMarker()
            return false
        }

        LogCollector.d(TAG, "ensureRetagged: 需改写 ${legacyOffsets.size} 个张量类型 42→43（${gguf.name}）")
        return runCatching {
            RandomAccessFile(gguf, "rw").use { raf ->
                legacyOffsets.forEach { (off, _) ->
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
