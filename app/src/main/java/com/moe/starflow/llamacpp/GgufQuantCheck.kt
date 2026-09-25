package com.moe.starflow.llamacpp

import com.moe.starflow.utils.LogCollector
import java.io.File
import java.io.RandomAccessFile

/**
 * GGUF 量化兼容性校验：**在导入和加载之前**判断这个文件当前引擎到底能不能读。
 *
 * 为什么需要它：GGUF 张量信息里只写了一个「量化类型号」，类型号的含义由引擎决定。
 * 腾讯给 Hy-MT2 出的几个私有量化，类型号和公版编号冲突/错位，例如：
 *
 * | 文件 | 张量类型号 | 实际每 256 值占用 | 公版里 40/42 号的含义 |
 * |---|---|---|---|
 * | Hy-MT2-1.8B-1.25Bit.gguf | 42 | **42 字节**（= block_stq1_0） | 42 = 上游 Q2_0（72 字节/256）→ 撞号 |
 * | Hy-MT2-1.8B-2Bit.gguf | 40 | **65 字节**（第三种私有量化） | 40 = 上游 NVFP4（144 字节/256）→ 不是它 |
 * | Hy-MT2-1.8B-Q4_K_M.gguf | 12/14/0 | 标准量化 | 标准，直接可用 |
 *
 * 校验方式：按类型尺寸表算出「所有张量应该占多少字节」，与文件里数据区的实际大小对比。
 * 只有一种解释能对上时结论可信；对上的是「42 当 STQ1_0」就说明需要重打标（42→43）；
 * 一种都对不上 → 不支持的量化（例如上面那个 2-bit），**在导入时就明确告诉用户**，
 * 而不是等加载到一半报个看不懂的错、更不是把文件改坏。
 *
 * ⚠️ 类型尺寸表只登记有把握的类型；遇到未登记的类型一律返回 [Compat.UNKNOWN]（**放行**，
 *    交给 llama.cpp 自己判断），避免因为表不全而误拦好模型。
 */
internal object GgufQuantCheck {

    private const val TAG = "GgufQuantCheck"
    private const val GGUF_MAGIC = 0x46554747
    private const val MAX_METADATA_HEADER = 64L * 1024 * 1024

    /** 量化类型号 → (每块元素数, 每块字节数)。取自 llama.cpp `type_traits`（含 PR #22836 的 STQ1_0）。 */
    private val TYPE_TRAITS: Map<Int, Pair<Int, Int>> = mapOf(
        0 to (1 to 4),        // F32
        1 to (1 to 2),        // F16
        2 to (32 to 18),      // Q4_0
        3 to (32 to 20),      // Q4_1
        6 to (32 to 22),      // Q5_0
        7 to (32 to 24),      // Q5_1
        8 to (32 to 34),      // Q8_0
        9 to (32 to 40),      // Q8_1
        10 to (256 to 84),    // Q2_K
        11 to (256 to 110),   // Q3_K
        12 to (256 to 144),   // Q4_K
        13 to (256 to 176),   // Q5_K
        14 to (256 to 210),   // Q6_K
        15 to (256 to 292),   // Q8_K
        28 to (1 to 8),       // F64
        30 to (1 to 2),       // BF16
        39 to (32 to 17),     // MXFP4
        40 to (64 to 36),     // NVFP4（上游）
        42 to (64 to 18),     // Q2_0（上游，PR #19357）
        43 to (256 to 42),    // STQ1_0（PR #22836；也是 1.25-bit 私有量化重打标后的号）
    )

    enum class Compat {
        /** 按文件里声明的类型号就能读 */
        SUPPORTED,

        /** 文件里的 42 号其实是 STQ1_0（腾讯 1.25-bit 私有编号）→ 需要 42→43 重打标 */
        NEEDS_RETAG,

        /** 布局对不上任何已知解释：当前引擎读不了（例如腾讯 2-bit 私有量化） */
        UNSUPPORTED,

        /** 含未登记类型 / 解析失败 → 不拦，交给引擎自己判断 */
        UNKNOWN,
    }

    data class TensorSpan(
        val typeFieldOffset: Long,
        val type: Int,
        val rowLen: Long,
        val rows: Long,
        val dataOffset: Long,
    ) {
        val elements: Long get() = rowLen * rows
    }

    data class Scan(
        val dataStart: Long,
        val fileSize: Long,
        val tensors: List<TensorSpan>,
        val alignment: Long,
    ) {
        val dataBytes: Long get() = fileSize - dataStart
    }

    // ───────────────────────── 对外 ─────────────────────────

    fun check(file: File): Compat {
        val scan = runCatching { scan(file) }.getOrElse { e ->
            LogCollector.w(TAG, "解析 GGUF 头失败，放行交给引擎：${e.message}")
            return Compat.UNKNOWN
        }
        if (scan.tensors.isEmpty()) return Compat.UNKNOWN

        // 用「张量数据偏移链」判定，而不是总字节数：GGUF 写入时每个张量按 alignment 对齐，
        // 偏移链能零容差地验证「类型号 → 块大小」这一解释对不对（总字节数法在小文件上需要容差，不可靠）。
        when (layoutConsistent(scan, retag = false)) {
            Layout.OK -> return Compat.SUPPORTED
            Layout.UNKNOWN_TYPE -> return Compat.UNKNOWN
            Layout.MISMATCH -> Unit
        }
        val has42 = scan.tensors.any { it.type == GgufTypeRetag.TYPE_LEGACY_1_25BIT }
        if (has42 && layoutConsistent(scan, retag = true) == Layout.OK) {
            LogCollector.d(TAG, "量化校验：判定为 1.25-bit 私有编号（需重打标 42→43）")
            return Compat.NEEDS_RETAG
        }
        LogCollector.w(TAG, "量化校验：偏移链对不上任何已知解释 → 不支持的量化类型")
        return Compat.UNSUPPORTED
    }

    private enum class Layout { OK, MISMATCH, UNKNOWN_TYPE }

    /**
     * 按 [retag] 指定的解释逐张量核对数据偏移链：
     * 排序后第一个张量必须从 0 开始，之后每个 = 上一个偏移 + 上一个大小，再按 alignment 对齐；
     * 末尾允许 alignment 以内的填充。
     */
    private fun layoutConsistent(scan: Scan, retag: Boolean): Layout {
        val align = if (scan.alignment in 1..4096) scan.alignment else 32L
        val sorted = scan.tensors.sortedBy { it.dataOffset }
        var pos = 0L
        sorted.forEachIndexed { i, t ->
            if (t.dataOffset != pos) return Layout.MISMATCH
            val type = if (retag && t.type == GgufTypeRetag.TYPE_LEGACY_1_25BIT) {
                GgufTypeRetag.TYPE_STQ1_0
            } else t.type
            val (blck, tsize) = TYPE_TRAITS[type] ?: return Layout.UNKNOWN_TYPE
            if (blck <= 0 || t.rowLen <= 0 || t.rowLen % blck != 0L) return Layout.MISMATCH
            pos += (t.rowLen / blck) * tsize * t.rows
            // 只有「还有下一个张量」时才需要按 alignment 对齐；最后一个不补，末尾填充另行容忍
            if (i != sorted.lastIndex) pos = ((pos + align - 1) / align) * align
        }
        val tail = scan.dataBytes - pos
        return if (tail in 0..align) Layout.OK else Layout.MISMATCH
    }

    fun scan(file: File): Scan {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 24) error("文件过小")
            raf.seek(0)
            val magic = readU32Le(raf)
            require(magic == GGUF_MAGIC) { "不是 GGUF（magic=0x${Integer.toHexString(magic)}）" }
            val version = readU32Le(raf)
            require(version in 2..3) { "不支持的 GGUF 版本 $version" }
            val tensorCount = readU64Le(raf)
            val kvCount = readU64Le(raf)
            require(tensorCount in 0..1_000_000) { "tensor_count 异常：$tensorCount" }
            require(kvCount in 0..10_000_000) { "kv_count 异常：$kvCount" }

            val alignment = skipMetadata(raf, kvCount)

            val out = ArrayList<TensorSpan>(tensorCount.toInt())
            repeat(tensorCount.toInt()) {
                readString(raf)                                    // name
                val nDims = readU32Le(raf)
                require(nDims in 0..8) { "tensor 维度异常：$nDims" }
                val dims = LongArray(nDims) { readU64Le(raf) }
                val typeOff = raf.filePointer
                val type = readU32Le(raf)
                val dataOffset = readU64Le(raf)                     // 相对数据区起点
                val rowLen = if (nDims > 0) dims[0] else 0L
                val rows = if (nDims > 1) dims.drop(1).fold(1L) { a, b -> a * b } else 1L
                out += TensorSpan(typeOff, type, rowLen, rows, dataOffset)
            }
            // 数据区按 alignment 对齐（GGUF 规范，默认 32）
            val pos = raf.filePointer
            val align = if (alignment in 1..4096) alignment else 32L
            val dataStart = pos + ((align - (pos % align)) % align)
            return Scan(dataStart, raf.length(), out, align)
        }
    }

    // ───────────────────────── 内部 ─────────────────────────

    // ── 小端读写（GGUF 全程小端；RandomAccessFile 原生大端）──
    private fun readU32Le(raf: RandomAccessFile): Int = Integer.reverseBytes(raf.readInt())
    private fun readU64Le(raf: RandomAccessFile): Long = java.lang.Long.reverseBytes(raf.readLong())

    /** 跳过元数据；顺便取出 `general.alignment`（默认 32）。 */
    private fun skipMetadata(raf: RandomAccessFile, kvCount: Long): Long {
        val started = raf.filePointer
        var alignment = 32L
        repeat(kvCount.toInt()) {
            val key = readString(raf)
            val type = readU32Le(raf)
            if (key == "general.alignment" && type == 4) {
                alignment = readU32Le(raf).toLong() and 0xFFFFFFFFL
            } else {
                skipValue(raf, type)
            }
            if (raf.filePointer - started > MAX_METADATA_HEADER) error("元数据区超过保护上限")
        }
        return alignment
    }

    private fun skipValue(raf: RandomAccessFile, type: Int) {
        when (type) {
            0, 1, 7 -> raf.skipBytes(1)
            2, 3 -> raf.skipBytes(2)
            4, 5, 6 -> raf.skipBytes(4)
            10, 11, 12 -> raf.skipBytes(8)
            8 -> readString(raf)
            9 -> {
                val elemType = readU32Le(raf)
                val n = readU64Le(raf)
                require(n in 0..100_000_000) { "数组长度异常：$n" }
                repeat(n.toInt()) { skipValue(raf, elemType) }
            }
            else -> error("未知 GGUF 值类型 $type")
        }
    }

    private fun readString(raf: RandomAccessFile): String {
        val len = readU64Le(raf)
        require(len in 0..MAX_METADATA_HEADER) { "字符串长度异常：$len" }
        val bytes = ByteArray(len.toInt())
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
