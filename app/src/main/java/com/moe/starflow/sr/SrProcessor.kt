package com.moe.starflow.sr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import com.moe.starflow.manga.OcrLock
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
     * 此刻**有没有任何**超分任务在跑（含"已排队、正在等 `OcrLock`"）。
     *
     * ⚠️ 用途：超分与 OCR 共用 [OcrLock]，所以超分持锁期间用户点翻译会撞上"引擎被占用"。
     * 阅读器的忙碌提示据此区分**是谁在占锁** —— 不区分的话用户看到的是"翻译引擎被占用"，
     * 而实际上只是后台在超分，看起来就像 bug。
     */
    fun isBusy(): Boolean = inFlight.isNotEmpty()

    /**
     * 对一页做超分并落盘。**覆盖**该页已有的超分结果（用户口径：只保留一份）。
     *
     * @param src 该页的**原图**（`ReaderPageSource.loadFull` 的结果）。
     *   ⚠️ **绝不能传"已渲染的译图"** —— 那会把画好的字当像素放大，坐标就真的无解了。
     *   （译文是**数据**不是像素：超分底图之后重新渲染 overlay 即可，译文会一起变清晰。）
     * @return 成功带图；失败**带具体原因**（用户口径：不许再写"可能没模型也可能图太大"这种糊话）
     */
    suspend fun enhanceAndStore(
        context: Context,
        mangaId: Long,
        page: Int,
        src: Bitmap,
        /** 这本书的身份指纹（`ImportedManga.translationKey`）；id 会被复用，落盘必须带上它 */
        mangaKey: String,
    ): SrOutcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)

        if (!SrSettings.isEnabledForReader(prefs)) return@withContext SrOutcome.fail(SrFailReason.DISABLED)
        if (src.width <= 0 || src.height <= 0) {
            return@withContext SrOutcome.fail(SrFailReason.PAGE_LOAD_FAILED, "bitmap ${src.width}x${src.height}")
        }

        val k = keyOf(mangaId, page)
        if (inFlight.putIfAbsent(k, true) != null) {
            LogCollector.d(TAG, "该页已有超分任务在跑，跳过重复触发: $k")
            return@withContext SrOutcome.fail(SrFailReason.BUSY)
        }
        var out: Bitmap? = null
        return@withContext try {
            // ⚠️ 与 OCR 串行 —— 但**不能用 `OcrLock.use`**！
            // `use {}` 的实现是"抢不到就 `throw RejectedExecutionException`"，而且 `OcrLock` 的
            // 类注释明确写着：**挂起函数里要走「先 while(isRunning) delay() 等锁 → tryAcquire →
            // try/finally 释放」**，不能用 `use`（协程在恢复点被取消时锁已拿到、finally 还没进 → 漏放）。
            //
            // 而且这里**必然**会撞锁：章节批量翻译是「OCR 串行 + 翻译并发」，
            // 本页 translatePhase 跑超分时，完全可能另一页正在 OCR。
            // 用 `use` 的话超分会直接抛异常失败 —— 表现为"自动超分时灵时不灵"。
            // ⚠️ 等锁条件有**两条**（用户 2026-10 追问「超分还没结束，下一个 OCR 就启动了怎么办」）：
            //    ① 锁被占着；
            //    ② **还有 OCR 在排队**（`hasOcrDemand`）—— 这条是"不插队"：没有它，超分会在一放锁
            //       的瞬间回头抢（它离锁最近），排队中的 OCR 页只能干等到 60s 超时被判失败
            //       （章节批量会成片地"什么都没翻、页却失败了"）。
            //    正在跑的这一次超分不会被打断，但下一次一定让给 OCR。
            while (OcrLock.isRunning || OcrLock.hasOcrDemand()) {
                if (!currentCoroutineContext().isActive) return@withContext SrOutcome.fail(SrFailReason.EXCEPTION, "cancelled while waiting for OcrLock")
                delay(LOCK_POLL_MS)
            }
            // ⚠️ 必须用**带令牌**的 acquire/release，并在锁内持续打心跳。
            //    `tryAcquire()` 丢掉令牌、`release()` 是无令牌版（无条件清零）—— 而超分推理
            //    （ncnn/Vulkan，重档位分钟级）远超 `OcrLock.STALE_TIMEOUT_MS`(30s)，于是：
            //    ① `maybeRecoverStale()` 判定持有者已死 → 强制放锁 → OCR 与超分同时在跑
            //       （正是这把锁要防的事：单例 ONNX 引擎被并发调用）；
            //    ② 本协程收尾的无令牌 release 会把**新持有者**的锁一起放掉 → 第三个任务再进来。
            //    `OcrLock` 的 KDoc 明写「长临界区必须用令牌版」，这里是全项目最后一处漏改。
            val lockToken = OcrLock.acquire()
            if (lockToken == 0L) {
                LogCollector.d(TAG, "超分等锁失败（OCR 正忙），本次跳过: $k")
                return@withContext SrOutcome.fail(SrFailReason.BUSY, "OcrLock busy")
            }
            // ⚠️ 继承当前派发器（本函数体已经在 `withContext(Dispatchers.IO)` 里）——
            //    写死 `launch(Dispatchers.IO)` 等于在已有 IO 上下文里再往真实线程池扔一个任务，
            //    对单测（`runTest` 虚拟时钟）是非确定性的。
            val heartbeatJob = launch {
                while (isActive) {
                    OcrLock.heartbeat(lockToken)
                    delay(LOCK_HEARTBEAT_MS)
                }
            }
            // ── 大图预处理（用户口径 2026-10：短边压到 1080p 再超分）──
            // ⚠️ 必须在**持锁期间**做完：缩放本身也是重活（4000x3000 分步下采样），
            //    放到锁外会与别的 OCR/超分抢核，正是这把锁要避免的事。
            // ⚠️ 压缩目标要**同时**满足「短边 1080」与「引擎的输入像素上限」——
            //    前者是用户口径，后者是硬约束（2x 档 2.5MP / Real-ESRGAN 4x 0.625MP）：
            //    2000x3000 的页在改这一版之前就是**直接超限失败**的，用户只看到一句"图太大"。
            // ⚠️ 上限取不到（0 = 引擎压根建不起来：没选模型 / 文件缺失 / 初始化失败）→
            //    **跳过压缩**：`upscaleForReader` 马上就会带着**具体原因**失败（"没选模型"和
            //    "图太大"用户要做的事完全不同），先花几百毫秒压一遍纯属白干。
            val feedPixels = SuperResolutionEngines.inputPixelLimitForReader(app, prefs)
            val feed: Bitmap = if (feedPixels > 0) {
                SrDownscale.plan(src.width, src.height, feedPixels)?.let { plan ->
                    SrDownscale.apply(src, plan).also {
                        LogCollector.d(
                            TAG,
                            "超分前压缩: ${src.width}x${src.height} → ${it.width}x${it.height}" +
                                "（引擎上限 ${feedPixels}px，$k）"
                        )
                    }
                } ?: src
            } else src
            val attempt: SrOutcome
            try {
                attempt = SuperResolutionEngines.upscaleForReader(app, prefs, feed)
            } finally {
                heartbeatJob.cancel()
                OcrLock.release(lockToken)
                // 压缩图是本函数造的 → 引擎已经不再读它，立刻回收。
                // ⚠️ `src` 是**调用方的**，绝不能在这里动（`feed === src` 时直接跳过）。
                if (feed !== src && !feed.isRecycled) feed.recycle()
            }
            if (!attempt.ok) {
                LogCollector.d(TAG, "超分未产出: ${attempt.reason} ${attempt.detail ?: ""} ($k)")
                return@withContext attempt
            }
            // `attempt.ok` 为真但 bitmap 为空只可能来自 `SrOutcome.stored()`（本函数不用它），兜底当失败
            val raw: Bitmap = attempt.bitmap
                ?: return@withContext SrOutcome.fail(SrFailReason.INFERENCE_FAILED)
            // 先交出所有权：下面任何一条提前 return 都由 finally 负责回收
            out = raw
            // ⚠️ 产物**没放大**就不是"超分结果"：`resolveSteps` 在「超分开着 + 模型不可用 +
            //    Anime4K 开着」时只给出 ANIME4K 一步，而 Anime4K 刻意不放大 → 落盘会造出
            //    「界面显示已超分、画面毫无变化」的幽灵状态（三枚按钮都在、切换也没区别），
            //    而且这个 1x 文件正是 `srPreviewFor` 早退分支（返回被回收的位图）的触发条件。
            //    返回明确原因，让用户看到"去选/去下载模型"，Anime4K 只作即时显示底图。
            //
            // ⚠️ 判据比的是**喂给引擎的那张图**（[feed]），不是原图 `src`：大图走压缩路径时
            //    产物可以 ≤ 原图（下面还要收敛到"像素数 ≤ 原图"），拿 src 比会把正常的超分结果
            //    判成"没放大"而白跑一遍。Anime4K 的 1x 仍然被这条拦住（产物宽度 == feed 宽度）。
            if (raw.width <= feed.width) {
                LogCollector.d(TAG, "超分产物未放大（${raw.width}px <= 输入 ${feed.width}px），不落盘: $k")
                return@withContext SrOutcome.fail(SrFailReason.NOT_UPSCALED)
            }
            // 收敛：产物**像素数不超过原图**（用户口径「体积不能超过原来的像素和大小」）。
            // 2x 重建出来的细节保留着，净效果是"原图分辨率、超分画质"。
            val product: Bitmap = SrDownscale.clampToOriginalPixels(raw, src.width, src.height)?.also {
                out = it                      // 先换所有权，再回收中间产物
                if (!raw.isRecycled) raw.recycle()
            } ?: raw
            val model = SrModelManager.getActiveKey(prefs)?.name ?: "-"
            val saved = SrStore.save(app, mangaId, page, product, model, mangaKey, cacheLimitMb(prefs))
            if (!saved) {
                LogCollector.e(TAG, "超分落盘失败: $k")
                SrOutcome.fail(SrFailReason.SAVE_FAILED)
            } else {
                // ⚠️ 产物**已写盘**，显示路径是 `SrStore.load` 从磁盘重读的 —— 没有任何
                //    调用方需要这张图（全项目零处读 `outcome.bitmap`）。以前把它交回去，
                //    结果是每个调用点都漏一次 recycle：2x 一页最大 ~40MB，整章批量 =
                //    每页丢一块。所有权留在本函数，由下面 finally 回收。
                SrOutcome.stored()
            }
        } catch (e: Throwable) {
            // 超分失败必须静默降级：用户看到的还是原图，绝不因为它翻不了页
            LogCollector.e(TAG, "超分异常（回退原图）: $k", e)
            SrOutcome.fail(SrFailReason.EXCEPTION, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // 没交出去/写盘失败的产物在这里回收（注意 src 是调用方的，不动）
            out?.let { if (!it.isRecycled && it !== src) it.recycle() }
            inFlight.remove(k)
        }
    }

    /** 等 OCR 锁的轮询间隔 */
    private const val LOCK_POLL_MS = 50L

    /**
     * 持锁期间的心跳间隔。
     * 必须**远小于** [OcrLock.STALE_TIMEOUT_MS]（30s）—— 超分单页推理可达分钟级，
     * 不打心跳就会被自愈机制误判成"持有者已死"。
     */
    private const val LOCK_HEARTBEAT_MS = 5_000L

    /** 缓存上限（MB）；用户没设过 → [SrStore.DEFAULT_LIMIT_MB] */
    private fun cacheLimitMb(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_CACHE_LIMIT_MB, SrStore.DEFAULT_LIMIT_MB)

    /** prefs 键：超分缓存上限（MB）。负数 = 不限 */
    const val KEY_CACHE_LIMIT_MB = "sr_cache_limit_mb"
}
