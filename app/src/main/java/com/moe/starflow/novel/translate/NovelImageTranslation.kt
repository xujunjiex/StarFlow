package com.moe.starflow.novel.translate

import org.json.JSONArray
import org.json.JSONObject

/**
 * 图片译文的**三态**（与漫画 `OverlayMode` 同义，见 `NovelImageOverlay`）：
 *
 * - [TRANSLATED] 译文画回图片（默认态）
 * - [ORIGINAL] **OCR 认出来的原文**画回原位（白块黑字）—— 不是裸原图；漫画那边同义
 * - [PLAIN] 纯原图，一个覆盖块都不画
 *
 * ⚠️ 「原文」不等于「纯图」（用户口径容易混）：原文态是"把原字盖掉、用干净字体重画一遍"，
 * 中文竖排/异体字被 OCR 认错时它是唯一能看清原字的手段；想看真实的原图用纯图态。
 */
enum class NovelImageVisual {
    TRANSLATED,
    ORIGINAL,
    PLAIN,
    ;

    /** 循环切换（阅读器右下角那个三态按钮）。 */
    fun next(): NovelImageVisual = when (this) {
        TRANSLATED -> ORIGINAL
        ORIGINAL -> PLAIN
        PLAIN -> TRANSLATED
    }
}

/**
 * 图片上的**一个文字块**：OCR 出的矩形 + 原文 + 译文。
 *
 * 矩形用裸 int（不用 `android.graphics.Rect`）：这份数据要进 JSON、也要能在纯 JVM 单测里构造，
 * 而 `Rect` 是 Android 类（纯 JVM 单测里是桩）。渲染时再包一层 `Rect`。
 */
data class NovelImageTextBlock(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val original: String,
    val translated: String,
    /** 竖排（日漫/竖排封面）；渲染时决定画横排还是竖排。 */
    val vertical: Boolean = false,
    /** OCR 估出来的字号（渲染的**起点**；实际字号由 `LayoutEngine` 按框大小二分）。 */
    val fontSize: Float = 16f,
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * 一张插图的**翻译载荷**（存 `novel_paragraph_translation.imagePayload` 的 JSON）。
 *
 * ### 为什么必须存下来，而不是只存译文文本
 * 图片段的译文不是"一段文字"，而是「若干文字块 + 各自的矩形 + 原文 + 译文」——
 * 要把译文**画回图片上**，缺任何一项都画不出来。存一份载荷的好处：
 * - 切三态（译文/原文/纯图）**不用重跑 OCR**（一次 OCR 几秒）
 * - 「重新翻译」可以只重发翻译请求，也可以整体重来
 * - 重排 / 重开阅读器 / 换字号之后照样能画（载荷里是**图片坐标**，与排版无关）
 *
 * ### 与行内三列的关系
 * `translatedText` 列存 [translatedText]（各块译文按换行拼接）——导出、进度、「已翻」判据
 * 都复用它，所以图片段在库里就是一条**普通的成功译文行**，不需要任何特殊的读法。
 * `sourceText` 列存 [sourceText]（OCR 原文），失败明细里显示的就是它。
 */
data class NovelImageTranslation(
    val sourceLang: String,
    val targetLang: String,
    /** 认出这些字的引擎（本地 OCR 或图片接口名，排查用）。 */
    val ocrEngine: String,
    val blocks: List<NovelImageTextBlock>,
    /**
     * 图片接口只返回整段文本、没有文字坐标时的兜底载荷。
     *
     * 有它时 [blocks] 为空：图片不画覆盖层，阅读器用文本弹窗展示译文；
     * 但它仍然算“这张图已经翻过”（导出/进度/「已翻」判据都复用 [translatedText]）。
     */
    val plainText: String? = null,
) {
    /** 各块原文按换行拼接（写入 `sourceText` 列）。 */
    val sourceText: String get() = blocks.joinToString("\n") { it.original }

    /** 各块译文按换行拼接（写入 `translatedText` 列）；纯文本兜底时直接用 [plainText]。 */
    val translatedText: String
        get() = plainText?.takeIf { it.isNotBlank() }
            ?: blocks.joinToString("\n") { it.translated }

    val isEmpty: Boolean get() = blocks.isEmpty() && plainText.isNullOrBlank()
}

/**
 * 载荷的 JSON 编解码（`org.json`，与项目其它 codec 同一套）。
 *
 * ⚠️ 解码**绝不允许抛**：载荷是历史数据，格式对不上（老版本/半截写入/被别的字段蹭脏）时
 * 返回 null 让调用方回落成"这张图没翻过"，而不是让阅读器打不开。
 */
object NovelImagePayloadCodec {

    fun encode(t: NovelImageTranslation): String {
        val arr = JSONArray()
        for (b in t.blocks) {
            arr.put(
                JSONObject().apply {
                    put("l", b.left)
                    put("t", b.top)
                    put("r", b.right)
                    put("b", b.bottom)
                    put("o", b.original)
                    put("x", b.translated)
                    put("v", b.vertical)
                    put("f", b.fontSize.toDouble())
                },
            )
        }
        return JSONObject().apply {
            put("sl", t.sourceLang)
            put("tl", t.targetLang)
            put("eng", t.ocrEngine)
            put("blocks", arr)
            t.plainText?.takeIf { it.isNotBlank() }?.let { put("text", it) }
        }.toString()
    }

    fun decode(json: String?): NovelImageTranslation? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("blocks") ?: return null
            val blocks = ArrayList<NovelImageTextBlock>(arr.length())
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val original = b.optString("o")
                val translated = b.optString("x")
                if (original.isEmpty() && translated.isEmpty()) continue
                blocks += NovelImageTextBlock(
                    left = b.optInt("l"),
                    top = b.optInt("t"),
                    right = b.optInt("r"),
                    bottom = b.optInt("b"),
                    original = original,
                    translated = translated,
                    vertical = b.optBoolean("v"),
                    fontSize = b.optDouble("f", 16.0).toFloat(),
                )
            }
            val plainText = o.optString("text").takeIf { it.isNotBlank() }
            if (blocks.isEmpty() && plainText == null) return null
            NovelImageTranslation(
                sourceLang = o.optString("sl"),
                targetLang = o.optString("tl"),
                ocrEngine = o.optString("eng"),
                blocks = blocks,
                plainText = plainText,
            )
        } catch (e: Exception) {
            null
        }
    }
}
