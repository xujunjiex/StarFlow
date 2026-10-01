package com.moe.starflow.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **超分记录系统**的接线守卫（源码级，纯 JVM）—— 用户口径 2026-10：
 * 「给超分面板也设计一个类似翻译面板那样的记录系统……显示超分的完成/进行中/失败状态，
 * 支持超分本章、查看详情、提示系统」+「不能出现两边都开启批量翻译某章和超分某章，要有互斥反馈和提示」。
 *
 * ⚠️ 为什么用源码断言：这条链路的失效方式几乎全是**静默**的 —— 记录写进库了但面板没人读、
 * 互斥判据写了但点击入口没接、UI 建好了但没挂到宿主推送链上。不崩不报错，单测也照过，
 * 只有人肉点一遍才发现"面板里什么都没有"。与 `SrReaderWiringTest` 同一手法。
 */
class SrRecordSystemWiringTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到源码：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    private val controller by lazy {
        read("src/main/java/com/moe/starflow/mangaimport/translate/ReaderTranslationController.kt")
    }
    private val activity by lazy {
        read("src/main/java/com/moe/starflow/mangaimport/reader/MangaReaderActivity.kt")
    }
    private val sheet by lazy {
        read("src/main/java/com/moe/starflow/mangaimport/reader/ReaderMenuSheet.kt")
    }
    private val adapter by lazy {
        read("src/main/java/com/moe/starflow/mangaimport/reader/ReaderSrStateAdapter.kt")
    }
    private val layout by lazy { read("src/main/res/layout/sheet_reader_menu.xml") }
    private val db by lazy { read("src/main/java/com/moe/starflow/data/TranslationHistoryDatabase.kt") }

    // ───────────── 记录落库 ─────────────

    @Test
    fun everySrRunWritesARecordOnStartAndFinish() {
        assertTrue("超分执行入口必须存在", controller.contains("private suspend fun runSr(page: Int, src: Bitmap)"))
        val body = controller.substring(
            controller.indexOf("private suspend fun runSr(page: Int, src: Bitmap)"),
        ).take(600)
        assertTrue("开跑前要写「超分中」", body.contains("markSrRunning(page, src, started)"))
        assertTrue("收尾要写结果（成功/失败）", body.contains("markSrFinished(page, outcome, started)"))
        // 成功时尺寸/模型/字节**从磁盘产物读回**，不从内存猜 —— 两处读必然漂移
        assertTrue("成功时读回真实产物元数据", controller.contains("SrStore.infoOf(context, manga.id, page, manga.translationKey)"))
        // 原图大小要给详情用
        assertTrue("要记录原图字节数", controller.contains("ownSource.pageBytes(page)"))
    }

    @Test
    fun recordsAreLoadedAndStaleRunningRowsAreClearedOnOpen() {
        assertTrue("进阅读器要载入超分记录", controller.contains("srRows.value = srDao.forManga(manga.id, mangaKey)"))
        assertTrue(
            "残留的「超分中」必须回退（否则那页永远卡在进行中，面板也再点不动）",
            controller.contains("srDao.resetRunning(manga.id, mangaKey)"),
        )
        val dao = read("src/main/java/com/moe/starflow/data/ImportedPageSr.kt")
        assertTrue("DAO 要有 resetRunning", dao.contains("suspend fun resetRunning("))
        assertEquals(
            "sql 里的字面量必须与实体的常量对齐（@Query 引用不了 Kotlin 常量）",
            true, dao.contains("SET state = 0") && dao.contains("AND state = 1"),
        )
    }

    @Test
    fun theNewTableHasBothAnEntityAndAMigration() {
        assertTrue("DB 要升到 19", db.contains("version = 19"))
        assertTrue("要注册新实体", db.contains("ImportedPageSr::class"))
        assertTrue("必须有 18→19 迁移（漏写 = 升级用户整库被删）", db.contains("MIGRATION_18_19"))
        assertTrue("迁移要挂进 addMigrations", db.contains("MIGRATION_17_18, MIGRATION_18_19"))
        assertTrue("纯新增 + 幂等", db.contains("CREATE TABLE IF NOT EXISTS imported_page_sr"))
    }

    // ───────────── 同模型不重超 ─────────────

    @Test
    fun bothAutoSrPathsShareTheSameModelCheck() {
        assertTrue(
            "判据必须只有一处（两处各写一份必然漂移）",
            controller.contains("private fun srAlreadyCurrent(page: Int): Boolean"),
        )
        assertEquals(
            "手动/自动路径与章节批量路径都要用同一个判据",
            2,
            Regex("if \\(srAlreadyCurrent\\(page\\)\\) return null").findAll(controller).count(),
        )
    }

    // ───────────── 互斥 ─────────────

    @Test
    fun theTwoBatchJobsAreMutuallyExclusiveWithVisibleFeedback() {
        assertTrue("要有超分挡住翻译的判据", controller.contains("fun srBatchBlockedByTranslate(): Boolean"))
        assertTrue("要有翻译挡住超分的判据", controller.contains("fun translateBatchBlockedBySr(): Boolean"))
        // 控制器内部两道（防别的调用点绕过）
        assertTrue("startSrChapterJob 要拦", controller.contains("if (srBatchBlockedByTranslate()) return"))
        assertTrue("startChapterJob 要拦", controller.contains("if (translateBatchBlockedBySr()) return"))
        // 宿主两道**必须有提示**，不能静默 return（静默 = 点了没反应）
        assertTrue(
            "点「超分本章」被翻译挡住时要提示",
            activity.contains("R.string.reader_sr_blocked_by_translate"),
        )
        assertTrue(
            "点「翻译本章」被超分挡住时要提示",
            activity.contains("R.string.reader_translate_blocked_by_sr"),
        )
    }

    // ───────────── 面板 UI ─────────────

    @Test
    fun theRecordListReusesTheTranslatePanelsUi() {
        // 复用同一批布局：换成超分还是这两份 XML
        assertTrue("章卡片复用翻译面板的布局", adapter.contains("R.layout.item_translate_chapter_row"))
        assertTrue("页行/详情复用翻译面板的布局", adapter.contains("R.layout.item_translate_page_state"))
        assertTrue("底色复用 CardBackdrop", adapter.contains("CardBackdrop.apply(") && adapter.contains("CardBackdrop.applyNested("))
        // 详情 = 用户要的那几项
        for (key in listOf(
            "reader_sr_detail_model", "reader_sr_detail_src", "reader_sr_detail_out",
        )) {
            assertTrue("详情要展示 $key", adapter.contains("R.string.$key"))
        }
        // 三种状态
        for (key in listOf("reader_sr_state_running", "reader_translate_state_success", "reader_translate_state_failed")) {
            assertTrue("状态徽章要覆盖 $key", adapter.contains("R.string.$key"))
        }
        // 章卡片两个按钮
        assertTrue("主按钮 = 超分本章", adapter.contains("R.string.reader_sr_chapter_translate"))
        assertTrue("次按钮 = 清空本章", adapter.contains("R.string.reader_sr_chapter_clear"))
    }

    /**
     * 记录列表挂在**超分面板**（调色面板的 `sr_panel_group`）里，**不是翻译面板**。
     *
     * 用户口径 2026-10：「给**超分面板**设计一个类似翻译面板那样的记录系统」——
     * 第一版做成了翻译面板里的一个「翻译 / 超分」来源页签，被用户当场否掉。
     */
    @Test
    fun theRecordListLivesInsideTheSrPanel() {
        // ① 记录列表的两块控件必须在 `sr_panel_group` 里面
        val groupStart = layout.indexOf("android:id=\"@+id/sr_panel_group\"")
        assertTrue("布局里必须有 sr_panel_group", groupStart > 0)
        val groupEnd = layout.indexOf("android:id=\"@+id/btn_anime4k\"", groupStart)
        assertTrue("找不到 sr_panel_group 的结束边界", groupEnd > groupStart)
        for (id in listOf("sr_filter_row", "rv_sr_records")) {
            val at = layout.indexOf("android:id=\"@+id/$id\"")
            assertTrue("布局里必须有 $id", at > 0)
            assertTrue("$id 必须在 sr_panel_group **里面**（超分面板，不是翻译面板）", at in groupStart until groupEnd)
        }
        // ② 不许再留「来源页签」那一套
        assertFalse("超分记录不该做成翻译面板里的来源页签", layout.contains("tab_records_sr"))
        assertFalse("同上", sheet.contains("recordsSource"))
        assertFalse("同上", sheet.contains("refreshRecordsTabs"))

        // ③ 面板要真的把超分适配器挂上去
        assertTrue("面板要接上超分适配器", sheet.contains("private val srAdapter by lazy"))
        assertTrue("要挂到 rv_sr_records", sheet.contains("R.id.rv_sr_records"))
        assertTrue("超分筛选行要自己一套", sheet.contains("R.id.sr_filter_row"))
        assertTrue(
            "超分的筛选键必须与翻译那条**独立**（互相不影响）",
            sheet.contains("private var srFilterKey = 0"),
        )
        assertTrue(
            "宿主推送必须**同源**推给两个适配器（新增字段漏一处 = 那处 UI 停在打开那一刻）",
            sheet.contains("srAdapter.submit("),
        )
        assertTrue("宿主状态快照要有超分记录", sheet.contains("val srRecords: List<ImportedPageSr> = emptyList()"))
        assertTrue("宿主状态快照要有超分任务", sheet.contains("val srJob: ReaderTranslationController.SrChapterJob? = null"))
        assertTrue("宿主必须真的填这两个字段", activity.contains("srRecords = c?.srRecords() ?: emptyList()"))
        assertTrue(activity.contains("srJob = c?.srChapterJob?.value"))
        // 从宿主回读到面板字段
        assertTrue(sheet.contains("currentSrRecords = st.srRecords"))
        assertTrue(sheet.contains("currentSrJob = st.srJob"))
        // 调色面板那一路也要刷新超分记录（否则用户切完超分再打开调色面板看到的是旧列表）
        assertTrue("调色面板重建时要重新绑超分记录", sheet.contains("setupSrRecords("))
    }

    /**
     * **底部进度条也要标出「已超分」的页**（用户口径 2026-10：「超分过的页码底部进度条也要有
     * 特别的颜色标记。想办法设计一种能分辨清楚的方案」）。
     *
     * 方案：**四层信息叠在同一根条上，两条细线分居粗带上/下**——
     * ```
     *   ▓▓▓ 2dp 绿：已翻译（压在粗带**中间**）
     *   ███ 5dp 粗带：白=已读 / 灰=未读（交界 = 当前位置）
     *   ━━━ 3dp 紫：已超分（画在粗带**下方**）
     * ```
     * ⚠️ 关键是**位置**而不只是颜色：两个维度互相独立（一页可以"翻了没超"或"超了没翻"），
     * 挤在同一条水平线上必然互相遮盖；分居粗带上下之后，即便分不清颜色（色弱 / 灰度截图）
     * 也能靠"在粗带上面还是下面"分辨。
     */
    @Test
    fun theProgressBarMarksUpscaledPagesSeparatelyFromTranslatedOnes() {
        val bar = read("src/main/java/com/moe/starflow/mangaimport/reader/ReaderProgressBar.kt")
        assertTrue("进度条要有独立的「已超分」入口", bar.contains("fun setSrPages(pages: Set<Int>)"))
        assertTrue("超分标记要有自己的画笔", bar.contains("srPaint"))
        assertTrue(
            "绿条画在**粗带中线**上",
            bar.contains("drawRuns(canvas, cy, w, span, translatedRuns, translatedPaint)"),
        )
        assertTrue(
            "紫条画在**粗带下方**（与绿分居两侧，互不遮盖）",
            bar.contains("drawRuns(canvas, cy + dp(5f), w, span, srRuns, srPaint)"),
        )
        assertTrue(
            "翻页换总页数时两条都要重算",
            Regex("srRuns = computeTranslatedRuns\\(srPages, total\\)").containsMatchIn(bar),
        )
        // 宿主接线 + 数据源口径
        assertTrue(
            "宿主要把「已成功的超分页」喂给进度条",
            activity.contains("setSrPages(translationController?.srPages()"),
        )
        assertTrue(
            "数据源只算 STATE_SUCCESS（RUNNING/FAILED 不该在进度条上留痕，否则失败后紫条不退）",
            controller.contains("it.state == ImportedPageSr.STATE_SUCCESS }.keys"),
        )
    }

    @Test
    fun srChapterJobStopsWhenTheReaderCloses() {
        // ⚠️ 超分本章**没有通知栏入口**，而它的进行中提示是系统级浮层窗口：
        //    跟着跑到后台会把芯片贴到别的应用上，而 unbindUi 已经清掉回调、再没人能收走它们。
        val onClosed = controller.substring(controller.indexOf("fun onReaderClosed()")).take(700)
        assertTrue("离开阅读器必须停掉超分本章", onClosed.contains("cancelSrChapterJob()"))
    }

    @Test
    fun theSrChapterJobIsCancellableAndReleasesBitmaps() {
        val i = controller.indexOf("fun startSrChapterJob(")
        assertTrue("找不到 startSrChapterJob", i > 0)
        val body = controller.substring(i, controller.indexOf("fun cancelSrChapterJob(", i))
        assertTrue("逐页取图后必须回收（一页 2x 就 40MB，整章必 OOM）", body.contains("src.recycle()"))
        assertTrue("收尾要清任务状态", body.contains("_srChapterJob.value = null"))
        assertFalse("不许把整章页图一次装进内存", body.contains("map { loadFull"))
    }
}
