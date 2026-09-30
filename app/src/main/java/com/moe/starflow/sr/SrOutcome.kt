package com.moe.starflow.sr

import android.content.Context
import androidx.annotation.StringRes
import com.moe.starflow.R

/**
 * 超分/增强**为什么没产出**。
 *
 * 用户口径（2026-10 真机反馈原话）：
 * > 「当前无法超分，提示让我检查模型和尺寸太大；不要搞这么模糊的提示信息，
 * >  到底是什么原因无法超分写清楚！！」
 *
 * 所以**每一种失败都必须能对上一条具体文案**，绝不允许再出现
 * 「请确认已选择并下载超分模型，或该页分辨率超出上限」这种把两种原因糊在一起的写法 ——
 * 用户照着提示去查，两条都要试一遍才知道是哪个。
 *
 * ⚠️ 新增失败分支时**必须**在本枚举加一项 + 在中英 `strings.xml` 各加一条文案；
 * `SrOutcomeTest` 会机械校验"每个枚举项都有非空文案"。
 */
enum class SrFailReason(@StringRes val messageRes: Int) {

    /** 阅读器的超分总开关没开 */
    DISABLED(R.string.sr_fail_disabled),

    /** 既没有可用的超分模型，Anime4K 也没开 —— 一条路都走不通 */
    NOTHING_ENABLED(R.string.sr_fail_nothing_enabled),

    /**
     * 产物**没有放大**（只跑到了 Anime4K，它刻意不放大）→ 不成其为"超分结果"，不落盘。
     *
     * 起因：`resolveSteps` 在「超分开关开着 + 模型不可用 + Anime4K 开着」时只给出 ANIME4K 一步，
     * 产物与原图同尺寸；无条件落盘会造出「界面显示已超分、画面毫无变化」的幽灵状态
     * （三枚超分按钮都在、点切换也没区别），用户只能靠「删除」退出这个假状态。
     */
    NOT_UPSCALED(R.string.sr_fail_not_upscaled),

    /** 一次都没选过模型（`sr_active_model_key` 为空） */
    NO_MODEL_SELECTED(R.string.sr_fail_no_model),

    /** 选了模型但文件不在/是空的（没下载完、被删、被系统清理） */
    MODEL_FILE_MISSING(R.string.sr_fail_model_missing),

    /** 模型文件在，但 ONNX Runtime 加载会话失败（文件损坏 / 算子不支持 / halo 不合法） */
    ENGINE_INIT_FAILED(R.string.sr_fail_engine_init),

    /** 源图超过超分模型的输入像素上限（不是"尺寸太大"这种含糊说法，会带上实际数字） */
    SOURCE_TOO_LARGE(R.string.sr_fail_source_too_large),

    /** Anime4K 初始化失败（GLES3 不可用 / 着色器编译失败） */
    ANIME4K_INIT_FAILED(R.string.sr_fail_anime4k_init),

    /** Anime4K 只吃 1.5MP，本页超了（要引导用户改用超分模型，而不是让他去猜） */
    ANIME4K_SOURCE_TOO_LARGE(R.string.sr_fail_anime4k_too_large),

    /** 引擎跑起来了但没产出（输出尺寸不符契约 / 补边不足 / 推理抛异常）—— 细节看 [SrOutcome.detail] */
    INFERENCE_FAILED(R.string.sr_fail_inference),

    /** 超分图落盘失败（空间不足 / 目录不可写） */
    SAVE_FAILED(R.string.sr_fail_save),

    /** 读不到这一页的原图 */
    PAGE_LOAD_FAILED(R.string.sr_fail_page_load),

    /** 这一页已经有超分任务在跑（`SrProcessor.inFlight` 去重） */
    BUSY(R.string.sr_fail_busy),

    /** 未归类的异常（细节看 [SrOutcome.detail]） */
    EXCEPTION(R.string.sr_fail_exception),
}

/**
 * 一次超分尝试的结果：**成功给图，失败必给原因**。
 *
 * @property bitmap 成功时**由本对象交出的**产物（1x 或 2x，见引擎的 `scale`）。
 *   只在使用 [Companion.ok] 时非空；[Companion.stored] 表示"已落盘、图已不再外传"。
 *   凡是非空，**调用方拥有它、用完必须自己回收**。
 * @property reason 失败原因（成功时 null）
 * @property detail 技术细节（**ASCII 短句**，例如 `output 100x100, expected 120x120`）。
 *   刻意不做本地化：它是给日志和"复制后发给我"用的，保持原样最好定位。
 */
class SrOutcome private constructor(
    val bitmap: android.graphics.Bitmap?,
    val reason: SrFailReason?,
    val detail: String?,
    private val succeeded: Boolean,
) {

    /**
     * 是否真的产出了可用结果。
     *
     * ⚠️ 用显式标志而不是 `bitmap != null`：落盘成功那条路径**刻意不交回位图**
     * （见 [Companion.stored]），但语义上仍是成功。
     */
    val ok: Boolean get() = succeeded

    /**
     * 给用户看的完整文案：**具体原因 + 可选的参数说明**（例如实际像素数与上限）。
     * 已经本地化，可直接丢给状态浮层或 Toast。
     */
    fun message(context: Context): String {
        val r = reason ?: return ""
        val base = context.getString(r.messageRes)
        return if (detail.isNullOrBlank()) base else base + "\n" + detail
    }

    companion object {
        /** 成功**并交出产物**：调用方拥有这张图，用完必须自己回收。 */
        fun ok(bitmap: android.graphics.Bitmap): SrOutcome = SrOutcome(bitmap, null, null, true)

        /**
         * 成功**且产物已落盘**：不交回任何 bitmap（[bitmap] 为 null，**别再读它**）。
         *
         * ⚠️ 为什么需要它：超分的显示路径是**从磁盘重读**的（`SrStore.load`），全项目
         * 没有任何调用方需要这张图。以前把它交回去，结果是每个调用点都漏掉一次 recycle ——
         * 2x 一页最大 ~40MB，整章批量翻译等于每页丢一块。所有权留在 [SrProcessor]，
         * 由它的 `finally` 回收。
         */
        fun stored(): SrOutcome = SrOutcome(null, null, null, true)

        fun fail(reason: SrFailReason, detail: String? = null): SrOutcome =
            SrOutcome(null, reason, detail, false)
    }
}
