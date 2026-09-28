package com.moe.starflow.sr.anime4k

/**
 * Anime4K 的 mpv 风格 GLSL 着色器解析 / 编译。
 *
 * 移植自 Kototoro 的 `Anime4kCompiler`（Apache-2.0），着色器本身来自
 * [bloc97/Anime4K](https://github.com/bloc97/Anime4K)（MIT，见
 * `assets/anime4k/LICENSE_Anime4K.txt`）。逻辑保持一致，便于与参考实现对拍。
 *
 * ⚠️ **`//!WHEN` 与 `//!COMPONENTS` 被显式忽略** —— 这是**有意保持参考实现的行为**，
 * 不是漏写。后果很重要：`AutoDownscalePre` 那两个 pass 的 `//!WIDTH OUTPUT.w` 会被
 * **无条件执行**，而本实现里 `OUTPUT` 恒等于输入尺寸 → **Anime4K 在本管线里不放大**
 * （输出尺寸 == 输入尺寸），它是"同分辨率线条修复/锐化"而不是超分。
 * 本地实测确认：三种模式输出尺寸都等于输入（见 `tools/sr-research/REPORT.md` §4.6）。
 * 若要让它真的放大，得实现 `//!WHEN` 求值 + 真正的 `OUTPUT`（显示目标尺寸）语义，
 * 这两件事**一起**改才有意义 —— 单改一个只会得到更奇怪的结果。
 *
 * 另一处刻意的缺失：**没有 `NATIVE` 纹理**，绑定时回退到 `MAIN`。
 */
data class Anime4kPass(
    val desc: String,
    val hook: String,
    val binds: List<String>,
    val save: String?,
    val widthExpression: String?,
    val heightExpression: String?,
    val fragmentCode: String
)

object Anime4kCompiler {

    /** 解析一份或多份 shader 源码 → 有序 pass 列表 */
    fun parse(shaderSources: List<String>): List<Anime4kPass> {
        val passes = mutableListOf<Anime4kPass>()

        for (source in shaderSources) {
            var currentDesc = ""
            var currentHook = ""
            val currentBinds = mutableListOf<String>()
            var currentSave: String? = null
            var currentWidth: String? = null
            var currentHeight: String? = null
            val currentCode = StringBuilder()
            var inHook = false

            for (line in source.lines()) {
                val tline = line.trim()
                if (tline.startsWith("//!DESC ")) {
                    currentDesc = tline.removePrefix("//!DESC ").trim()
                } else if (tline.startsWith("//!HOOK ")) {
                    // 遇到新的 HOOK = 上一个 pass 结束
                    if (inHook && currentCode.isNotEmpty()) {
                        passes.add(
                            Anime4kPass(currentDesc, currentHook, currentBinds.toList(),
                                currentSave, currentWidth, currentHeight, currentCode.toString())
                        )
                        currentBinds.clear()
                        currentSave = null
                        currentWidth = null
                        currentHeight = null
                        currentCode.clear()
                    }
                    inHook = true
                    currentHook = tline.removePrefix("//!HOOK ").trim()
                } else if (inHook) {
                    when {
                        tline.startsWith("//!BIND ") ->
                            currentBinds.add(tline.removePrefix("//!BIND ").trim())
                        tline.startsWith("//!SAVE ") ->
                            currentSave = tline.removePrefix("//!SAVE ").trim()
                        tline.startsWith("//!WIDTH ") ->
                            currentWidth = tline.removePrefix("//!WIDTH ").trim()
                        tline.startsWith("//!HEIGHT ") ->
                            currentHeight = tline.removePrefix("//!HEIGHT ").trim()
                        // ⚠️ 刻意忽略（见类注释）：COMPONENTS 不做分量裁剪、WHEN 不做条件求值
                        tline.startsWith("//!COMPONENTS ") || tline.startsWith("//!WHEN ") -> Unit
                        tline.startsWith("//") -> Unit
                        else -> currentCode.appendLine(line)
                    }
                }
            }
            if (inHook && currentCode.isNotEmpty()) {
                passes.add(
                    Anime4kPass(currentDesc, currentHook, currentBinds.toList(),
                        currentSave, currentWidth, currentHeight, currentCode.toString())
                )
            }
        }
        return passes
    }

    /**
     * 把一个 pass 编译成 GLES3 片元着色器。
     *
     * 生成的契约（与参考实现一致）：
     * - 固定声明 `MAIN`（永远可用）+ `MAIN_size` / `MAIN_pt`
     * - 该 pass 的其它 bind 各声明一份 `sampler2D` + `_size` / `_pt`
     * - `HOOKED_*` 宏指向本 pass 的 hook 纹理
     * - 主体末尾固定 `void main() { outColor = hook(); }` —— **着色器里必须定义 `vec4 hook()`**
     */
    fun compileToFragmentShader(pass: Anime4kPass): String {
        val sb = StringBuilder()
        sb.appendLine("#version 300 es")
        sb.appendLine("precision highp float;")
        sb.appendLine("in vec2 vTexCoord;")
        sb.appendLine("out vec4 outColor;")

        sb.appendLine("uniform sampler2D MAIN;")
        sb.appendLine("uniform vec2 MAIN_size;")
        sb.appendLine("uniform vec2 MAIN_pt;")
        sb.appendLine("#define MAIN_pos vTexCoord")
        sb.appendLine("#define MAIN_tex(pos) texture(MAIN, pos)")
        sb.appendLine("#define MAIN_texOff(offset) texture(MAIN, vTexCoord + (offset) * MAIN_pt)")

        val uniqueBinds = pass.binds.distinct()
            .filter { it != "MAIN" && it != "HOOKED" }
            .toMutableList()
        if (pass.hook != "MAIN" && !uniqueBinds.contains(pass.hook)) {
            uniqueBinds.add(pass.hook)
        }
        for (bind in uniqueBinds) {
            sb.appendLine("uniform sampler2D $bind;")
            sb.appendLine("uniform vec2 ${bind}_size;")
            sb.appendLine("uniform vec2 ${bind}_pt;")
            sb.appendLine("#define ${bind}_pos vTexCoord")
            sb.appendLine("#define ${bind}_tex(pos) texture($bind, pos)")
            sb.appendLine("#define ${bind}_texOff(offset) texture($bind, vTexCoord + (offset) * ${bind}_pt)")
        }

        val hookedTarget = pass.hook
        sb.appendLine("#define HOOKED_pos vTexCoord")
        sb.appendLine("#define HOOKED_tex(pos) texture($hookedTarget, pos)")
        sb.appendLine("#define HOOKED_texOff(offset) texture($hookedTarget, vTexCoord + (offset) * ${hookedTarget}_pt)")
        sb.appendLine("#define HOOKED_size ${hookedTarget}_size")
        sb.appendLine("#define HOOKED_pt ${hookedTarget}_pt")

        sb.appendLine(pass.fragmentCode)
        sb.appendLine("void main() {")
        sb.appendLine("    outColor = hook();")
        sb.appendLine("}")
        return sb.toString()
    }
}
