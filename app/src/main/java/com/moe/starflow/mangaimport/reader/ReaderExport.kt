package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import com.moe.starflow.mangaimport.translate.ReaderTranslationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 打包下载的文件命名规则（纯函数，单测覆盖）。
 *
 * 三种下载共用一条原则：**沿用原压缩包/目录里的原名与序号**，绝不按「第几个已翻译页」重新编号 ——
 * 原包 1..10 页只翻了 1/2/5/6，导出的就是 `001/002/005/006`，而不是 `1/2/3/4`。
 */
internal object ExportNaming {

    /** 译文文件统一 JPEG（体积最小），扩展名固定 `.jpg`。 */
    const val EXT = ".jpg"

    /** 双语包里译文文件名的后缀。 */
    const val TRANSLATED_SUFFIX = "_译文"

    /** 只含译文的包：`ch1/005.png` → `ch1/005.jpg`；`005` → `005.jpg`。 */
    fun translatedEntry(key: String): String = dirOf(key) + stemOf(key) + EXT

    /** 双语包里的译文条目：`ch1/005.png` → `ch1/005_译文.jpg`。 */
    fun translatedPairEntry(key: String): String = dirOf(key) + stemOf(key) + TRANSLATED_SUFFIX + EXT

    /** 双语包里的原文条目：**原名原样**（含原扩展名——原包叫 `005.png` 就还是 `005.png`）。 */
    fun originalPairEntry(key: String): String = key

    private fun dirOf(key: String): String {
        val slash = key.lastIndexOf('/')
        return if (slash >= 0) key.substring(0, slash + 1) else ""
    }

    private fun stemOf(key: String): String {
        val name = key.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        // dot > 0：`.hidden`（点在开头）没有主干之分，整名当主干
        return if (dot > 0) name.substring(0, dot) else name
    }
}

/**
 * 阅读器打包导出（在 IO 线程执行，产出临时 zip，落盘由调用方走 MediaStore）。
 *
 * ⚠️ 文件名里**保留原目录前缀**：zip 里出现重名条目会让 `ZipOutputStream.putNextEntry` 抛
 * `ZipException: duplicate entry`（子目录漫画 `ch1/005.jpg` 与 `ch2/005.jpg` 会撞），整个导出直接失败。
 */
internal object ReaderExport {

    /** 导出 JPEG 质量（与微信/网盘场景一致：肉眼无损、体积最小）。 */
    private const val JPEG_QUALITY = 95

    /**
     * 导出**已翻译页**到 [tempFile]。
     *
     * [both] = true → 双语包：每页两个条目（原文原名 + `_译文.jpg`）；false → 只出译文。
     * 未翻译/翻译失败的页不导出（所以包内页数比原包少，但序号仍是原序号）。
     *
     * @param onProgress 每完成一页回调一次（**主线程**），参数为 (已完成, 总数)。
     * @return 无已翻译页 → false；导出异常 → false（临时文件由调用方删除）。
     */
    suspend fun exportTranslated(
        source: ReaderPageSource,
        controller: ReaderTranslationController,
        both: Boolean,
        tempFile: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val pages = controller.translatedPages().sorted()
        if (pages.isEmpty()) return@withContext false
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zip ->
                var done = 0
                for (page in pages) {
                    val key = source.key(page) ?: continue
                    // 双语包先放原文：原样搬运字节，不做解码再编码（避免 JPEG 二次有损压缩）
                    if (both && !copyOriginal(source, zip, page, ExportNaming.originalPairEntry(key))) continue
                    val entryName = if (both) ExportNaming.translatedPairEntry(key)
                    else ExportNaming.translatedEntry(key)
                    val bmp = controller.renderForExport(page) ?: continue
                    try {
                        zip.putNextEntry(ZipEntry(entryName))
                        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, zip)
                        zip.closeEntry()
                    } finally {
                        bmp.recycle() // 渲染产物是独立副本（renderOverlay 内部 copy），用完即回收
                    }
                    done++
                    withContext(Dispatchers.Main) { onProgress(done, pages.size) }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 把原始条目字节原样写入 zip。 */
    private fun copyOriginal(
        source: ReaderPageSource,
        zip: ZipOutputStream,
        page: Int,
        entryName: String,
    ): Boolean = try {
        source.openEntry(page)?.use { input ->
            zip.putNextEntry(ZipEntry(entryName))
            input.copyTo(zip)
            zip.closeEntry()
            true
        } ?: false
    } catch (e: Exception) {
        false
    }
}
