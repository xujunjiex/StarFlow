package com.moe.starflow.sr.anime4k

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import com.moe.starflow.sr.SuperResolutionEngine
import com.moe.starflow.sr.anime4k.gl.EglCore
import com.moe.starflow.sr.anime4k.gl.GlUtil
import com.moe.starflow.sr.anime4k.gl.OffscreenSurface
import com.moe.starflow.utils.LogCollector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Anime4K 基础层引擎：逐 pass 跑 GLES3 离屏管线。
 *
 * **这不是超分**：`scale = 1`，输出尺寸 == 输入尺寸，只做同分辨率的线条修复/锐化。
 * 之所以实现 [SuperResolutionEngine]，是为了让它和超分模型共用同一条调用链
 * （开关判断、失败静默降级、阅读器/截图链路都只有一份代码）—— 调用方拿到的是
 * "处理过的图"，尺寸一样而已。原因见 [Anime4kCompiler] 类注释。
 *
 * 管线与 Kototoro 的 `Anime4kImageEngine` 等价（Apache-2.0），含两处刻意的取舍：
 * 1. **不管线程序号**：每个 pass 都是全屏 pass，不保留 `MAIN` 原纹理，
 *    所以同一纹理可以既当输入又当输出目标吗？——**不行**，本实现每个 pass 都新建目标纹理，
 *    避免 GL 的 feedback loop（读写同一纹理是未定义行为）。
 * 2. **纹理及时释放**：预计算「每个 pass 之后还会被谁 bind」，一旦某个名字不再被后续 pass
 *    使用且被覆盖，立即 `glDeleteTextures`。不做这一步，mode A 的 49 个 pass 会把
 *    每趟的中间纹理全留在显存里（一张 1280×1854 的 RGBA16F 就是 19MB，2x 时 76MB）→ 必 OOM。
 *
 * ⚠️ EGL 上下文**绑定线程**，所有 GL 调用必须在同一线程完成 —— 这里用一个单线程
 * executor 串行化 initialize/process/release，并保证 `makeCurrent`/`makeNothingCurrent` 成对。
 */
class Anime4kEngine(
    private val context: Context,
    private val mode: Anime4kMode
) : SuperResolutionEngine {

    /** Anime4K 不放大（见类注释） */
    override val scale: Int = 1

    private val glThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Anime4kGL").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private var eglCore: EglCore? = null
    private var surface: OffscreenSurface? = null
    private var isInitialized = false

    private val passes = mutableListOf<Anime4kPass>()
    private val programs = mutableListOf<Int>()
    private var vbo = 0
    private val vboData = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    /** 每个 pass 之后还会被用到的纹理名（用于及早删除不再需要的纹理） */
    private lateinit var laterUses: Array<Set<String>>

    /** 输入像素上限（见 companion 里的说明）。暴露给上层，好给出"带数字"的提示。 */
    override val maxInputPixels: Long get() = MAX_INPUT_PIXELS

    /**
     * 上一次 [upscale] 失败的技术细节（短 ASCII 句，见接口注释）。
     * ⚠️ 所有 GL 调用都在同一个单线程 executor 上（见类注释），这里只是普通字段。
     */
    private var failDetail: String? = null

    override fun lastFailDetail(): String? = failDetail

    companion object {
        private const val TAG = "Anime4kEngine"

        /** 输入像素上限：Anime4K 中间纹理是 RGBA16F，链最长 49 趟，必须限幅 */
        private const val MAX_INPUT_PIXELS = 1_500_000L

        /** shader 资产目录（随 APK，MIT，见 assets/anime4k/LICENSE_Anime4K.txt） */
        private const val SHADER_DIR = "anime4k"

        private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
"""
    }

    /** 在 GL 线程上初始化（读资产 + 建上下文 + 编译所有 pass）。失败返回 false。 */
    fun initialize(): Boolean = (runOnGl {
        if (isInitialized) return@runOnGl true
        try {
            val sources = mode.shaders.map { name ->
                context.assets.open("$SHADER_DIR/$name").bufferedReader().use { it.readText() }
            }
            passes.clear()
            passes.addAll(Anime4kCompiler.parse(sources))
            laterUses = computeLaterUses(passes)

            eglCore = EglCore()
            surface = OffscreenSurface(eglCore!!, 1, 1).apply { makeCurrent() }

            for (pass in passes) {
                val program = GlUtil.createProgram(VERTEX_SHADER, Anime4kCompiler.compileToFragmentShader(pass))
                if (program == 0) {
                    LogCollector.e(TAG, "pass 编译失败: ${pass.desc} (${mode.id})")
                    release()
                    return@runOnGl false
                }
                programs.add(program)
            }

            val buf = GlUtil.createFloatBuffer(vboData)
            val bufs = IntArray(1)
            GLES30.glGenBuffers(1, bufs, 0)
            vbo = bufs[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vboData.size * 4, buf, GLES30.GL_STATIC_DRAW)

            isInitialized = true
            LogCollector.d(TAG, "初始化完成: ${mode.id}, ${passes.size} 个 pass")
            true
        } catch (e: Throwable) {
            LogCollector.e(TAG, "初始化失败: ${mode.id}", e)
            // ⚠️ 必须调 releaseOnGl()（直接清理）而不是 release()：后者会再 submit 回
            // 同一个单线程 executor 并 .get() → **自己等自己，死锁**
            releaseOnGl()
            false
        } finally {
            eglCore?.makeNothingCurrent()
        }
    }) ?: false

    override fun upscale(src: Bitmap): Bitmap? = runOnGl {
        failDetail = null
        if (!isInitialized) {
            failDetail = "engine not initialized"
            return@runOnGl null
        }
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) {
            failDetail = "empty source ${w}x$h"
            return@runOnGl null
        }
        if (w.toLong() * h.toLong() > MAX_INPUT_PIXELS) {
            LogCollector.d(TAG, "跳过 Anime4K：输入 ${w}x$h 超过 ${MAX_INPUT_PIXELS / 1000}K 像素上限")
            failDetail = "source ${w}x$h > $MAX_INPUT_PIXELS px"
            return@runOnGl null
        }

        surface?.makeCurrent()
        val textures = HashMap<String, Int>()
        val sizes = HashMap<String, Pair<Int, Int>>()
        val owned = HashSet<Int>()          // 本趟创建的纹理，结束时兜底清理
        var fbo = 0
        try {
            val inputTex = GlUtil.createTexture(src)
            owned.add(inputTex)
            textures["MAIN"] = inputTex
            sizes["MAIN"] = w to h
            // ⚠️ OUTPUT 恒等于输入尺寸：这正是"Anime4K 不放大"的直接原因（见 Anime4kCompiler 注释）
            sizes["OUTPUT"] = w to h

            val fbos = IntArray(1)
            GLES30.glGenFramebuffers(1, fbos, 0)
            fbo = fbos[0]

            for (i in passes.indices) {
                val pass = passes[i]
                // 与参考实现同一条跳过规则：hook 不是 MAIN 且该纹理不存在 → 跳过
                if (pass.hook != "MAIN" && !textures.containsKey(pass.hook)) continue

                val passW = pass.widthExpression
                    ?.let { Anime4kSizeEvaluator.evaluate(it, sizes) }
                    ?: sizes[pass.hook]?.first ?: w
                val passH = pass.heightExpression
                    ?.let { Anime4kSizeEvaluator.evaluate(it, sizes) }
                    ?: sizes[pass.hook]?.second ?: h
                val pw = passW.coerceAtLeast(1)
                val ph = passH.coerceAtLeast(1)

                val isLast = i == lastExecutablePass(passes)
                val outTex = if (isLast) GlUtil.createEmpty8BitTexture(pw, ph)
                else GlUtil.createEmptyTexture(pw, ph)
                owned.add(outTex)

                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, outTex, 0
                )
                GLES30.glViewport(0, 0, pw, ph)

                val program = programs[i]
                GLES30.glUseProgram(program)

                // 绑定 MAIN + 该 pass 声明的其它 bind + hook（去重）
                var unit = 0
                val names = LinkedHashSet<String>()
                names.add("MAIN")
                names.addAll(pass.binds)
                if (pass.hook != "MAIN") names.add(pass.hook)
                for (name in names) {
                    val loc = GLES30.glGetUniformLocation(program, name)
                    if (loc < 0) continue          // 该 pass 没声明这个 sampler（被优化掉了）
                    val tex = textures[name] ?: textures["MAIN"] ?: continue
                    val size = sizes[name] ?: sizes["MAIN"] ?: (w to h)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                    GLES30.glUniform1i(loc, unit)
                    val sizeLoc = GLES30.glGetUniformLocation(program, "${name}_size")
                    if (sizeLoc >= 0) GLES30.glUniform2f(sizeLoc, size.first.toFloat(), size.second.toFloat())
                    val ptLoc = GLES30.glGetUniformLocation(program, "${name}_pt")
                    if (ptLoc >= 0) GLES30.glUniform2f(ptLoc, 1f / size.first, 1f / size.second)
                    unit++
                }

                // 顶点属性指向本引擎的共享 VBO
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                GLES30.glDisableVertexAttribArray(0)
                GLES30.glDisableVertexAttribArray(1)

                // 纹理名易主：旧纹理若后续不再被 bind，立即释放
                val key = pass.save ?: "MAIN"
                if (!laterUses[i].contains(key)) {
                    textures[key]?.let { old ->
                        GLES30.glDeleteTextures(1, intArrayOf(old), 0)
                        owned.remove(old)
                    }
                }
                textures[key] = outTex
                sizes[key] = pw to ph
            }

            // 回读最终 MAIN（RGBA8）
            val outW = sizes["MAIN"]?.first ?: w
            val outH = sizes["MAIN"]?.second ?: h
            val finalTex = textures["MAIN"] ?: return@runOnGl null
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, finalTex, 0
            )
            val byteCount = outW * outH * 4
            val bb = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, outW, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
            bb.position(0)
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(bb)
            return@runOnGl bitmap
        } catch (e: Throwable) {
            // 与超分引擎同一约定：失败一律返回 null，调用方用原图
            LogCollector.e(TAG, "Anime4K 处理失败 (${mode.id})", e)
            failDetail = "gl pipeline threw ${e.javaClass.simpleName}: ${e.message}"
            null
        } finally {
            runCatching {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                if (owned.isNotEmpty()) {
                    GLES30.glDeleteTextures(owned.size, owned.toIntArray(), 0)
                }
            }
            eglCore?.makeNothingCurrent()
        }
    }

    override fun release() {
        runOnGl { releaseOnGl() }
    }

    /**
     * 真正的 GL 资源清理（**必须在 GL 线程上、且不能再 submit**）。
     * [release] 从外部调它；[initialize] 的失败分支直接调它（那里已经在 GL 线程上）。
     */
    private fun releaseOnGl() {
        runCatching {
            surface?.makeCurrent()
            programs.forEach { GLES30.glDeleteProgram(it) }
            programs.clear()
            if (vbo != 0) {
                GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
                vbo = 0
            }
            surface?.release()
            surface = null
            eglCore?.release()
            eglCore = null
        }
        isInitialized = false
    }

    // ---------- helpers ----------

    /**
     * 把 block 丢到 GL 线程执行并等结果。
     *
     * ⚠️ 返回 `T?`：GL 线程里抛异常时**返回 null 而不是把异常吞掉后返回一个假的 T**
     * —— 对非空返回类型（如 initialize 的 Boolean）返回 null 会在调用点拆箱 NPE。
     */
    private fun <T> runOnGl(block: () -> T): T? {
        return try {
            glThread.submit(block).get()
        } catch (e: Throwable) {
            LogCollector.e(TAG, "GL 线程执行失败: ${e.message}", e)
            null
        }
    }

    /** 最后一个**会真的执行**的 pass（同参考实现的判据），只有它写 RGBA8，其余写 RGBA16F */
    private fun lastExecutablePass(passes: List<Anime4kPass>): Int =
        passes.indices.lastOrNull { i ->
            val p = passes[i]
            p.hook == "MAIN" || p.save != null
        } ?: (passes.size - 1)

    /**
     * 预计算「第 i 个 pass 执行完之后，还有哪些纹理名会被用到」。
     * 用它在 pass 覆盖某个名字时判断旧纹理能否立即删除。
     */
    private fun computeLaterUses(passes: List<Anime4kPass>): Array<Set<String>> {
        val result = arrayOfNulls<Set<String>>(passes.size)
        var acc = mutableSetOf<String>()
        for (i in passes.indices.reversed()) {
            result[i] = acc.toSet()
            acc = acc.toMutableSet()
            acc.add("MAIN")
            acc.addAll(passes[i].binds)
            if (passes[i].hook != "MAIN") acc.add(passes[i].hook)
            passes[i].save?.let { acc.add(it) }
        }
        @Suppress("UNCHECKED_CAST")
        return result as Array<Set<String>>
    }
}
