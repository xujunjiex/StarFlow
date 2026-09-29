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
 * @property bitmap 成功时的产物（2x 或 1x，见引擎的 `scale`）
 * @property reason 失败原因（成功时 null）
 * @property detail 技术细节（**ASCII 短句**，例如 `output 100x100, expected 120x120`）。
 *   刻意不做本地化：它是给日志和"复制后发给我"用的，保持原样最好定位。
 */
class SrOutcome private constructor(
    val bitmap: android.graphics.Bitmap?,
    val reason: SrFailReason?,
    val detail: String?,
) {

    val ok: Boolean get() = bitmap != null

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
        fun ok(bitmap: android.graphics.Bitmap): SrOutcome = SrOutcome(bitmap, null, null)

        fun fail(reason: SrFailReason, detail: String? = null): SrOutcome =
            SrOutcome(null, reason, detail)
    }
}
