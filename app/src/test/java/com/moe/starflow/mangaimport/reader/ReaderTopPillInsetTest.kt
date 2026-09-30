package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 顶部「章节页码胶囊」必须排在提示浮层**下面**（用户口径 2026-10）。
 *
 * 现场症状：「提示系统顶部和阅读器的章节页码组件**重叠**了」。
 * 提示浮层是 `TranslationStatusOverlay` —— `TYPE_APPLICATION_OVERLAY` 系统窗口，
 * `Gravity.TOP | CENTER_HORIZONTAL`、`y = 24dp`，一条 12sp 芯片约 24dp 高 → 占 **24~48dp**。
 * 而两个阅读器的胶囊原来都写 `layout_marginTop="38dp"` —— 上沿正压在芯片上。
 *
 * ⚠️ 这不只是「看不清」：浮层窗口会**吞掉落在它矩形内的触摸**，胶囊被压住的那一段点不动，
 * 而「点胶囊开章节目录」正是用户报「不知道为什么没有了」的那条路。
 *
 * ⚠️ 两个布局**必须一起改**：同一个浮层、同一个 38dp，只改漫画那边等于没修
 * （小说阅读器会照旧重叠）。所以这里两条断言 + 一条"两处取值一致"。
 *
 * 口径是「下移保证避开一条多一点就行」（用户原话）—— 不追求躲开 3 条堆叠，
 * 那要把胶囊压到屏幕近 1/5 处，得不偿失。
 */
class ReaderTopPillInsetTest {

    companion object {
        /** 浮层的顶距（`TranslationStatusOverlay.getViewParams()` 的 `Gravity.TOP -> 24dp`）。 */
        private const val NOTICE_TOP_DP = 24

        /** 一条芯片的高度（12sp 文字 + 上下各 4dp padding）。 */
        private const val NOTICE_CHIP_DP = 24

        /** 再让出一点余量，避免行高/字体缩放把它吃掉。 */
        private const val CLEARANCE_DP = 12

        private const val MIN_TOP_DP = NOTICE_TOP_DP + NOTICE_CHIP_DP + CLEARANCE_DP
    }

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到布局：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    /**
     * 取以 [anchor]（某个 `android:id="@+id/xxx"` 字面量）开头的**那个元素**的 `layout_marginTop`。
     *
     * ⚠️ 用「下一个 `android:id=`」当右边界，而不是"锚点后 N 个字符"：元素属性个数会变，
     * 魔法窗口一改就静默失效（这里只要求 marginTop 在下一个 id 之前 —— 它本来就是元素的
     * 前几个属性之一，稳）。
     */
    private fun marginTopOf(rel: String, anchor: String): Int {
        val text = read(rel)
        val at = text.indexOf(anchor)
        assertTrue("$rel 里找不到 $anchor", at > 0)
        val end = text.indexOf("android:id=", at + anchor.length).let { if (it > at) it else text.length }
        val m = Regex("android:layout_marginTop=\"(\\d+)dp\"")
            .find(text.substring(at, end))
        assertTrue("$anchor 上找不到 layout_marginTop（元素结构变了？）", m != null)
        return m!!.groupValues[1].toInt()
    }

    private fun assertClearsNotice(where: String, topDp: Int) {
        assertTrue(
            "$where 的顶部胶囊 marginTop=${topDp}dp —— 压在提示浮层的 24~48dp 上。" +
                "胶囊至少要到 ${MIN_TOP_DP}dp（浮层顶 24 + 芯片 24 + 余量 12）",
            topDp >= MIN_TOP_DP,
        )
    }

    @Test
    fun mangaTopPillSitsBelowTheNoticeOverlay() {
        assertClearsNotice(
            "漫画阅读器 activity_manga_reader.xml",
            marginTopOf("src/main/res/layout/activity_manga_reader.xml", "android:id=\"@+id/tv_page_indicator\""),
        )
    }

    @Test
    fun novelTopPillSitsBelowTheNoticeOverlay() {
        assertClearsNotice(
            "小说阅读器 activity_novel_reader.xml",
            marginTopOf("src/main/res/layout/activity_novel_reader.xml", "android:id=\"@+id/top_pill\""),
        )
    }

    /** 两个阅读器共用同一个浮层，顶距必须一致 —— 不一致就说明只改了一边。 */
    @Test
    fun bothReadersUseTheSameTopInset() {
        assertEquals(
            "两个阅读器的顶部胶囊顶距必须一致（同一个提示浮层，只改一边等于没修）",
            marginTopOf("src/main/res/layout/activity_manga_reader.xml", "android:id=\"@+id/tv_page_indicator\""),
            marginTopOf("src/main/res/layout/activity_novel_reader.xml", "android:id=\"@+id/top_pill\""),
        )
    }
}
