package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 顶部「章节页码胶囊」与提示浮层的**上下关系**（用户口径 2026-10）：
 *
 * > 「章节胶囊**不要紧贴屏幕顶部**，**保证提示在章节胶囊下面**就行了；
 * >  这个位置不要动，感觉有点太靠下了……不要间隔那么大的空间」
 *
 * 所以是**胶囊留在原位、提示条紧贴它下沿**：
 * ```
 *   34dp  [←]          [⋮]      ← 返回 / 菜单在两侧，与居中的胶囊不抢横向空间
 *   38dp  ┌ 第3章 · 5/22 ┐      ← 胶囊（约 26dp 高 → 下沿 ~64dp）
 *  ~66dp  ▓ 正在超分第 5 页… ▓   ← 提示条**紧贴**胶囊下沿（实测 pill.bottom + 2dp）
 *         ▓ 翻译中 · P5 ▓
 * ```
 *
 * ⚠️ 为什么必须钉死：提示浮层是 `TYPE_APPLICATION_OVERLAY` **系统窗口**，
 * 会**吞掉落在它矩形内的触摸** —— 提示条压住胶囊那一段，胶囊就点不动，
 * 而「点胶囊开章节目录」正是用户报「不知道怎么没有了」的那条路。
 *
 * ⚠️ 浮层是进程级单例、`Status_Position` 是**用户设置**，所以阅读器不能改设置，
 * 只能在 `onStart`/`onStop` 用 `setTopScreenY` 临时覆盖**屏幕坐标**顶距。
 * 这里是**静态**检查（布局里的 marginTop 与兜底估算常量），真正的"紧贴"由宿主的
 * 实测推送保证 —— 固定 dp 在大字号下会压住胶囊、在小字号下会留大缝，用户两次都在纠这一点。
 *
 * ⚠️ **必须是屏幕坐标（`getLocationOnScreen`），不能是视图的 `pill.bottom`**（2026-10 事故）：
 * `View.bottom` 相对父布局，而 `TYPE_APPLICATION_OVERLAY` 窗口被 WMS 按系统栏内缩
 * （真机 `Frames: parent=[0,138][1220,2660]`，即窗口 `y=0` 对应屏幕 138px）。
 * 阅读器窗口是整屏的（`frame=[0,0][1220,2712]`），于是两边差了一个系统栏高 ≈ 42dp ——
 * 表现就是「提示条永远比胶囊低一大截，怎么改 dp 都像原地没动」。
 */
class ReaderTopPillInsetTest {

    companion object {
        /** 胶囊的高度（13sp 文字 + 上下各 5dp padding）。 */
        private const val PILL_HEIGHT_DP = 26

        /** 胶囊距屏幕顶的最小距离（用户口径：「不要紧贴屏幕顶部」）。 */
        private const val MIN_PILL_TOP_DP = 20

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

    /**
     * 静态守卫：**兜底估算值不能压住胶囊**。
     *
     * ⚠️ 只检查"估算 ≥ 胶囊下沿"。**别在这里要求"留多少余量"** —— 真正的位置是
     * `pill.bottom + 2dp` 实测推的（见 [bothReadersPushTheNoticeStripBelowThePillAndRestoreIt]），
     * 估算值只在胶囊还没布局的那几毫秒用得上。
     */
    private fun assertPillIsAboveTheNotices(where: String, topDp: Int) {
        val pillBottom = topDp + PILL_HEIGHT_DP
        val noticeTop = readerNoticeTopDp()
        assertTrue(
            "$where 的胶囊 marginTop=${topDp}dp（下沿约 ${pillBottom}dp）会压到布局前的兜底提示位" +
                "（${noticeTop}dp）—— 胶囊下沿必须 ≤ 兜底值",
            pillBottom <= noticeTop,
        )
        assertTrue(
            "$where 的胶囊不该紧贴屏幕顶（用户口径：不要紧贴顶部）",
            topDp >= MIN_PILL_TOP_DP,
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
    fun bothReadersPushTheNoticeStripBelowThePillAndRestoreIt() {
        for ((name, rel) in listOf(
            "漫画阅读器" to MANGA_ACT,
            "小说阅读器" to NOVEL_ACT,
        )) {
            val src = read(rel)
            assertTrue("$name 的 onStart 要推提示条位置", src.contains("pushNoticeTopBelowPill()"))
            assertTrue(
                "$name 必须按**实测的胶囊下沿**推（不猜固定 dp：猜的在大字号下压住胶囊、" +
                    "在小字号下留缝 —— 用户两次都在纠这一点）",
                src.contains("pill.height"),
            )
            // ⚠️ 取屏幕坐标，不是视图的 pill.bottom —— 浮层窗口被系统栏内缩，两者差一个系统栏高
            assertTrue(
                "$name 必须用 getLocationOnScreen() 取**屏幕坐标**：视图的 pill.bottom 相对父布局，" +
                    "而浮层窗口坐标被系统栏内缩过，混用会让提示条整体低一条系统栏（约 42dp）",
                src.contains("getLocationOnScreen"),
            )
            assertTrue(
                "$name 的胶囊下沿必须是「屏幕 y + 高度」，不是视图的 `pill.bottom`",
                src.contains("loc[1] + pill.height"),
            )
            assertTrue(
                "$name 的 onStop 必须恢复默认顶距（单例不清会连带别的页面一起偏）",
                src.contains("setTopScreenY(null)"),
            )
            assertTrue(
                "$name 要监听胶囊高度变化（章名换行会把胶囊撑高）",
                src.contains("addOnLayoutChangeListener"),
            )
        }
        // 浮层侧：覆盖值是**屏幕坐标**，且自带窗口内缩校准
        val overlay = read(OVERLAY)
        assertTrue("浮层要提供屏幕坐标的覆盖入口", overlay.contains("fun setTopScreenY(screenY: Int?)"))
        assertTrue("要有布局前的兜底估算", overlay.contains("const val READER_TOP_OFFSET_DP ="))
        assertTrue(
            "浮层必须把屏幕坐标换算成窗口坐标（减去系统栏内缩），不能直接当 LayoutParams.y 用",
            overlay.contains("want - windowTopOffsetPx"),
        )
        assertTrue(
            "内缩量只能**量**出来（WindowInsets 在沉浸全屏下报 0，而 parent frame 仍是 138）",
            overlay.contains("private fun calibrateTopOffset(") && overlay.contains("lastRawTopPx"),
        )
    }

    /**
     * 老 API `setTopOffsetPx`（窗口坐标）必须彻底消失。
     *
     * ⚠️ 留着它等于留着一条"同一个字段两种坐标系"的路 —— 新调用点抄谁全凭运气，
     * 而这两者只差 42dp、肉眼一眼看不出错，只会表现成"提示条有点偏低"。改名是为了让
     * 混淆在编译期就过不去。
     */
    @Test
    fun theWindowCoordinateApiIsGone() {
        for (rel in listOf(MANGA_ACT, NOVEL_ACT, OVERLAY)) {
            assertTrue(
                "$rel 里还留着 setTopOffsetPx（窗口坐标口径）—— 会被当成屏幕坐标误用",
                !read(rel).contains("setTopOffsetPx"),
            )
        }
    }
}
