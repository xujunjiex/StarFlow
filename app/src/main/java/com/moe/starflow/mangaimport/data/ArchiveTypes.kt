package com.moe.starflow.mangaimport.data

import java.io.File

/** 漫画压缩包的实际格式（按**内容**判定，不看扩展名）。 */
enum class ArchiveKind {
    ZIP,
    RAR,
    SEVEN_ZIP,

    /**
     * PDF（漫画扫描件 / 电子版）。
     *
     * ⚠️ 它的处理方式与压缩包**相反**：zip 保留原包随机读、rar/7z 解压成目录，
     * 而 PDF **保留原文件 + 按需栅格化**（见 [PdfPageRenderer] / [PdfRasterPolicy]）——
     * pdfium 本来就支持按页随机渲染，导入时全量转图只会白占 2~3 倍空间。
     */
    PDF,

    /** 认不出来（损坏 / 不是压缩包 / 不支持的格式）。 */
    UNKNOWN
}

/**
 * 压缩包 / PDF 格式识别：**按魔数判定，不看扩展名**。
 *
 * ⚠️ 为什么不信扩展名：`.cbr` 里塞的常常是改名的 zip，`.zip` 里也可能是 rar；
 * 而两条读取链路完全不同（zip 走 `ZipFile` 随机读、rar/7z 走 libarchive 顺序解压），
 * 认错了就是「导入成功但一页都没有」或者「导入失败」。
 */
object ArchiveTypes {

    data class Inspection(val kind: ArchiveKind, val readFailure: Exception? = null)

    private const val ZIP_MAGIC = 0x50          // 'P'
    private val SEVEN_ZIP_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

    /**
     * 一次性读这么多字节用于识别。
     *
     * ⚠️ 不能只读 8 字节去判 PDF：PDF 规范允许文件头**出现在前 1024 字节内的任意位置**
     * （有些工具会在前面塞 BOM / 注释 / 垃圾字节），只比对第 0 字节会把这类正常文件判成 UNKNOWN。
     */
    private const val SNIFF_BYTES = 1024

    fun detect(file: File): ArchiveKind = inspect(file).kind

    /** Like [detect], but preserves the actual file-open/read failure for directory scanners. */
    fun inspect(file: File): Inspection {
        val head = ByteArray(SNIFF_BYTES)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (e: Exception) {
            return Inspection(ArchiveKind.UNKNOWN, e)
        }
        if (read < 6) return Inspection(ArchiveKind.UNKNOWN)
        // zip：PK\x03\x04（普通）/ PK\x05\x06（空包）/ PK\x07\x08（跨卷）
        if (head[0].toInt() == ZIP_MAGIC && head[1].toInt() == 0x4B &&
            (head[2].toInt() == 0x03 || head[2].toInt() == 0x05 || head[2].toInt() == 0x07)
        ) {
            return Inspection(ArchiveKind.ZIP)
        }
        // rar：Rar!\x1a\x07（\x00 = RAR4，\x01\x00 = RAR5）
        if (head[0].toInt() == 0x52 && head[1].toInt() == 0x61 && head[2].toInt() == 0x72 &&
            head[3].toInt() == 0x21 && head[4].toInt() == 0x1A && head[5].toInt() == 0x07
        ) {
            return Inspection(ArchiveKind.RAR)
        }
        // 7z：7z\xBC\xAF\x27\x1C
        if (SEVEN_ZIP_MAGIC.indices.all { head[it] == SEVEN_ZIP_MAGIC[it] }) {
            return Inspection(ArchiveKind.SEVEN_ZIP)
        }
        // PDF：%PDF-（允许前面有最多 1KB 的杂字节）
        if (indexOf(head, read, PDF_MAGIC) >= 0) return Inspection(ArchiveKind.PDF)
        return Inspection(ArchiveKind.UNKNOWN)
    }

    private fun indexOf(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || length < needle.size) return -1
        outer@ for (start in 0..(length - needle.size)) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }

    /** 需要 libarchive 解压成目录的格式（zip 走 `ZipFile` 随机读、PDF 按需栅格化，都不解压）。 */
    fun needsExtraction(kind: ArchiveKind): Boolean =
        kind == ArchiveKind.RAR || kind == ArchiveKind.SEVEN_ZIP
}
