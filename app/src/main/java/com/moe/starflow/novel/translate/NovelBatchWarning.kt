package com.moe.starflow.novel.translate

import com.moe.starflow.utils.TokenEstimator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **单批超长预警**（用户口径）：
 * 「一批的合并文本估算超过设定的阈值时弹窗提示『单次请求内容超长、效果可能变差』，是否继续」。
 *
 * 判据是**粗估 token**（[TokenEstimator]，CJK≈1/字、其它≈1/4 字符），不接任何厂商的分词器 ——
 * 这里要的是"大概多长"，不是精确计费。
 *
 * ⚠️ 阈值是**用户设置**（面板里的「单批预警阈值」滑块），不是写死的常量；档位都是较大的值
 * （2048 起）—— 一批 3 段正常小说（每段几百字）也就 1~2k tokens，档位太小会逢批必弹。
 */
object NovelBatchWarning {

    /**
     * 可选档位（token）。**单射**（一个位置一个值，见漫画调试滑块的教训）且默认值恰好落在档位上。
     *
     * 面板滑块的位置 ↔ 值走 [tiers]/[thresholdAt] 这一对函数，不要再各写一份互逆公式。
     */
    val tiers: List<Int> = listOf(2048, 4096, 8192, 16384, 32768)

    /** 默认 4096（用户指定）。 */
    const val DEFAULT_THRESHOLD = 4096

    /** 默认档位在 [tiers] 里的下标。 */
    val defaultIndex: Int get() = tiers.indexOf(DEFAULT_THRESHOLD)

    fun maxIndex(): Int = tiers.lastIndex

    /** 滑块位置 → 阈值（越界夹回两端）。 */
    fun thresholdAt(index: Int): Int = tiers[index.coerceIn(0, tiers.lastIndex)]

    /** 阈值 → 滑块位置（不在档位上的旧值收敛到最近的档位）。 */
    fun indexOf(threshold: Int): Int {
        var best = 0
        var bestDiff = Int.MAX_VALUE
        tiers.forEachIndexed { i, v ->
            val d = kotlin.math.abs(v - threshold)
            if (d < bestDiff) {
                bestDiff = d
                best = i
            }
        }
        return best
    }

    /** 任意存下来的值 → 最接近的档位（老值/手改值一律收敛，滑块与判据不会打架）。 */
    fun normalize(threshold: Int): Int = thresholdAt(indexOf(threshold))

    /**
     * 本批合并文本的 token 估算。
     *
     * ⚠️ 与真正发出去的请求同口径：`NovelTranslationBatch.buildPrompt` 把各段按 `[编号] 原文`
     * 拼起来，段与段之间是换行（空白不计），所以这里也只拼接原文本身。
     */
    fun estimateOf(texts: Iterable<String>): Int = TokenEstimator.estimate(texts.joinToString("\n"))

    /** 是否该弹预警。阈值 <= 0 视为关闭预警。 */
    fun shouldWarn(estimate: Int, threshold: Int): Boolean = threshold > 0 && estimate > threshold
}

/**
 * 预警的宿主回调（注入 [NovelChapterTranslator]）。
 *
 * - [threshold] 每次现读设置（用户在面板上改了立刻生效，不必重建引擎）
 * - [confirm] 返回 true = 继续翻这一批；false = **取消这一批**（不发送、不写库）
 *
 * ⚠️ 后台任务（阅读器已关）没有 UI 可弹窗：实现方应当**放行**（返回 true），
 * 不然后台任务会静默停在一半 —— 预警是给用户看的提示，不是硬闸门。
 */
class NovelBatchWarnGate(
    val threshold: () -> Int,
    val confirm: suspend (estimate: Int, threshold: Int) -> Boolean,
)

/**
 * 「不要再问一遍」的确认器：**同一轮任务里只打扰用户一次**。
 *
 * 用户对一批说了「取消这次翻译」之后，同一轮里后面那些同样超长的批**直接跳过、不再弹窗** ——
 * 一整章几百批、每批都超长的话，逐批弹窗等于把用户按在弹窗上。新一轮任务（翻译本章 / 选段翻译 /
 * 队列重启）由调用方 [reset]。
 *
 * 纯逻辑（无 Android 依赖），单测直接驱动。
 */
class NovelOversizeConfirmer(
    /** 真正弹窗问用户的那一下（UI 侧实现）。 */
    private val ask: suspend (estimate: Int, threshold: Int) -> Boolean,
) {

    private val declined = AtomicBoolean(false)

    /** 新一轮任务开始：重新允许弹窗。 */
    fun reset() = declined.set(false)

    /** 已经拒绝过（本轮后续超长批会静默跳过）。 */
    fun hasDeclined(): Boolean = declined.get()

    suspend fun confirm(estimate: Int, threshold: Int): Boolean {
        if (declined.get()) return false
        val ok = ask(estimate, threshold)
        if (!ok) declined.set(true)
        return ok
    }
}
