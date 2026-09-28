package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **面板主题守卫**（源码级，纯 JVM）。
 *
 * 为什么加：配色问题反复出现，根因不是"忘了写约定"，而是**约定只是散文、没有机械约束**，
 * 加上两个具体的结构性坑：
 *
 * 1. ⚠️ **同一个控件同时登记进「标题色」和「次要色」两个清单** → 两段 `setTextColor` 先后执行，
 *    **后写的那份赢** → 控件静默变成次要色（「翻译模型最大同时请求数」的标题就这样被反复改回淡色；
 *    我修了一次、又被别人的写入盖回去一次）。→ [同一控件不许同时出现在两个配色清单里]
 * 2. ⚠️ **布局里新增了控件、但忘了登记进清单** → 深色面板下它保持布局默认的深色字，压在同色背景上看不见。
 *    → [面板里的文字控件必须登记进配色清单]
 *
 * 面板的深浅跟的是**阅读背景**（`ReaderMenuSheet.darkPanel` / `NovelPanelSheet.darkPanel`），
 * 不是系统主题；弹窗那一侧由 `ReaderThemeGuardTest` + `ReaderDialogs` 守。
 */
class PanelThemeGuardTest {

    private val srcRoot = File("src/main/java/com/moe/starflow")
    private val layoutRoot = File("src/main/res/layout")

    private val panels = listOf(
        Triple(
            "mangaimport/reader/ReaderMenuSheet.kt",
            "sheet_reader_menu.xml",
            "漫画面板",
        ),
        Triple(
            "novel/reader/NovelPanelSheet.kt",
            "sheet_novel_menu.xml",
            "文本面板",
        ),
    )

    /**
     * 抽某个 `.setTextColor(xxx)` 所对应的那个 `listOf(...)` 里的所有 `R.id.xxx`。
     *
     * ⚠️ 必须**从每个 setTextColor 向前找最近的 listOf(**：直接写一个跨块的贪婪正则会把
     * 文件里别的 `listOf(`（点击映射表等）也算进来 → 满屏假阳性（第一版就这么错的）。
     */
    /**
     * 读两个面板里 **BEGIN/END 标记之间**的配色清单。
     *
     * ⚠️ 早先版本直接解析 `listOf(...).forEach {...}` 源码 → 写错三版（跨块贪婪、向前找最近的
     * `listOf(`、中间游离的 setTextColor 带偏）都是假阳性。现在清单本身被提成 `intArrayOf` 字段 +
     * 标记注释，测试只读标记之间的内容 —— **解析不再依赖代码风格**。
     */
    private fun idsBetweenMarkers(source: String, marker: String): Set<String> {
        val begin = source.indexOf("// PANEL_THEME_${marker}_IDS_BEGIN")
        val end = source.indexOf("// PANEL_THEME_${marker}_IDS_END")
        if (begin < 0 || end < 0 || end <= begin) {
            throw AssertionError(
                "面板缺少 PANEL_THEME_${marker}_IDS 标记（配色清单必须写成 intArrayOf 字段并保留标记，" +
                    "见 ReaderMenuSheet/NovelPanelSheet）",
            )
        }
        return Regex("""R\.id\.(\w+)""")
            .findAll(source.substring(begin, end))
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `同一控件不许同时出现在两个配色清单里`() {
        val problems = mutableListOf<String>()
        panels.forEach { (sheetRel, _, name) ->
            val src = File(srcRoot, sheetRel).readText()
            val label = idsBetweenMarkers(src, "LABEL")
            val sub = idsBetweenMarkers(src, "SUB")
            assertTrue("$name：没能解析出配色清单（正则失配，测试本身要修）", label.isNotEmpty() && sub.isNotEmpty())
            val both = label intersect sub
            if (both.isNotEmpty()) {
                problems += "$name：${both.joinToString()} —— 同时在「标题色」与「次要色」清单里，" +
                    "两段 setTextColor 后写的赢 → 控件会静默变成次要色（淡色）"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `面板里的文字控件必须登记进配色清单`() {
        // 容器 / 分段选中圈 / Tab 图标等由别的机制处理，显式豁免（新增豁免要在这里写清楚理由）
        val exemptPrefixes = listOf("panel_", "row_", "seg_", "sel_", "sb_", "sw_", "rv_", "btn_", "iv_", "batch_group_content", "tv_webtoon_translated_badge")
        val problems = mutableListOf<String>()
        panels.forEach { (sheetRel, layoutRel, name) ->
            val src = File(srcRoot, sheetRel).readText()
            val layout = File(layoutRoot, layoutRel).readText()
            // 只查 TextView（它们必须有明确颜色），并且排除已豁免前缀
            Regex("""<TextView[^>]*?android:id="@\+id/(\w+)"""")
                .findAll(layout)
                .map { it.groupValues[1] }
                .filter { id -> exemptPrefixes.none { id.startsWith(it) } }
                .forEach { id ->
                    if (!src.contains("R.id.$id")) problems += "$name：tv 控件 $id 未在 ${File(sheetRel).name} 里登记"
                }
        }
        assertTrue(
            problems.joinToString("\n") +
                "\n（新增 TextView 必须进 applyPanelTheme 的 labelColor/subColor 其中之一；" +
                "确实不需要的要在本测试的豁免清单里写明）",
            problems.isEmpty(),
        )
    }
}
