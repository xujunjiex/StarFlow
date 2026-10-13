package com.moe.starflow.mangaimport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PDF 导入的**接线守卫**（源码级，不依赖 Android 运行时）。
 *
 * 为什么用源码断言而不是行为测试：`PdfRenderer` 是 pdfium 的 native 实现，Robolectric 里
 * 没有影子实现 → 真渲染只能在真机上验证。但"接线有没有接上/有没有偷偷转图/口径是不是单一"
 * 这些问题恰恰最容易在后续重构里被改坏，而且改坏了**不会立刻报错**（表现为某天用户说
 * "PDF 导进去一页都没有" 或 "译文框全部偏了"）。这里把本轮定下的口径钉死。
 *
 * 本轮定下的口径（用户 2026-10 讨论结论）：
 * 1. **保留 PDF、按需栅格化**：导入只探页数 + 渲一张封面，**不批量转图**（存储 ×1，不是 ×3）
 * 2. **像素空间只有一个来源**（`PdfRasterPolicy`）：气泡坐标/OCR/超分/导出共用
 * 3. **导出 PDF 走 JPEG 直嵌**（`DCTDecode`），不用 `PdfDocument` 的无损压缩（那会几个 GB）
 * 4. **FD 必须有人释放**：阅读器销毁、控制器回收各关一次
 */
class PdfImportWiringTest {

    private companion object {
        const val ARCHIVE_TYPES = "src/main/java/com/moe/starflow/mangaimport/data/ArchiveTypes.kt"
        const val IMPORTER = "src/main/java/com/moe/starflow/mangaimport/data/MangaImporter.kt"
        const val MANGA = "src/main/java/com/moe/starflow/mangaimport/data/ImportedManga.kt"
        const val STORE = "src/main/java/com/moe/starflow/mangaimport/data/ImportedMangaStore.kt"
        const val CHAPTER_MIGRATOR = "src/main/java/com/moe/starflow/mangaimport/data/MangaChapterMigrator.kt"
        const val RENDERER = "src/main/java/com/moe/starflow/mangaimport/data/PdfPageRenderer.kt"
        const val POLICY = "src/main/java/com/moe/starflow/mangaimport/data/PdfRasterPolicy.kt"
        const val SOURCE = "src/main/java/com/moe/starflow/mangaimport/reader/ReaderPageSource.kt"
        const val READER = "src/main/java/com/moe/starflow/mangaimport/reader/MangaReaderActivity.kt"
        const val CONTROLLER = "src/main/java/com/moe/starflow/mangaimport/translate/ReaderTranslationController.kt"
        const val PDF_EXPORT = "src/main/java/com/moe/starflow/mangaimport/reader/ReaderPdfExport.kt"
        const val WRITER = "src/main/java/com/moe/starflow/mangaimport/reader/PdfJpegWriter.kt"
        const val FRAGMENT = "src/main/java/com/moe/starflow/mangaimport/ImportMangaFragment.kt"
        const val STRINGS_EN = "src/main/res/values/strings.xml"
        const val STRINGS_ZH = "src/main/res/values-zh/strings.xml"
    }

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到文件：${f.absolutePath}（单测工作目录应是模块目录 app/）", f.exists())
        return f.readText()
    }

    /** 取一段源码：[from] 起、到 [to] 之前（用于断言"某个函数里没有做某事"）。 */
    private fun section(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("源码里找不到锚点：$from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("源码里找不到结束锚点：$to", end > start)
        return src.substring(start, end)
    }

    // ===== 1. 识别：按魔数，不认扩展名 =====

    @Test
    fun `pdfIsDetectedByMagicNotByExtension`() {
        val src = read(ARCHIVE_TYPES)
        assertTrue("ArchiveKind 必须有 PDF", src.contains("PDF,"))
        assertTrue("必须按 %PDF- 魔数识别", src.contains("\"%PDF-\".toByteArray"))
        // ⚠️ PDF 规范允许文件头出现在前 1024 字节内的任意位置 → 只比对第 0 字节会误判"不是 PDF"
        assertTrue("嗅探窗口必须 ≥1KB", src.contains("SNIFF_BYTES = 1024"))
        assertTrue("必须在窗口内查找而不是只比首字节", src.contains("indexOf(head, read, PDF_MAGIC)"))
    }

    // ===== 2. 导入：不转图、页数来自 page tree =====

    @Test
    fun `pdfImportKeepsTheOriginalFileAndNeverRasterisesEagerly`() {
        val src = read(IMPORTER)
        assertTrue("importArchiveToFile 必须把 PDF 分流到 importPdf",
            src.contains("ArchiveKind.PDF -> importPdf("))
        val body = section(src, "private fun importPdf(", "\n    /** zip/cbz")
        assertTrue("页数必须来自 PdfRenderer.pageCount", body.contains("renderer.pageCount"))
        assertTrue("封面取第一页", body.contains("renderCanonical(0)"))
        // ⚠️ 核心决策：不落任何页图。写文件 / 重新编码 = 又变回"导入即转图"（存储 ×3、分辨率焊死）
        assertFalse("PDF 导入不许写图片文件", body.contains("FileOutputStream"))
        assertFalse("PDF 导入不许重新编码位图", body.contains("compress("))
        assertFalse("PDF 导入不许解压", body.contains("LibArchiveExtractor"))
        assertTrue("必须标成 PDF 条目", body.contains("isPdf = true"))
        assertTrue("整本一章（章标题留空 → 显示「第1章」）", body.contains("MangaChapter(number = 1"))
        assertTrue("探究页数后必须关掉渲染器（FD 不能漏）", body.contains("renderer.close()"))
    }

    @Test
    fun `thePdfFlagSurvivesPersistence`() {
        assertTrue("ImportedManga 要有 isPdf", read(MANGA).contains("val isPdf: Boolean = false"))
        val store = read(STORE)
        assertTrue("清单必须写 isPdf", store.contains("put(\"isPdf\", isPdf)"))
        assertTrue("老清单读回 false", store.contains("optBoolean(\"isPdf\", false)"))
    }

    @Test
    fun `chapterBackfillSkipsPdfEntries`() {
        // PDF 的 localRoot 是**文件**：按目录枚举永远为空，回填只会白跑一遍。
        // ⚠️ 断言写成"条件行里真的出现了 `|| manga.isPdf`"，别只 grep `manga.isPdf`
        //    ——那样写在注释里、写在别的分支里都算过。
        assertTrue(
            "跳过的判据必须在同一行的条件表达式里",
            read(CHAPTER_MIGRATOR).contains("|| manga.isPdf ||"),
        )
    }

    // ===== 3. 像素空间：只有一个来源 =====

    @Test
    fun `everyPdfPixelSizeComesFromTheSinglePolicy`() {
        val src = read(SOURCE)
        assertTrue("必须持有渲染器", src.contains("PdfPageRenderer(inputFile)"))
        assertTrue("权威尺寸必须经 PdfRasterPolicy（见 PdfPageRenderer.canonicalSize）", src.contains("pdfGeometry?.canonicalSize("))
        assertTrue(
            "采样尺寸也走策略（在渲染器内部一次 openPage 内算完）",
            read(RENDERER).contains("PdfRasterPolicy.sampledSize(canonical, targetWidthPx)"),
        )
        assertTrue(
            "缩略图采样同样走策略",
            read(RENDERER).contains("PdfRasterPolicy.downscale(canonical, sample)"),
        )
        // ⚠️ 反例守卫：在 ReaderPageSource 里写死 DPI/换算系数 = 出现第二套口径
        assertFalse("不许在数据源里手写 DPI 换算（72f）", src.contains("72f"))
        assertFalse("不许在数据源里手写 300dpi（300f）", src.contains("300f"))
        assertFalse("不许对 PDF 页做二次缩放（先渲大再缩）", src.contains("createScaledBitmap"))
    }

    @Test
    fun `thePolicyPinsTheBaselineDpiAndCaps`() {
        val policy = read(POLICY)
        assertTrue("基准 300 DPI", policy.contains("RASTER_DPI: Int = 300"))
        assertTrue("长边上限", policy.contains("MAX_LONG_SIDE"))
        assertTrue("总像素上限（防 OOM）", policy.contains("MAX_PIXELS"))
        assertTrue("长边下限（异常小页也要能 OCR）", policy.contains("MIN_LONG_SIDE"))
    }

    @Test
    fun `thePdfRendererIsInitialisedBeforeThePageList`() {
        // ⚠️ Kotlin 属性初始化按声明顺序执行，而 rawKeys 的初始化器要读 pdf.pageCount ——
        //    把 `pdf` 声明到 rawKeys 后面就是启动即 NPE（PDF 书一打开就"文件丢失"）
        val src = read(SOURCE)
        val pdfField = src.indexOf("private val pdf: PdfPageRenderer?")
        val rawKeys = src.indexOf("private val rawKeys: List<String>")
        assertTrue("两个字段都必须存在", pdfField >= 0 && rawKeys >= 0)
        assertTrue("`pdf` 必须声明在 `rawKeys` 之前", pdfField < rawKeys)
    }

    @Test
    fun `everyReadPathGoesThroughThePdfBranch`() {
        val src = read(SOURCE)
        // ⚠️ **逐个方法**断言，不数"全文出现几次 if (isPdf)"：那种数法会把字段初始化
        //    （`if (isPdf) PdfPageRenderer(...)`）也算进去，阈值只能定得宽松 —— 删掉两三个真分支
        //    仍然全绿，而后果是"缩略图空白 / Webtoon 行高塌成 0（页面一直跳）"这种难查的老 bug。
        fun body(fn: String, next: String): String = section(src, fn, next)
        assertTrue(
            "loadFull 必须有 PDF 分支",
            body("fun loadFull(", "openEntry(position: Int)").contains("if (isPdf)"),
        )
        assertTrue(
            "openEntry 必须有 PDF 分支（否则旧 zip 双语链路遇到 PDF 交白卷）",
            body("fun openEntry(", "private fun pdfEntryStream").contains("if (isPdf)"),
        )
        assertTrue(
            "decodeWebtoon 必须有 PDF 分支（在目标尺寸上直接光栅化）",
            body("private fun decodeWebtoon(", "private fun decodeThumb").contains("pdfRenderToWidth("),
        )
        assertTrue(
            "decodeThumb 必须有 PDF 分支（预览网格不空）",
            body("private fun decodeThumb(", "private fun decodeZipEntry").contains("if (isPdf)"),
        )
        assertTrue(
            "ensureOriginalSize 必须有 PDF 分支（Webtoon 行高靠它）",
            body("private fun ensureOriginalSize(", "private fun computeSample").contains("if (isPdf)"),
        )
        assertTrue(
            "pageBytes 必须对 PDF 明说返回 0（不编假数据）",
            body("fun pageBytes(", "fun rawPageKeys").contains("if (closed || isPdf) return 0"),
        )
        assertTrue(
            "resolveRawPages 必须有 PDF 分支（页序 = page tree 顺序）",
            body("private fun resolveRawPages(", "fun close()").contains("PdfPageKeys.all(count)"),
        )
        // PDF 权威图要有内存 LRU，且额度取自**同一处口径**（不能各写一个数字）
        assertTrue("PDF 权威图要有内存 LRU", src.contains("pdfFullCache"))
        assertTrue(
            "缓存预算必须取 PdfRasterPolicy.CANONICAL_CACHE_BUDGET_BYTES（自己写数字会小于单页上限 → 缓存恒不命中）",
            src.contains("LruCache<Int, Bitmap>(PdfRasterPolicy.CANONICAL_CACHE_BUDGET_BYTES)"),
        )
        // ⚠️ 缓存里的位图不许外借（调用方会 recycle）→ 必须返回副本
        assertTrue("必须返回副本", src.contains("cached.copy(Bitmap.Config.ARGB_8888, false)"))
        // 页面尺寸查询走**独立渲染器**：主线程（onBindViewHolder）不能和在途渲染抢同一把锁
        assertTrue(
            "尺寸查询必须走独立的 pdfGeometry 渲染器（否则主线程会阻塞整次渲染 → 掉帧/ANR）",
            src.contains("private val pdfGeometry: PdfPageRenderer?"),
        )
        assertTrue(
            "pdfCanonicalSize 必须用 pdfGeometry 而不是渲染器 pdf",
            section(src, "private fun pdfCanonicalSize(", "private fun pdfLoadFull(").contains("pdfGeometry?.canonicalSize"),
        )
    }

    @Test
    fun `pageByteSizeIsHonestAboutPdf`() {
        // PDF 没有"某一页的字节数"这回事 —— 编一个平均值比返回 0 更糟（面板会显示得像真数据）
        val src = read(SOURCE)
        assertTrue(src.contains("if (closed || isPdf) return 0"))
    }

    // ===== 4. FD 生命周期 =====

    @Test
    fun `bothOwnersCloseTheirPageSource`() {
        val src = read(SOURCE)
        assertTrue("数据源要能关上", src.contains("fun close()"))
        assertTrue("阅读器销毁必须释放自己的数据源", read(READER).contains("closingSource?.close()"))
        assertTrue("控制器被回收时必须释放自持的数据源", read(CONTROLLER).contains("ownSource.close()"))
        assertTrue("渲染器真的关 FD", read(RENDERER).contains("pfd?.close()"))
        // ⚠️ **close 必须是终态**：退出阅读器那一刻在途的 loadFull 用的是关闭前捕获的 lambda，
        //    若允许"关掉后再自动重开"，那次调用会把整本 PDF 重新打开，而此后没人再关 → 每退出一次漏一个 FD。
        assertTrue("ReaderPageSource 要有 closed 终态", src.contains("@Volatile private var closed = false"))
        assertTrue("close() 要置终态", src.contains("closed = true"))
        assertTrue(
            "读取入口必须先看 closed（不能只在 PDF 私有分支里看）",
            src.contains("if (closed) return null"),
        )
        assertTrue("PdfPageRenderer 关闭后拒绝重开", read(RENDERER).contains("拒绝重新打开"))
    }

    @Test
    fun `bothPageSourceConstructionSitesPassThePdfFlag`() {
        listOf(READER to "阅读器", CONTROLLER to "控制器").forEach { (path, who) ->
            val src = read(path)
            assertTrue(
                "$who 建数据源时必须透传 isPdf（漏了 = PDF 书按目录枚举 → 0 页「文件丢失」）",
                src.contains("isPdf = manga.isPdf"),
            )
        }
    }

    // ===== 5. 导出 =====

    @Test
    fun `pdfExportEmbedsJpegInsteadOfRelyingOnLosslessPdfDocument`() {
        val writer = read(WRITER)
        assertTrue("图像必须用 DCTDecode（JPEG 直嵌）", writer.contains("/Filter /DCTDecode"))
        assertFalse("不许声明无损 Flate（整页位图会变几 GB）", writer.contains("/Filter /Flate"))
        assertFalse("不许用 PdfDocument（Skia 后端对整页位图走无损压缩）",
            read(PDF_EXPORT).contains("android.graphics.pdf.PdfDocument"))
        assertTrue("PDF 导出要用这个写入器", read(PDF_EXPORT).contains("PdfJpegWriter(buffered)"))
    }

    @Test
    fun `pdfTranslatedExportCoversEveryPageSoPageNumbersStayAligned`() {
        val body = read(PDF_EXPORT)
        // 译文 PDF = 全页（未译页回填原图）；双语 = 已译页「原图页 + 译图页」
        assertTrue("必须逐页遍历整本", body.contains("for (page in 0 until total)"))
        assertTrue("未译页要回填原图", body.contains("else if (!both)"))
        assertTrue("双语模式先出原图页", body.contains("if (both && writeOriginalPage("))
        assertTrue("一页都没翻过时不出包", body.contains("if (translated.isEmpty())"))
        // ⚠️ **两个计数必须分开**：「这页没进包」与「这页在、只是没译文」对用户是两件事。
        //    合成一个的话，译文 PDF 的未译页（回填了原图、页数完整）会被报成"N 页跳过" = 骗用户缺页。
        assertTrue("必须有 untranslated 计数", body.contains("var untranslated = 0"))
        assertTrue("真·跳过只在整页都没写进去时计", body.contains("!wroteThisPage -> skipped++"))
        assertTrue("没译文（页在）单独计", body.contains("rendered == null -> untranslated++"))
        assertTrue("结果要带上第三个计数", body.contains("ExportOutcome.Done(written, skipped, untranslated)"))
        // ⚠️ `written` 必须真的累加（漏了它每次导出都判 Failed，用户点了导出什么都没有）。
        //    真正的守卫是行为测试 `ReaderPdfExportAccountingTest`，这里只做一道粗筛。
        assertTrue("written 必须在写入成功处累加", Regex("written\\+\\+").findAll(body).count() >= 3)
        // 提示语也要分开：untranslated 走新文案，不能说成"跳过"
        val readerSrc = read(READER)
        assertTrue("提示必须区分 skipped / untranslated",
            readerSrc.contains("outcome.untranslated > 0") &&
                readerSrc.contains("R.string.reader_download_done_untranslated"))
        listOf(STRINGS_EN, STRINGS_ZH).forEach { path ->
            assertTrue("$path 缺 reader_download_done_untranslated",
                read(path).contains("reader_download_done_untranslated"))
        }
    }

    @Test
    fun `theReaderDispatchesPdfExports`() {
        val src = read(READER)
        assertTrue("译文/双语导出要分流到 PDF 导出", src.contains("ReaderPdfExport.export("))
        assertTrue("原文导出 = 原样复制 PDF", src.contains("private suspend fun exportOriginalPdf()"))
        assertTrue("落盘 MIME 必须是 application/pdf", src.contains("PDF_MIME = \"application/pdf\""))
        assertTrue("文件名后缀要区分 PDF 与 zip", src.contains("reader_export_pdf_suffix_both"))
        // ⚠️ **哪条分支用哪个 MIME** 必须钉住：把 PDF 标成 zip（或反过来）用户可见
        //    （文件管理器不认后缀 / 打不开），但所有"常量存在"式断言都还是绿的。
        assertTrue(
            "PDF 原文导出必须用 PDF_MIME 流式落盘",
            section(src, "private suspend fun exportOriginalPdf()", "private fun exportOriginalZip()")
                .contains("streamToDownloads(display, PDF_MIME)"),
        )
        assertTrue(
            "PDF 译文/双语导出必须用 PDF_MIME 流式落盘",
            section(src, "private suspend fun exportPdfToDownloads(", "private data class ExportNotice")
                .contains("streamToDownloads(display, PDF_MIME)"),
        )
        assertTrue(
            "zip 译文导出仍必须走 writeToDownloads(..., ZIP_MIME)",
            section(src, "private suspend fun exportMessage(", "\n    /** 下载文件名")
                .contains("writeToDownloads(display, tmp, ZIP_MIME)"),
        )
        // ⚠️ 原文导出不许"重新生成"：那会丢矢量/文本层、体积还涨；也不许再落临时文件（同一份数据占两次盘）
        val body = section(src, "private suspend fun exportOriginalPdf()", "private fun exportOriginalZip()")
        assertFalse("原文导出不许重新编码", body.contains("Bitmap"))
        assertFalse("原文导出不许重新成 PDF", body.contains("PdfJpegWriter"))
        assertFalse("原文导出不许落临时副本", body.contains("cacheDir"))
        // PDF 译文导出必须直接写流（不落临时文件），且必须"写完 trailer 才算成功"
        val pdfExport = section(src, "private suspend fun exportPdfToDownloads(", "private data class ExportNotice")
        assertFalse("PDF 导出不许先落临时文件", pdfExport.contains("cacheDir"))
        assertTrue("只有 Done 才算落盘成功（Failed/Empty 要删掉那条 MediaStore 记录）",
            pdfExport.contains("outcome is ExportOutcome.Done"))
        // ⚠️ 直写流的收尾必须**重抛取消**（仓库既定规矩：取消不是「导出失败」）。
        //    `catch (Exception)` 会连 CancellationException 一起吞掉 → 用户退出阅读器后还会看到「导出失败」。
        val stream = section(src, "private suspend fun streamToDownloads(", "private fun writeToDownloads(")
        assertTrue(
            "streamToDownloads 必须先接住 CancellationException（删掉那条记录后）再重抛",
            stream.contains("catch (e: kotlinx.coroutines.CancellationException)") &&
                stream.contains("throw e"),
        )
    }

    // ===== 6. 入口与文案 =====

    @Test
    fun `theFilePickerOffersPdfAndTheCopyMentionsIt`() {
        assertTrue(
            "SAF 选择器按 mime 过滤：不给 application/pdf 的话 .pdf 在选择器里是灰的",
            read(FRAGMENT).contains("\"application/pdf\""),
        )
        listOf(STRINGS_EN, STRINGS_ZH).forEach { path ->
            val s = read(path)
            assertTrue("$path 缺 import_fail_pdf_encrypted", s.contains("import_fail_pdf_encrypted"))
            assertTrue("$path 缺 import_fail_pdf_invalid", s.contains("import_fail_pdf_invalid"))
            assertTrue("$path 缺 reader_export_pdf_suffix_original", s.contains("reader_export_pdf_suffix_original"))
            assertTrue("$path 缺 reader_export_pdf_suffix_translated", s.contains("reader_export_pdf_suffix_translated"))
            assertTrue("$path 缺 reader_export_pdf_suffix_both", s.contains("reader_export_pdf_suffix_both"))
        }
        // 失败原因枚举与 Fragment 的映射必须成对（漏一个 UI 就显示不出原因）
        val reasons = read("src/main/java/com/moe/starflow/mangaimport/data/ImportTask.kt")
        assertTrue(reasons.contains("PDF_ENCRYPTED"))
        assertTrue(reasons.contains("PDF_INVALID"))
        assertEquals(
            "PDF 失败原因必须映射到文案",
            2,
            Regex("ImportFailureReason\\.PDF_").findAll(read(FRAGMENT)).count(),
        )
    }
}
