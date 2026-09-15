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

    /** 分批路径已完整处理。[translated] 可能为空列表（如本页未检测到文字）。 */
    data class Handled(val translated: List<TranslatedBubble>) : BatchOutcome

    /** 不该走 / 走不通分批：开关关闭、Hy-MT2、引擎组合不支持、气泡数 ≤ 阈值、或中途异常回退。 */
    data object NotApplicable : BatchOutcome
}
