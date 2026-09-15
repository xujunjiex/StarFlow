package com.moe.starflow.manga.pipeline

import com.moe.starflow.manga.types.TranslatedBubble

/**
 * 分批管线的处理结果。
 *
 * 与旧 `MangaFloatingService.incrementalTranslateFlow(): Boolean` 的对应关系：
 * - [Handled] ← 旧返回值 `true`（调用方跳过原有流程）
 * - [NotApplicable] ← 旧返回值 `false`（调用方走原有流程；包含"气泡太少"与"中途出错回退"两种情况）
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

    /** 不该走 / 走不通分批：开关关闭、Hy-MT2、引擎组合不支持、气泡数 ≤ 阈值、或中途异常回退。 */
    data object NotApplicable : BatchOutcome
}
