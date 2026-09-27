package com.moe.starflow.mangaimport.data

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 真实漫画压缩包的元数据读取（**opt-in**）：设了 `COMIC_METADATA_REAL_FILE=<路径>` 才跑，
 * 否则整类跳过 —— 与 `GgufTypeRetagRealFileTest` 同一套做法（真实文件不入库，
 * 默认 skip 保证 CI/别人机器上不会红）。
 *
 * 用途：确认「真包里的 ComicInfo.xml / meta.json → 简介」这条链路端到端是对的
 * （合成样本再全也代表不了真实工具写出来的文件）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComicMetadataRealFileTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun realFile(): File? =
        System.getenv("COMIC_METADATA_REAL_FILE")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?.takeIf { it.isFile }

    @Test
    fun realArchive_yieldsDescription() {
        val file = realFile()
        assumeTrue("未设 COMIC_METADATA_REAL_FILE，跳过真实文件测试", file != null)
        assumeTrue("真实文件只测 zip（rar/7z 需要 native 解压）", ArchiveTypes.detect(file!!) == ArchiveKind.ZIP)

        val texts = MangaImporter.readZipMetadataTexts(file)
        assertTrue("真实包里应该能读到 ComicInfo.xml / meta.json：${file.name}", texts.isNotEmpty())
        texts.forEach { (n, t) -> println("[ComicMetadataRealFile] entry=$n len=${t.length}") }

        val meta = ComicMetadataParser.parse(texts)
        assertTrue("真实包的元数据应该能解析出字段", meta != null)
        println("[ComicMetadataRealFile] parsed=$meta")

        val desc = ComicMetadataParser.descriptionOf(ctx, texts)
        assertTrue("元数据应该能生成简介：${file.name}", desc.isNotBlank())
        // ⚠️ 逐行带前缀打印：控制台会按 ~120 字符折行，不带前缀的话 grep 会漏看被折走的行
        desc.lines().forEach { println("[ComicMetadataRealFile] desc| $it") }
    }
}
