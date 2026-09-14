package com.moe.starflow.manga.state
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*
import com.moe.starflow.manga.*
import com.moe.starflow.manga.render.*
import com.moe.starflow.manga.merge.*

import com.moe.starflow.manga.engine.*
import com.moe.starflow.manga.types.*
import com.moe.starflow.manga.config.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.moe.starflow.R
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.PerceptualHash

/** 自动翻译检测状态。 */
enum class DetectState { IDLE, MOTION, STABLE }

/**
 * 漫画自动翻译状态机（从 MangaFloatingService 阶段 2 提取）。
 *
 * 主动状态机：内部 Handler 调度检测循环（区别于游戏模式 AutoTranslateEngine 的被动 Decision 模式，
 * 因漫画状态机内嵌调度 + overlay 副作用）。副作用经回调注入，由服务提供：
 * 截图请求、进度浮层、Toast、region 缓存清空。
 *
 * 权限检查（MediaProjection/无障碍/pendingAutoStart）留在服务；翻译成功后的
 * lastTranslatedHash/lastTranslatedTime 由服务写入（公开 var）。
 */
class MangaAutoTranslateEngine(
    private val context: Context,
    private val isResultOrMenuShowing: () -> Boolean,
    private val isProcessing: () -> Boolean,
    private val onShowProgress: (String) -> Unit,
    private val onDismissProgress: () -> Unit,
    private val onTriggerTranslation: () -> Unit,
    private val onClearRegionCache: () -> Unit,
    private val onShowToast: (String) -> Unit
) {
    private val TAG = "MangaAutoTranslateEngine"

    companion object {
        const val PHASH_STABLE_THRESHOLD = 0.95f   // >= 此值认为画面没变
        const val PHASH_NEW_PAGE_THRESHOLD = 0.60f  // < 此值认为是全新页面
        const val STABLE_CONFIRM_COUNT = 2          // 连续稳定次数
        const val DETECT_INTERVAL_MS = 500L         // 运动中检测间隔
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile var isAutoTranslating = false
    @Volatile var isManualTranslating = false
    var detectState = DetectState.IDLE
    var lastTranslatedHash = 0L        // 上次翻译页的哈希（IDLE 判断是否需翻译）
    var lastTranslatedTime = 0L       // 上次翻译时间戳（40s 超时省电检测，服务读）
    private var stableCount = 0
    private var motionStartTime = 0L
    private var previousScreenshotHash = 0L

    /** 开始自动翻译：初始化状态 + 清 region 缓存 + 触发首检。权限门由服务先执行。 */
    fun start() {
        isAutoTranslating = true
        detectState = DetectState.IDLE
        stableCount = 0
        previousScreenshotHash = 0L
        lastTranslatedHash = 0L
        lastTranslatedTime = 0L
        onClearRegionCache()
        scheduleNextDetection(0L)
        LogCollector.d(TAG, "Auto-translate started")
        onShowToast(context.getString(R.string.manga_auto_translate_start))
    }

    fun stop() {
        isAutoTranslating = false
        isManualTranslating = false
        detectState = DetectState.IDLE
        stableCount = 0
        previousScreenshotHash = 0L
        handler.removeCallbacksAndMessages(null)
        onClearRegionCache()
        onDismissProgress()
        LogCollector.d(TAG, "Auto-translate stopped")
        onShowToast(context.getString(R.string.manga_auto_translate_stop))
    }

    /** 取消所有已调度的检测（服务销毁/UI 关闭时用，不停自动翻译）。 */
    fun clearScheduled() {
        handler.removeCallbacksAndMessages(null)
    }

    /** 翻译完成后重置到 IDLE + 立即重新检测（服务翻译流程用）。 */
    fun resetToIdle() {
        detectState = DetectState.IDLE
        stableCount = 0
        scheduleNextDetection(0L)
    }

    /** 调度下一次检测（服务翻译流程共用）。 */
    fun scheduleNextDetection(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ runAutoDetect() }, delayMs)
    }

    /**
     * pHash 状态机：在截图 collector 中调用。
     * 返回 true = 应继续处理（OCR+翻译），false = 跳过本次截图。
     *
     * 状态流转:
     *   IDLE   → pHash 相似 → 已翻译? 跳过 : 翻译
     *         → pHash 不同 → 进入 MOTION
     *   MOTION → pHash 相似 → stableCount++ → 达标? 进入 STABLE : 继续等
     *         → pHash 不同 → 重置计数，继续等
     *   STABLE → 判断是否新页面 → 翻译 or 跳过 → 回到 IDLE
     */
    fun processAutoDetectPHash(currentHash: Long): Boolean {
        LogCollector.d(TAG, "AutoDetect: state=$detectState, current=$currentHash, prev=$previousScreenshotHash, lastTranslated=$lastTranslatedHash")

        when (detectState) {
            DetectState.IDLE -> {
                // 首次截图，直接翻译
                if (lastTranslatedHash == 0L && previousScreenshotHash == 0L) {
                    previousScreenshotHash = currentHash
                    LogCollector.d(TAG, "AutoDetect[IDLE]: first screenshot → translate")
                    return true
                }

                // IDLE: 比较当前截图和上次翻译页的哈希
                val simToTranslated = PerceptualHash.similarity(lastTranslatedHash, currentHash)
                if (simToTranslated >= PHASH_STABLE_THRESHOLD) {
                    // 画面没变或回到了已翻译的页面，跳过
                    LogCollector.d(TAG, "AutoDetect[IDLE]: simToTranslated=$simToTranslated → skip")
                    previousScreenshotHash = currentHash
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                    return false
                } else {
                    // 画面和已翻译页不同 — 可能翻页/滚动，进入 MOTION 等稳定
                    detectState = DetectState.MOTION
                    stableCount = 0
                    previousScreenshotHash = currentHash
                    motionStartTime = System.currentTimeMillis()
                    LogCollector.d(TAG, "AutoDetect[IDLE→MOTION]: simToTranslated=$simToTranslated, motion detected")
                    onShowProgress(context.getString(R.string.detecting))
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                    return false
                }
            }

            DetectState.MOTION -> {
                // MOTION: 比较连续两次截图，判断页面是否稳定
                val simConsecutive = PerceptualHash.similarity(previousScreenshotHash, currentHash)
                previousScreenshotHash = currentHash

                if (simConsecutive >= PHASH_STABLE_THRESHOLD) {
                    // 连续两次截图一致 → 页面已稳定
                    stableCount++
                    if (stableCount >= STABLE_CONFIRM_COUNT) {
                        detectState = DetectState.STABLE
                        LogCollector.d(TAG, "AutoDetect[MOTION→STABLE]: consecutive sim=$simConsecutive, stabilized after ${stableCount} checks")
                        return onMotionStabilized(currentHash)
                    } else {
                        LogCollector.d(TAG, "AutoDetect[MOTION]: stabilizing... consecutive sim=$simConsecutive, count=$stableCount")
                        scheduleNextDetection(DETECT_INTERVAL_MS)
                        return false
                    }
                } else {
                    // 还在动，重置计数继续等
                    stableCount = 0
                    scheduleNextDetection(DETECT_INTERVAL_MS)
                    return false
                }
            }

            DetectState.STABLE -> {
                // 不应该停留在此状态，回到 IDLE
                detectState = DetectState.IDLE
                scheduleNextDetection(DETECT_INTERVAL_MS)
                return false
            }
        }
    }

    private fun runAutoDetect() {
        if (!isAutoTranslating) return
        if (isResultOrMenuShowing()) {
            scheduleNextDetection(1000L)
            return
        }
        if (isProcessing()) {
            scheduleNextDetection(DETECT_INTERVAL_MS)
            return
        }
        // 请求截图 — 截图到达后由 collector 处理 pHash 状态机
        onTriggerTranslation()
    }

    /**
     * 画面稳定后 — 判断是否需要翻译。
     * @return true = 应翻译，false = 跳过
     */
    private fun onMotionStabilized(stableHash: Long): Boolean {
        val similarityToTranslated = PerceptualHash.similarity(lastTranslatedHash, stableHash)

        if (similarityToTranslated >= PHASH_STABLE_THRESHOLD) {
            // 与已翻译页面相同，跳过
            LogCollector.d(TAG, "onMotionStabilized: same as translated page → skip")
            detectState = DetectState.IDLE
            scheduleNextDetection(1000L)
            return false
        }

        if (similarityToTranslated < PHASH_NEW_PAGE_THRESHOLD) {
            // 差异巨大 → 翻页，但保留文本缓存（文字匹配决定是否复用，不靠坐标）
            LogCollector.d(TAG, "onMotionStabilized: new page (sim=$similarityToTranslated), keeping text cache")
        } else {
            // 小幅变化 → 滚动，保留缓存做增量翻译
            LogCollector.d(TAG, "onMotionStabilized: incremental (sim=$similarityToTranslated), keeping cache")
        }

        // 需要翻译
        return true
    }
}
