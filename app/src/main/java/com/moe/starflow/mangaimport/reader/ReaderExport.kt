package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import com.moe.starflow.mangaimport.translate.ReaderTranslationController
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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

    /**
     * 跑完：[written] 写进包的页数（双语一页可能两个条目，按**页**计），
     * [skipped] 因读图失败被跳过的页数，
     * [untranslated] **在包里但没有译文**的页数（zip 的原图回填页 / 未译页只出原文，PDF 同口径）。
     *
     * ⚠️ 两个计数**语义不同，提示语也必须不同**：`skipped` = 这一页没进包（真缺页）；
     * `untranslated` = 页在、只是没译文。把它说成"跳过"就是在骗用户。
     */
    data class Done(val written: Int, val skipped: Int, val untranslated: Int = 0) : ExportOutcome
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
     * 导出到 [tempFile]（zip）。
     *
     * 口径（用户口径 2026-10）：
     * - [both] = true → **双语包恒全书页**：已译页两个条目（原文原名 + `_译文.jpg`）；
     *   没有译文的页**只出原文那一半**（同一张图不重复放两次）。
     * - [both] = false → 译文包：
     *   [includeUntranslated] = true → 全书页，没有译文的页用**原图回填**（沿用原名原扩展名，
     *   原样搬字节 → 零二次有损压缩）；false → 旧口径，只出已成功翻译的页。
     *
     * [translatedSuffix] 由调用方按界面语言传入（中文 `_译文` / 英文 `_translated`），
     * 不在本类硬编码中文。
     *
     * @param onProgress 每完成一页回调一次（**主线程**），参数为 (已写入, 参与导出的页数)。
     */
    suspend fun exportTranslated(
        source: ReaderPageSource,
        controller: ReaderTranslationController,
        both: Boolean,
        includeUntranslated: Boolean,
        translatedSuffix: String,
        tempFile: File,
        onProgress: (done: Int, total: Int) -> Unit,
        preferSr: Boolean = false,
    ): ExportOutcome {
        // 已译页取**导出开始那一刻**的快照：用户完全可能在导出过程中删掉某页译文 / 清空本章
        val translated = controller.translatedPages()
        return exportToZip(
            pageCount = source.size,
            isTranslated = { it in translated },
            isAvailable = source::pageAvailable,
            exportKey = source::exportKey,
            openOriginal = source::openEntry,
            renderTranslated = { controller.renderForExport(it) },
            readSr = { if (preferSr) controller.loadSrForExport(it) else null },
            renderTranslatedWithBase = { page, sr -> controller.renderForExport(page, sr) },
            both = both,
            includeUntranslated = includeUntranslated,
            translatedSuffix = translatedSuffix,
            tempFile = tempFile,
            onProgress = onProgress,
        )
    }

    /**
     * [exportTranslated] 的实现（依赖全部以 lambda 传入）。
     *
     * 这样拆是为了**能对页数/两个计数/未译页回填写行为测试**：`ReaderPageSource` 与
     * `ReaderTranslationController` 都是重型 Android 依赖，绑死就测不了（与 `ReaderPdfExport`
     * 当初拆 `readOriginal`/`renderTranslated`/`encodeJpeg` 是同一个理由）。
     */
    internal suspend fun exportToZip(
        pageCount: Int,
        isTranslated: (Int) -> Boolean,
        isAvailable: (Int) -> Boolean,
        exportKey: (Int) -> String?,
        openOriginal: (Int) -> InputStream?,
        renderTranslated: (Int) -> Bitmap?,
        both: Boolean,
        includeUntranslated: Boolean,
        translatedSuffix: String,
        tempFile: File,
        onProgress: (done: Int, total: Int) -> Unit,
        compressRendered: (Bitmap, OutputStream) -> Unit = { bmp, out ->
            check(bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out))
        },
        readSr: (Int) -> Bitmap? = { null },
        renderTranslatedWithBase: ((Int, Bitmap?) -> Bitmap?)? = null,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        // 参与导出的页：双语恒全书页；译文模式按 includeUntranslated 决定要不要未译页。
        // ⚠️ 全书页口径下真缺页也要进循环，才会落进 skipped 计数并被如实告知；
        //    旧口径只导已译页 —— 那些**本来就不导出**的未译页即使源文件缺失也不该报「跳过」，
        //    否则用户会以为包不完整（"跳过"的语义只能是"这页本该在包里"）。
        val pages = (0 until pageCount).filter { page ->
            both || includeUntranslated || isTranslated(page)
        }
        if (pages.isEmpty()) return@withContext ExportOutcome.Empty
        var written = 0
        var skipped = 0
        var untranslated = 0
        val used = HashSet<String>()
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zip ->
                for (page in pages) {
                    currentCoroutineContext().ensureActive()
                    if (!isAvailable(page)) {
                        skipped++
                        continue
                    }
                    val key = exportKey(page)
                    if (key == null) {
                        skipped++
                        continue
                    }
                    // ⚠️ **先渲染译文，再写任何条目**：渲染要几秒，先把包写脏了再发现失败，
                    // 用户看到的就是"这一页只有原图"却没人告诉他为什么
                    val sr = readSr(page)
                    val bmp = try {
                        if (!isTranslated(page)) null
                        else if (renderTranslatedWithBase != null) renderTranslatedWithBase(page, sr)
                        else renderTranslated(page)
                    } catch (e: Throwable) {
                        sr?.recycle()
                        throw e
                    }
                    try {
                        when {
                            both -> {
                                // 双语：先原文（未译页就靠这一张），再译文
                                val originalName = ExportNaming.uniqueEntryName(
                                    if (sr == null) key else ExportNaming.translatedEntry(key), used,
                                )
                                if (!writeOriginal(openOriginal, zip, page, originalName, sr, compressRendered)) {
                                    skipped++
                                    continue
                                }
                                if (bmp == null) {
                                    // 页在、只是没译文：原图那一半已经进包，如实计数（不是 skipped）
                                    untranslated++
                                } else {
                                    val entryName = ExportNaming.uniqueEntryName(
                                        ExportNaming.translatedPairEntry(key, translatedSuffix), used,
                                    )
                                    zip.putNextEntry(ZipEntry(entryName))
                                    compressRendered(bmp, zip)
                                    zip.closeEntry()
                                }
                                written++
                            }

                            bmp != null -> {
                                val entryName = ExportNaming.uniqueEntryName(
                                    ExportNaming.translatedEntry(key), used,
                                )
                                zip.putNextEntry(ZipEntry(entryName))
                                compressRendered(bmp, zip)
                                zip.closeEntry()
                                written++
                            }

                            includeUntranslated -> {
                                // 未译页回填：**原始字节 + 原名原扩展名**（不 decode/不重编码）
                                if (!writeOriginal(openOriginal, zip, page, ExportNaming.uniqueEntryName(
                                        if (sr == null) key else ExportNaming.translatedEntry(key), used), sr, compressRendered)) {
                                    skipped++
                                    continue
                                }
                                untranslated++
                                written++
                            }

                            else -> skipped++
                        }
                    } finally {
                        // 渲染产物是独立副本（renderOverlay 内部 copy），用完即回收
                        if (bmp != null && !bmp.isRecycled) bmp.recycle()
                        sr?.recycle()
                    }
                    withContext(Dispatchers.Main) { onProgress(written, pages.size) }
                }
            }
            ExportOutcome.Done(written, skipped, untranslated)
        } catch (e: CancellationException) {
            // 取消（退出阅读器 → onDestroy 取消 lifecycleScope）要按结构化并发继续上抛，
            // 不能当成「导出失败」吞掉 —— 否则协程被取消后还在往下跑
            throw e
        } catch (e: Exception) {
            // ⚠️ 必须落盘：否则用户只看到一句「导出失败」，重名条目 / 磁盘满 / 渲染异常
            // 三种完全不同的原因长得一模一样，排查无门（CLAUDE.md：错误要能在日志查看器看到）
            LogCollector.e(
                TAG,
                "exportTranslated failed both=$both includeUntranslated=$includeUntranslated tmp=$tempFile",
                e,
            )
            if (written > 0) ExportOutcome.Done(written, skipped, untranslated) else ExportOutcome.Failed
        }
    }

    /** 超分页重新编码为 JPEG；其余页面保留源条目字节和扩展名。 */
    suspend fun exportOriginal(
        source: ReaderPageSource,
        tempFile: File,
        readSr: (Int) -> Bitmap?,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        var written = 0
        var skipped = 0
        val used = HashSet<String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zip ->
            for (page in 0 until source.size) {
                currentCoroutineContext().ensureActive()
                val key = source.exportKey(page)
                if (key == null || !source.pageAvailable(page)) { skipped++; continue }
                val sr = readSr(page)
                try {
                    val name = if (sr == null) key else ExportNaming.translatedEntry(key)
                    if (writeOriginal(source::openEntry, zip, page, ExportNaming.uniqueEntryName(name, used), sr)) written++
                    else skipped++
                } finally { sr?.recycle() }
            }
        }
        if (written == 0) ExportOutcome.Failed else ExportOutcome.Done(written, skipped)
    }

    private fun writeOriginal(
        openOriginal: (Int) -> InputStream?, zip: ZipOutputStream, page: Int, entryName: String, sr: Bitmap?,
        compress: (Bitmap, OutputStream) -> Unit = { bmp, out ->
            check(bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out))
        },
    ): Boolean {
        if (sr == null) return copyOriginal(openOriginal, zip, page, entryName)
        zip.putNextEntry(ZipEntry(entryName))
        compress(sr, zip)
        zip.closeEntry()
        return true
    }

    /** 把原始条目字节原样写入 zip。 */
    private fun copyOriginal(
        openOriginal: (Int) -> InputStream?,
        zip: ZipOutputStream,
        page: Int,
        entryName: String,
    ): Boolean = try {
        openOriginal(page)?.use { input ->
            zip.putNextEntry(ZipEntry(entryName))
            input.copyTo(zip)
            zip.closeEntry()
            true
        } ?: false
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        LogCollector.e(TAG, "copyOriginal failed page=$page entry=$entryName", e)
        false
    }
}
