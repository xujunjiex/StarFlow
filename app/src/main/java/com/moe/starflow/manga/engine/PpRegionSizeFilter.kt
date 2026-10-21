package com.moe.starflow.manga.engine

import android.content.Context
import android.content.SharedPreferences
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** PP 漫画整页检测框的相对尺寸过滤；在裁剪、识别及分批之前计算一次。 */
object PpRegionSizeFilter {
    const val KEY_ENABLED = "Manga_PP_Size_Filter"
    const val KEY_SMALL = "Manga_PP_Size_Filter_Small"
    const val KEY_LARGE = "Manga_PP_Size_Filter_Large"
    const val DEFAULT_TENTHS = 35
    const val MIN_TENTHS = 5
    const val MAX_TENTHS = 80
    private const val MIN_SAMPLES = 5
    private const val MAD_SCALE = 1.4826
    private const val EPS = 1e-9

    data class Config(
        val enabled: Boolean = false,
        val smallTolerance: Double = 3.5,
        val largeTolerance: Double = 3.5
    ) {
        init {
            require(smallTolerance in 0.5..8.0 && largeTolerance in 0.5..8.0)
        }
    }

    enum class Reason { TOO_SMALL, TOO_LARGE, INVALID_GEOMETRY }
    data class Discarded(val index: Int, val reason: Reason, val size: Double?)
    data class Bounds(val median: Double, val lower: Double, val upper: Double)
    data class Selection(
        val keptIndices: List<Int>,
        val discarded: List<Discarded>,
        val bounds: Bounds?
    ) {
        fun reasonText(item: Discarded): String = when (item.reason) {
            Reason.TOO_SMALL -> "page-size small: ${format(item.size)} < ${format(bounds?.lower)}"
            Reason.TOO_LARGE -> "page-size large: ${format(item.size)} > ${format(bounds?.upper)}"
            Reason.INVALID_GEOMETRY -> "page-size invalid geometry"
        }
    }

    fun read(context: Context): Config = read(CustomPreference.getInstance(context).getSharedPreferences())

    fun read(prefs: SharedPreferences): Config = Config(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        smallTolerance = prefs.getInt(KEY_SMALL, DEFAULT_TENTHS).coerceIn(MIN_TENTHS, MAX_TENTHS) / 10.0,
        largeTolerance = prefs.getInt(KEY_LARGE, DEFAULT_TENTHS).coerceIn(MIN_TENTHS, MAX_TENTHS) / 10.0
    )

    /** 无 Android 度量或引擎状态依赖，返回原始下标以保持框与分数、读序的对应关系。 */
    fun evaluate(boxes: List<FloatArray>, config: Config?): Selection {
        if (config?.enabled != true) return Selection(boxes.indices.toList(), emptyList(), null)
        val sizes = boxes.map(::shortSide)
        val samples = sizes.filterNotNull().map(::ln).sorted()
        val center = if (samples.size >= MIN_SAMPLES) median(samples) else null
        val spread = center?.let { m ->
            val mad = median(samples.map { abs(it - m) }.sorted())
            // 检测尺寸相同时 MAD=0；给尺度一个下限，默认容差对应中位数的 0.5–2 倍。
            // 容差乘在尺度之外，因此用户调节在 MAD=0 时也始终有效。
            max(MAD_SCALE * mad, ln(2.0) / 3.5)
        }
        val lowerLog = center?.let { it - config.smallTolerance * spread!! }
        val upperLog = center?.let { it + config.largeTolerance * spread!! }
        val bounds = center?.let { Bounds(exp(it), exp(lowerLog!!), exp(upperLog!!)) }
        val kept = mutableListOf<Int>()
        val discarded = mutableListOf<Discarded>()
        for (i in boxes.indices) {
            val size = sizes[i]
            val reason = when {
                size == null -> Reason.INVALID_GEOMETRY
                lowerLog != null && ln(size) < lowerLog - EPS -> Reason.TOO_SMALL
                upperLog != null && ln(size) > upperLog + EPS -> Reason.TOO_LARGE
                else -> null
            }
            if (reason == null) kept += i else discarded += Discarded(i, reason, size)
        }
        return Selection(kept, discarded, bounds)
    }

    fun selectPage(boxes: List<FloatArray>, config: Config?, tag: String): Selection {
        val selection = evaluate(boxes, config)
        if (config?.enabled == true) {
            val range = selection.bounds?.let { "${format(it.lower)}..${format(it.upper)} median=${format(it.median)}" }
                ?: "insufficient samples (<$MIN_SAMPLES)"
            LogCollector.d(tag, "PP page-size: input=${boxes.size}, kept=${selection.keptIndices.size}, " +
                "discarded=${selection.discarded.size}, range=$range")
        }
        return selection
    }

    fun filterPage(boxes: List<FloatArray>, config: Config?, tag: String): List<FloatArray> {
        if (config?.enabled != true) return boxes
        return selectPage(boxes, config, tag).keptIndices.map { boxes[it] }
    }

    private fun shortSide(box: FloatArray): Double? {
        if (box.size < 8 || (0 until 8).any { !box[it].isFinite() }) return null
        fun edge(a: Int, b: Int) = hypot(
            box[a].toDouble() - box[b], box[a + 1].toDouble() - box[b + 1]
        )
        val size = min((edge(0, 2) + edge(4, 6)) / 2, (edge(2, 4) + edge(6, 0)) / 2)
        return size.takeIf { it.isFinite() && it > 0 }
    }

    private fun median(sorted: List<Double>): Double {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    private fun format(value: Double?): String = value?.let {
        String.format(java.util.Locale.ROOT, "%.1f", it)
    } ?: "?"
}
