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

    @Test
    fun thePanelSwitchesBetweenTranslateAndSrRecords() {
        assertTrue("布局要有两个来源页签", layout.contains("@+id/tab_records_translate") && layout.contains("@+id/tab_records_sr"))
        assertTrue("面板要接上超分适配器", sheet.contains("private val srAdapter by lazy"))
        assertTrue("切换页签 = 换 RecyclerView 的 adapter", sheet.contains("if (recordsSource == 1) srAdapter else pageAdapter"))
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
