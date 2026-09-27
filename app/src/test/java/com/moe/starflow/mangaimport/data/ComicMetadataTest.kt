package com.moe.starflow.mangaimport.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 压缩包元数据 → 简介的守卫。
 *
 * 样本取自真实文件 `nhentai-684364 - [Chikyuujin (Tamura-chan)] Ganbare!! … .cbz`
 * （里面同时有 `ComicInfo.xml` 与 `meta.json`）。两条要点：
 * - 两种格式**都要认**，都在时 ComicInfo 优先、json 补空
 * - 坏数据**绝不能让导入失败**：解析失败返回 null / 空简介
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComicMetadataTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** 临时 zip / 目录（导入链路的真实读取测试用）。 */
    @get:Rule
    val tmp = TemporaryFolder()

    private val comicInfo = """
        <?xml version="1.0" encoding="utf-8"?>
        <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <Title>[Chikyuujin (Tamura-chan)] Ganbare!! Isekai Tensei Izumi-chan [Digital]</Title>
          <Series>original</Series>
          <AlternateSeries>[ちきゅうじん (田村ちゃん)] がんばれ！！異世界転生いずみちゃん [DL版]</AlternateSeries>
          <Writer>tamura-chan</Writer>
          <Translator>chikyuujin</Translator>
          <Format>doujinshi</Format>
          <Tags>sole female, lolicon, schoolgirl uniform, multi-work series, twintails, urination, slime</Tags>
          <LanguageISO>ja</LanguageISO>
          <Web>https://nhentai.net/g/684364/</Web>
          <PageCount>22</PageCount>
          <Year>2026</Year>
          <Month>9</Month>
          <Day>27</Day>
          <Manga>YesAndRightToLeft</Manga>
          <AgeRating>Adults Only 18+</AgeRating>
        </ComicInfo>
    """.trimIndent()

    private val metaJson = """
        {"id":684364,"title":{"english":"[Chikyuujin (Tamura-chan)] Ganbare!! Isekai Tensei Izumi-chan [Digital]","japanese":"[ちきゅうじん (田村ちゃん)] がんばれ！！異世界転生いずみちゃん [DL版]"},"upload_date":1790499147,"num_pages":22,"num_favorites":28,"scanlator":"","tags":[{"id":33172,"type":"category","name":"doujinshi"},{"id":6346,"type":"language","name":"japanese"},{"id":35762,"type":"tag","name":"sole female"},{"id":90671,"type":"parody","name":"original"},{"id":19440,"type":"tag","name":"lolicon"},{"id":1352,"type":"tag","name":"slime"},{"id":144881,"type":"group","name":"chikyuujin"},{"id":144880,"type":"artist","name":"tamura-chan"}]}
    """.trimIndent()

    // ===== 条目识别 =====

    @Test
    fun isMetadataEntry_ignoresCaseAndFolder() {
        assertTrue(ComicMetadataParser.isMetadataEntry("ComicInfo.xml"))
        assertTrue(ComicMetadataParser.isMetadataEntry("comicinfo.xml"))
        assertTrue(ComicMetadataParser.isMetadataEntry("Book/ch1/COMICINFO.XML"))
        assertTrue(ComicMetadataParser.isMetadataEntry("meta.json"))
        assertTrue(ComicMetadataParser.isMetadataEntry("Book\\meta.JSON"))
        assertFalse(ComicMetadataParser.isMetadataEntry("001.jpg"))
        assertFalse(ComicMetadataParser.isMetadataEntry("metadata.json"))
        assertFalse(ComicMetadataParser.isMetadataEntry("info.txt"))
    }

    // ===== ComicInfo.xml =====

    @Test
    fun comicInfo_parsesAllFields() {
        val m = ComicMetadataParser.parseComicInfo(comicInfo)!!
        assertEquals("tamura-chan", m.writer)
        assertEquals("chikyuujin", m.translator)
        assertEquals("original", m.series)
        assertEquals("doujinshi", m.format)
        assertEquals("ja", m.languageIso)
        assertEquals("https://nhentai.net/g/684364/", m.web)
        assertEquals(22, m.pageCount)
        assertEquals(2026, m.year)
        assertTrue("标签要按逗号拆开", m.tags.contains("lolicon"))
        assertEquals(7, m.tags.size)
    }

    @Test
    fun comicInfo_toleratesBomAndGarbage() {
        // 带 BOM 的真实场景（有工具会写出 BOM，不剥掉 XmlPullParser 会直接抛）
        val withBom = "\uFEFF" + comicInfo
        assertEquals("tamura-chan", ComicMetadataParser.parseComicInfo(withBom)?.writer)
        // ⚠️ 坏 XML 一律 null，**不抛**：元数据缺失只该少填一段简介，不能让整本书导入失败。
        // 注意「一个字段都没解析出来」也算 null —— 被截断的 XML 在 KXmlParser 下不一定抛异常
        assertNull(ComicMetadataParser.parseComicInfo("<ComicInfo><Title>oops"))
        assertNull(ComicMetadataParser.parseComicInfo("not xml at all"))
        assertNull(ComicMetadataParser.parseComicInfo("<ComicInfo><Month>9</Month></ComicInfo>"))
    }

    // ===== meta.json =====

    @Test
    fun metaJson_mapsNhentaiTypes() {
        val m = ComicMetadataParser.parseNhentaiJson(metaJson)!!
        // artist → 作者，group → 社团/译者，parody → 系列，category → 类型
        assertEquals("tamura-chan", m.writer)
        assertEquals("chikyuujin", m.translator)
        assertEquals("original", m.series)
        assertEquals("doujinshi", m.format)
        assertEquals(22, m.pageCount)
        // 只有 type=tag 的进标签（category/language/parody/artist/group 都不算）
        assertEquals(listOf("sole female", "lolicon", "slime"), m.tags)
        // language 标签是 "japanese"，不是 ISO 码 → 不能塞进 languageIso
        assertNull(m.languageIso)
    }

    @Test
    fun metaJson_synthesizesNhentaiUrlFromId() {
        val m = ComicMetadataParser.parseNhentaiJson("""{"id":123,"tags":[]}""")!!
        assertEquals("https://nhentai.net/g/123/", m.web)
        // 既没有 id 也没有任何字段 → 视为「没有元数据」（不能返回一个空壳让调用方填空简介）
        assertNull(ComicMetadataParser.parseNhentaiJson("""{"tags":[]}"""))
        assertNull(ComicMetadataParser.parseNhentaiJson("""{"num_favorites":3}"""))
    }

    @Test
    fun metaJson_badInputReturnsNull() {
        assertNull(ComicMetadataParser.parseNhentaiJson("{"))
        assertNull(ComicMetadataParser.parseNhentaiJson("plain text"))
    }

    // ===== 合并：ComicInfo 优先，json 只补空 =====

    @Test
    fun parse_comicInfoWinsAndJsonFillsGaps() {
        val json = """{"id":1,"tags":[{"type":"artist","name":"json-artist"},{"type":"tag","name":"json-tag"}]}"""
        val m = ComicMetadataParser.parse(
            listOf("meta.json" to json, "ComicInfo.xml" to comicInfo)
        )!!
        // ComicInfo 的 Writer/Series/Tags 都非空 → json 的对应字段不覆盖
        assertEquals("tamura-chan", m.writer)
        assertEquals("original", m.series)
        // ComicInfo 的标签优先（json 的 "json-tag" 不覆盖）
        assertTrue(m.tags.contains("lolicon"))
        assertFalse(m.tags.contains("json-tag"))
    }

    @Test
    fun parse_jsonOnlyStillWorks() {
        val m = ComicMetadataParser.parse(listOf("meta.json" to metaJson))!!
        assertEquals("tamura-chan", m.writer)
        assertEquals("original", m.series)
    }

    @Test
    fun parse_shallowEntryWinsOverNested() {
        // 两个 ComicInfo：浅层（根）优先
        val root = comicInfo.replace("<Series>original</Series>", "<Series>root-series</Series>")
        val nested = comicInfo.replace("<Series>original</Series>", "<Series>nested-series</Series>")
        val m = ComicMetadataParser.parse(
            listOf("Book/ComicInfo.xml" to nested, "ComicInfo.xml" to root)
        )!!
        assertEquals("root-series", m.series)
    }

    @Test
    fun parse_noMetadataReturnsNull() {
        assertNull(ComicMetadataParser.parse(emptyList()))
        assertNull(ComicMetadataParser.parse(listOf("001.jpg" to "x")))
    }

    // ===== 简介文案 =====

    @Test
    fun description_listsAuthorCircleSeriesTagsSource() {
        val desc = ComicMetadataParser.descriptionOf(ctx, listOf("ComicInfo.xml" to comicInfo))
        assertEquals(5, desc.lines().size)
        assertTrue(desc.contains("tamura-chan"))
        assertTrue(desc.contains("chikyuujin"))
        assertTrue(desc.contains("original"))
        assertTrue(desc.contains("lolicon"))
        assertTrue(desc.contains("https://nhentai.net/g/684364/"))
    }

    @Test
    fun description_skipsEmptyFields() {
        // 只有作者 → 简介只有一行（不留「标签：」这种空行）
        val meta = ComicMetadata(writer = "someone")
        val desc = ComicMetadataParser.buildDescription(ctx, meta)
        assertEquals(1, desc.lines().size)
        assertTrue(desc.contains("someone"))
    }

    @Test
    fun description_truncatesLongTagList() {
        val meta = ComicMetadata(tags = (1..60).map { "tag-$it" })
        val desc = ComicMetadataParser.buildDescription(ctx, meta)
        val line = desc.lines().single()
        assertTrue("标签行过长会撑爆两行的简介展示：${line.length}", line.length < 200)
        assertTrue("截断要留痕迹", line.endsWith("…"))
    }

    @Test
    fun description_emptyWhenNoMetadata() {
        assertEquals("", ComicMetadataParser.descriptionOf(ctx, emptyList()))
        assertEquals("", ComicMetadataParser.descriptionOf(ctx, listOf("x.jpg" to "y")))
    }

    // ===== 导入链路上的真实读取（zip 条目 / 解压后的目录） =====

    /** 真实 zip（含一层书名目录）→ 与导入时**同一条**读取路径 → 简介。 */
    @Test
    fun zipMetadata_readThroughImportPath() {
        val zip = tmp.newFile("book.cbz")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("Book/ComicInfo.xml"))
            z.write(comicInfo.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("meta.json"))
            z.write(metaJson.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            // 图片条目混在里面：元数据读取必须只挑元数据
            z.putNextEntry(java.util.zip.ZipEntry("Book/001.webp"))
            z.write(ByteArray(32))
            z.closeEntry()
        }
        val texts = MangaImporter.readZipMetadataTexts(zip)
        assertEquals("只该挑出两个元数据条目", 2, texts.size)
        val desc = ComicMetadataParser.descriptionOf(ctx, texts)
        assertTrue(desc.contains("tamura-chan"))
        assertTrue(desc.contains("chikyuujin"))
        assertTrue(desc.contains("original"))
        assertTrue(desc.contains("lolicon"))
        assertTrue(desc.contains("https://nhentai.net/g/684364/"))
    }

    /** rar/7z 解压出来的 `pages/`（元数据可能在子目录里）。 */
    @Test
    fun dirMetadata_readForExtractedArchives() {
        val dir = tmp.newFolder("pages")
        java.io.File(dir, "ch1").mkdirs()
        java.io.File(dir, "ch1/ComicInfo.xml").writeText(comicInfo, Charsets.UTF_8)
        java.io.File(dir, "001.webp").writeText("x", Charsets.UTF_8)
        val texts = MangaImporter.readDirMetadataTexts(dir)
        assertEquals(listOf("ch1/ComicInfo.xml"), texts.map { it.first })
        assertTrue(ComicMetadataParser.descriptionOf(ctx, texts).contains("tamura-chan"))
    }

    /** 自称 meta.json 的超大条目不能整个读进内存。 */
    @Test
    fun oversizedMetadata_isSkipped() {
        val zip = tmp.newFile("big.cbz")
        val big = "x".repeat((ComicMetadataParser.MAX_METADATA_BYTES + 1024).toInt())
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("meta.json"))
            z.write(big.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("ComicInfo.xml"))
            z.write(comicInfo.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        val texts = MangaImporter.readZipMetadataTexts(zip)
        assertEquals("超大 meta.json 必须被跳过，只留 ComicInfo", 1, texts.size)
        assertEquals("ComicInfo.xml", texts.single().first)
    }
}
