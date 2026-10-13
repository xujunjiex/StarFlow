package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.data.MangaChapterSplitter
import com.moe.starflow.mangaimport.data.PdfPageKeys
import com.moe.starflow.mangaimport.data.PdfPageRenderer
import com.moe.starflow.mangaimport.data.PdfRasterPolicy
import java.io.File
import com.moe.starflow.library.LibrarySources
import com.moe.starflow.mangaimport.data.ArchiveTypes
import com.moe.starflow.mangaimport.data.ArchiveKind
import com.moe.starflow.mangaimport.data.LibraryArchiveReader
import java.util.concurrent.ConcurrentHashMap
import com.moe.starflow.utils.LogCollector
import java.util.zip.ZipFile

/**
 * 阅读器页面数据源：页解析（zip / 目录 / PDF）+ 全图 / 缩略图加载。
 *
 * - 全图：完整解码（ViewPager2 页显示）
 * - 缩略图：按目标尺寸降采样解码，带 LruCache（进度条/预览网格用，避免重复解大图）
 * 统一处理 zip（ZipFile 读 entry）、目录（File 枚举）与 **PDF（按需栅格化）** 三种存储源。
 *
 * ## PDF 源的三条硬约束（改动前必读）
 * 1. **页 = PDF page tree 里的页**（`PdfPageRenderer.pageCount`），与有没有文本层无关；
 *    扫描 PDF 只是每页内容是一整张位图，分页口径完全一样。
 * 2. **像素尺寸必须由 `PdfRasterPolicy` 给出**：图片有文件里写死的原生尺寸，PDF 没有 ——
 *    是我们自己选的。`originalWidth/Height`、`loadFull`、`loadWebtoon`、`loadThumb`、导出
 *    必须调同一个口径，否则气泡坐标会画在别处（而且看起来像随机错位）。
 * 3. **不落盘**：PDF 页图只在内存里按需生成（译文本来也不落图），原 PDF 是唯一事实来源。
 */
private const val TAG = "ReaderPageSource"

/**
 * ⚠️ 超分/增强结果的缓存预算（按像素计）—— **当前无消费者**。
 *
 * v2 架构（2026-10）把超分移出 `ReaderPageSource`（见类内大段注释），
 * 显示底图的缓存在新的「显示底图」路径里。这个常量留着是因为它是**像素预算的口径来源**，
 * 新路径会复用同一个数字；真接完新路径后若仍无引用，直接删。
 */
@Suppress("unused")
private const val ENHANCE_CACHE_PIXEL_BUDGET = 24 * 1024 * 1024

class ReaderPageSource(
    private val isArchive: Boolean,
    private val localRoot: String,
    /** 见 [ImportedManga.isPdf]：PDF 源按需栅格化，不读 zip、不枚举目录。 */
    private val isPdf: Boolean = false
) {

    /**
     * PDF 渲染器（见类文档）。非 PDF 源恒为 null。
     *
     * ⚠️ **必须声明在 [rawKeys] 之前**：`rawKeys` 的初始化器要读它的 `pageCount`
     *    （Kotlin 属性初始化按**声明顺序**执行，声明晚了读到的就是未初始化字段 → NPE）。
     *    构造本身**不打开文件**（`PdfPageRenderer` 首次使用时才开 FD），所以这里代价为 0。
     */
    private val sourceLease = LibrarySources.acquire(File(localRoot))
    private val inputFile by lazy { LibrarySources.file(File(localRoot)) }
    private val nativeArchive = try {
        if (isArchive && ArchiveTypes.detect(inputFile) in setOf(ArchiveKind.RAR, ArchiveKind.SEVEN_ZIP))
            LibraryArchiveReader.acquire(inputFile) else null
    } catch (e: Exception) { sourceLease.close(); throw e }
    @Volatile private var cachedZip: ZipFile? = null
    private val pdf: PdfPageRenderer? = try { if (isPdf) PdfPageRenderer(inputFile) else null }
        catch (e: Exception) { nativeArchive?.close(); sourceLease.close(); throw e }

    /**
     * **只用来查页面尺寸**的第二个渲染器（同一个文件的另一份 FD）。
     *
     * ⚠️ 为什么值得多开一份：`ReaderAdapters` 在 **onBindViewHolder（主线程）** 里查
     * `originalWidth/Height`（Webtoon 要按原始宽高比钉死行高），而渲染在 IO 上跑、且**整个
     * native 渲染期间都持着渲染器那把锁**（真机实测一页 300dpi 渲染 80~275ms）。共用一个渲染器
     * 时主线程会整整阻塞一次渲染 —— Webtoon 快速滑动时连续掉帧，最坏 ANR。
     * 尺寸查询走独立实例后，两边互不阻塞，只剩 `openPage` 本身的开销
     * （实测 A5 页 0.9ms / 高密度页 27ms，每页只查一次并进 map 缓存）。
     *
     * 反面方案已排除：**构造期一次性预取全部页尺寸**（真机实测高密度页 27ms/页 → 几百页就是秒级，
     * 而且全落在"打开阅读器"那一下）。任何"查不到就返回 0"的写法也不行 ——
     * 那会让 Webtoon 行高退回 `WRAP_CONTENT`（模块文档里记过的「页面一直跳」）。
     */
    private val pdfGeometry: PdfPageRenderer? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (isPdf) PdfPageRenderer(inputFile) else null
    }

    /** 关闭 = **终态**（见 [close]）：之后所有读取直接返回空，不再打开文件。 */
    @Volatile private var closed = false

    /**
     * 页序 + 章节表：**由实际文件推导**（不与清单里存的那份对比，文件才是事实来源）。
     *
     * ⚠️ 必须与导入侧用**同一个切分器**（[MangaChapterSplitter]）：它同时负责「按章分组」
     * 与「章内自然排序」，两边算出来不一样的话章区间就会错位（点第3章跳到别人的页）。
     * 清单里的章节仅用于书架显示；当前页序供读取，不回写外部编辑。
     */
    /** 原始页 key（**未排序**）：分章/排序的输入，页序迁移也要用它算旧顺序。 */
    private val rawKeys: List<String> = try { resolveRawPages() }
        catch (e: Exception) { nativeArchive?.close(); cachedZip?.close(); pdf?.close(); sourceLease.close(); throw e }

    private val split: MangaChapterSplitter.Result = MangaChapterSplitter.split(rawKeys)

    private val pageKeys: List<String> = split.keys

    /** 章节表（含 `startPage`/`pageCount`，都是 [pageKeys] 的下标区间）。 */
    val chapters: List<MangaChapter> get() = split.chapters

    // 按「张数」计数的缩略图缓存（每张 ~几十 KB，48 张约 2-4MB）
    private val thumbCache: LruCache<Int, Bitmap> = object : LruCache<Int, Bitmap>(MAX_THUMBS) {
        override fun sizeOf(key: Int, value: Bitmap) = 1
    }

    // Webtoon 连续滚动采样图缓存（按像素预算计数，避免滚动反复全图解码）
    private val webtoonCache = object : LruCache<Int, Bitmap>(WEBTOON_CACHE_PIXEL_BUDGET) {
        override fun sizeOf(key: Int, value: Bitmap) =
            value.allocationByteCount.coerceAtLeast(value.rowBytes * value.height)
    }

    // ── ⚠️ 这里**不再有任何超分/增强**（2026-10 v2 架构，改动前必读）──
    //
    // 曾经的 `enhanceCache` + `enhanceForReader(position, src)` 已整体删除。原因有两条，都是硬的：
    //
    // 1. **它在主线程上跑推理 → ANR / 系统卡死**。`loadFull` 的调用方里有
    //    `ReaderAdapters.kt:89/:221`（在 `onBindViewHolder` 里！）—— 一次 ONNX 2x 是 1~3 秒、
    //    Anime4K mode A 是 49 趟 GL，直接卡死整个界面。用户报的"开启超分系统卡死"就是这个。
    //
    // 2. **超分与 OCR 解耦**（用户口径 2026-10）：OCR **永远在原图上**做，
    //    超分只在 OCR **之后**、作为**显示底图**出现。所以 `loadFull` 只管解码，
    //    增强归「显示底图」那条独立路径（`SrDisplaySource`）。
    //
    // ⇒ **`loadFull` / `loadWebtoon` 里绝不允许出现任何 `upscale*` / 增强调用。**
    //    `ReaderSrThreadingTest` 用源码级断言钉死这一条。

    // 复用一个打开的 ZipFile：避免每页都重新读中央目录（400 页漫画的关键开销）
    private fun zip(): ZipFile? {
        if (nativeArchive != null) return null
        cachedZip?.let { return it }
        return synchronized(this) {
            cachedZip ?: try {
                ZipFile(inputFile).also { cachedZip = it }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ── PDF 源（`isPdf`）──
    //
    // PDF 没有"文件里写死的像素尺寸"，页图要现渲（一次性 ~100-500ms/页）。所以：
    // ① 渲染器只开一份（`PdfRenderer` 同一时刻只能开一个 Page，且 FD 是有限资源）；
    // ② 渲出来的**权威尺寸**位图进一个小 LRU 缓存 —— 同一页在一次翻译里会被要 2~4 次
    //    （显示 / OCR / 超分输入 / 导出渲染），缓存后只需要渲一次、其余给副本。
    //
    // ⚠️ **缓存里的位图绝不外借**：`loadFull` 的契约是"调用方拥有的新位图"（调用方会 recycle），
    //    直接把缓存实例交出去会被回收成废图 → 一律返回 `copy()`。
    // ⚠️ 缓存预算只放得下 1~2 页（一页 300DPI 的 A5 ≈ 17MB、B5 ≈ 26MB），这是**故意**的：
    //    阅读是顺序的，留太多页等于和 `renderLru`(100MB) 抢内存。
    private val pdfFullCache = object : LruCache<Int, Bitmap>(PdfRasterPolicy.CANONICAL_CACHE_BUDGET_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) =
            value.allocationByteCount.coerceAtLeast(value.rowBytes * value.height)
        // ⚠️ **刻意不在 entryRemoved 里 recycle**：被挤出的那一瞬间，另一个线程可能正在
        //    `copy()` 它（缓存命中 → 复制出借）。`copy()` 撞上 recycle 会抛
        //    IllegalStateException（被 runCatching 吞掉 → 那一页白屏），是偶发且难复现的坑。
        //    位图像素在 Java 堆上（API 26+），交给 GC 回收即可；LruCache 的额度仍然有效。
    }

    /** PDF 该页的权威像素尺寸（`[宽, 高]`）；不是 PDF / 取不到返回 null。走**独立**渲染器，见 [pdfGeometry]。 */
    private fun pdfCanonicalSize(position: Int): IntArray? {
        if (closed) return null
        return pdfGeometry?.canonicalSize(position)?.takeIf { it[0] > 0 && it[1] > 0 }
    }

    /** PDF 全尺寸页图：缓存优先，未命中才真渲。返回**副本**（调用方拥有，负责 recycle）。 */
    private fun pdfLoadFull(position: Int): Bitmap? {
        if (closed) return null
        pdfFullCache.get(position)?.takeIf { !it.isRecycled }?.let { cached ->
            return runCatching { cached.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
        val rendered = pdf?.renderCanonical(position) ?: return null
        pdfFullCache.put(position, rendered)
        return runCatching { rendered.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    /**
     * PDF 按采样尺寸渲染（缩略图 / Webtoon 采样图）：在**目标尺寸**上直接光栅化，不是先渲大再缩。
     *
     * ⚠️ 尺寸计算收在 `PdfPageRenderer` 里（一次 `openPage` 内算完就渲）——
     *    在这里先查 `pdfCanonicalSize` 再渲染会多开一次页（真机实测 0.9~27ms/页）。
     */
    private fun pdfRenderSampled(position: Int, sample: Int): Bitmap? {
        if (closed) return null
        return pdf?.renderScaledBySample(position, sample)
    }

    /** PDF Webtoon 采样图（按屏宽）。 */
    private fun pdfRenderToWidth(position: Int, targetWidth: Int): Bitmap? {
        if (closed) return null
        return pdf?.renderSampledToWidth(position, targetWidth)
    }

    private val readErrors = ConcurrentHashMap<Int, String>()
    fun pageError(position: Int): String? = readErrors[position]
    val size: Int get() = pageKeys.size

    /** 某页对应的存储 key（zip 内 entry 名 / 目录相对路径），供导出等场景。 */
    fun key(position: Int): String? = pageKeys.getOrNull(position)

    /** 载入全尺寸页图（应在 IO 线程调用）。 */
    fun loadFull(position: Int): Bitmap? {
        if (closed || pageKeys.getOrNull(position) == null) return null
        return try {
            if (isPdf) pdfLoadFull(position)
            else openEntry(position)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            readErrors[position] = "${pageKeys.getOrNull(position)}：${e.javaClass.simpleName}：${e.message}"
            LogCollector.w(TAG, "读取原图失败 position=$position path=$localRoot", e)
            null
        }
    }

    /**
     * 原始条目字节流（双语包导出原文用）。调用方负责 close。
     *
     * ⚠️ 走原始字节而不是 [loadFull] + 重新编码：原图多是 JPEG，解成 Bitmap 再压回去就是**二次
     * 有损压缩**（画质掉一档、体积还可能更大），而这里只是原样搬运。
     *
     * ⚠️ **PDF 是例外**：PDF 里没有"这一页的原始字节流"（页是矢量/图像对象的组合），
     * 只能现渲再编码成 JPEG。所以 PDF 的"原名原文条目"精度等同于渲染结果 ——
     * PDF 的正式导出出口是 PDF（见 `ReaderPdfExport`），这里只是保证旧链路（zip 双语包）
     * 遇到 PDF 时不至于交白卷。
     */
    fun openEntry(position: Int): java.io.InputStream? {
        if (closed) return null
        val key = pageKeys.getOrNull(position) ?: return null
        if (isPdf) return pdfEntryStream(position)
        return try {
            if (nativeArchive != null) nativeArchive.reader.openEntry(key)
            else if (isArchive) {
                val zip = zip() ?: return null
                val entry = zip.getEntry(key) ?: return null
                zip.getInputStream(entry)
            } else LibrarySources.open(File(localRoot, key))
        } catch (e: Exception) {
            readErrors[position] = "$key：${e.javaClass.simpleName}：${e.message}"
            LogCollector.w(TAG, "读取页面失败：$key", e)
            null
        }
    }

    /** PDF：把该页渲成等价于原图的 JPEG 字节流（见 [openEntry] 的 ⚠️）。 */
    private fun pdfEntryStream(position: Int): java.io.InputStream? {
        val bmp = pdfLoadFull(position) ?: return null
        return try {
            val out = java.io.ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, PDF_ENTRY_JPEG_QUALITY, out)) return null
            java.io.ByteArrayInputStream(out.toByteArray())
        } catch (e: Exception) {
            LogCollector.w(TAG, "openEntry(PDF): 编码失败 position=$position（${e.message}）")
            null
        } finally {
            bmp.recycle()
        }
    }

    /** 载入缩略图（应在 IO 线程调用），缓存命中直接返回。 */
    fun loadThumb(position: Int): Bitmap? {
        if (closed) return null
        thumbCache.get(position)?.let { return it }
        val bmp = decodeThumb(position) ?: return null
        thumbCache.put(position, bmp)
        return bmp
    }

    /**
     * Webtoon 连续滚动用：按目标宽度**采样解码**（不放大、限高），带 LRU 缓存。
     * 300-400 页大漫画全图解码会内存爆炸/滚动卡死，必须降采样到屏宽再显示。
     * 应在 IO 线程调用。
     */
    /** 翻起区采样目标宽（主线程安全设置；由阅读器打开时设为屏幕宽）。 */
    @Volatile
    var webtoonWidth = 0

    fun loadWebtoon(position: Int, targetWidth: Int): Bitmap? {
        if (closed) return null
        webtoonCache.get(position)?.let { return it }
        val bmp = decodeWebtoon(position, targetWidth)
        if (bmp != null) webtoonCache.put(position, bmp)
        return bmp
    }

    /**
     * 主线程安全：取下一页采样图供翻起区绘制。
     * 缓存未命中时**同步解码兜底**（每页仅一次，随后命中缓存）→ 翻起区一定有下一页，不黑屏。
     */
    fun peekWebtoon(position: Int): Bitmap? {
        if (closed) return null
        webtoonCache.get(position)?.let { return it }
        if (webtoonWidth <= 0) return null
        val bmp = decodeWebtoon(position, webtoonWidth)
        if (bmp != null) webtoonCache.put(position, bmp)
        return bmp
    }

    private fun decodeWebtoon(position: Int, targetWidth: Int): Bitmap? {
        if (closed) return null
        return try {
            if (isPdf) pdfRenderToWidth(position, targetWidth)
            else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openEntry(position)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                decodeEntryScaled(position, computeSample(bounds.outWidth, bounds.outHeight, targetWidth))
            }
        } catch (e: Exception) { LogCollector.w(TAG, "读取采样页失败", e); null }
    }

    /** 页图**原始**尺寸缓存（只读元数据，不解码像素）。0 = 读不到。
     *  ConcurrentHashMap：主线程（适配器钉行高）与 IO 线程（译图缩放）都会读。 */
    private val originalWidths = ConcurrentHashMap<Int, Int>()
    private val originalHeights = ConcurrentHashMap<Int, Int>()

    /** 该页**原图**宽度（只读元数据，不解码像素）。读不到返回 0。
     *
     * 用途：Webtoon 译图必须按屏宽**采样解码**（Webtoon 页可能是 1080×12000，全解析一页就
     * 50MB+），而气泡坐标是在**原图空间**算出来的 —— 渲染到采样图时必须按
     * `采样图宽 ÷ 原图宽` 等比缩放，否则译文会整体偏移、字号也不对。
     */
    fun originalWidth(position: Int): Int {
        if (nativeArchive == null && (isArchive || isPdf || com.moe.starflow.library.LibrarySessions.findFile(File(localRoot))?.first?.storage !is com.moe.starflow.library.SafLibraryStorage)
            || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) ensureOriginalSize(position)
        return originalWidths[position] ?: 0
    }

    /**
     * 该页**原始文件**的字节数（拿不到 → 0）。
     *
     * 用途：超分面板的「详情」要对比「原始 / 超分后」的大小（用户口径 2026-10）。
     * ⚠️ zip 走**中央目录里记的** `entry.size`（不解压）；目录导入走 `File.length()`。
     * 两者都是"文件的大小"，不是"解码后占的内存"—— 这正是用户要看的东西。
     *
     * ⚠️ **PDF 恒为 0**：PDF 里没有"某一页的文件字节数"（页是矢量/图像对象的组合，
     * 还常与其他页共用资源流）——不编一个"平均每页大小"这种看着像真数据的假值。
     * 0 → 面板显示 `-`（`ReaderSrStateAdapter.fmtBytes` 的既有口径）。
     */
    fun pageBytes(position: Int): Long {
        if (closed || isPdf) return 0
        val key = pageKeys.getOrNull(position) ?: return 0
        return runCatching {
            if (nativeArchive != null) 0L
            else if (isArchive) zip()?.getEntry(key)?.size ?: 0L
            else LibrarySources.file(File(localRoot, key)).length()
        }.getOrDefault(0)
    }

    /** 原始页 key（未排序）—— 只给页序迁移用（[com.moe.starflow.mangaimport.data.MangaPageOrder]）。 */
    fun rawPageKeys(): List<String> = rawKeys

    /** 当前权威页序（分章后的阅读顺序）。 */
    fun orderedPageKeys(): List<String> = pageKeys

    /** 该页**原图**高度。用途：适配器按原始宽高比把行高**钉死**，见 [WebtoonAdapter] 的绑定注释。 */
    fun originalHeight(position: Int): Int {
        if (nativeArchive == null && (isArchive || isPdf || com.moe.starflow.library.LibrarySessions.findFile(File(localRoot))?.first?.storage !is com.moe.starflow.library.SafLibraryStorage)
            || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) ensureOriginalSize(position)
        return originalHeights[position] ?: 0
    }

    @Synchronized
    private fun ensureOriginalSize(position: Int) {
        if (closed || originalWidths.containsKey(position)) return
        var w = 0; var h = 0
        runCatching {
            if (isPdf) {
                val size = pdfCanonicalSize(position)
                w = size?.get(0) ?: 0; h = size?.get(1) ?: 0
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openEntry(position)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                w = bounds.outWidth; h = bounds.outHeight
            }
        }.onFailure { LogCollector.w(TAG, "读取原图尺寸失败", it) }
        originalWidths[position] = w
        originalHeights[position] = h
    }

    /** 采样率：按目标宽度适配 + 限制超高页 + 限制总像素（防超大页 OOM 卡死）。
     *
     *  ⚠️ 判据**只有一份**（[PdfRasterPolicy.sampleFor]）：图片路径与 PDF 路径共用它，
     *     各写一份就会出现"同一页在原图/Webtoon 下算出不同尺寸"，气泡缩放系数随之对不上。 */
    private fun computeSample(boundsW: Int, boundsH: Int, targetWidth: Int): Int =
        PdfRasterPolicy.sampleFor(boundsW, boundsH, targetWidth)

    private fun decodeThumb(position: Int): Bitmap? {
        if (closed) return null
        return runCatching { if (isPdf) pdfRenderSampled(position, THUMB_SAMPLE)
            else decodeEntryScaled(position, THUMB_SAMPLE) }.getOrNull()
    }

    private fun decodeEntryScaled(position: Int, sample: Int): Bitmap? =
        openEntry(position)?.use { BitmapFactory.decodeStream(it, null,
            BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }) }

    private fun decodeZipEntry(zip: ZipFile, name: String, sample: Int): Bitmap? {
        val entry = zip.getEntry(name) ?: return null
        zip.getInputStream(entry).use { input ->
            if (sample <= 1) return BitmapFactory.decodeStream(input)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        return zip.getInputStream(entry).use { input ->
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeStream(input, null, opts)
        }
    }

    private fun decodeFileScaled(file: File, sample: Int): Bitmap? {
        if (sample <= 1) return BitmapFactory.decodeFile(file.absolutePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** 枚举原始页 key（未排序：排序与分章都由 [MangaChapterSplitter] 一处负责）。 */
    private fun resolveRawPages(): List<String> {
        // PDF：页序就是 page tree 的顺序（**权威且固定**，不需要按文件名猜顺序），
        // 所以直接按页数生成合成 key（规则见 [PdfPageKeys]，纯函数、有单测）。
        if (isPdf) {
            val count = pdf?.pageCount ?: 0
            if (count <= 0) {
                // 打不开/文件丢了：返回空页表，由阅读器侧走「文件丢失」提示，而不是崩溃
                LogCollector.w(TAG, "PDF 打不开或 0 页: $localRoot")
                return emptyList()
            }
            return PdfPageKeys.all(count)
        }
        nativeArchive?.let { return it.reader.entries.filter(ArchivedMangaReader::isImageFile) }
        return if (isArchive) {
            val out = mutableListOf<String>()
            try {
                ZipFile(inputFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (!e.isDirectory && ArchivedMangaReader.isImageFile(e.name)) out.add(e.name)
                    }
                }
                out
            } catch (e: Exception) {
                // zip 缺失/损坏时返回空，由阅读器侧展示「文件丢失」提示，而不是崩溃
                emptyList()
            }
        } else {
            LibrarySources.listImages(File(localRoot)) ?: ArchivedMangaReader.listImageFilesInDir(File(localRoot))
        }
    }

    /**
     * 释放持有的文件句柄（PDF 的 FD / 缓存的 ZipFile）与缓存。
     *
     * ⚠️ **这是终态**：之后所有读取都返回 null/空（[closed]），**不会再重新打开文件**。
     * 原因是"关闭"发生在退出阅读器 / 控制器被回收的那一刻，而在途的 `loadFull` 可能还在跑
     * （读的是关闭前捕获的 lambda）—— 允许重开的话那次调用会重新打开整本 PDF，而这个数据源
     * 此后再也没人关 → 每退出一次漏一个 FD。合法用法的重建路径都是**新建数据源**
     * （阅读器重进、控制器重建），不需要"复活"。
     *
     * ⚠️ 必须有人调：`PdfRenderer` 持有一个 `ParcelFileDescriptor`，FD 是有限资源；
     * 阅读器 `onDestroy` 与控制器回收（`ReaderTranslationHub.releaseIfIdle`）各调一次。
     */
    @Synchronized
    fun close() {
        closed = true
        runCatching { pdf?.close() }
        runCatching { pdfGeometry?.close() }
        runCatching { cachedZip?.close() }
        cachedZip = null
        runCatching { pdfFullCache.evictAll() }
        nativeArchive?.close()
        sourceLease.close()
    }

    private companion object {
        const val THUMB_SAMPLE = 4
        const val MAX_THUMBS = 48
        // Webtoon 采样缓存像素预算（~60MB，约覆盖十几屏）
        const val WEBTOON_CACHE_PIXEL_BUDGET = 60 * 1024 * 1024
        // PDF 走旧 zip 链路导出原文时的 JPEG 质量（正式出口是 PDF，见 ReaderPdfExport）
        const val PDF_ENTRY_JPEG_QUALITY = 92
    }
}