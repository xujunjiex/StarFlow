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
 * GGUF 量化校验 + 1.25-bit 重打标（42→43）的回归守卫。
 *
 * 这是**二进制格式解析 + 原地改写**，写错就是把用户几百 MB 的模型改坏，所以样本文件必须
 * **布局自洽**（数据区大小 = 按类型尺寸表算出来的字节数），否则校验会判 UNSUPPORTED。
 *
 * 重点盯三件事：
 *  1. 真·腾讯 1.25-bit（42 号但布局是 STQ1_0 = 42 字节/256 值）→ 判定 NEEDS_RETAG 并改写；
 *  2. **上游 Q2_0 文件（同样是 42 号，布局 72 字节/256）→ 判定 SUPPORTED，绝不改写**（防改坏好模型）；
 *  3. 第三种私有量化（如腾讯 2-bit，65 字节/256）→ 判定 UNSUPPORTED，导入/加载时给明确提示。
 */
class GgufTypeRetagTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 类型尺寸表：(每块元素数, 每块字节数) */
    private val traits = mapOf(
        0 to (1 to 4),        // F32
        12 to (256 to 144),   // Q4_K
        14 to (256 to 210),   // Q6_K
        40 to (64 to 36),     // NVFP4（上游）
        42 to (64 to 18),     // Q2_0（上游）
        43 to (256 to 42),    // STQ1_0（重打标后）
    )

    private data class Tensor(val name: String, val type: Int, val rowLen: Long = 256L, val rows: Long = 1L)

    /**
     * 写一个布局自洽的样本 GGUF（张量数据偏移按 32 字节对齐，与真实文件一致）。
     * @param dataTraits 写数据区时按哪套尺寸表算（用来伪造「42 其实是 STQ1_0」这种私有编号）
     */
    private fun writeGguf(
        file: File,
        tensors: List<Tensor>,
        kvCount: Int = 3,
        dataTraits: Map<Int, Pair<Int, Int>> = traits,
    ) {
        val align = 32L
        // 先算每个张量的数据偏移与大小（与 gguf-py 一致：顺序摆放 + 32 字节对齐）
        val offsets = ArrayList<Long>(tensors.size)
        val sizes = ArrayList<Long>(tensors.size)
        var pos = 0L
        tensors.forEach { t ->
            val (blck, tsize) = dataTraits[t.type] ?: (1 to 4)
            val bytes = (t.rowLen / blck) * tsize * t.rows
            offsets += pos
            sizes += bytes
            pos += bytes
            pos = ((pos + align - 1) / align) * align
        }

        RandomAccessFile(file, "rw").use { raf ->
            raf.write("GGUF".toByteArray(Charsets.US_ASCII))
            raf.writeU32Le(3)
            raf.writeU64Le(tensors.size.toLong())
            raf.writeU64Le(kvCount.toLong())

            if (kvCount > 0) { writeString(raf, "general.architecture"); raf.writeU32Le(8); writeString(raf, "hunyuan-dense") }
            if (kvCount > 1) { writeString(raf, "general.file_type"); raf.writeU32Le(4); raf.writeU32Le(41) }
            if (kvCount > 2) {
                writeString(raf, "tokenizer.ggml.tokens"); raf.writeU32Le(9)
                raf.writeU32Le(8); raf.writeU64Le(2)
                writeString(raf, "a"); writeString(raf, "bb")
            }

            tensors.forEachIndexed { i, t ->
                writeString(raf, t.name)
                raf.writeU32Le(1)                 // n_dims = 1
                raf.writeU64Le(t.rowLen)
                raf.writeU32Le(t.type)
                raf.writeU64Le(offsets[i])
            }
            // 数据区：按同样的对齐规则铺满
            val pad = ((align - (raf.filePointer % align)) % align).toInt()
            repeat(pad) { raf.writeByte(0) }
            tensors.forEachIndexed { i, _ ->
                repeat(sizes[i].toInt()) { raf.writeByte(0x11) }
                if (i != tensors.lastIndex) {
                    val p = ((align - (raf.filePointer % align)) % align).toInt()
                    repeat(p) { raf.writeByte(0) }
                }
            }
        }
    }

    private fun RandomAccessFile.writeU32Le(v: Int) = writeInt(Integer.reverseBytes(v))
    private fun RandomAccessFile.writeU64Le(v: Long) = writeLong(java.lang.Long.reverseBytes(v))

    private fun writeString(raf: RandomAccessFile, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        raf.writeU64Le(bytes.size.toLong())
        raf.write(bytes)
    }

    // ───────────────────── 扫描 / 校验 ─────────────────────

    @Test
    fun `扫描能定位每个张量的类型字段与类型值`() {
        val f = tmp.newFile("a.gguf")
        writeGguf(
            f,
            listOf(
                Tensor("blk.0.attn_k.weight", 42),
                Tensor("output_norm.weight", 0, rowLen = 256),
                Tensor("token_embd.weight", 14, rowLen = 512, rows = 2),
            ),
        )
        val offsets = GgufTypeRetag.findTypeFieldOffsets(f)
        assertEquals(3, offsets.size)
        assertEquals(42, offsets[0].second)
        assertEquals(0, offsets[1].second)
        assertEquals(14, offsets[2].second)
    }

    @Test
    fun `真 1_25bit 布局判定为需要重打标并改写为 43`() {
        val f = tmp.newFile("b.gguf")
        // 42 号占 42 字节/256 值 = STQ1_0 的真实布局（1.25-bit 私有编号）
        writeGguf(
            f,
            listOf(
                Tensor("w1", 42), Tensor("w2", 42), Tensor("n", 0),
            ),
            dataTraits = traits + (42 to (256 to 42)),
        )
        assertEquals(GgufQuantCheck.Compat.NEEDS_RETAG, GgufQuantCheck.check(f))

        assertTrue("含 42 号且布局为 STQ1_0 → 应执行重打标", GgufTypeRetag.ensureRetagged(f))
        assertEquals(
            listOf(GgufTypeRetag.TYPE_STQ1_0, GgufTypeRetag.TYPE_STQ1_0, 0),
            GgufTypeRetag.findTypeFieldOffsets(f).map { it.second },
        )
        val marker = LlamaCppPaths.retagMarker(f)
        assertTrue(marker.isFile)
        assertEquals(GgufTypeRetag.md5(f), marker.readText())
        // 改写后布局仍然自洽（STQ1_0 = 42 字节/256 值）
        assertEquals(GgufQuantCheck.Compat.SUPPORTED, GgufQuantCheck.check(f))
    }

    @Test
    fun `上游 Q2_0 文件同样是 42 号但绝不改写`() {
        val f = tmp.newFile("q2_0.gguf")
        // 42 号占 72 字节/256 值 = 上游 Q2_0 的真实布局 → 我们的引擎原生就能读，不能动
        writeGguf(f, listOf(Tensor("w1", 42), Tensor("w2", 42)), dataTraits = traits)
        assertEquals(GgufQuantCheck.Compat.SUPPORTED, GgufQuantCheck.check(f))

        val before = GgufTypeRetag.md5(f)
        assertFalse("上游 Q2_0 不该被重打标", GgufTypeRetag.ensureRetagged(f))
        assertEquals("文件必须一字不改", before, GgufTypeRetag.md5(f))
        assertEquals(listOf(42, 42), GgufTypeRetag.findTypeFieldOffsets(f).map { it.second })
    }

    @Test
    fun `腾讯 2bit 那类私有量化判定为不支持`() {
        val f = tmp.newFile("2bit.gguf")
        // 40 号占 65 字节/256 值（实测的腾讯 2-bit）既不是 NVFP4(144) 也不是别的已知类型
        writeGguf(f, listOf(Tensor("w1", 40), Tensor("w2", 40)), dataTraits = traits + (40 to (256 to 65)))
        assertEquals(GgufQuantCheck.Compat.UNSUPPORTED, GgufQuantCheck.check(f))
        assertFalse("不支持的量化不该被改写", GgufTypeRetag.ensureRetagged(f))
        assertFalse("也不该留下标记", LlamaCppPaths.retagMarker(f).exists())
    }

    @Test
    fun `已重打标过再调用是幂等的`() {
        val f = tmp.newFile("c.gguf")
        writeGguf(f, listOf(Tensor("w", 42)), dataTraits = traits + (42 to (256 to 42)))
        assertTrue(GgufTypeRetag.ensureRetagged(f))
        val md5AfterFirst = GgufTypeRetag.md5(f)
        assertTrue(GgufTypeRetag.ensureRetagged(f))
        assertEquals(md5AfterFirst, GgufTypeRetag.md5(f))
    }

    @Test
    fun `已打标但缺标记文件时会补写标记`() {
        // 模拟「改完瞬间进程被杀」：文件已是 43 号，但没有标记 → 必须补写，否则重新校验会误删
        val f = tmp.newFile("e.gguf")
        writeGguf(f, listOf(Tensor("w", 43), Tensor("n", 0)))
        assertFalse(LlamaCppPaths.retagMarker(f).exists())
        assertTrue(GgufTypeRetag.ensureRetagged(f))
        val marker = LlamaCppPaths.retagMarker(f)
        assertTrue("应补出标记文件", marker.isFile)
        assertEquals(GgufTypeRetag.md5(f), marker.readText())
    }

    @Test
    fun `没有 42 号张量的普通 GGUF 不打标`() {
        val f = tmp.newFile("d.gguf")
        writeGguf(f, listOf(Tensor("w", 12), Tensor("n", 0)))
        val before = GgufTypeRetag.md5(f)
        assertFalse(GgufTypeRetag.ensureRetagged(f))
        assertFalse(LlamaCppPaths.retagMarker(f).exists())
        assertEquals("文件不应被改动", before, GgufTypeRetag.md5(f))
    }

    @Test
    fun `looksLikeGguf 能挡住改名混入的非 GGUF 文件`() {
        val gguf = tmp.newFile("real.gguf")
        writeGguf(gguf, listOf(Tensor("w", 12)))
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
            raf.writeLong(1_000_000L)
            raf.writeLong(0L)
        }
        assertFalse(GgufTypeRetag.ensureRetagged(f))
        // 解析失败一律 UNKNOWN（放行交给引擎），不能因为解析器的问题拦下用户
        assertEquals(GgufQuantCheck.Compat.UNKNOWN, GgufQuantCheck.check(f))
    }
}
