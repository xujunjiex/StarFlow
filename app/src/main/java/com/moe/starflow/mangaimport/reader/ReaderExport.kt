package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import com.moe.starflow.mangaimport.translate.ReaderTranslationController
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
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

    /** 只含译文的包：`ch1/005.png` → `ch1/005.jpg`；`005` → `005.jpg`。 */
    fun translatedEntry(key: String): String = dirOf(key) + stemOf(key) + EXT

    /** 双语包里的译文条目：`ch1/005.png` + `_译文` → `ch1/005_译文.jpg`（后缀随界面语言，见 strings）。 */
    fun translatedPairEntry(key: String, suffix: String): String =
        dirOf(key) + stemOf(key) + suffix + EXT

    /** 双语包里的原文条目：**原名原样**（含原扩展名——原包叫 `005.png` 就还是 `005.png`）。 */
    fun originalPairEntry(key: String): String = key

    /**
     * 去重后返回可用的条目名：撞名时在扩展名前追加 `_2`/`_3`…，并记进 [used]。
     *
     * ⚠️ **必须去重，否则整包导出失败**：`ZipOutputStream.putNextEntry` 遇重名直接抛
     * `ZipException: duplicate entry`。撞名有两类，都不是假想：
     * - 同一目录下 `005.png` 与 `005.jpg` —— 译文扩展名统一成 `.jpg` 后都映射到 `005.jpg`
     * - 双语包里原文条目若是**上一次导出的** `005_译文.jpg`（导出的包再导入当新书读），
     *   会与本次为 `005.jpg` 生成的译文名撞上
     *
     * 原文条目先登记，所以撞名时总是**译文改名**，原文保持原名。
     */
    fun uniqueEntryName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val slash = name.lastIndexOf('/')
        val dot = name.lastIndexOf('.')
        val hasExt = dot > slash + 1 // 点在最后一个目录分隔符之后才算扩展名（`a.b/005` 不算）
        val stem = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$stem" + "_$index" + ext
            if (used.add(candidate)) return candidate
            index++
        }
    }

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

/** 打包导出的结果。三态必须分开：「没有可导的页」「导出中断」「部分页被跳过」对用户是三件事。 */
internal sealed interface ExportOutcome {
    /** 一页都没翻过，无可导出。 */
    data object Empty : ExportOutcome

    /** 导出被异常中断（临时包不可用）。 */
    data object Failed : ExportOutcome

    /** 跑完：[written] 写进包的页数，[skipped] 因渲染/读图失败被跳过的页数。 */
    data class Done(val written: Int, val skipped: Int) : ExportOutcome
}

/**
 * 阅读器打包导出（在 IO 线程执行，产出临时 zip，落盘由调用方走 MediaStore）。
 *
 * ⚠️ 文件名里**保留原目录前缀**：zip 里出现重名条目会让 `ZipOutputStream.putNextEntry` 抛
 * `ZipException: duplicate entry`（子目录漫画 `ch1/005.jpg` 与 `ch2/005.jpg` 会撞），整个导出直接失败。
 */
internal object ReaderExport {

    private const val TAG = "ReaderExport"

    /** 导出 JPEG 质量（与微信/网盘场景一致：肉眼无损、体积最小）。 */
    private const val JPEG_QUALITY = 95

    /**
     * 导出**已翻译页**到 [tempFile]。
     *
     * [both] = true → 双语包：每页两个条目（原文原名 + `_译文.jpg`）；false → 只出译文。
     * 未翻译/翻译失败的页不导出（所以包内页数比原包少，但序号仍是原序号）。
     *
     * [translatedSuffix] 由调用方按界面语言传入（中文 `_译文` / 英文 `_translated`），
     * 不在本类硬编码中文。
     *
     * @param onProgress 每完成一页回调一次（**主线程**），参数为 (已写入, 总数)。
     */
    suspend fun exportTranslated(
        source: ReaderPageSource,
        controller: ReaderTranslationController,
        both: Boolean,
        translatedSuffix: String,
        tempFile: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val pages = controller.translatedPages().sorted()
        if (pages.isEmpty()) return@withContext ExportOutcome.Empty
        var written = 0
        var skipped = 0
        val used = HashSet<String>()
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zip ->
                for (page in pages) {
                    val key = source.key(page)
                    // ⚠️ **先渲染译文，再写任何条目**：渲染不出来就整页跳过。反过来的话，
                    // 双语包里会留下一张没有 `_译文` 配对的孤儿原图（用户以为这页翻译丢了）
                    val bmp = if (key != null) controller.renderForExport(page) else null
                    if (key == null || bmp == null) {
                        skipped++
                        continue
                    }
                    try {
                        if (both &&
                            !copyOriginal(source, zip, page, ExportNaming.uniqueEntryName(ExportNaming.originalPairEntry(key), used))
                        ) {
                            skipped++
                            continue
                        }
                        val entryName = ExportNaming.uniqueEntryName(
                            if (both) ExportNaming.translatedPairEntry(key, translatedSuffix)
                            else ExportNaming.translatedEntry(key),
                            used,
                        )
                        zip.putNextEntry(ZipEntry(entryName))
                        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, zip)
                        zip.closeEntry()
                    } finally {
                        bmp.recycle() // 渲染产物是独立副本（renderOverlay 内部 copy），用完即回收
                    }
                    written++
                    withContext(Dispatchers.Main) { onProgress(written, pages.size) }
                }
            }
            ExportOutcome.Done(written, skipped)
        } catch (e: CancellationException) {
            // 取消（退出阅读器 → onDestroy 取消 lifecycleScope）要按结构化并发继续上抛，
            // 不能当成「导出失败」吞掉 —— 否则协程被取消后还在往下跑
            throw e
        } catch (e: Exception) {
            // ⚠️ 必须落盘：否则用户只看到一句「导出失败」，重名条目 / 磁盘满 / 渲染异常
            // 三种完全不同的原因长得一模一样，排查无门（CLAUDE.md：错误要能在日志查看器看到）
            LogCollector.e(TAG, "exportTranslated failed both=$both tmp=$tempFile", e)
            if (written > 0) ExportOutcome.Done(written, skipped) else ExportOutcome.Failed
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
        LogCollector.e(TAG, "copyOriginal failed page=$page entry=$entryName", e)
        false
    }
}
