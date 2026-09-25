package com.moe.starflow.manga.config

import android.content.SharedPreferences
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.TextDirection

/**
 * RT-DETR-V2 + manga-ocr 的**渲染方向**设置（两态，默认竖排右→左）。
 *
 * ### 为什么这条路径不判断方向
 *
 * RT-DETR-V2 只**检测矩形气泡**（没有文字行 quad），而 manga-ocr 只识别**日文**——
 * 日漫正文几乎不存在横排，唯一需要"判断"的场合是标题/拟声词等少数横排文字。
 * 用一个 `h > w` 的弱信号去猜，代价是**多列竖排的宽气泡必被猜反**：漫画里 2~4 列的对话框
 * 天生"宽 > 高"，判成横排后译文按行渲染，整块方向就错了（用户实测：同页另一半竖排正常，
 * 这一条横着排）。
 *
 * 所以这里**不做几何判断**：默认竖排（右→左），想要横排的人自己选。
 *
 * ### 生效范围（**只对 RT-DETR-V2 + manga-ocr 生效**）
 *
 * | 引擎组合 | 方向从哪来 |
 * |---|---|
 * | **RT-DETR-V2 + manga-ocr** | 本设置（两态：竖排右→左 / 横排），**不做横竖判断** |
 * | PP-OCRv5 / PP-OCRv6 | **自动判断**横竖（文字行 quad 是强信号），本设置**不生效**；竖排列序仍走 `Manga_Text_Direction` |
 * | ML Kit | 同上（自动判断），固定右→左 |
 *
 * ⚠️ 与 `Manga_Text_Direction`（竖排方向：右→左 / 左→右）**不是一回事**，两者独立：
 * 那个管"竖排的列从哪边开始"，只在 PP 路径的竖排上生效；本设置管 RT 路径"竖排还是横排"。
 * 用户把竖排方向设成"左→右"时，RT 路径**不会**跟着变（日文竖排恒右→左）。
 *
 * 回归守卫：[RtTextDirectionTest]。
 */
object RtTextDirection {

    /** prefs key（【识别自由文字】正下方的「RT-DETR 渲染方向」）。 */
    const val KEY = "Manga_RT_Text_Direction"

    /** 竖排右→左（默认，传统日漫）。 */
    const val VALUE_VERTICAL_RL = "0"

    /** 横排渲染（标题/拟声词等横排内容）。 */
    const val VALUE_HORIZONTAL = "1"

    /** 解析 prefs 值。**未知/缺失值一律回退竖排右→左**（默认值即设置页 defaultValue）。 */
    fun fromPref(value: String?): TextDirection =
        if (value == VALUE_HORIZONTAL) TextDirection.HORIZONTAL else TextDirection.VERTICAL_RL

    fun load(prefs: SharedPreferences): TextDirection =
        fromPref(prefs.getString(KEY, VALUE_VERTICAL_RL))

    /**
     * 气泡/渲染方向解析：**RT-DETR-V2 用本设置，其余引擎用 `Manga_Text_Direction`**。
     *
     * 收敛成一处的原因：解析散在悬浮窗、阅读器、查看器、分批管线四处，任一处漏掉就是
     * 「设置只对某个入口生效」这类静默失效。
     */
    fun resolve(
        detEngine: DetEngine,
        rtDirection: TextDirection,
        mangaTextDirection: TextDirection,
    ): TextDirection = if (detEngine == DetEngine.RT_DETR_V2) rtDirection else mangaTextDirection
}

/** [MangaModeConfig] 的渲染方向：RT 路径看本设置，PP/ML Kit 看 `Manga_Text_Direction`。 */
val MangaModeConfig.renderTextDirection: TextDirection
    get() = RtTextDirection.resolve(detEngine, rtTextDirection, textDirection)
