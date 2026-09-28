package com.moe.starflow.sr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import com.moe.starflow.manga.OcrLock
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 一页超分的**执行器**：跑引擎 → 落盘 → 记标记。
 *
 * ## 调用约束（写在最前面，违反就是 ANR 或闪退）
 * 1. **必须在后台线程**（本类内部已 `withContext(Dispatchers.IO)`，调用方直接调即可）
 * 2. **不得从 `onBindViewHolder` / 面板构建等主线程路径同步调**
 * 3. 与 OCR 用同一把 [OcrLock] 串行 —— 两者都是本地重计算（ONNX / GL），
 *    并行只会互相抢核、都变慢，还多占一份内存
 *
 * ## 并发去重
 * 同一页可能同时被「翻译流程自动超分」与「用户手动点超分」触发。
 * [inFlight] 按 `mangaId:page` 去重：已在跑就直接返回"等它"，避免两个任务
 * **同时写同一个 webp 文件**（损坏文件 / 落盘互相覆盖）。
 *
 * ## 与 koto 的差异
 * koto 的 `ReaderSuperResolutionManager` 是**下载时预生成**（`PREPARE_SUPER_RESOLUTION`）；
 * 我们**只由用户触发**（用户口径）：翻译时自动超分（开关控制）或点每页按钮。
 */
object SrProcessor {

    private const val TAG = "SrProcessor"

    /** 正在跑的页（key = "mangaId:page"）；用于并发去重 */
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    private fun keyOf(mangaId: Long, page: Int) = "$mangaId:$page"

    /** 该页此刻是否正在超分（UI 用来显示转圈/禁用按钮） */
    fun isRunning(mangaId: Long, page: Int): Boolean = inFlight.containsKey(keyOf(mangaId, page))

    /**
     * 对一页做超分并落盘。**覆盖**该页已有的超分结果（用户口径：只保留一份）。
     *
     * @param src 该页的**原图**（`ReaderPageSource.loadFull` 的结果）。
     *   ⚠️ **绝不能传"已渲染的译图"** —— 那会把画好的字当像素放大，坐标就真的无解了。
     *   （译文是**数据**不是像素：超分底图之后重新渲染 overlay 即可，译文会一起变清晰。）
     * @return 落盘成功（false = 未开启/没模型/失败/已有同页任务在跑）
     */
    suspend fun enhanceAndStore(
        context: Context,
        mangaId: Long,
        page: Int,
        src: Bitmap
    ): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)

        if (!SrSettings.isEnabledForReader(prefs)) return@withContext false
        if (src.width <= 0 || src.height <= 0) return@withContext false

        val k = keyOf(mangaId, page)
        if (inFlight.putIfAbsent(k, true) != null) {
            LogCollector.d(TAG, "该页已有超分任务在跑，跳过重复触发: $k")
            return@withContext false
        }
        var out: Bitmap? = null
        return@withContext try {
            // 与 OCR 串行（同一把锁）。⚠️ 超分模型是几百 MB + 纯 CPU 卷积，
            // 和 OCR 并行只会两边都慢，且内存峰值翻倍。
            out = OcrLock.use { SuperResolutionEngines.upscaleForReader(app, prefs, src) }
            if (out == null) {
                LogCollector.d(TAG, "超分未产出（未开启/没模型/失败/超像素上限）: $k")
                false
            } else {
                val model = SrModelManager.getActiveKey(prefs)?.name ?: "-"
                val ok = SrStore.save(app, mangaId, page, out, model, cacheLimitMb(prefs))
                if (!ok) LogCollector.e(TAG, "超分落盘失败: $k")
                ok
            }
        } catch (e: Throwable) {
            // 超分失败必须静默降级：用户看到的还是原图，绝不因为它翻不了页
            LogCollector.e(TAG, "超分异常（回退原图）: $k", e)
            false
        } finally {
            // 产物已写盘/已无用 —— 这里回收（注意 src 是调用方的，不动）
            out?.let { if (!it.isRecycled && it !== src) it.recycle() }
            inFlight.remove(k)
        }
    }

    /** 缓存上限（MB）；用户没设过 → [SrStore.DEFAULT_LIMIT_MB] */
    private fun cacheLimitMb(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_CACHE_LIMIT_MB, SrStore.DEFAULT_LIMIT_MB)

    /** prefs 键：超分缓存上限（MB）。负数 = 不限 */
    const val KEY_CACHE_LIMIT_MB = "sr_cache_limit_mb"
}
