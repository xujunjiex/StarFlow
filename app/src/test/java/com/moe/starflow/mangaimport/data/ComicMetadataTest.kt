package com.moe.starflow.mangaimport.data

import android.content.Context
import com.moe.starflow.R
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
 * 要点：
 * - **四种公开格式都要认**：ComicInfo.xml / CoMet / ComicBookInfo / 通用 JSON；
 *   而且**按内容嗅探**——文件名写错了也要能读出来
 * - 合并是**逐字段**的：ComicInfo 优先，其余只补空
 * - ⚠️ **绝不合成来源**：模型里没有来源字段，简介里也没有「来源」行；输入里写了 `<Web>` 也不显示
 *   （曾用 id 拼第三方站点 URL，字面量进 dex 后被安装器按域名黑名单报「含色情内容」）
 * - 坏数据**绝不能让导入失败**：解析失败返回 null / 空简介
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComicMetadataTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** 临时 zip / 目录（导入链路的真实读取测试用）。 */
    @get:Rule
    val tmp = TemporaryFolder()

    /** 真实的 ComicInfo.xml：故意带 `<Web>`，用来证明来源不会进简介。 */
    private val comicInfo = """
        <?xml version="1.0" encoding="utf-8"?>
        <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <Title>Sample Comic [Digital]</Title>
          <Series>original</Series>
          <AlternateSeries>サンプルコミック [DL版]</AlternateSeries>
          <Writer>sample-artist</Writer>
          <Penciller>sample-penciller</Penciller>
          <Translator>sample-group</Translator>
          <Format>doujinshi</Format>
          <Tags>action, fantasy, school life, twintails, slime, drama, adventure</Tags>
          <LanguageISO>ja</LanguageISO>
          <Web>https://example.com/series/42</Web>
          <PageCount>22</PageCount>
          <Year>2026</Year>
          <Month>9</Month>
          <Day>27</Day>
          <Manga>YesAndRightToLeft</Manga>
        </ComicInfo>
    """.trimIndent()

    /** CoMet（根 `<comet>`）。 */
    private val comet = """
        <comet>
          <title>Comet Title</title>
          <series>Comet Series</series>
          <issue>3</issue>
          <publisher>Example Press</publisher>
          <year>2019</year>
          <month>5</month>
          <genre>Science Fiction, Adventure</genre>
          <language>en</language>
          <writer>comet-writer</writer>
          <synopsis>The comet synopsis.</synopsis>
          <pages>48</pages>
        </comet>
    """.trimIndent()

    /** ComicRack 的 ComicBookInfo/1.0（作者在 credits 里）。 */
    private val comicBookInfo = """
        {"ComicBookInfo/1.0":{
           "series":"Cbi Series","title":"Cbi Title","publisher":"Example Press",
           "publicationMonth":10,"publicationYear":2003,"issue":1,"numberOfIssues":12,
           "genre":"Horror","language":"English",
           "credits":[{"person":"cbi-writer","role":"Writer"},
                      {"person":"cbi-penciller","role":"Penciller"},
                      {"person":"cbi-translator","role":"Translator"}],
           "tags":["zombies"],
           "comments":"Cbi comments.",
           "web":"https://example.com/cbi"},
         "appID":"ComicRack"}
    """.trimIndent()

    /** 通用 JSON：键名是各下载器自己拍的，tags 带 booru 风格的 `type` 角色。 */
    private val metaJson = """
        {"id":42,"title":{"english":"Sample Comic [Digital]","japanese":"サンプルコミック [DL版]"},
         "upload_date":1790499147,"num_pages":22,"num_favorites":28,"scanlator":"",
         "permalink":"https://example.com/episode/42",
         "tags":[{"id":1,"type":"category","name":"doujinshi"},
                 {"id":2,"type":"language","name":"japanese"},
                 {"id":3,"type":"tag","name":"action"},
                 {"id":4,"type":"tag","name":"fantasy"},
                 {"id":5,"type":"parody","name":"original"},
                 {"id":6,"type":"group","name":"sample-group"},
                 {"id":7,"type":"artist","name":"sample-artist"}]}
    """.trimIndent()

    // ===== 条目识别 =====

    @Test
    fun isMetadataEntry_acceptsEveryKnownName() {
        listOf(
            "ComicInfo.xml", "comicinfo.xml", "Book/ch1/COMICINFO.XML",
            "comet.xml", "Comet.xml", "Book/comet.xml",
            "ComicBookInfo.json", "meta.json", "Book\\meta.JSON",
            "ComicBookInfo",
        ).forEach { assertTrue("应该认识：$it", ComicMetadataParser.isMetadataEntry(it)) }
        listOf("001.jpg", "metadata.json", "info.txt", "comicbookinfo.txt", "series.json").forEach {
            assertFalse("不该认识：$it", ComicMetadataParser.isMetadataEntry(it))
        }
    }

    // ===== ComicInfo.xml =====

    @Test
    fun comicInfo_parsesAllFields() {
        val m = ComicMetadataParser.parseComicInfo(comicInfo)!!
        assertEquals("sample-artist", m.writer)
        assertEquals("sample-group", m.translator)
        assertEquals("original", m.series)
        assertEquals("doujinshi", m.format)
        assertEquals("ja", m.languageIso)
        assertEquals(22, m.pageCount)
        assertEquals(2026, m.year)
        assertEquals("2026-09-27", m.publishedAt)
        assertTrue(m.tags.contains("slime"))
        assertEquals(7, m.tags.size)
    }

    @Test
    fun comicInfo_toleratesBomAndGarbage() {
        // 带 BOM 的真实场景（有工具会写出 BOM，不剥掉 XmlPullParser 会直接抛）
        val withBom = "\uFEFF" + comicInfo
        assertEquals("sample-artist", ComicMetadataParser.parseComicInfo(withBom)?.writer)
        // ⚠️ 坏 XML 一律 null，**不抛**：元数据缺失只该少填一段简介，不能让整本书导入失败。
        // 注意「一个字段都没解析出来」也算 null —— 被截断的 XML 在 KXmlParser 下不一定抛异常
        assertNull(ComicMetadataParser.parseComicInfo("<ComicInfo><Title>oops"))
        assertNull(ComicMetadataParser.parseComicInfo("not xml at all"))
        assertNull(ComicMetadataParser.parseComicInfo("<ComicInfo><Month>9</Month></ComicInfo>"))
    }

    // ===== CoMet =====

    @Test
    fun comet_parsesCoreFields() {
        val m = ComicMetadataParser.parseCoMet(comet)!!
        assertEquals("Comet Title", m.title)
        assertEquals("Comet Series", m.series)
        assertEquals("comet-writer", m.writer)
        assertEquals("The comet synopsis.", m.summary)
        assertEquals(48, m.pageCount)
        assertEquals(2019, m.year)
        assertEquals("en", m.languageIso)
        assertEquals("2019-05", m.publishedAt)
        assertEquals(listOf("Science Fiction", "Adventure"), m.tags)
        // 根不是 <comet> 的不当 CoMet 读
        assertNull(ComicMetadataParser.parseCoMet(comicInfo))
    }

    // ===== ComicBookInfo =====

    @Test
    fun comicBookInfo_readsCreditsGenreAndComments() {
        val m = ComicMetadataParser.parseComicBookInfo(comicBookInfo)!!
        assertEquals("Cbi Series", m.series)
        assertEquals("Cbi Title", m.title)
        assertEquals("Cbi comments.", m.summary)
        // credits 按 role 取人：Writer 归作者，Translator 归社团
        assertEquals("cbi-writer", m.writer)
        assertEquals("cbi-translator", m.translator)
        assertEquals(2003, m.year)
        assertEquals("2003-10", m.publishedAt)
        // genre 同时当类型和标签（这类包没有 tags 时它就是唯一分类）
        assertEquals(listOf("zombies", "Horror"), m.tags)
        // "English" 是名字不是 ISO 码，不能塞进 languageIso
        assertNull(m.languageIso)
    }

    // ===== 通用 JSON =====

    @Test
    fun genericJson_mapsBooruRoleTags() {
        val m = ComicMetadataParser.parseAny(metaJson)!!
        // artist → 作者，group → 社团，parody → 系列，category → 类型
        assertEquals("sample-artist", m.writer)
        assertEquals("sample-group", m.translator)
        assertEquals("original", m.series)
        assertEquals("doujinshi", m.format)
        assertEquals(22, m.pageCount)
        // 只有普通 tag 进标签（category/language/parody/artist/group 都不算）
        assertEquals(listOf("action", "fantasy"), m.tags)
        // language 标签是 "japanese" 这种名字，不是 ISO 码 → 不能塞进 languageIso
        assertNull(m.languageIso)
    }

    @Test
    fun genericJson_badInputReturnsNull() {
        assertNull(ComicMetadataParser.parseAny("{"))
        assertNull(ComicMetadataParser.parseAny("plain text"))
        assertNull(ComicMetadataParser.parseAny("[]"))
        // 只有 id、别的字段一个都没有 → 视为「没有元数据」（不能返回空壳让调用方填空简介）
        assertNull(ComicMetadataParser.parseAny("""{"id":123,"tags":[]}"""))
        assertNull(ComicMetadataParser.parseAny("""{"tags":[]}"""))
        assertNull(ComicMetadataParser.parseAny("""{"num_favorites":3}"""))
    }

    // ===== 按内容嗅探：文件名不作数 =====

    @Test
    fun sniffing_ignoresFileName() {
        // 叫 meta.json 的其实是 CoMet
        assertEquals("Comet Series", ComicMetadataParser.parse(listOf("meta.json" to comet))!!.series)
        // 叫 ComicInfo.xml 的其实是 ComicBookInfo
        assertEquals("Cbi Series", ComicMetadataParser.parse(listOf("ComicInfo.xml" to comicBookInfo))!!.series)
        // 叫 comicbookinfo.json 的其实是 ComicInfo
        assertEquals(
            "sample-artist",
            ComicMetadataParser.parse(listOf("comicbookinfo.json" to comicInfo))!!.writer,
        )
    }

    // ===== 合并：ComicInfo 优先，其余只补空 =====

    @Test
    fun parse_comicInfoWinsAndOthersFillGaps() {
        val json = """{"id":1,"tags":[{"type":"artist","name":"json-artist"},{"type":"tag","name":"json-tag"}]}"""
        val m = ComicMetadataParser.parse(
            listOf("meta.json" to json, "ComicInfo.xml" to comicInfo)
        )!!
        // ComicInfo 的 Writer/Series/Tags 都非空 → json 的对应字段不覆盖
        assertEquals("sample-artist", m.writer)
        assertEquals("original", m.series)
        assertTrue(m.tags.contains("slime"))
        assertFalse(m.tags.contains("json-tag"))
    }

    @Test
    fun priority_orderIsFormatBasedNotNameBased() {
        val texts = listOf(
            "Book/meta.json" to """{"title":"generic-title","summary":"generic-summary"}""",
            "ComicBookInfo.json" to comicBookInfo,
            "comet.xml" to comet,
            "ComicInfo.xml" to comicInfo,
        )
        val m = ComicMetadataParser.parse(texts)!!
        // 书名取 ComicInfo；它没有 summary，按优先级由 CoMet 补上（不是 generic）
        assertEquals("Sample Comic [Digital]", m.title)
        assertEquals("The comet synopsis.", m.summary)
    }

    @Test
    fun parse_jsonOnlyStillWorks() {
        val m = ComicMetadataParser.parse(listOf("meta.json" to metaJson))!!
        assertEquals("sample-artist", m.writer)
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
    fun description_listsAuthorCircleSeriesTags() {
        val desc = ComicMetadataParser.descriptionOf(ctx, listOf("ComicInfo.xml" to comicInfo))
        assertEquals(7, desc.lines().size)
        assertTrue(desc.contains("sample-artist"))
        assertTrue(desc.contains("sample-group"))
        assertTrue(desc.contains("original"))
        assertTrue(desc.contains("slime"))
        assertTrue(desc.contains("Title:"))
        assertTrue(desc.contains("Pages: 22"))
    }

    /**
     * ⚠️ 最关键的一条守卫：**任何格式、任何输入，简介里都不会出现来源行**。
     *
     * 曾经的实现拿 `id` 拼第三方站点 URL，字面量跟着 dex 进安装包，
     * 安装器/商店按域名黑名单静态扫描就报「含色情内容」——真机安装提示就是这么来的。
     */
    @Test
    fun description_neverShowsSource() {
        val sourcePrefix = ctx.getString(R.string.comic_meta_source, "").trim()
        val englishPrefix = "Source:"
        listOf(
            "ComicInfo.xml" to comicInfo,
            "comet.xml" to comet,
            "ComicBookInfo.json" to comicBookInfo,
            "meta.json" to metaJson,
        ).forEach { (name, text) ->
            val desc = ComicMetadataParser.descriptionOf(ctx, listOf(name to text))
            assertTrue("$name 应该能出简介", desc.isNotBlank())
            assertFalse("$name 的简介里不该有来源：$desc", desc.contains(sourcePrefix))
            assertFalse("$name 的简介里不该有来源：$desc", desc.contains(englishPrefix))
            assertFalse("$name 的简介里不该出现输入里的链接：$desc", desc.contains("example.com"))
        }
    }

    @Test
    fun description_worksForEveryFormat() {
        listOf(
            "comet.xml" to comet,
            "ComicBookInfo.json" to comicBookInfo,
            "meta.json" to metaJson,
        ).forEach { (name, text) ->
            assertTrue(name, ComicMetadataParser.descriptionOf(ctx, listOf(name to text)).isNotBlank())
        }
    }

    @Test
    fun downloaderMetadataWithEpisodeFieldsProducesADescription() {
        val json = """{"episode_id":"9253191255607512279","page_title":"[139話 前編]SPY×FAMILY - 遠藤達哉 | 少年ジャンプ＋","series_title":"SPY×FAMILY","episode_label":"139話 前編","page_count":17,"published_at":"2026-08-02T15:00:00Z","permalink":"https://example.com/episode/9253191255607512279","note":"Personal reading copy"}"""
        val meta = ComicMetadataParser.parse(listOf("meta.json" to json))!!
        assertEquals("SPY×FAMILY", meta.series)
        assertEquals("139話 前編", meta.episode)
        assertEquals(17, meta.pageCount)
        val description = ComicMetadataParser.buildDescription(ctx, meta)
        assertTrue(description.contains("139話 前編"))
        assertTrue(description.contains("Pages: 17"))
        assertTrue(description.contains("2026-08-02"))
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

    // ===== 历史简介清理 =====

    @Test
    fun stripSourceLines_removesOnlySourceLines() {
        val before = "Sample Comic\n来源：https://example.com/x\nPages: 22\nSource: https://example.com/y"
        val after = ComicMetadataParser.stripSourceLines(before, "来源：")
        assertEquals("Sample Comic\nPages: 22", after)
        // 没有来源行时原样返回（调用方据此判断要不要写库）
        assertEquals("Sample Comic\nPages: 22", ComicMetadataParser.stripSourceLines("Sample Comic\nPages: 22", "来源："))
        assertEquals("", ComicMetadataParser.stripSourceLines("", "来源："))
    }

    @Test
    fun stripSourceLines_keepsUserTextThatMerelyStartsWithSource() {
        // 只删「来源：」开头的整行；正文里提到来源词但不在行首的不动
        val text = "正文里提到 来源：这个词\n来源：https://example.com/x"
        assertEquals("正文里提到 来源：这个词", ComicMetadataParser.stripSourceLines(text, "来源："))
    }

    // ===== 导入链路上的真实读取（zip 条目 / 目录） =====

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
        assertTrue(desc.contains("sample-artist"))
        assertTrue(desc.contains("sample-group"))
        assertTrue(desc.contains("original"))
        assertTrue(desc.contains("slime"))
    }

    /** ComicRack 的 ComicBookInfo 写在 zip **注释**里（没有条目名）→ 也要读到。 */
    @Test
    fun zipComment_carriesComicBookInfo() {
        val zip = tmp.newFile("commented.cbz")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { z ->
            z.setComment("ComicRack export\n$comicBookInfo")
            z.putNextEntry(java.util.zip.ZipEntry("001.webp"))
            z.write(ByteArray(16))
            z.closeEntry()
        }
        val texts = MangaImporter.readZipMetadataTexts(zip)
        assertEquals(1, texts.size)
        assertEquals("Cbi Series", ComicMetadataParser.parse(texts)!!.series)
    }

    /** 普通注释（打包器写的说明）不能被当成元数据。 */
    @Test
    fun zipComment_plainTextIsIgnored() {
        val zip = tmp.newFile("plain-comment.cbz")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { z ->
            z.setComment("packed by somebody, version 3")
            z.putNextEntry(java.util.zip.ZipEntry("001.webp"))
            z.write(ByteArray(16))
            z.closeEntry()
        }
        assertTrue(MangaImporter.readZipMetadataTexts(zip).isEmpty())
    }

    /** rar/7z 解压出来的 `pages/`（元数据可能在子目录里）。 */
    @Test
    fun dirMetadata_readForExtractedArchives() {
        val dir = tmp.newFolder("pages")
        java.io.File(dir, "ch1").mkdirs()
        java.io.File(dir, "ch1/ComicInfo.xml").writeText(comicInfo, Charsets.UTF_8)
        java.io.File(dir, "comet.xml").writeText(comet, Charsets.UTF_8)
        java.io.File(dir, "001.webp").writeText("x", Charsets.UTF_8)
        val texts = MangaImporter.readDirMetadataTexts(dir)
        assertEquals(listOf("ch1/ComicInfo.xml", "comet.xml"), texts.map { it.first }.sorted())
        assertTrue(ComicMetadataParser.descriptionOf(ctx, texts).contains("sample-artist"))
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
