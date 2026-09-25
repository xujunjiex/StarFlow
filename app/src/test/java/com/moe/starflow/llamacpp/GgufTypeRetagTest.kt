package com.moe.starflow.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * GGUF 量化类型重打标（42 → 43）的回归守卫。
 *
 * 这是**二进制格式解析 + 原地改写**，一旦写错就是把用户的 461MB 模型改坏，
 * 所以用真实布局的小样本文件盯住：偏移计算、readString/skipValue、幂等、标记文件、MD5 变化。
 */
class GgufTypeRetagTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ───────────────────── 构造样本 GGUF ─────────────────────

    private fun writeGguf(
        file: File,
        kvCount: Int,
        tensors: List<Triple<String, Int, Int>>, // name, nDims, typeId
        dims: List<Long> = listOf(4L),
    ) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("GGUF".toByteArray(Charsets.US_ASCII))
            raf.writeU32Le(3)                              // version
            raf.writeU64Le(tensors.size.toLong())          // tensor_count
            raf.writeU64Le(kvCount.toLong())               // metadata_kv_count

            // 元数据：用几种典型类型覆盖 skipValue 的分支（string / u32 / array<string>）
            if (kvCount > 0) { writeString(raf, "general.architecture"); raf.writeU32Le(8); writeString(raf, "llama") }
            if (kvCount > 1) { writeString(raf, "general.file_type"); raf.writeU32Le(4); raf.writeU32Le(15) }
            if (kvCount > 2) {
                writeString(raf, "tokenizer.ggml.tokens"); raf.writeU32Le(9)
                raf.writeU32Le(8)                          // 数组元素类型 string
                raf.writeU64Le(2)                          // 2 个
                writeString(raf, "a"); writeString(raf, "bb")
            }

            // 张量信息
            tensors.forEach { (name, nDims, typeId) ->
                writeString(raf, name)
                raf.writeU32Le(nDims)
                repeat(nDims) { raf.writeU64Le(dims.getOrElse(it) { 1L }) }
                raf.writeU32Le(typeId)
                raf.writeU64Le(0L)                         // offset
            }
            // 一点张量数据（内容不重要）
            repeat(64) { raf.writeByte(0x11) }
        }
    }

    private fun RandomAccessFile.writeU32Le(v: Int) = writeInt(Integer.reverseBytes(v))
    private fun RandomAccessFile.writeU64Le(v: Long) = writeLong(java.lang.Long.reverseBytes(v))

    private fun writeString(raf: RandomAccessFile, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        raf.writeU64Le(bytes.size.toLong())
        raf.write(bytes)
    }

    // ───────────────────── 测试 ─────────────────────

    @Test
    fun `扫描能定位每个张量的类型字段与类型值`() {
        val f = tmp.newFile("a.gguf")
        writeGguf(
            f, kvCount = 3,
            tensors = listOf(
                Triple("blk.0.attn_k.weight", 2, GgufTypeRetag.TYPE_LEGACY_1_25BIT),
                Triple("output_norm.weight", 1, 0),
                Triple("token_embd.weight", 2, 14),
            ),
            dims = listOf(8L, 4L),
        )
        val offsets = GgufTypeRetag.findTypeFieldOffsets(f)
        assertEquals(3, offsets.size)
        assertEquals(GgufTypeRetag.TYPE_LEGACY_1_25BIT, offsets[0].second)
        assertEquals(0, offsets[1].second)
        assertEquals(14, offsets[2].second)
    }

    @Test
    fun `重打标把 42 改写成 43 并留标记文件`() {
        val f = tmp.newFile("b.gguf")
        writeGguf(
            f, kvCount = 3,
            tensors = listOf(
                Triple("w1", 1, GgufTypeRetag.TYPE_LEGACY_1_25BIT),
                Triple("w2", 1, GgufTypeRetag.TYPE_LEGACY_1_25BIT),
                Triple("n", 1, 0),
            ),
        )
        val before = GgufTypeRetag.md5(f)

        assertTrue("含 42 号张量 → 应执行重打标", GgufTypeRetag.ensureRetagged(f))

        val after = GgufTypeRetag.findTypeFieldOffsets(f).map { it.second }
        assertEquals(listOf(GgufTypeRetag.TYPE_STQ1_0, GgufTypeRetag.TYPE_STQ1_0, 0), after)
        val marker = LlamaCppPaths.retagMarker(f)
        assertTrue("应写出 .retagged 标记", marker.isFile)
        assertEquals(GgufTypeRetag.md5(f), marker.readText())
        assertFalse("文件内容确实变了（MD5 应不同）", before == marker.readText())
    }

    @Test
    fun `已重打标过再调用是幂等的`() {
        val f = tmp.newFile("c.gguf")
        writeGguf(f, kvCount = 1, tensors = listOf(Triple("w", 1, GgufTypeRetag.TYPE_LEGACY_1_25BIT)))
        assertTrue(GgufTypeRetag.ensureRetagged(f))
        val md5AfterFirst = GgufTypeRetag.md5(f)
        // 第二次：有标记 → 直接返回 true，不再改写
        assertTrue(GgufTypeRetag.ensureRetagged(f))
        assertEquals(md5AfterFirst, GgufTypeRetag.md5(f))
    }

    @Test
    fun `没有 42 号张量的普通 GGUF 不打标`() {
        val f = tmp.newFile("d.gguf")
        writeGguf(
            f, kvCount = 1,
            tensors = listOf(Triple("w", 1, 12), Triple("n", 1, 0)),  // Q4_K + F32
        )
        val before = GgufTypeRetag.md5(f)
        assertFalse(GgufTypeRetag.ensureRetagged(f))
        assertFalse("不应产生标记文件", LlamaCppPaths.retagMarker(f).exists())
        assertEquals("文件不应被改动", before, GgufTypeRetag.md5(f))
    }

    @Test
    fun `looksLikeGguf 能挡住改名混入的非 GGUF 文件`() {
        val gguf = tmp.newFile("real.gguf")
        writeGguf(gguf, kvCount = 1, tensors = listOf(Triple("w", 1, 12)))
        assertTrue(GgufTypeRetag.looksLikeGguf(gguf))

        val fake = tmp.newFile("fake.gguf")
        fake.writeText("this is definitely not a gguf file, just text")
        assertFalse(GgufTypeRetag.looksLikeGguf(fake))
    }

    @Test
    fun `损坏的头部不会抛异常到调用方`() {
        val f = tmp.newFile("broken.gguf")
        RandomAccessFile(f, "rw").use { raf ->
            raf.write("GGUF".toByteArray(Charsets.US_ASCII))
            raf.writeInt(3)
            raf.writeLong(1_000_000L)  // tensor_count 巨大但文件很短
            raf.writeLong(0L)
        }
        // ensureRetagged 内部 runCatching → 返回 false，不抛出
        assertFalse(GgufTypeRetag.ensureRetagged(f))
    }
}
