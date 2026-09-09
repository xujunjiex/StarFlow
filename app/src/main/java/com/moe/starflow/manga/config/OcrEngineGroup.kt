package com.moe.starflow.manga.config
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import com.moe.starflow.R
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine

/**
 * 统一 OCR 引擎选择：4 组，游戏/漫画共用唯一事实来源。
 * 游戏/漫画引擎映射、支持源语言、是否需下载全部收敛于此。
 */
enum class OcrEngineGroup(
    val key: String,
    val labelRes: Int,
    val gameEngine: Int,          // 游戏 FloatingBallService：MLKIT=0/V5=1/MANGA=2/V6=3
    val mangaDet: DetEngine,
    val mangaOcr: OcrEngine,
    val sourceLangs: Set<String>, // 30 种池内子集
    val needsDownload: Boolean,
    val requiredModelsRes: Int
) {
    MLKIT("mlkit", R.string.model_group_mlkit, 0, DetEngine.MLKIT, OcrEngine.MLKit,
        setOf("zh","zh-TW","en","ja","ko","fr","de","es","pt","it","nl","pl","sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","ca","af","hi","mr","ne"),
        needsDownload = false, requiredModelsRes = 0),
    PP_OCR_V6("ppocrv6", R.string.model_group_ppocrv6, 3, DetEngine.PP_OCR_V6, OcrEngine.PPOcrV6,
        setOf("zh","zh-TW","en","ja","fr","de","es","pt","it","nl","pl","sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","ca","af"),
        needsDownload = false, requiredModelsRes = 0),
    PP_OCR_V5("ppocrv5", R.string.model_group_ppocrv5, 1, DetEngine.PP_OCR_V5, OcrEngine.PPOcrV5,
        setOf("zh","zh-TW","en","ja","ko","ru"),
        needsDownload = true, requiredModelsRes = R.string.ocr_group_v5_required),
    RT_MANGA("rtmanga", R.string.model_group_rt_manga, 2, DetEngine.RT_DETR_V2, OcrEngine.MangaOcr,
        setOf("ja"),
        needsDownload = true, requiredModelsRes = R.string.ocr_group_manga_required);

    companion object {
        /** 悬浮窗快捷切换的常用源语言（仅中文繁体/日/英/韩），顺序即悬浮窗循环顺序。主页完整 30 语言池不受影响。 */
        val FLOATING_COMMON_LANGS = listOf("zh-TW", "ja", "en", "ko")

        /** 30 种语言池（全组并集），顺序即首页源语言列表顺序 */
        val ALL_LANGS = listOf(
            "zh","zh-TW","en","ja","ko","ru","fr","de","es","pt","it","nl","pl",
            "sv","da","no","fi","cs","hu","ro","tr","vi","id","ms","fil","hi","mr","ne","ca","af"
        )
        fun fromKey(key: String): OcrEngineGroup = entries.firstOrNull { it.key == key } ?: MLKIT
    }
}
