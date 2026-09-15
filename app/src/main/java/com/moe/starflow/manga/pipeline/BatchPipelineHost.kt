package com.moe.starflow.manga.pipeline

import android.content.Context
import com.moe.starflow.manga.state.RegionCacheManager
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.types.TranslatedBubble
import com.moe.starflow.translate.TranslationTextAPI
import com.moe.starflow.translate.widget.BallStateManager
import java.util.LinkedList

/**
 * 分批管线的宿主钩子。
 *
 * 管线本身不持有任何界面 / 缓存 / 引擎管理对象 —— 需要这些能力时"喊一声"，由宿主决定怎么做：
 * - **截屏翻译**：改悬浮球图标、弹底部状态条、贴到结果浮层、写缓存表
 * - **阅读器**（Spec 2）：更新状态浮层、写该页翻译记录、渲染到页
 *
 * 有了这层，同一份分批代码两边都能用，以后改分批逻辑只改一处。
 */
interface BatchPipelineHost {

    /** 引擎接口要求（旧代码传的是 Service 本身）。管线也用它解析字符串资源。 */
    val context: Context

    /** 翻译器（旧 `MangaFloatingService.translatorText`）。为 null 时管线抛 RuntimeException，与旧行为一致。 */
    val translator: TranslationTextAPI?

    /**
     * 引擎就绪。旧实现是三条路线各自在开头调 `engineManager` 的初始化方法，
     * 此处合并为单入口（调用方按 det/ocr 分派，行为不变）。
     */
    suspend fun ensureEnginesReady(det: DetEngine, ocr: OcrEngine)

    /** 固定文案进度（旧 `showProgressOverlay(getString(textRes))`）。 */
    fun onProgress(textRes: Int)

    /** 提示（旧 `showToast(text, long)`）。 */
    fun onToast(text: CharSequence, long: Boolean)

    /** 错误（旧 `statusOverlay.showError(text)`）。 */
    fun onError(text: CharSequence)

    /** 悬浮球状态（旧 `ballStateManager?.setState(state)`）。阅读器场景可不实现（没有球）。 */
    fun onBallState(state: BallStateManager.State)

    /**
     * 流式局部结果（旧 `launchPartialRender { renderStreamingOverlay(bitmap, bubbles) }`）。
     *
     * ⚠️ 调用方应**跟踪渲染 Job**，避免截图 bitmap 被回收后仍被读取。
     * 旧代码第二批流式用的是不跟踪 Job 的裸 `lifecycleScope.launch`（既有隐患），
     * 新接口统一走本钩子 —— 这是本次唯一的刻意行为修正。
     */
    fun onPartialRender(bubbles: List<TranslatedBubble>)

    /**
     * 某一批的完整结果（旧 `renderAndShowMergedOverlay(bitmap, bubbles, saveCache=false, showCopyButton=false)`）。
     *
     * ⚠️ 必须是 **suspend**：旧代码在此处 await 渲染完成才继续第二批，改成 `launch` 会改变时序。
     */
    suspend fun onBatchResult(bubbles: List<TranslatedBubble>)

    /** 用户是否已取消（旧 `translationCancelled`）。 */
    fun isCancelled(): Boolean

    /** 批次间上下文历史（旧 `contextHistory`）。管线在分批结束后负责回滚，避免污染后续页面。 */
    fun contextHistory(): LinkedList<Pair<String, String>>

    /** 文本级译文复用缓存（旧 `regionCache`，实例非 object）。 */
    fun textCache(): RegionCacheManager
}
