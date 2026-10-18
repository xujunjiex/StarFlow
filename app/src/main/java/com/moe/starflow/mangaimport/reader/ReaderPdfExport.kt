package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import com.moe.starflow.mangaimport.data.PdfRasterPolicy
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * PDF 书的「打包下载」：把译文/双语写成 PDF，**直接写进调用方给的流**。
 *
 * ## 为什么吃 OutputStream 而不是自己落一个临时文件
 * PDF 书动辄 100MB~1GB（双语更多），而 `cacheDir` 在 /data 分区 —— 先写一份临时文件再复制到
 * Downloads 等于**同一份数据在磁盘上出现两次**（还要 2~3 倍瞬时空间），手机上很容易「导出失败」
 * 而用户完全不知道为什么（源文件明明在）。所以这里只负责"往流里写"，落盘/回滚由调用方管
 * （见 `MangaReaderActivity.streamToDownloads`：建 MediaStore 行 → 写 → 失败删行）。
 *
 * ## 与 zip 导出（[ReaderExport]）的三点不同
 * 1. **出口是 PDF 而不是 zip**：PDF 书的用户预期就是拿到 PDF（也才能再导回别处读）
 * 2. **译文 PDF = 全页**：已翻译页出译图，未翻译/失败页**回填原图** ——
 *    PDF 少页会让整本的页码错位，比"某几页还是原文"难用得多
 *    （zip 导出刻意相反：只出已翻译页，因为包里没有"页"的概念）
 * 3. **双语 PDF = 已翻译页「原图页 + 译图页」成对**，未翻译页只出原图页（不重复一张一样的图）。
 *    页数因此是 `N + 已翻译页数`，不是 `2N`
 *
 * ## 体积与耗时（用户要能预期）
 * 每页都要：渲染（PDF 页现渲 / 译图实时渲）→ JPEG 编码 → 嵌入 PDF。
 * 一页约 0.3~1s，300 页的书要几分钟；体积 ≈ 各页 JPEG 之和（见 [PdfJpegWriter] 的说明）。
 *
 * 进度：每处理完**一页原书页**回调一次（双语模式下这一页可能写了两张）。
 */
internal object ReaderPdfExport {

    private const val TAG = "ReaderPdfExport"

    /** 嵌入 PDF 的 JPEG 质量：肉眼无损区间的下限，体积换得动（300 页能差出一倍）。 */
    private const val JPEG_QUALITY = 88

    /**
     * 导出成 PDF。
     *
     * ⚠️ 页图由**调用方**提供（而不是直接吃 `ReaderPageSource` + `Controller`）：这两个都是重型
     * Android 依赖，绑死它们就没法对"页数/两个计数/截断判定"这些**纯逻辑**写行为测试 ——
     * 而这几条正是最容易改错、且错了用户会以为"整本都翻了"的地方（`ReaderPdfExportAccountingTest`）。
     *
     * @param pageCount 全书页数（= `ReaderPageSource.size`）
     * @param translatedPages 导出**开始时**"应该有译文"的页集合快照（= `controller.translatedPages()`）
     * @param readOriginal 取该页原图（= `source.loadFull`）。**返回的位图归本函数所有**，用完即回收
     * @param renderTranslated 渲染该页译图（= `controller.renderForExport`）；null = 这页没有译文。
     *                         同样**归本函数所有**、用完即回收
     * @param both true = 双语 PDF（已译页：原图页 + 译图页）；false = 译文 PDF（全页，未译页原图）
     * @param out **调用方拥有**的输出流（本函数只 flush，不 close）
     * @param onProgress 每处理完一页原书页回调一次（**主线程**），参数为 (已处理, 总页数)
     * @param encodeJpeg 位图 → JPEG 字节（测试缝：默认实现就是 `Bitmap.compress(JPEG, 88)`）
     * @param originalOnly 只导出底图，允许没有任何译文。
     * @param pageDimensions 原页像素尺寸；超分后仍按此尺寸计算纸张大小。
     */
    suspend fun export(
        pageCount: Int,
        translatedPages: Set<Int>,
        readOriginal: (Int) -> Bitmap?,
        renderTranslated: (Int) -> Bitmap?,
        both: Boolean,
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit,
        encodeJpeg: (Bitmap) -> ByteArray? = ::encodeJpeg,
        originalOnly: Boolean = false,
        pageDimensions: ((Int) -> Pair<Int, Int>)? = null,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val total = pageCount
        if (total <= 0) return@withContext ExportOutcome.Empty
        val translated = translatedPages
        // 一页都没翻过 → 与 zip 导出同一条口径：不产出"纯原图"的假译文包
        if (!originalOnly && translated.isEmpty()) return@withContext ExportOutcome.Empty

        var written = 0
        /**
         * 这一页**什么都没写进 PDF**（真·跳过）：阅读器会少一页。
         */
        var skipped = 0
        /**
         * 这一页**在包里，但没有译文**（双语 = 只出了原图页；译文 PDF = 用原图回填）。
         *
         * ⚠️ 和 [skipped] 分开计数是有意的：把"回填了原图"也说成"N 页跳过"，用户会以为 PDF 缺页。
         */
        var untranslated = 0
        /** 是否真的写到了 trailer —— 没有它的话，中途 IOException 会被当成"部分成功"交付一个打不开的 PDF。 */
        var completed = false
        val buffered = BufferedOutputStream(out, 64 * 1024)
        try {
            val writer = PdfJpegWriter(buffered)
            for (page in 0 until total) {
                val dimensions = pageDimensions?.invoke(page)
                if (originalOnly) {
                    if (writeOriginalPage(writer, readOriginal, encodeJpeg, page, dimensions)) written++ else skipped++
                    withContext(Dispatchers.Main) { onProgress(page + 1, total) }
                    continue
                }
                /** 本页写进 PDF 的张数（双语模式下已译页是 2 张），同时作为"这页有没有产出"的判据。 */
                var wroteThisPage = false
                // ① 双语：先出"原图页"（未翻译页就靠这一张，所以不能省）
                if (both && writeOriginalPage(writer, readOriginal, encodeJpeg, page, dimensions)) {
                    written++
                    wroteThisPage = true
                }
                // ② 译图页：按导出开始时的快照判断"这页应该有译文"，再现读载荷渲染
                val expected = page in translated
                val rendered = if (expected) renderTranslated(page) else null
                if (expected && rendered == null) {
                    // ⚠️ **必须记一笔**：导出是跨 onStop 继续跑的长任务，用户完全可能中途
                    //    「删除该页译文 / 清空本章」，于是快照里还有、载荷已经取不到了。
                    //    不记的话双语包会只剩原图页、两个计数都还是 0 —— 用户以为整本都翻了。
                    LogCollector.w(TAG, "译文页渲染失败（导出途中译文被删除/载荷为空）page=$page")
                }
                if (rendered != null) {
                    try {
                        if (writeBitmapPage(writer, rendered, encodeJpeg, dimensions)) {
                            written++
                            wroteThisPage = true
                        }
                    } finally {
                        // renderForExport 给的是独立副本 → 用完即回收（别攒内存）
                        rendered.recycle()
                    }
                } else if (!both) {
                    // 译文 PDF：未翻译页回填原图，保证整本页数一致、可连续阅读
                    if (writeOriginalPage(writer, readOriginal, encodeJpeg, page, dimensions)) {
                        written++
                        wroteThisPage = true
                    }
                }
                when {
                    // 这一页连原图都没进包 → 真·跳过（阅读器会少一页）
                    !wroteThisPage -> skipped++
                    // 页在，只是没有译文（未翻译 / 中途被删 / 渲染失败）
                    rendered == null -> untranslated++
                }
                withContext(Dispatchers.Main) { onProgress(page + 1, total) }
            }
            writer.finish()
            buffered.flush()
            completed = true
            // ⚠️ **`written` 必须真的累加**：它同时是"有没有交付任何东西"的判据 ——
            //    只记 `wroteThisPage` 而漏了 `written++`，每次导出都会走到这里判 Failed，
            //    调用方（streamToDownloads）还会把刚落盘的那条记录删掉 → 用户点了导出什么都没有
            //    （2026-10 真发生：行为测试 `ReaderPdfExportAccountingTest` 抓到的）。
            if (written <= 0) ExportOutcome.Failed
            else ExportOutcome.Done(written, skipped, untranslated)
        } catch (e: CancellationException) {
            // 取消（退出阅读器 → onDestroy 取消 lifecycleScope）要按结构化并发继续上抛
            throw e
        } catch (e: Exception) {
            // 必须落盘：磁盘满 / 渲染异常 / JPEG 编码失败在用户侧长得一模一样
            LogCollector.e(TAG, "exportPdf failed both=$both written=$written completed=$completed", e)
            // ⚠️ **没写完 trailer 的 PDF 是打不开的**（没有 xref）——宁可报失败也不能当成功交付：
            //    zip 截断还能解开一部分，PDF 截断连页数都读不出来。
            if (!completed) ExportOutcome.Failed
            else if (written > 0) ExportOutcome.Done(written, skipped, untranslated)
            else ExportOutcome.Failed
        }
    }

    /** 原图页：由调用方提供位图，返回是否写成功；位图用完即回收（契约：归本函数所有）。 */
    private fun writeOriginalPage(
        writer: PdfJpegWriter,
        readOriginal: (Int) -> Bitmap?,
        encodeJpeg: (Bitmap) -> ByteArray?,
        page: Int,
        dimensions: Pair<Int, Int>?,
    ): Boolean {
        val bmp = readOriginal(page) ?: return false
        return try {
            writeBitmapPage(writer, bmp, encodeJpeg, dimensions)
        } finally {
            bmp.recycle()
        }
    }

    /** 位图 → JPEG → 一页 PDF。页的**点尺寸**由像素尺寸按栅格化 DPI 反推（= 原页面尺寸）。 */
    private fun writeBitmapPage(
        writer: PdfJpegWriter,
        bitmap: Bitmap,
        encodeJpeg: (Bitmap) -> ByteArray?,
        dimensions: Pair<Int, Int>?,
    ): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val jpeg = encodeJpeg(bitmap) ?: return false
        if (jpeg.isEmpty()) return false
        val ptPerPx = 72f / PdfRasterPolicy.RASTER_DPI
        val pageW = dimensions?.first?.takeIf { it > 0 } ?: w
        val pageH = dimensions?.second?.takeIf { it > 0 } ?: h
        writer.addPage(jpeg, w, h, pageW * ptPerPx, pageH * ptPerPx)
        return true
    }

    /** 默认的 JPEG 编码（唯一实现，测试可替换成假编码器从而不依赖 Skia）。 */
    private fun encodeJpeg(bitmap: Bitmap): ByteArray? = ByteArrayOutputStream().use { buffer ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) return null
        buffer.toByteArray()
    }
}
