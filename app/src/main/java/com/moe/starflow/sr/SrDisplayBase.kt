package com.moe.starflow.sr

/**
 * 「阅读器这一页该用**哪种底图**」+ 「渲染缓存 key 里的底图签名」—— **纯函数，可纯 JVM 单测**。
 *
 * 为什么要单独抽出来：这个判断散在渲染路径里必然出现「关了超分还在显示超分图」「换了模型
 * 屏幕上还是旧模型的图」这类**不崩不报错、只是显示不对**的静默问题。抽成纯函数之后
 * `SrDisplayBaseTest` 能把整张真值表钉死（与 [SuperResolutionEngines.resolveSteps] 同一套路）。
 *
 * ## 与 [SuperResolutionEngines.resolveSteps] 的关系
 * 两者**必须同源**：`resolveSteps` 决定"要不要跑增强"，本类决定"显示用哪张图"。
 * 同一条互斥规则（超分模型可用 → Anime4K 让位）在两边各写一份就会漂移。
 *
 * ## 签名为什么必须存在
 * 同一页在「原图 / 超分模型 / Anime4K」三种底图下渲出来的位图是**不同**的，
 * 而 `renderLru` 的 key 只有 `页号 + overlay 态`。签名进 key 之后：
 * - 超分做完了 → 签名从 `o` 变 `s:XXX` → 旧渲染自然失配，不会把糊底图的译图当新结果返回
 * - 用户换模型 → `s:A` 变 `s:B` → 自动重渲染（不会拿 A 的产物冒充 B）
 * - 用户切回原图 → 变回 `o`，且**磁盘文件不动**（用户口径：切换只影响显示）
 */
enum class SrBaseKind {
    /** 原图（超分关 / 用户把该页切回原图 / 该页还没有超分文件） */
    ORIGINAL,

    /** 已落盘的超分模型底图（2x） */
    SR_MODEL,

    /** Anime4K 同分辨率增强底图（1x） */
    ANIME4K,
}

object SrDisplayBase {

    /**
     * 该页该用哪种底图。
     *
     * @param srVisualOn 用户对该页的二态选择（true = 显示超分底图）。**默认 true**：
     *   用户口径「超分后翻译默认也显示在超分后的图片上」。
     * @param srModelUsable 选了模型**且**文件在（`SuperResolutionEngines.isSrModelUsable`）
     * @param storedSrFile 该页已有落盘的超分结果（`SrStore.exists`）
     *
     * ⚠️ 用户切回原图（`srVisualOn = false`）时，**连 Anime4K 也不跑** —— 那是"看原图"这个
     * 意图本身，再叠一层锐化就不是原图了。
     */
    fun resolveBaseKind(
        srVisualOn: Boolean,
        srEnabled: Boolean,
        srModelUsable: Boolean,
        anime4kEnabled: Boolean,
    ): SrBaseKind {
        if (!srVisualOn) return SrBaseKind.ORIGINAL
        // 与 resolveSteps 同一判据：超分模型可用时 Anime4K 让位（互斥）
        if (srEnabled && srModelUsable) return SrBaseKind.SR_MODEL
        return if (anime4kEnabled) SrBaseKind.ANIME4K else SrBaseKind.ORIGINAL
    }

    /**
     * 底图签名（进渲染缓存 key）。
     *
     * `"o"` 原图 / `"s:<模型名>"` 超分模型 / `"a:<Anime4K 档位 id>"` Anime4K。
     *
     * ⚠️ **`SR_MODEL` 但该页没有落盘文件时降级成 `"o"`**：表示"该页还没超分过"，
     * 此时渲染确实就是原图 —— 签名必须如实反映**这一页实际用的底图**，
     * 否则自动超分完成后旧渲染不会被作废。
     */
    fun baseSignature(
        kind: SrBaseKind,
        srModelName: String?,
        anime4kModeId: String?,
        storedSrFile: Boolean,
    ): String = when (kind) {
        SrBaseKind.ORIGINAL -> "o"
        SrBaseKind.SR_MODEL ->
            if (storedSrFile && !srModelName.isNullOrEmpty()) "s:$srModelName" else "o"
        SrBaseKind.ANIME4K ->
            if (!anime4kModeId.isNullOrEmpty()) "a:$anime4kModeId" else "o"
    }
}
