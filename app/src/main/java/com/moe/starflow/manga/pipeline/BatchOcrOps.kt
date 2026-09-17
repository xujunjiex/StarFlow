package com.moe.starflow.manga.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.moe.starflow.manga.engine.DetectionBridge
import com.moe.starflow.manga.engine.PPOcrV5Engine
import com.moe.starflow.manga.engine.PPOcrV6Engine
import com.moe.starflow.manga.types.CroppedBubble
import com.moe.starflow.manga.types.CroppedTextLine
import com.moe.starflow.manga.types.RecResult
import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.manga.types.TextBlockInfo

/**
 * 引擎调用抽象（测试缝）。
 *
 * 存在的唯一理由：搬家类改动最容易出的错是"少调一步"或"顺序变了" —— 编译能过、
 * 跑起来也不报错、手动测很难穷尽。有了这层缝，单测能注入假引擎、记录调用序列，
 * 把"行为零变化"变成可断言的事实。
 *
 * 方法一律**原样转调**现有引擎单例，不重新设计签名。
 */
interface BatchOcrOps {

    /** `PPOcrV5Engine.resolveRecLang` 的转调。返回 (识别语言, 提示文案)。 */
    fun resolveRecLangV5(ctx: Context, lang: String): Pair<PPOcrV5Engine.RecLang?, String?>

    /* ---------- 检测 + 逐行裁剪（引擎侧为 suspend）---------- */

    /**
     * @param verticalDirection 竖排列序（`Manga_Text_Direction`）。检测阶段就地排序，
     *   分批边界与送进翻译的拼接顺序都建立在此，故必须由调用方传真实配置。
     */
    suspend fun detectLinesV5(ctx: Context, bmp: Bitmap, verticalDirection: TextDirection): List<CroppedTextLine>
    suspend fun detectLinesV6(ctx: Context, bmp: Bitmap, verticalDirection: TextDirection): List<CroppedTextLine>
    suspend fun detectBubblesRTDetr(bmp: Bitmap, keepTextFree: Boolean): List<CroppedBubble>

    /* ---------- 批量识别 ---------- */

    suspend fun recognizeV5(ctx: Context, crops: List<Bitmap>, lang: PPOcrV5Engine.RecLang): List<RecResult>
    suspend fun recognizeV6(ctx: Context, crops: List<Bitmap>): List<RecResult>
    suspend fun recognizeCroppedBubbles(crops: List<CroppedBubble>, lang: String): List<TextBlockInfo>
}

/** 直连真实引擎（生产用）。 */
object RealBatchOcrOps : BatchOcrOps {

    override fun resolveRecLangV5(ctx: Context, lang: String): Pair<PPOcrV5Engine.RecLang?, String?> =
        PPOcrV5Engine.resolveRecLang(ctx, lang)

    override suspend fun detectLinesV5(
        ctx: Context, bmp: Bitmap, verticalDirection: TextDirection
    ): List<CroppedTextLine> = DetectionBridge.detectAndCropPPOcrV5Lines(ctx, bmp, verticalDirection)

    override suspend fun detectLinesV6(
        ctx: Context, bmp: Bitmap, verticalDirection: TextDirection
    ): List<CroppedTextLine> = DetectionBridge.detectAndCropPPOcrV6Lines(ctx, bmp, verticalDirection)

    override suspend fun detectBubblesRTDetr(bmp: Bitmap, keepTextFree: Boolean): List<CroppedBubble> =
        DetectionBridge.detectAndCropRTDetrV2(bmp, keepTextFree)

    override suspend fun recognizeV5(
        ctx: Context, crops: List<Bitmap>, lang: PPOcrV5Engine.RecLang,
    ): List<RecResult> = PPOcrV5Engine.recognizeBatchWithCls(ctx, crops, lang)

    override suspend fun recognizeV6(ctx: Context, crops: List<Bitmap>): List<RecResult> =
        PPOcrV6Engine.recognizeBatchWithCls(ctx, crops)

    override suspend fun recognizeCroppedBubbles(
        crops: List<CroppedBubble>, lang: String,
    ): List<TextBlockInfo> = DetectionBridge.recognizeCroppedBubbles(crops, lang)
}
