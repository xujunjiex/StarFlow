package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * 阅读器页面数据源：页解析（zip / 目录）+ 全图 / 缩略图加载。
 *
 * - 全图：完整解码（ViewPager2 页显示）
 * - 缩略图：按目标尺寸降采样解码，带 LruCache（进度条/预览网格用，避免重复解大图）
 * 统一处理 zip（ZipFile 读 entry）与目录（File 枚举）两种存储源。
 */
class ReaderPageSource(
    private val isArchive: Boolean,
    private val localRoot: String
) {

    private val pageKeys: List<String> = resolvePages()

    // 按「张数」计数的缩略图缓存（每张 ~几十 KB，48 张约 2-4MB）
    private val thumbCache: LruCache<Int, Bitmap> = object : LruCache<Int, Bitmap>(MAX_THUMBS) {
        override fun sizeOf(key: Int, value: Bitmap) = 1
    }

    // Webtoon 连续滚动采样图缓存（按像素预算计数，避免滚动反复全图解码）
    private val webtoonCache = object : LruCache<Int, Bitmap>(WEBTOON_CACHE_PIXEL_BUDGET) {
        override fun sizeOf(key: Int, value: Bitmap) =
            value.allocationByteCount.coerceAtLeast(value.rowBytes * value.height)
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
        val key = pageKeys.getOrNull(position) ?: return null
        return try {
            if (isArchive) {
                ZipFile(File(localRoot)).use { zip -> decodeZipEntry(zip, key, 1) }
            } else {
                BitmapFactory.decodeFile(File(localRoot, key).absolutePath)
            }
        } catch (e: Exception) {
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

    private fun resolvePages(): List<String> {
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
                ArchivedMangaReader.sortNaturally(out)
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