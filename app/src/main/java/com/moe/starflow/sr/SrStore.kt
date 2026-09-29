package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.moe.starflow.utils.LogCollector
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 超分结果的**落盘**（唯一入口，照 koto 的做法 + 按用户口径改三处）。
 *
 * ## 目录与命名
 * ```
 * getExternalFilesDir("sr_cache")/<mangaId>_<pageIndex>.webp     ← 超分底图（有损 WEBP q90）
 * getExternalFilesDir("sr_cache")/<mangaId>_<pageIndex>.json     ← 模型标记
 * ```
 *
 * ## ⚠️ 与 koto 的三处刻意差异
 * | | koto | 我们 | 为什么 |
 * |---|---|---|---|
 * | 目录 | `context.cacheDir/sr_cache` | **`getExternalFilesDir`** | `cacheDir` 会被系统清掉 → 出现"标记说已超分、文件却没了"的不一致 |
 * | 文件名 | `${fileName}_${modelId}_sr` 的 hashCode | **只有 mangaId+页码** | 用户口径：**永远只保留一份**，换模型**覆盖**（koto 是每模型一份，靠 LRU 淘汰） |
 * | 触发 | 下载时预生成（`PREPARE_SUPER_RESOLUTION`） | **只由用户触发** | 用户口径：不需要下载时预生成 |
 *
 * 相同的是：**有损 WEBP q90**（实测 2x 一页 ≈ 249KB、6 页均值 274KB；无损要 **1533KB/5.6 倍**，
 * 一本 200 页 299MB，不可接受）、按 `lastModified` 做 LRU。
 *
 * ## 真值口径
 * **以"文件是否存在且非空"为真值**；JSON 标记只用来回答"是哪个模型超的"。
 * 文件被系统/用户清掉 → 自动视为未超分，不会出现"标记说超了但图是原图"。
 */
object SrStore {

    private const val TAG = "SrStore"

    /** 子目录名（`getExternalFilesDir` 下） */
    const val DIR_NAME = "sr_cache"

    /** 落盘质量：与 koto 一致 */
    private const val QUALITY = 90

    /** 默认上限 512MB（一本 200 页 2x ≈ 54MB，够用几十本） */
    const val DEFAULT_LIMIT_MB = 512

    /** 用户可在设置里关掉上限（传负数） */
    const val UNLIMITED = -1

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }

    private fun imageFile(ctx: Context, mangaId: Long, page: Int): File =
        File(dir(ctx), "${mangaId}_$page.webp")

    private fun markerFile(ctx: Context, mangaId: Long, page: Int): File =
        File(dir(ctx), "${mangaId}_$page.json")

    /** 该页是否已有超分结果（**以文件为准**，不看标记） */
    fun exists(ctx: Context, mangaId: Long, page: Int): Boolean =
        imageFile(ctx, mangaId, page).let { it.isFile && it.length() > 0L }

    /** 该页超分结果用的模型名（没有/标记损坏 → null） */
    fun modelOf(ctx: Context, mangaId: Long, page: Int): String? = try {
        val f = markerFile(ctx, mangaId, page)
        if (!f.isFile) null else JSONObject(f.readText()).optString("model").takeIf { it.isNotEmpty() }
    } catch (e: Throwable) {
        LogCollector.w(TAG, "读超分标记失败: ${e.message}")
        null
    }

    /**
     * 「该页**已落盘**的超分结果是哪个模型超的」—— 没有落盘结果时 null。
     *
     * ⚠️ 这是**显示底图签名**要的那一个（`SrDisplayBase.baseSignature` 的 `srModelName`）：
     * 签名的语义必须是"**这份文件的实际内容**"，而不是"当前选中的模型"。
     * 两者混用会出现：
     * - 换了模型但还没重超 → 签名跟着变 → 白作废一次渲染缓存（内容其实没变）
     * - 反过来更糟：重超完成、文件已换，若签名还跟着"当前选中"走就可能**不变** →
     *   旧的渲染被别人当成新结果返回
     *
     * 标记缺失/损坏时返回 [UNKNOWN_MODEL]（**文件确实是超分图**，只是不知道谁超的 —— 不能当成"没超分"）。
     */
    fun storedModelOrNull(ctx: Context, mangaId: Long, page: Int): String? {
        if (!exists(ctx, mangaId, page)) return null
        return modelOf(ctx, mangaId, page) ?: UNKNOWN_MODEL
    }

    /** 超分图存在但标记丢了时的占位模型名 */
    const val UNKNOWN_MODEL = "-"

    /**
     * 写入一页的超分结果（**覆盖**已有文件 —— 用户口径"只保留一份"）。
     *
     * @param bitmaps 传进来的 2x 底图；本函数**不回收**它（归调用方）。
     * @return 落盘成功
     */
    fun save(
        ctx: Context,
        mangaId: Long,
        page: Int,
        bitmap: Bitmap,
        modelName: String,
        limitMb: Int = DEFAULT_LIMIT_MB
    ): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val img = imageFile(ctx, mangaId, page)
        return try {
            // ⚠️ `CompressFormat.WEBP` 在 API 30+ 已废弃，但它的语义就是"有损 WEBP"（quality 生效）。
            //    新常量只在 30+ 存在，所以按版本分流 —— 写死 WEBP_LOSSY 会让 minSdk 29 崩。
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            FileOutputStream(img).use { out -> bitmap.compress(fmt, QUALITY, out) }
            if (!img.isFile || img.length() <= 0L) {
                LogCollector.e(TAG, "超分落盘失败（文件为空）: ${img.name}")
                img.delete()
                return false
            }
            // 标记：只记"是哪个模型超的"，不参与真值判断
            runCatching {
                markerFile(ctx, mangaId, page).writeText(
                    JSONObject()
                        .put("model", modelName)
                        .put("scale", 2)
                        .put("w", bitmap.width)
                        .put("h", bitmap.height)
                        .put("ts", System.currentTimeMillis())
                        .toString()
                )
            }.onFailure { LogCollector.w(TAG, "写超分标记失败: ${it.message}") }
            manageCache(ctx, limitMb)
            LogCollector.d(TAG, "超分落盘: ${img.name} ${bitmap.width}x${bitmap.height} " +
                    "${img.length() / 1024}KB model=$modelName")
            true
        } catch (e: Throwable) {
            LogCollector.e(TAG, "超分落盘异常: ${img.name}", e)
            img.delete()
            false
        }
    }

    /** 读取一页的超分底图（失败 → null，调用方回退原图） */
    fun load(ctx: Context, mangaId: Long, page: Int): Bitmap? {
        val f = imageFile(ctx, mangaId, page)
        if (!f.isFile || f.length() <= 0L) return null
        return try {
            // 刷新 LRU 时间戳：读过的文件不该先被淘汰
            f.setLastModified(System.currentTimeMillis())
            BitmapFactory.decodeFile(f.absolutePath)?.also {
                LogCollector.d(TAG, "超分读取: ${f.name} ${it.width}x${it.height}")
            }
        } catch (e: Throwable) {
            LogCollector.e(TAG, "超分读取异常: ${f.name}", e)
            null
        }
    }

    /** 删掉一本书的全部超分结果（删漫画时连带调） */
    fun deleteManga(ctx: Context, mangaId: Long) {
        val prefix = "${mangaId}_"
        runCatching {
            dir(ctx).listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        }.onFailure { LogCollector.w(TAG, "清理漫画超分失败 mangaId=$mangaId: ${it.message}") }
    }

    /** 清空全部超分缓存（设置页入口） */
    fun clear(ctx: Context): Int {
        var n = 0
        runCatching {
            dir(ctx).listFiles()?.forEach { if (it.delete()) n++ }
        }.onFailure { LogCollector.w(TAG, "清空超分缓存失败: ${it.message}") }
        return n
    }

    /** 当前占用字节数（设置页显示用） */
    fun totalBytes(ctx: Context): Long =
        runCatching { dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    /**
     * LRU 淘汰：按 `lastModified` 从最旧开始删，直到总占用 ≤ 上限。
     *
     * @param limitMb 上限（MB）；[UNLIMITED] 或更小的负数 = 不淘汰
     */
    fun manageCache(ctx: Context, limitMb: Int) {
        if (limitMb < 0) return
        val limit = limitMb.toLong() * 1024L * 1024L
        val files = dir(ctx).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit) break
            val len = f.length()
            // ⚠️ 图片与标记**成对删**：只删图不删标记会留下孤儿标记
            //    （虽然真值看文件、孤儿标记无害，但下次写入前若读到旧模型名会误导）
            if (f.delete()) {
                total -= len
                if (f.name.endsWith(".webp")) {
                    val marker = File(f.parentFile, f.name.removeSuffix(".webp") + ".json")
                    if (marker.isFile) marker.delete()
                }
            }
        }
    }
}
