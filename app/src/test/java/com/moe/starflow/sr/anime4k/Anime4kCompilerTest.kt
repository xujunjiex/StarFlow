package com.moe.starflow.sr.anime4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anime4K 解析 / 编译 / 尺寸求值的回归守卫（纯 JVM，不需要 GL）。
 *
 * 这里锁死的是**与参考实现（Kototoro / mpv 语义）一致的三个契约**，改动前先读
 * [Anime4kCompiler] 的类注释：
 * 1. `//!WHEN` / `//!COMPONENTS` **被忽略** —— 这是"Anime4K 在本管线里不放大"的根因，
 *    谁要是"顺手把 WHEN 实现了"，输出尺寸/画面会变，这个测试会立刻红。
 * 2. 生成的片元着色器必须声明 `MAIN` + 该 pass 的其它 bind + `HOOKED_*` 宏，并以
 *    `outColor = hook();` 结尾（着色器约定定义 `vec4 hook()`）。
 * 3. 尺寸表达式是 RPN；引用**不存在的纹理**按 0 计（调用方要自己把 OUTPUT 塞进去）。
 */
class Anime4kCompilerTest {

    private val sample = """
        //!DESC test-pass-1
        //!HOOK MAIN
        //!BIND MAIN
        //!SAVE conv2d_tf
        //!WIDTH MAIN.w
        //!HEIGHT MAIN.h
        //!COMPONENTS 4
        //!WHEN OUTPUT.w MAIN.w / 1.200 >
        vec4 hook() { return MAIN_tex(MAIN_pos); }
    """.trimIndent()

    private val secondPass = """
        //!DESC test-pass-2
        //!HOOK MAIN
        //!BIND MAIN
        //!BIND conv2d_tf
        //!SAVE MAIN
        //!WIDTH conv2d_tf.w 2 *
        //!HEIGHT conv2d_tf.h 2 *
        vec4 hook() { return HOOKED_tex(HOOKED_pos); }
    """.trimIndent()

    @Test
    fun parse_splitsPassesAndCapturesDirectives() {
        val passes = Anime4kCompiler.parse(listOf(sample, secondPass))
        assertEquals("两份源码各一个 pass", 2, passes.size)

        val p0 = passes[0]
        assertEquals("test-pass-1", p0.desc)
        assertEquals("MAIN", p0.hook)
        assertEquals(listOf("MAIN"), p0.binds)
        assertEquals("conv2d_tf", p0.save)
        assertEquals("MAIN.w", p0.widthExpression)
        assertEquals("MAIN.h", p0.heightExpression)

        val p1 = passes[1]
        assertEquals("conv2d_tf.w 2 *", p1.widthExpression)
        assertEquals("conv2d_tf.h 2 *", p1.heightExpression)
        assertEquals("MAIN", p1.save)
        assertEquals("BIND 要按出现顺序收全", listOf("MAIN", "conv2d_tf"), p1.binds)
    }

    /** `//!WHEN` 必须被忽略：实现它会让本管线真的放大，是行为变更而不是补全 */
    @Test
    fun parse_ignoresWhenAndComponents() {
        val passes = Anime4kCompiler.parse(listOf(sample))
        assertEquals(1, passes.size)
        assertTrue(
            "WHEN 不能出现在 pass 元数据里（它是被刻意忽略的）",
            passes.none { it.desc.contains("WHEN") || it.widthExpression?.contains("OUTPUT") == true }
        )
    }

    @Test
    fun compile_declaresMainBindsAndHookedMacros() {
        val pass = Anime4kCompiler.parse(listOf(secondPass)).first()
        val src = Anime4kCompiler.compileToFragmentShader(pass)

        assertTrue("必须有 GLES3 版本行", src.contains("#version 300 es"))
        assertTrue("必须声明 MAIN", src.contains("uniform sampler2D MAIN;"))
        assertTrue("必须声明 bind 纹理", src.contains("uniform sampler2D conv2d_tf;"))
        assertTrue("bind 要带 _size", src.contains("uniform vec2 conv2d_tf_size;"))
        assertTrue("bind 要带 _pt", src.contains("uniform vec2 conv2d_tf_pt;"))
        assertTrue("HOOKED 要指向本 pass 的 hook", src.contains("#define HOOKED_pos vTexCoord"))
        assertTrue("MAIN_texOff 宏要在", src.contains("#define MAIN_texOff(offset)"))
        assertTrue("必须以 hook() 收尾", src.trimEnd().endsWith("outColor = hook();\n}"))
    }

    /** pass 的 hook 不是 MAIN 时，hook 纹理也要被声明成 uniform（否则 shader 编译不过） */
    @Test
    fun compile_declaresHookTextureWhenHookIsNotMain() {
        val prekernel = """
            //!DESC clamp
            //!HOOK PREKERNEL
            //!BIND HOOKED
            //!BIND STATSMAX
            vec4 hook() { return HOOKED_tex(HOOKED_pos); }
        """.trimIndent()
        val src = Anime4kCompiler.compileToFragmentShader(Anime4kCompiler.parse(listOf(prekernel)).first())
        assertTrue("hook=PREKERNEL 也要声明 uniform", src.contains("uniform sampler2D PREKERNEL;"))
        assertTrue("HOOKED 指向 PREKERNEL", src.contains("#define HOOKED_size PREKERNEL_size"))
    }

    // ---------- 尺寸求值 ----------

    @Test
    fun evaluate_rpnBasics() {
        val sizes = mapOf("MAIN" to (1280 to 1854), "OUTPUT" to (1280 to 1854))
        assertEquals(1280, Anime4kSizeEvaluator.evaluate("MAIN.w", sizes))
        assertEquals(1854, Anime4kSizeEvaluator.evaluate("MAIN.h", sizes))
        // OUTPUT.h 2 / → 927（1854/2）
        assertEquals(927, Anime4kSizeEvaluator.evaluate("OUTPUT.h 2 /", sizes))
        // conv2d_last_tf.w 2 * → 未知纹理按 0 → 0
        assertEquals(0, Anime4kSizeEvaluator.evaluate("conv2d_last_tf.w 2 *", sizes))
    }

    @Test
    fun evaluate_unknownTextureCountsAsZero() {
        val sizes = mapOf("MAIN" to (100 to 200))
        assertEquals("引用不存在的纹理按 0（调用方要自己塞 OUTPUT/NATIVE）",
            0, Anime4kSizeEvaluator.evaluate("NATIVE.w", sizes))
        assertEquals(100, Anime4kSizeEvaluator.evaluate("MAIN.width", sizes))
        assertEquals(200, Anime4kSizeEvaluator.evaluate("MAIN.height", sizes))
    }

    // ---------- 预设 ----------

    @Test
    fun modes_haveStableIdsAndNonEmptyShaders() {
        for (mode in Anime4kMode.entries) {
            assertTrue("${mode.name} 的 id 不能为空", mode.id.isNotBlank())
            assertTrue("${mode.name} 至少要有一个 shader", mode.shaders.isNotEmpty())
        }
        // id 是持久化语义（prefs 存它），重复或改名都会让用户已选档失配
        assertEquals("id 必须唯一", Anime4kMode.entries.size, Anime4kMode.entries.map { it.id }.toSet().size)
        assertEquals("ANIME4K_A", Anime4kMode.A.id)
        assertEquals("ANIME4K_C", Anime4kMode.C.id)
    }

    /** 预设引用的 shader 必须是真实存在于 assets/anime4k 的文件（拼错会在运行时才炸） */
    @Test
    fun modes_referenceExistingShaderAssets() {
        val dir = java.io.File("src/main/assets/anime4k")
        if (!dir.isDirectory) return   // 非 Gradle 工作目录下跳过（例如从别的 cwd 跑）
        val available = dir.listFiles { f -> f.name.endsWith(".glsl") }?.map { it.name }?.toSet() ?: emptySet()
        assertTrue("assets/anime4k 里一个 shader 都没有", available.isNotEmpty())
        for (mode in Anime4kMode.entries) {
            for (shader in mode.shaders) {
                assertTrue("${mode.name} 引用了不存在的资产: $shader", shader in available)
            }
        }
    }

    // ---------- 面板循环顺序（UX 契约） ----------

    /**
     * 从「关闭」出发一路 [Anime4kMode.nextAfter]：必须**每个档恰好经过一次**，最后回到 null（关闭）。
     *
     * 这条不变式同时锁两件事：
     * ① 面板点一遍不会漏档／不会卡在某一档反复打转；
     * ② 用户一旦打开，**一定能在面板里关掉**（早期设计里"关闭"不在循环内 → 只能去个性化页关，用户找不到）。
     */
    @Test
    fun anime4kCycle_visitsEveryModeOnceThenCloses() {
        val seen = mutableListOf<Anime4kMode>()
        var cur = Anime4kMode.nextAfter(null)
        var guard = 0
        while (cur != null && guard++ < 20) {
            assertTrue("循环里出现了重复档位: $cur", cur !in seen)
            seen.add(cur)
            cur = Anime4kMode.nextAfter(cur)
        }
        assertEquals("循环必须覆盖全部档位", Anime4kMode.entries.size, seen.size)
        assertNull("循环必须能回到「关闭」", cur)
    }

    /** 第一次点开给最保守的一档（实测最忠实原画），不是改动最大的 A */
    @Test
    fun anime4kCycle_startsFromMostConservative() {
        assertEquals(Anime4kMode.C, Anime4kMode.nextAfter(null))
    }

    /** 每个档位都要有自己的显示名，且互不相同（否则面板切了看不出变化） */
    @Test
    fun anime4kLabels_areDistinctForEveryMode() {
        val res = Anime4kMode.entries.map { Anime4kMode.labelResOf(it) }
        assertEquals("档位数与标签数不符", Anime4kMode.entries.size, res.size)
        assertEquals("档位标签有重复", res.size, res.toSet().size)
        assertTrue("标签资源不能为 0", res.all { it != 0 })
    }
}
