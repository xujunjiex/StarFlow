package com.moe.starflow.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **阅读器配色守卫**（纯源码级，不依赖 Android）。
 *
 * 背景：阅读器的深浅跟的是**阅读背景**，不是系统主题。早先的弹窗做法是
 * 「换窗口底 + 把文字刷浅」—— 而 `AlertDialog` 的**面板底由主题给**（系统浅色时是白的），
 * 窗口底被面板盖住，字却被刷浅 → **白底白字，完全看不见**
 * （用户 2026-09-28：「背景白色文字你也搞成白色我怎么看？？我不希望再出现色彩主题搭配的显示问题！」）。
 *
 * 现在唯一正确做法：**用 `ReaderDialogs.context(context, dark)` 把 uiMode 钉成对应模式再建弹窗**，
 * 面板/文字/按钮统一由主题给色。这个测试把这条钉死：
 *
 * 1. 阅读器相关文件里出现 `AlertDialog.Builder(` 时，必须传 `ReaderDialogs.context(...)` 的结果；
 * 2. `ReaderDialogs` 必须真的在改 `uiMode`（防止有人"简化"回刷字色）；
 * 3. 自定义内容视图必须过 `tintCustomView`。
 */
class ReaderThemeGuardTest {

    private val root = File("src/main/java/com/moe/starflow")

    /** 阅读器 / 面板相关的源码文件（弹窗都在这几个里）。 */
    private val readerFiles = listOf(
        "mangaimport/reader/MangaReaderActivity.kt",
        "mangaimport/reader/ReaderMenuSheet.kt",
        "mangaimport/reader/ReaderChapterDialog.kt",
        "mangaimport/reader/ReaderPagePreviewDialog.kt",
        "novel/reader/NovelReaderActivity.kt",
        "novel/reader/NovelPanelSheet.kt",
        "novel/reader/NovelTocDialog.kt",
    )

    private fun read(rel: String): String {
        val f = File(root, rel)
        assertTrue("源码文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `阅读器里的 AlertDialog 必须用对应 night 模式的上下文构建`() {
        val offenders = mutableListOf<String>()
        readerFiles.forEach { rel ->
            val src = read(rel)
            // 允许两种写法：内联 `ReaderDialogs.context(...)`，或先 `val x = ReaderDialogs.context(...)` 再传 x
            val themedVars = Regex("""val\s+(\w+)\s*=\s*ReaderDialogs\.context\(""")
                .findAll(src).map { it.groupValues[1] }.toSet()
            src.lines().forEachIndexed { i, line ->
                if (!line.contains("AlertDialog.Builder(")) return@forEachIndexed
                val ok = line.contains("ReaderDialogs.context(") ||
                    themedVars.any { line.contains("AlertDialog.Builder($it)") }
                if (!ok) offenders += "$rel:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue(
            "以下弹窗没有用 ReaderDialogs.context(...) 建（= 面板底会跟系统主题，字色却跟阅读背景 " +
                "→ 会出现白底白字）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `ReaderDialogs 必须是强制 uiMode，而不是刷字色`() {
        val src = read("utils/ReaderDialogs.kt")
        assertTrue(
            "ReaderDialogs 必须用 Configuration 覆盖 uiMode（否则 AlertDialog 的面板底与文字色来源不一致）",
            src.contains("UI_MODE_NIGHT_YES") && src.contains("createConfigurationContext"),
        )
        // ⚠️ 裸 createConfigurationContext 会让 AppCompat 抛
        // "You need to use a Theme.AppCompat theme"（线上崩过一次，栈顶就是 ReaderDialogs.context）
        assertTrue(
            "ReaderDialogs.context() 必须把配置上下文再套 ContextThemeWrapper（否则 AppCompat 认不出主题 → 弹窗必崩）",
            src.contains("ContextThemeWrapper") && src.contains("themeResIdOf"),
        )
        assertTrue("ReaderDialogs 必须提供 context()/style()/show() 三个入口", 
            src.contains("fun context(") && src.contains("fun style(") && src.contains("fun show("))
    }

    @Test
    fun `自定义内容视图必须过 tintCustomView`() {
        // 这两个弹窗用 XML 里写死颜色的自定义视图，必须按亮度翻转
        assertTrue(
            "下载弹窗（dialog_reader_download）的文字/分割线颜色写死在 XML 里，必须过 tintCustomView",
            read("mangaimport/reader/MangaReaderActivity.kt").contains("ReaderDialogs.tintCustomView(view, dark)"),
        )
        assertTrue(
            "缩略图预览弹窗（dialog_page_preview）同理必须过 tintCustomView",
            read("mangaimport/reader/ReaderPagePreviewDialog.kt").contains("ReaderDialogs.tintCustomView(view, dark)"),
        )
    }
}
