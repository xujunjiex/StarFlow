package com.moe.starflow.sr.anime4k

import android.content.SharedPreferences
import com.moe.starflow.R

/**
 * Anime4K 的 6 个预设（**色彩面板里可调的那个参数**）。
 *
 * 着色器组合与 Kototoro 的 `Anime4KShaderAssets` 逐项对齐（同名文件、同顺序），
 * 资产放在 `assets/anime4k/`。
 *
 * ⚠️ **本管线里 Anime4K 不放大**（输出尺寸 == 输入尺寸），它是"同分辨率线条修复/锐化"。
 * 原因见 [Anime4kCompiler] 的类注释（`//!WHEN` 被忽略 + `OUTPUT` 恒等于输入尺寸）。
 * 所以它的定位是**基础显示层**：零下载、零模型、~几十毫秒，让线条更利，
 * 不产生新的分辨率；要真放大得下载超分模型。
 *
 * 实测（真实漫画页，四张图平均"缩回 PSNR"，越高越忠实原图）：
 * mode C 28.45 / A 25.09 / B 23.57 —— 都比超分模型低不少（waifu2x 约 40），
 * 也就是说它是**"看着更利"而非"更还原"**，这正是把它当"基础层"而不是"超分"的原因。
 */
enum class Anime4kMode(
    /** 存进 prefs 的稳定标识（**不要改**，改了用户的已选档会失配） */
    val id: String,
    /** 预设用到的 shader 资产文件名（顺序即执行顺序） */
    val shaders: List<String>
) {
    /** 强恢复 + 放大（最重） */
    A("ANIME4K_A", listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_VL.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl"
    )),

    /** 均衡（恢复 M+S，放大 M+S） */
    B("ANIME4K_B", listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Restore_CNN_S.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_S.glsl"
    )),

    /** 只降噪 / 线条修复，**不动结构**（最轻、最保守，实测最忠实） */
    C("ANIME4K_C", listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_S.glsl"
    )),

    /** A 的加强版（多一道 Restore_CNN_M） */
    AA("ANIME4K_AA", listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_VL.glsl",
        "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl"
    )),

    /** B 的加强版（末段改用 M 放大） */
    BB("ANIME4K_BB", listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Restore_CNN_S.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl"
    )),

    /** C 的加强版（Restore_CNN_M 替代 S） */
    CA("ANIME4K_CA", listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_M.glsl"
    ));

    companion object {
        /** prefs 键：色彩面板里选的 Anime4K 档位 */
        const val KEY_MODE = "anime4k_mode"

        /** prefs 键：Anime4K 基础层总开关（与超分模型互相独立） */
        const val KEY_ENABLED = "anime4k_enabled"

        /** 默认档位 = 线条修复（最保守，先给用户看"几乎无损"的效果） */
        val DEFAULT_MODE = CA

        fun fromPrefs(prefs: SharedPreferences): Anime4kMode {
            val raw = prefs.getString(KEY_MODE, null) ?: return DEFAULT_MODE
            val stored = entries.firstOrNull { it.id == raw } ?: return DEFAULT_MODE
            // 旧档位（C/A/BB）折到新的三档，老用户的已存档位不失效
            return canonical(stored)
        }

        fun setMode(prefs: SharedPreferences, mode: Anime4kMode) {
            prefs.edit().putString(KEY_MODE, mode.id).apply()
        }

        /** 面板点击的循环顺序：**关闭 → 线条修复 → 均衡 → 强恢复 → 关闭**（由弱到强，再回关闭）。 */
        private val CYCLE = listOf(CA, B, AA)

        /** 当前档位的下一个；传 null（=已关闭）返回第一档，最后一档返回 null（=关闭） */
        fun nextAfter(current: Anime4kMode?): Anime4kMode? {
            if (current == null) return CYCLE.first()
            val i = CYCLE.indexOf(current)
            return if (i < 0 || i == CYCLE.size - 1) null else CYCLE[i + 1]
        }

        /**
         * 面板/存档解析：把**旧档位**映射到新的三档（老用户的已存档位不能失效）。
         * `C→CA`、`BB→B`、`A→AA`，其余原样。
         */
        fun canonical(mode: Anime4kMode): Anime4kMode = when (mode) {
            C -> CA
            BB -> B
            A -> AA
            else -> mode
        }

        /** 面板显示的档位名（唯一一份映射，别在 UI 里再写一套 when） */
        fun labelResOf(mode: Anime4kMode): Int = when (canonical(mode)) {
            CA -> R.string.reader_anime4k_mode_repair
            B -> R.string.reader_anime4k_mode_balanced
            AA -> R.string.reader_anime4k_mode_strong
            // 下面三档已不再暴露（canonical 会把它们折上去），仅为穷尽 when 保留
            A, C, BB -> R.string.reader_anime4k_mode_strong
        }

        /** 直接开关（面板循环到"关闭"时用） */
        fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        /**
         * Anime4K 基础层是否开启。
         *
         * ⚠️ **默认关**（2026-10 用户口径：「默认用 Anime4K 无」）。
         * 它曾经默认开，作为"没下模型时的零成本兜底"；现在定位改成
         * **超分的替代显示层** —— 两者互斥（见 [SuperResolutionEngines.resolveSteps]），
         * 默认开启会让人以为超分没生效。
         */
        fun isEnabled(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

        /** 默认值：**关** */
        const val DEFAULT_ENABLED = false
    }
}
