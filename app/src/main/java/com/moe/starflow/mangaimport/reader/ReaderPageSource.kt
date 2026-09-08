package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.moe.starflow.mangaimport.data.ArchivedMangaReader
import java.io.File
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

    /** 载入缩略图（应在 IO 线程调用），缓存命中直接返回。 */
    fun loadThumb(position: Int): Bitmap? {
        thumbCache.get(position)?.let { return it }
        val bmp = decodeThumb(position) ?: return null
        thumbCache.put(position, bmp)
        return bmp
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
            ZipFile(File(localRoot)).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.isDirectory && ArchivedMangaReader.isImageFile(e.name)) out.add(e.name)
                }
            }
            ArchivedMangaReader.sortNaturally(out)
        } else {
            ArchivedMangaReader.listImageFilesInDir(File(localRoot))
        }
    }

    private companion object {
        const val THUMB_SAMPLE = 4
        const val MAX_THUMBS = 48
    }
}