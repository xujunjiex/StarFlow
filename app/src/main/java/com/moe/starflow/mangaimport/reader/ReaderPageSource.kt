package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import com.moe.starflow.mangaimport.data.MangaChapter
import com.moe.starflow.mangaimport.data.MangaChapterSplitter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.moe.starflow.utils.LogCollector
import java.util.zip.ZipFile

/**
 * 阅读器页面数据源：页解析（zip / 目录）+ 全图 / 缩略图加载。
 *
 * - 全图：完整解码（ViewPager2 页显示）
 * - 缩略图：按目标尺寸降采样解码，带 LruCache（进度条/预览网格用，避免重复解大图）
 * 统一处理 zip（ZipFile 读 entry）与目录（File 枚举）两种存储源。
 */
private const val TAG = "ReaderPageSource"

/**
 * 阅读器「先超分再翻译」的增强结果缓存预算（按像素计）。
 *
 * ⚠️ 增强结果**与原图同尺寸**（见 [com.moe.starflow.sr.SrPageEnhancer] 类注释），
 * 所以一份就是一张整页 ARGB_8888。给 24MB ≈ 6~8 张 A4 页面；翻回去能秒出，
 * 又不会把整本书攒在内存里。
 */
private const val ENHANCE_CACHE_PIXEL_BUDGET = 24 * 1024 * 1024

class ReaderPageSource(
    private val isArchive: Boolean,
    private val localRoot: String
) {

    /**
     * 页序 + 章节表：**由实际文件推导**（不与清单里存的那份对比，文件才是事实来源）。
     *
     * ⚠️ 必须与导入侧用**同一个切分器**（[MangaChapterSplitter]）：它同时负责「按章分组」
     * 与「章内自然排序」，两边算出来不一样的话章区间就会错位（点第3章跳到别人的页）。
     * 清单里那份只给书架显示章数用，阅读器这份才是权威 —— 两者不一致时以这份为准并回写清单。
     */
    /** 原始页 key（**未排序**）：分章/排序的输入，页序迁移也要用它算旧顺序。 */
    private val rawKeys: List<String> = resolveRawPages()

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

    // ── 阅读器「先超分再翻译」的增强结果缓存 ──
    // 超分一次 1~3s，不缓存的话每次翻回同一页都要重算（翻页体验直接崩）。
    private val enhanceCache = object : LruCache<Int, Bitmap>(ENHANCE_CACHE_PIXEL_BUDGET) {
        override fun sizeOf(key: Int, value: Bitmap) =
            value.allocationByteCount.coerceAtLeast(value.rowBytes * value.height)
    }

    /** 上次增强所用的设置指纹（[com.moe.starflow.sr.SrPageEnhancer.signature]）；变了就清缓存 */
    @Volatile
    private var enhanceSignature: String? = null

    /**
     * 按当前设置增强一页。
     *
     * 增强**必须与原图同尺寸**（否则 OCR 出的 `bubbleRects` 会整体放大，之后按原图重渲染时错位），
     * 这一点由 [com.moe.starflow.sr.SrPageEnhancer] 保证。
     *
     * 任何一环不满足（没开开关 / 没选模型也没开 Anime4K / 增强失败）都**返回原图** ——
     * 增强是锦上添花，绝不能因为它翻不了页或翻不了译。
     */
    /**
     * 超分缓存**快路径**：命中就返回一份独立副本，调用方**不必再解码整页**
     * （`loadFull` 每次适配器绑定都会被调，白解一张 ~9MB 的全尺寸图很亏）。
     *
     * @return null = 没开超分 / 签名已变 / 未命中 / 副本分配失败（都当未命中，走完整流程）
     */
    private fun enhanceCachedOrNull(position: Int): Bitmap? {
        val sig = com.moe.starflow.sr.SrPageEnhancer.signature() ?: return null
        if (sig != enhanceSignature) return null
        val cached = enhanceCache.get(position) ?: return null
        if (cached.isRecycled) {
            enhanceCache.remove(position)
            return null
        }
        // ⚠️ 必须给**独立副本**：翻译/导出路径会 recycle 它们拿到的位图（见下面 enhanceForReader 的说明）
        return runCatching { cached.copy(cached.config ?: Bitmap.Config.ARGB_8888, true) }.getOrNull()
            ?: run {
                // copy 失败（OOM 时正是它失败）绝不能退回 cached 本身 → 当未命中
                enhanceCache.remove(position)
                null
            }
    }

    private fun enhanceForReader(position: Int, src: Bitmap): Bitmap {
        val sig = com.moe.starflow.sr.SrPageEnhancer.signature() ?: return src
        if (sig != enhanceSignature) {
            // 设置变了（换模型/换档位/开关）→ 旧的增强图全部作废
            enhanceCache.evictAll()
            enhanceSignature = sig
        }
        // ⚠️ **所有权**：翻译/导出路径会 `recycle()` 它们从 `loadFull` 拿到的位图
        // （`translatePhase` 的 finally、`renderForExport` 的 orig.recycle()）。
        // 直接把手伸进缓存的那一份交出去 = 缓存里留下一个已 recycle 的位图 →
        // 下次命中返回它 → 空白页 / "Canvas: trying to use a recycled bitmap" 崩溃。
        // 所以：缓存永远留自己的一份副本，**交给调用方的永远是独立实例**。
        enhanceCache.get(position)?.let { cached ->
            val copy = if (cached.isRecycled) null else runCatching {
                cached.copy(cached.config ?: Bitmap.Config.ARGB_8888, true)
            }.getOrNull()
            // ⚠️ copy 失败（OOM 时正是它失败）**绝不能退回 cached 本身**：那又是一份"调用方会 recycle"
            // 的共享实例 —— 等于把刚修掉的 use-after-recycle 崩溃请回来。失败就当缓存未命中，重做一次。
            if (copy != null) return copy
            enhanceCache.remove(position)
        }
        val out = com.moe.starflow.sr.SrPageEnhancer.enhanceForReader(src) ?: return src
        if (out !== src) {
            // mutable=true 保证 copy 一定是**新分配**（immutable 源 + copy(false)/createBitmap 会返回原实例）
            runCatching { out.copy(out.config ?: Bitmap.Config.ARGB_8888, true) }
                .getOrNull()?.let { enhanceCache.put(position, it) }
        }
        return out
    }

    // 复用一个打开的 ZipFile：避免每页都重新读中央目录（400 页漫画的关键开销）
    @Volatile private var cachedZip: ZipFile? = null

    private fun zip(): ZipFile? {
        cachedZip?.let { return it }
        return synchronized(this) {
            cachedZip ?: try {
                ZipFile(File(localRoot)).also { cachedZip = it }
            } catch (e: Exception) {
                null
            }
        }
    }

    val size: Int get() = pageKeys.size

    /** 某页对应的存储 key（zip 内 entry 名 / 目录相对路径），供导出等场景。 */
    fun key(position: Int): String? = pageKeys.getOrNull(position)

    /** 载入全尺寸页图（应在 IO 线程调用）。 */
    fun loadFull(position: Int): Bitmap? {
        val key = pageKeys.getOrNull(position)
        if (key == null) {
            // ⚠️ 以前这里静默返回 null：调用方只知道"图没了"，不知道是"页号越界 / 页表为空"。
            // 阅读器翻译在 cbz 上"点了没反应、日志一片空白"就是被这类静默 return 埋掉的。
            LogCollector.w(TAG, "loadFull: 页号越界 position=$position（size=${pageKeys.size}）")
            return null
        }
        // ⚠️ 先查超分缓存：命中直接给副本 —— 不解码、不走下面的解码/增强流程
        enhanceCachedOrNull(position)?.let { return it }
        return try {
            val bmp = if (isArchive) {
                // 复用缓存的 ZipFile（与 openEntry 同一条路径）：每次新开都要重读中央目录，
                // 大包上尤其亏（原来这里每次 loadFull 都 new 一个）
                val zip = zip()
                if (zip == null) {
                    LogCollector.w(TAG, "loadFull: 压缩包打不开 isArchive=true path=$localRoot key=$key")
                    null
                } else {
                    decodeZipEntry(zip, key, 1)
                }
            } else {
                BitmapFactory.decodeFile(File(localRoot, key).absolutePath)
            }
            if (bmp == null) LogCollector.w(TAG, "loadFull: 解码失败（返回 null）key=$key")
            // 「先超分再翻译」：增强结果与原图同尺寸，坐标空间不变（见 enhanceForReader 注释）
            val enhanced = bmp?.let { enhanceForReader(position, it) }
            // ⚠️ 增强产出的是**新位图**时，把刚解码的源图还回去（缓存里已存独立副本，
            // 源图此刻只被这里引用）—— 否则每页多留一张全尺寸位图等 GC。
            if (bmp != null && enhanced != null && enhanced !== bmp) bmp.recycle()
            enhanced
        } catch (e: Exception) {
            // ⚠️ **不能再静默吞掉**：解不出来必须留下可供排查的原因（条目缺失/流被关/解码异常）。
            // 这条 W 日志是"cbz 无法翻译"这类问题的第一现场证据。
            LogCollector.w(TAG, "loadFull: 解码异常 key=$key（${e.javaClass.simpleName}: ${e.message}）")
            null
        }
    }

    /**
     * 原始条目字节流（双语包导出原文用）。调用方负责 close。
     *
     * ⚠️ 走原始字节而不是 [loadFull] + 重新编码：原图多是 JPEG，解成 Bitmap 再压回去就是**二次
     * 有损压缩**（画质掉一档、体积还可能更大），而这里只是原样搬运。
     */
    fun openEntry(position: Int): java.io.InputStream? {
        val key = pageKeys.getOrNull(position) ?: return null
        return try {
            if (isArchive) {
                val zip = zip() ?: return null
                val entry = zip.getEntry(key) ?: return null
                zip.getInputStream(entry)
            } else {
                val file = File(localRoot, key)
                if (file.isFile) file.inputStream() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 载入缩略图（应在 IO 线程调用），缓存命中直接返回。 */
    fun loadThumb(position: Int): Bitmap? {
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
        webtoonCache.get(position)?.let { return it }
        if (webtoonWidth <= 0) return null
        val bmp = decodeWebtoon(position, webtoonWidth)
        if (bmp != null) webtoonCache.put(position, bmp)
        return bmp
    }

    private fun decodeWebtoon(position: Int, targetWidth: Int): Bitmap? {
        val key = pageKeys.getOrNull(position) ?: return null
        return try {
            if (isArchive) {
                val zip = zip() ?: return null
                val entry = zip.getEntry(key) ?: return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                decodeZipEntry(zip, key, computeSample(bounds.outWidth, bounds.outHeight, targetWidth))
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(File(localRoot, key).absolutePath, bounds)
                decodeFileScaled(File(localRoot, key), computeSample(bounds.outWidth, bounds.outHeight, targetWidth))
            }
        } catch (e: Exception) {
            null
        }
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
        ensureOriginalSize(position)
        return originalWidths[position] ?: 0
    }

    /** 原始页 key（未排序）—— 只给页序迁移用（[com.moe.starflow.mangaimport.data.MangaPageOrder]）。 */
    fun rawPageKeys(): List<String> = rawKeys

    /** 当前权威页序（分章后的阅读顺序）。 */
    fun orderedPageKeys(): List<String> = pageKeys

    /** 该页**原图**高度。用途：适配器按原始宽高比把行高**钉死**，见 [WebtoonAdapter] 的绑定注释。 */
    fun originalHeight(position: Int): Int {
        ensureOriginalSize(position)
        return originalHeights[position] ?: 0
    }

    @Synchronized
    private fun ensureOriginalSize(position: Int) {
        if (originalWidths.containsKey(position)) return
        val key = pageKeys.getOrNull(position) ?: return
        var w = 0
        var h = 0
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (isArchive) {
                val zip = zip()
                val entry = zip?.getEntry(key)
                if (zip != null && entry != null) {
                    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                    w = bounds.outWidth
                    h = bounds.outHeight
                }
            } else {
                BitmapFactory.decodeFile(File(localRoot, key).absolutePath, bounds)
                w = bounds.outWidth
                h = bounds.outHeight
            }
        } catch (e: Exception) {
            w = 0
            h = 0
        }
        originalWidths[position] = w
        originalHeights[position] = h
    }

    /** 采样率：按目标宽度适配 + 限制超高页 + 限制总像素（防超大页 OOM 卡死）。 */
    private fun computeSample(boundsW: Int, boundsH: Int, targetWidth: Int): Int {
        val w = boundsW.coerceAtLeast(1)
        val h = boundsH.coerceAtLeast(1)
        val target = targetWidth.coerceAtLeast(1)
        val maxHeight = target * 12          // 高宽比上限 ~12:1
        val maxArea = 12_000_000            // 单幅像素上限 ~48MB
        var sample = 1
        while (true) {
            val sw = w / sample
            val sh = h / sample
            if (sw <= target && sh <= maxHeight && sw * sh <= maxArea) break
            sample *= 2
        }
        return sample
    }

    private fun decodeThumb(position: Int): Bitmap? {
        val key = pageKeys.getOrNull(position) ?: return null
        return try {
            if (isArchive) {
                ZipFile(File(localRoot)).use { zip ->
                    decodeZipEntry(zip, key, THUMB_SAMPLE)
                }
            } else {
                decodeFileScaled(File(localRoot, key), THUMB_SAMPLE)
            }
        } catch (e: Exception) {
            null
        }
    }

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
        return if (isArchive) {
            val out = mutableListOf<String>()
            try {
                ZipFile(File(localRoot)).use { zip ->
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
            ArchivedMangaReader.listImageFilesInDir(File(localRoot))
        }
    }

    private companion object {
        const val THUMB_SAMPLE = 4
        const val MAX_THUMBS = 48
        // Webtoon 采样缓存像素预算（~60MB，约覆盖十几屏）
        const val WEBTOON_CACHE_PIXEL_BUDGET = 60 * 1024 * 1024
    }
}