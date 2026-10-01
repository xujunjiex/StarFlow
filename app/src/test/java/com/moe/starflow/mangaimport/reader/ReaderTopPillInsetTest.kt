package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 顶部「章节页码胶囊」与提示浮层的**上下关系**（用户口径 2026-10）：
 *
 * > 「我希望**胶囊在提示条上面**，这样多条信息堆下来也不会盖住它」
 *
 * 所以是**胶囊贴顶、提示条往下让**，不是反过来：
 * ```
 *   10dp  ┌ 第3章 · 5/22 ┐      ← 胶囊（约 26dp 高 → 下沿 ~36dp）
 *   44dp  ▓ 正在超分第 5 页… ▓   ← 提示条从这里开始，往下堆
 *         ▓ 翻译中 · P5 ▓
 *   34dp  [←]          [⋮]      ← 返回 / 菜单在两侧，与居中的胶囊/提示条不抢横向空间
 * ```
 *
 * ⚠️ 为什么必须钉死：提示浮层是 `TYPE_APPLICATION_OVERLAY` **系统窗口**，
 * 会**吞掉落在它矩形内的触摸** —— 提示条压住胶囊那一段，胶囊就点不动，
 * 而「点胶囊开章节目录」正是用户报「不知道怎么没有了」的那条路。
 *
 * ⚠️ 浮层是进程级单例、`Status_Position` 是**用户设置**，所以阅读器不能改设置，
 * 只能在 `onStart`/`onStop` 用 `setTopOffsetDp` 临时覆盖顶距（本测试同时检查那条接线在）。
 */
class ReaderTopPillInsetTest {

    companion object {
        /** 胶囊的高度（13sp 文字 + 上下各 5dp padding）。 */
        private const val PILL_HEIGHT_DP = 26

        /** 再让出一点余量，避免行高/字体缩放把它吃掉。 */
        private const val CLEARANCE_DP = 6

        private val MANGA = "src/main/res/layout/activity_manga_reader.xml"
        private val NOVEL = "src/main/res/layout/activity_novel_reader.xml"
        private val MANGA_ACT = "src/main/java/com/moe/starflow/mangaimport/reader/MangaReaderActivity.kt"
        private val NOVEL_ACT = "src/main/java/com/moe/starflow/novel/reader/NovelReaderActivity.kt"
        private val OVERLAY = "src/main/java/com/moe/starflow/translate/TranslationStatusOverlay.kt"

        const val PILL_ID_MANGA = "android:id=\"@+id/tv_page_indicator\""
        const val PILL_ID_NOVEL = "android:id=\"@+id/top_pill\""
    }

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到文件：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    /**
     * 取以 [anchor] 开头的那个元素的 `layout_marginTop`。
     * 右边界用「下一个 `android:id=`」—— marginTop 本来就在元素前几个属性里，稳。
     */
    private fun marginTopOf(rel: String, anchor: String): Int {
        val text = read(rel)
        val at = text.indexOf(anchor)
        assertTrue("$rel 里找不到 $anchor", at > 0)
        val end = text.indexOf("android:id=", at + anchor.length).let { if (it > at) it else text.length }
        val m = Regex("android:layout_marginTop=\"(\\d+)dp\"").find(text.substring(at, end))
        assertTrue("$anchor 上找不到 layout_marginTop（元素结构变了？）", m != null)
        return m!!.groupValues[1].toInt()
    }

    /** 浮层里那个「阅读器专用顶距」常量。 */
    private fun readerNoticeTopDp(): Int {
        val m = Regex("const val READER_TOP_OFFSET_DP = (\\d+)").find(read(OVERLAY))
        assertTrue("TranslationStatusOverlay 里找不到 READER_TOP_OFFSET_DP", m != null)
        return m!!.groupValues[1].toInt()
    }

    private fun assertPillIsAboveTheNotices(where: String, topDp: Int) {
        val pillBottom = topDp + PILL_HEIGHT_DP
        val noticeTop = readerNoticeTopDp()
        assertTrue(
            "$where 的胶囊 marginTop=${topDp}dp（下沿约 ${pillBottom}dp）压到了提示条" +
                "（阅读器里顶距 ${noticeTop}dp）—— 用户口径是**胶囊在提示条上面**。" +
                "胶囊要 ≤ ${noticeTop - PILL_HEIGHT_DP - CLEARANCE_DP}dp",
            pillBottom + CLEARANCE_DP <= noticeTop,
        )
    }

    @Test
    fun mangaTopPillSitsAboveTheNoticeOverlay() {
        assertPillIsAboveTheNotices(
            "漫画阅读器 activity_manga_reader.xml",
            marginTopOf(MANGA, PILL_ID_MANGA),
        )
    }

    @Test
    fun novelTopPillSitsAboveTheNoticeOverlay() {
        assertPillIsAboveTheNotices(
            "小说阅读器 activity_novel_reader.xml",
            marginTopOf(NOVEL, PILL_ID_NOVEL),
        )
    }

    /** 两个阅读器共用同一个浮层，顶距必须一致 —— 不一致就说明只改了一边。 */
    @Test
    fun bothReadersUseTheSameTopInset() {
        assertEquals(
            "两个阅读器的胶囊顶距必须一致（同一个提示浮层，只改一边等于没修）",
            marginTopOf(MANGA, PILL_ID_MANGA),
            marginTopOf(NOVEL, PILL_ID_NOVEL),
        )
    }

    /**
     * **两个阅读器都必须真的覆盖提示条顶距**，并且离开时恢复。
     *
     * ⚠️ 光改布局没用：浮层是**进程级单例**、顶距默认 24dp（截图翻译链路的口径），
     * 阅读器不主动让位就还是压在胶囊上。也不能改用户的 `Status_Position` 设置 ——
     * 那是全局的，改完游戏/截屏两条链路跟着变。
     */
    @Test
    fun bothReadersPushTheNoticeStripDownAndRestoreIt() {
        for ((name, rel) in listOf(
            "漫画阅读器" to MANGA_ACT,
            "小说阅读器" to NOVEL_ACT,
        )) {
            val src = read(rel)
            assertTrue(
                "$name 的 onStart 必须把提示条让到胶囊下面",
                src.contains("setTopOffsetDp(TranslationStatusOverlay.READER_TOP_OFFSET_DP)"),
            )
            assertTrue(
                "$name 的 onStop 必须恢复默认顶距（单例不清会连带别的页面一起偏）",
                src.contains("setTopOffsetDp(null)"),
            )
        }
    }
}
