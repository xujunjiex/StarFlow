package com.moe.starflow.utils
import com.moe.starflow.translate.widget.*

import android.content.SharedPreferences
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.OcrEngine
import com.moe.starflow.manga.config.OcrEngineGroup

/** 统一 OCR 引擎选择的共享 prefs 读写（游戏/漫画共用）。 */
object OcrEngineManager {
    const val PREF_KEY = "Ocr_Engine_Group"

    fun getOcrEngineGroup(prefs: SharedPreferences): OcrEngineGroup {
        val key = prefs.getString(PREF_KEY, null)
        if (key != null) {
            // 未知 key 兜底 PP_OCR_V6，与全新安装默认一致。
            return OcrEngineGroup.entries.firstOrNull { it.key == key } ?: OcrEngineGroup.PP_OCR_V6
        }
        // 迁移：优先漫画 prefs（4 组一一对应）；无漫画 legacy 键时用游戏 prefs 兜底。
        // ⚠️ 必须用 contains() 判断漫画 legacy 键是否存在——getInt 的默认值 5/5 恰好命中 PP_OCR_V6 组，
        // 若直接匹配会让仅有游戏 legacy（如 Game_OCR_Engine=0=MLKIT）的用户被误迁移成 PP_OCR_V6。
        val hasMangaLegacy = prefs.contains("Manga_Det_Model") || prefs.contains("Manga_Rec_Model")
        val migrated = if (hasMangaLegacy) {
            val det = prefs.getInt("Manga_Det_Model", DetEngine.PP_OCR_V6.value)
            val rec = prefs.getInt("Manga_Rec_Model", OcrEngine.PPOcrV6.value)
            OcrEngineGroup.entries.firstOrNull {
                it.mangaDet.value == det && it.mangaOcr.value == rec
            } ?: fromGameEngine(prefs.getInt("Game_OCR_Engine", OcrEngineGroup.PP_OCR_V6.gameEngine))
        } else {
            // 无 Game_OCR_Engine legacy 键（全新安装）→ 默认 PP_OCR_V6。
            fromGameEngine(prefs.getInt("Game_OCR_Engine", OcrEngineGroup.PP_OCR_V6.gameEngine))
        }
        setOcrEngineGroup(prefs, migrated)
        return migrated
    }

    fun setOcrEngineGroup(prefs: SharedPreferences, group: OcrEngineGroup) {
        prefs.edit().putString(PREF_KEY, group.key).apply()
    }

    /**
     * 悬浮窗循环切换源语言（游戏/漫画共用唯一实现，不再各自写一份）。
     * 只在「常用语言 ∩ 当前 OCR 组支持语言」内循环，跳过不支持的；
     * 写回共享 pref `Source_Language`（与主页选中一一对应）。
     * @return 切换后的语言代码；无可切换（如 manga-ocr 仅支持 ja）返回 null
     */
    fun cycleFloatingSourceLang(prefs: SharedPreferences): String? {
        val group = getOcrEngineGroup(prefs)
        val cycle = OcrEngineGroup.FLOATING_COMMON_LANGS.filter { it in group.sourceLangs }
        if (cycle.isEmpty()) return null
        val current = prefs.getString("Source_Language", "ja")
        val idx = cycle.indexOf(current).coerceAtLeast(0)
        for (i in 1..cycle.size) {
            val next = cycle[(idx + i) % cycle.size]
            if (next == current) return null          // 单语言组（manga-ocr）→ 无可切换
            prefs.edit().putString("Source_Language", next).apply()
            return next
        }
        return null
    }

    /** 游戏 OCR 引擎值 → 组。MLKIT=0/V5=1/MANGA=2/V6=3。 */
    private fun fromGameEngine(value: Int): OcrEngineGroup = when (value) {
        0 -> OcrEngineGroup.MLKIT
        1 -> OcrEngineGroup.PP_OCR_V5
        2 -> OcrEngineGroup.RT_MANGA
        3 -> OcrEngineGroup.PP_OCR_V6
        // 未知值兜底：与全新安装默认一致（PP_OCR_V6）
        else -> OcrEngineGroup.PP_OCR_V6
    }
}
