package com.moe.starflow.sr.anime4k.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import com.moe.starflow.utils.LogCollector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Anime4K 用到的 GLES3 小工具。移植自 Kototoro 的 `GlUtil`（Apache-2.0）。 */
object GlUtil {

    private const val TAG = "Anime4kGl"

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fs = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource) ?: return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            LogCollector.e(TAG, "链接失败: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        // shader 已链接，标记删除（引用计数归零后自动回收）
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    private fun loadShader(type: Int, source: String): Int? {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            LogCollector.e(TAG, "编译失败(type=$type): ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return null
        }
        return shader
    }

    fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(coords.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(coords)
        fb.position(0)
        return fb
    }

    /**
     * 把 Bitmap 上传成 RGBA8 纹理（LINEAR / CLAMP_TO_EDGE）。
     *
     * ⚠️ **手工按 RGBA 逐字节上传**，不走 `GLUtils.texImage2D`：后者在部分设备上是 BGRA 顺序，
     * 会导致 **R↔B 通道互换**（红蓝颠倒，且只在部分机型上出现）。
     */
    fun createTexture(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR.toFloat())
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR.toFloat())
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            buf.put(((pixel shr 16) and 0xFF).toByte()) // R
            buf.put(((pixel shr 8) and 0xFF).toByte())  // G
            buf.put((pixel and 0xFF).toByte())          // B
            buf.put(((pixel shr 24) and 0xFF).toByte()) // A
        }
        buf.position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
        )
        return textureId
    }

    /**
     * 中间结果的空纹理：**RGBA16F**。
     *
     * ⚠️ 必须用浮点纹理：Anime4K 是多趟卷积，中途会出现 <0 / >1 的值（`Restore_CNN_*`
     * 的中间层实测均值是负的）。用 RGBA8 存中间结果会被截断到 [0,1]，
     * 后续趟数拿到的就是被夹过的数据 → 画面出现色偏与断层。
     */
    fun createEmptyTexture(width: Int, height: Int): Int = createEmpty(width, height, GLES30.GL_RGBA16F)

    /** 最终输出用的空纹理：RGBA8（回读给 Bitmap） */
    fun createEmpty8BitTexture(width: Int, height: Int): Int = createEmpty(width, height, GLES30.GL_RGBA8)

    private fun createEmpty(width: Int, height: Int, internalFormat: Int): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR.toFloat())
        GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR.toFloat())
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val type = if (internalFormat == GLES30.GL_RGBA16F) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
            GLES30.GL_RGBA, type, null
        )
        return textureId
    }
}
