package com.moe.starflow.mangaimport.data

import java.io.File

/** 漫画压缩包的实际格式（按**内容**判定，不看扩展名）。 */
enum class ArchiveKind {
    ZIP,
    RAR,
    SEVEN_ZIP,

    /** 认不出来（损坏 / 不是压缩包 / 不支持的格式）。 */
    UNKNOWN
}

/**
 * 压缩包格式识别：**按魔数判定，不看扩展名**。
 *
 * ⚠️ 为什么不信扩展名：`.cbr` 里塞的常常是改名的 zip，`.zip` 里也可能是 rar；
 * 而两条读取链路完全不同（zip 走 `ZipFile` 随机读、rar/7z 走 libarchive 顺序解压），
 * 认错了就是「导入成功但一页都没有」或者「导入失败」。
 */
object ArchiveTypes {

    private const val ZIP_MAGIC = 0x50          // 'P'
    private val SEVEN_ZIP_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    fun detect(file: File): ArchiveKind {
        val head = ByteArray(8)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (e: Exception) {
            -1
        }
        if (read < 6) return ArchiveKind.UNKNOWN
        // zip：PK\x03\x04（普通）/ PK\x05\x06（空包）/ PK\x07\x08（跨卷）
        if (head[0].toInt() == ZIP_MAGIC && head[1].toInt() == 0x4B &&
            (head[2].toInt() == 0x03 || head[2].toInt() == 0x05 || head[2].toInt() == 0x07)
        ) {
            return ArchiveKind.ZIP
        }
        // rar：Rar!\x1a\x07（\x00 = RAR4，\x01\x00 = RAR5）
        if (head[0].toInt() == 0x52 && head[1].toInt() == 0x61 && head[2].toInt() == 0x72 &&
            head[3].toInt() == 0x21 && head[4].toInt() == 0x1A && head[5].toInt() == 0x07
        ) {
            return ArchiveKind.RAR
        }
        // 7z：7z\xBC\xAF\x27\x1C
        if (SEVEN_ZIP_MAGIC.indices.all { head[it] == SEVEN_ZIP_MAGIC[it] }) {
            return ArchiveKind.SEVEN_ZIP
        }
        return ArchiveKind.UNKNOWN
    }

    /** 需要 libarchive 解压成目录的格式（zip 不走这条路：`ZipFile` 支持随机读，不用解压）。 */
    fun needsExtraction(kind: ArchiveKind): Boolean =
        kind == ArchiveKind.RAR || kind == ArchiveKind.SEVEN_ZIP
}
