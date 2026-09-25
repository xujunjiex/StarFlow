package com.moe.starflow.llamacpp

import com.moe.starflow.utils.LogCollector
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * GGUF 量化类型重打标：把内置 Hy-MT2 1.25-bit 模型里的张量类型 `42` 改写成 `43`。
 *
 * 背景（详见 `patches/README.md`）：
 *  - 腾讯官方分发的 `Hy-MT2-1.8B-1.25Bit.gguf` 用私有量化类型，文件里 224 个权重张量写的是 **42**；
 *  - 我们现在的 llama.cpp 基线是官方仓库 + PR #22836（`STQ1_0` 放在 **43**），
 *    因为官方 master 已经把 42 分配给了自己的 `Q2_0`；
 *  - 所以 42 号文件在 43 号引擎上会被当成 `Q2_0` 解析 → 必须改写文件里的类型号。
 *
 * 安全性：
 *  - 只改「tensor info 里的 4 字节类型字段」，不动张量数据；幂等（已是 43 就跳过）；
 *  - 改完写 `<file>.retagged`（内容是重打标后的 MD5）作为标记与校验依据；
 *  - 失败/中断时标记不会写，下次进入会重新扫描并补齐未改的条目（半改状态可自愈）。
 */
object GgufTypeRetag {

    private const val TAG = "GgufTypeRetag"

    /** 腾讯 1.25-bit（STQ1_0）在官方分发的文件里占用的类型号 */
    const val TYPE_LEGACY_1_25BIT = 42

    /** 我们引擎里 STQ1_0 的类型号（与官方 Q2_0 错开） */
    const val TYPE_STQ1_0 = 43

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian
    private const val MAX_METADATA_HEADER = 64L * 1024 * 1024 // 元数据区上限保护（正常几 MB）

    /**
     * 确保 [gguf] 已完成重打标。
     *
     * @return true = 文件已是（或刚被改成）43 号；false = 不需要改（没有 42 号张量）或失败
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
        val offsets = runCatching { findTypeFieldOffsets(gguf) }.getOrElse { e ->
            LogCollector.e(TAG, "解析 GGUF 头失败：${e.message}", e)
            return false
        }
        val legacy = offsets.filter { it.second == TYPE_LEGACY_1_25BIT }
        if (legacy.isEmpty()) {
            // 已经没有 42 号了。这里要区分两种情况：
            //  a) 普通 GGUF（本来就没有 42 号）→ 什么都不做；
            //  b) **已经打过标但标记没写成**（改完瞬间进程被杀）→ 必须补写标记，
            //     否则重新校验时会拿清单里的旧 MD5 比对，把这个正确的文件当损坏删掉。
            val alreadyPatched = offsets.any { it.second == TYPE_STQ1_0 }
            if (alreadyPatched) {
                runCatching {
                    val md5 = md5(gguf)
                    marker.writeText(md5)
                    LogCollector.d(TAG, "ensureRetagged: 已是 43 号但缺标记，补写 md5=$md5")
                }.onFailure { LogCollector.e(TAG, "补写标记失败：${it.message}", it) }
                return true
            }
            LogCollector.d(TAG, "ensureRetagged: 无 42 号张量（普通 GGUF），不打标")
            return false
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
     *
     * GGUF 布局：magic(4) version(u32) tensor_count(u64) kv_count(u64) → 元数据 KV → tensor infos。
     * tensor info = name(string) n_dims(u32) dims(u64*n) type(u32) offset(u64)
     *
     * ⚠️ GGUF 所有多字节字段都是**小端**，而 `RandomAccessFile.readInt/readLong` 是**大端** ——
     *    必须显式反转字节，否则类型号/长度全都会读错（回归守卫见 GgufTypeRetagTest）。
     */
    fun findTypeFieldOffsets(gguf: File): List<Pair<Long, Int>> {
        RandomAccessFile(gguf, "r").use { raf ->
            if (raf.length() < 24) error("文件过小")
            raf.seek(0)
            val magic = readU32Le(raf)
            require(magic == GGUF_MAGIC) { "不是 GGUF 文件（magic=0x${Integer.toHexString(magic)}）" }
            val version = readU32Le(raf)
            require(version in 2..3) { "不支持的 GGUF 版本 $version" }
            val tensorCount = readU64Le(raf)
            val kvCount = readU64Le(raf)
            require(tensorCount in 0..1_000_000) { "tensor_count 异常：$tensorCount" }
            require(kvCount in 0..10_000_000) { "kv_count 异常：$kvCount" }

            skipMetadata(raf, kvCount)

            val out = ArrayList<Pair<Long, Int>>(tensorCount.toInt())
            repeat(tensorCount.toInt()) {
                readString(raf)                                       // name
                val nDims = readU32Le(raf)
                require(nDims in 0..8) { "tensor 维度异常：$nDims" }
                repeat(nDims) { readU64Le(raf) }                       // dims
                val typeOff = raf.filePointer
                val type = readU32Le(raf)
                readU64Le(raf)                                         // data offset
                out += typeOff to type
            }
            LogCollector.d(TAG, "GGUF v$version tensor=$tensorCount kv=$kvCount 扫描完成")
            return out
        }
    }

    // ── 小端读写辅助（GGUF 全程小端；RandomAccessFile 原生是大端）──
    private fun readU32Le(raf: RandomAccessFile): Int = Integer.reverseBytes(raf.readInt())
    private fun readU64Le(raf: RandomAccessFile): Long = java.lang.Long.reverseBytes(raf.readLong())

    private fun skipMetadata(raf: RandomAccessFile, kvCount: Long) {
        val started = raf.filePointer
        repeat(kvCount.toInt()) {
            readString(raf)                 // key
            val type = readU32Le(raf)
            skipValue(raf, type)
            if (raf.filePointer - started > MAX_METADATA_HEADER) error("元数据区超过保护上限")
        }
    }

    /** GGUF 值类型：0..12（见 gguf 规范） */
    private fun skipValue(raf: RandomAccessFile, type: Int) {
        when (type) {
            0, 1, 7 -> raf.skipBytes(1)          // u8 / i8 / bool
            2, 3 -> raf.skipBytes(2)             // u16 / i16
            4, 5, 6 -> raf.skipBytes(4)          // u32 / i32 / f32
            10, 11, 12 -> raf.skipBytes(8)       // u64 / i64 / f64
            8 -> readString(raf)                 // string
            9 -> {                               // array
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
            readU32Le(raf) == GGUF_MAGIC
        }
    }.getOrDefault(false)
}
