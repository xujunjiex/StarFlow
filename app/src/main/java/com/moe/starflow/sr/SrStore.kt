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

    /**
     * 目录占用的**累计字节数**。`-1` = 未知（首次 [manageCache] 会扫一次并回填）。
     *
     * ⚠️ 为什么需要：`save()` 每写一页都会调一次 [manageCache]，而它会 `listFiles()` +
     * 逐文件 `length()` —— 默认 512MB 上限下约 2000 个文件，外部存储走 FUSE 就是约 4000 次
     * stat，整章批量翻译等于**每页白扫一遍目录**。上限本身是软预算（不是正确性约束），
     * 用累计值短路即可。外部改动（系统清缓存 / 用户手动删）只会让累计值**偏大** →
     * 更早触发一次真实扫描并自行纠正，方向是安全的。
     */
    @Volatile
    private var cachedTotalBytes: Long = -1L

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }

    private fun imageFile(ctx: Context, mangaId: Long, page: Int): File =
        File(dir(ctx), "${mangaId}_$page.webp")

    private fun markerFile(ctx: Context, mangaId: Long, page: Int): File =
        File(dir(ctx), "${mangaId}_$page.json")

    /**
     * 标记里记的**归属指纹**字段名。配合 [ImportedManga.translationKey] 用。
     *
     * ⚠️ **为什么必须有指纹**：漫画 id = 书架最大 id + 1，**删书后重新导入会复用同一个 id**
     * （与 `imported_page_translation` 那边是同一个坑）。只按 `mangaId_page` 命名的话，
     * 删书的异步清理一旦丢失或还在途中，新书就会把**旧书的放大页**当成自己的底图渲染出来 ——
     * 用户看到的是完全不相干的图，且没有任何报错。
     */
    private const val KEY_FIELD = "key"

    /**
     * 该页是否有**属于这本书**的超分结果。
     *
     * 真值口径：图片文件在且非空 **且** 标记里的指纹与 [mangaKey] 相符。
     * ⚠️ 标记缺失/损坏一律算"不是这本书的"（宁可回落原图，也不冒渲染别人页面的风险）——
     * 与 `srBaseFor` 里"尺寸异常就宁可回落原图"同一条取舍。
     */
    fun exists(ctx: Context, mangaId: Long, page: Int, mangaKey: String): Boolean {
        if (mangaKey.isEmpty()) return false
        val img = imageFile(ctx, mangaId, page)
        if (!img.isFile || img.length() <= 0L) return false
        return readMarker(ctx, mangaId, page)?.optString(KEY_FIELD) == mangaKey
    }

    /** 标记内容（文件不在/解析失败 → null）。 */
    private fun readMarker(ctx: Context, mangaId: Long, page: Int): JSONObject? = try {
        val f = markerFile(ctx, mangaId, page)
        if (!f.isFile) null else JSONObject(f.readText())
    } catch (e: Throwable) {
        LogCollector.w(TAG, "读超分标记失败: ${e.message}")
        null
    }

    /**
     * 该页超分结果用的模型名。**必须属于 [mangaKey] 这本书**，否则 null。
     *
     * 指纹对但 `model` 字段为空 → 返回 [UNKNOWN_MODEL]（文件确实是超分图，只是不知道谁超的）。
     */
    fun modelOf(ctx: Context, mangaId: Long, page: Int, mangaKey: String): String? {
        if (mangaKey.isEmpty()) return null
        val m = readMarker(ctx, mangaId, page) ?: return null
        if (m.optString(KEY_FIELD) != mangaKey) return null
        return m.optString("model").takeIf { it.isNotEmpty() }
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
     */
    fun storedModelOrNull(ctx: Context, mangaId: Long, page: Int, mangaKey: String): String? {
        if (!exists(ctx, mangaId, page, mangaKey)) return null
        return modelOf(ctx, mangaId, page, mangaKey) ?: UNKNOWN_MODEL
    }

    /** 超分图存在但标记里没有模型名时的占位模型名 */
    const val UNKNOWN_MODEL = "-"

    /**
     * 落盘标记里记着的**结果元数据**（超分面板「详情」要展示的那几项）。
     *
     * 尺寸/时间来自标记文件（写的时候就在里面了），**字节数来自图片文件本身**
     * （标记不存它 —— 存了也会与真实文件漂移，而文件才是真值）。
     */
    data class StoredInfo(
        val model: String,
        val width: Int,
        val height: Int,
        val scale: Int,
        val savedAtMs: Long,
        val bytes: Long,
    )

    /**
     * 读一页超分结果的元数据；**没有属于这本书的结果时返回 null**。
     *
     * 与 [load] 同一条归属校验（指纹不符一律当没有），不做的话删书重导后
     * 面板会展示上一本书的模型名与尺寸。
     */
    fun infoOf(ctx: Context, mangaId: Long, page: Int, mangaKey: String): StoredInfo? {
        if (!exists(ctx, mangaId, page, mangaKey)) return null
        val m = readMarker(ctx, mangaId, page) ?: return null
        val img = imageFile(ctx, mangaId, page)
        return StoredInfo(
            model = m.optString("model").takeIf { it.isNotEmpty() } ?: UNKNOWN_MODEL,
            width = m.optInt("w", 0),
            height = m.optInt("h", 0),
            scale = m.optInt("scale", 2),
            savedAtMs = m.optLong("ts", 0L),
            bytes = if (img.isFile) img.length() else 0L,
        )
    }

    /**
     * 写入一页的超分结果（**覆盖**已有文件 —— 用户口径"只保留一份"）。
     *
     * @param bitmaps 传进来的 2x 底图；本函数**不回收**它（归调用方）。
     * @param mangaKey 这本书的身份指纹（`ImportedManga.translationKey`）。**必填**：
     *   id 会被复用（见 [exists]），标记里没有它就无法证伪"这是别人书的放大页"。
     * @return 落盘成功
     */
    fun save(
        ctx: Context,
        mangaId: Long,
        page: Int,
        bitmap: Bitmap,
        modelName: String,
        mangaKey: String,
        limitMb: Int = DEFAULT_LIMIT_MB
    ): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        if (mangaKey.isEmpty()) {
            // 没有指纹就写不出可被认领的结果 —— 与其留一个没人能用/可能被别人认领的孤儿文件，不如不写
            LogCollector.e(TAG, "超分落盘被拒：缺少身份指纹 (mangaId=$mangaId page=$page)")
            return false
        }
        val img = imageFile(ctx, mangaId, page)
        return try {
            // 同一页是**覆盖写**（文件名不含模型 id）→ 算增量时要把旧的那份扣掉
            val prevLen = if (img.isFile) img.length() else 0L
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
            // 标记：**归属指纹 + 是哪个模型超的**。
            // ⚠️ 指纹现在是**真值的一部分**（见 [exists]），不再只是"参考信息" ——
            //    所以这次写失败不能像以前那样只记个 W 就算落盘成功：那份图将永远认领不了，
            //    留着只会占缓存额度。写失败就把图一起删掉，如实返回 false（上层会提示保存失败）。
            val markerWritten = runCatching {
                markerFile(ctx, mangaId, page).writeText(
                    JSONObject()
                        .put(KEY_FIELD, mangaKey)
                        .put("model", modelName)
                        .put("scale", 2)
                        .put("w", bitmap.width)
                        .put("h", bitmap.height)
                        .put("ts", System.currentTimeMillis())
                        .toString()
                )
            }.onFailure { LogCollector.e(TAG, "写超分标记失败: ${it.message}") }.isSuccess
            if (!markerWritten) {
                img.delete()
                return false
            }
            // 维护累计值（未知时保持 -1：首次 manageCache 会扫一次并回填），再决定要不要淘汰
            if (cachedTotalBytes >= 0) cachedTotalBytes += img.length() - prevLen
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

    /**
     * 读取一页的超分底图（失败 / 不属于这本书 → null，调用方回退原图）。
     *
     * ⚠️ 先校验归属再解码：只按 id 取图正是"删书后 id 被复用 → 新书渲染出旧书放大页"的入口。
     */
    fun load(ctx: Context, mangaId: Long, page: Int, mangaKey: String): Bitmap? {
        if (!exists(ctx, mangaId, page, mangaKey)) return null
        val f = imageFile(ctx, mangaId, page)
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

    /**
     * 删掉**一页**的超分结果（图片 + 标记成对删）。
     *
     * 用户口径（2026-10）：「超分之后…同样可以删除超分结果」——
     * 与「清除本页译文」对称：删完这一页回落到原图显示，其它页不受影响。
     */
    fun deletePage(ctx: Context, mangaId: Long, page: Int): Boolean {
        var ok = false
        runCatching {
            val img = imageFile(ctx, mangaId, page)
            if (img.isFile && img.delete()) ok = true
            val marker = markerFile(ctx, mangaId, page)
            if (marker.isFile) marker.delete()
        }.onFailure { LogCollector.w(TAG, "删除单页超分失败 mangaId=$mangaId page=$page: ${it.message}") }
        if (ok) LogCollector.d(TAG, "已删除超分结果: ${mangaId}_$page")
        return ok
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
        // 已知占用明显低于上限（留 10% 余量吸收并发写入）→ 不必扫目录（见 [cachedTotalBytes]）
        val known = cachedTotalBytes
        if (known >= 0 && known <= limit - limit / 10) return
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
        cachedTotalBytes = total
    }
}
