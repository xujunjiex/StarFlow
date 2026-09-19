package com.moe.starflow.manga.pipeline

import com.moe.starflow.manga.types.CroppedBubble
import com.moe.starflow.manga.types.TranslatedBubble

/**
 * 分批管线的处理结果。
 *
 * 与旧 `MangaFloatingService.incrementalTranslateFlow(): Boolean` 的对应关系：
 * - [Handled] ← 旧返回值 `true`（调用方跳过原有流程）
 * - [NotApplicable] ← 旧返回值 `false`（调用方走原有流程；包含"气泡太少"与"中途出错回退"两种情况）
 * - [DetectedNotBatched] ← 旧返回值 `false`，但**检测已经跑过**，调用方可以直接复用而不必重跑
 */
sealed interface BatchOutcome {

    /**
     * 分批路径已完整处理，调用方应**收尾**（`finalizeIncremental`：存缓存 / 球状态置完成 / 更新
     * `lastTranslatedHash` / 调度下一次自动检测）。[translated] 可能为空列表
     * （如第一批 OCR 未产出文字块，此时旧实现同样会调 finalize）。
     */
    data class Handled(val translated: List<TranslatedBubble>) : BatchOutcome

    /**
     * 分批路径已处理，但**无结果可收尾**（本页未检测到文字/气泡，已弹过提示）。
     *
     * 调用方应跳过原有流程，**但绝不可调用 `finalizeIncremental`** ——
     * 旧实现在这三个出口是直接 `return true`（对应 [IncrementalBatchPipeline] 的
     * `rtDetrMangaOcr` / `ppOcrV5` / `ppOcrV6` 各自的"未检测到"分支），
     * 而 `finalizeIncremental` 即使收到空列表也会执行 `lastTranslatedHash = currentPHash`
     * → 自动翻译状态机把空页误判为"已翻译"，**该页之后不会再被翻译**。
     * 这是本管线最容易踩的坑。
     */
    data object HandledEmpty : BatchOutcome

    /** 不该走 / 走不通分批：开关关闭、Hy-MT2、引擎组合不支持、或中途异常回退。 */
    data object NotApplicable : BatchOutcome

    /**
     * 「检测**已经跑完**，只是气泡太少不值得分批」——把裁剪好的检测结果交回调用方**复用**。
     *
     * 存在意义：普通路径（气泡少的页面恒定走它）本来会**再跑一遍整页检测**。RT-DETR-V2 的一次
     * 前向在手机上是几百毫秒级的开销，气泡少的页面重复付一次纯属浪费。
     *
     * ⚠️ **谁复用谁回收**：管线**不再**回收 [bubbles] 里的 `croppedBitmap`。调用方要么拿去识别
     * （`DetectionBridge.recognizeCroppedBubbles` 成功时会自己回收），要么直接逐个 `recycle()`
     * 后按 [NotApplicable] 处理。忘了回收就是内存泄漏。
     */
    data class DetectedNotBatched(val bubbles: List<CroppedBubble>) : BatchOutcome
}

