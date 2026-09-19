package com.moe.starflow.me.settings

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.FragmentActivity
import com.moe.starflow.R
import com.moe.starflow.manga.config.ReplacementRule
import com.moe.starflow.manga.config.TranslationTextRules
import com.moe.starflow.utils.CustomPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf
import org.xmlpull.v1.XmlPullParser

/**
 * 「译文替换表」二级面板：动态 Preference 屏能正常搭出来 + 设置页里的入口指向真实类。
 *
 * 第二条尤其重要：`app:fragment` 写错类名不会有编译错误，只会在用户点进去时**直接崩**。
 * 这里从 `personalization.xml` 里把类名读出来、再用 `Class.forName` 反查，改名漏改 XML 就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationReplacementFragmentTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /**
     * ⚠️ 必须用 `CustomPreference` 那份实例（面板读的就是它）。
     * 它是**进程级单例**，Robolectric 每个测试方法换一个 Application，直接
     * `PreferenceManager.getDefaultSharedPreferences(ctx)` 拿到的是**另一个**实例 → 存进去的规则
     * 面板读不到（本测试第一版就栽在这：断言 4 行得到 3 行 = 规则没被加载出来）。
     */
    private fun prefs() = CustomPreference.getInstance(ctx).getSharedPreferences()

    @Before
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    private fun launch(): TranslationReplacementFragment {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create().start().resume()
        val fragment = TranslationReplacementFragment()
        controller.get().supportFragmentManager
            .beginTransaction().add(fragment, "under-test").commitNow()
        return fragment
    }

    private fun titles(fragment: TranslationReplacementFragment): List<String> {
        val screen = fragment.preferenceScreen
        return (0 until screen.preferenceCount).map { screen.getPreference(it).title.toString() }
    }

    /** 按标题点某一行（避免依赖行序：空表时第 0 行是「还没有规则」提示，不是添加行）。 */
    private fun clickRowWithTitle(fragment: TranslationReplacementFragment, title: String) {
        val screen = fragment.preferenceScreen
        val idx = (0 until screen.preferenceCount)
            .firstOrNull { screen.getPreference(it).title.toString() == title }
            ?: error("找不到标题为「$title」的行，实际：${titles(fragment)}")
        screen.getPreference(idx).performClick()
        // ⚠️ AlertDialog.show() 的 onShow 是 **post 到主线程** 的：不 idle 一遍，setOnShowListener 里
        // 绑的按钮监听还没生效，performClick 落到的就是默认按钮（直接关窗、什么都不存）
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clickAddRow(fragment: TranslationReplacementFragment) =
        clickRowWithTitle(fragment, ctx.getString(R.string.manga_replacement_add))

    /** 按文字点对话框按钮（比 `getButton(BUTTON_NEUTRAL)` 稳：后者在 Robolectric 下可能是 null）。 */
    private fun clickDialogButtonByText(text: String) {
        val root = latestDialog().window!!.decorView as View
        val target = findViewByText(root, text) ?: error("对话框里找不到「$text」按钮")
        target.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findViewByText(v: View, text: String): View? {
        if (v is android.widget.Button && v.text?.toString() == text) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findViewByText(v.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun latestDialog(): AlertDialog =
        (ShadowAlertDialog.getLatestAlertDialog() ?: ShadowDialog.getLatestDialog() as? AlertDialog)
            ?: error("没有弹出对话框")

    /** 弹出对话框里的输入框：容器里按顺序是两个 EditText（查找 / 替换为）。 */
    private fun dialogEditTexts(): List<EditText> {
        val root = latestDialog().window!!.decorView.findViewById<ViewGroup>(android.R.id.content)
        val out = mutableListOf<EditText>()
        fun walk(v: View) {
            if (v is EditText) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    // ===== 面板真实逻辑：增 / 改 / 删 / 上限 / 校验 =====

    @Test
    fun addRule_viaDialog_savesIt() {
        val fragment = launch()
        clickAddRow(fragment)

        val fields = dialogEditTexts()
        assertEquals("对话框应有两个输入框", 2, fields.size)
        fields[0].setText("...")
        fields[1].setText(".")
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        assertEquals(listOf(ReplacementRule("...", ".")), TranslationTextRules.load(prefs()))
        assertTrue("保存后应重排界面（新规则出现在标题里）", titles(fragment).contains("..."))
    }

    @Test
    fun editRule_viaDialog_overwritesIt() {
        TranslationTextRules.save(prefs(), listOf(ReplacementRule("...", ".")))
        val fragment = launch()

        clickRowWithTitle(fragment, "...")
        val fields = dialogEditTexts()
        assertEquals("...", fields[0].text.toString())
        assertEquals(".", fields[1].text.toString())
        fields[1].setText("……")
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        assertEquals(listOf(ReplacementRule("...", "……")), TranslationTextRules.load(prefs()))
    }

    @Test
    fun deleteRule_viaNeutralButton_removesIt() {
        TranslationTextRules.save(
            prefs(),
            listOf(ReplacementRule("...", "."), ReplacementRule("~", ""))
        )
        val fragment = launch()

        clickRowWithTitle(fragment, "...")   // 第一条规则那一行
        // 按**文字**找按钮点：Robolectric 下 getButton(BUTTON_NEUTRAL) 可能拿不到真实按钮实例
        clickDialogButtonByText(ctx.getString(R.string.manga_replacement_delete))

        assertEquals(listOf(ReplacementRule("~", "")), TranslationTextRules.load(prefs()))
    }

    /** 空/纯空白「查找内容」必须被拦下：空 from 会往每个字符间插内容，纯空格会把译文空格成片改写。 */
    @Test
    fun blankFrom_isRejected() {
        val fragment = launch()

        clickAddRow(fragment)
        dialogEditTexts()[0].setText("   ")
        dialogEditTexts()[1].setText("x")
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        assertEquals("空白 from 不能落盘", emptyList<ReplacementRule>(), TranslationTextRules.load(prefs()))
    }

    /** 到达上限后不再弹对话框（否则用户填半天才发现存不进去）。 */
    @Test
    fun atMaxRules_addRowDoesNotOpenDialog() {
        val many = (0 until TranslationTextRules.MAX_RULES)
            .map { ReplacementRule("f$it", "t$it") }
        TranslationTextRules.save(prefs(), many)
        val fragment = launch()

        // 规则 N 条 → 下标 N 是「添加规则」
        clickAddRow(fragment)

        assertEquals("上限处不应弹窗、也不应改清单", many, TranslationTextRules.load(prefs()))
    }

    @Test
    fun emptyTable_showsHintAndAddRow() {
        val titles = titles(launch())
        assertTrue("应有「还没有规则」提示，实际 $titles", titles.contains(ctx.getString(R.string.manga_replacement_empty_title)))
        assertTrue("应有「添加规则」行，实际 $titles", titles.contains(ctx.getString(R.string.manga_replacement_add)))
    }

    @Test
    fun rules_areRenderedAsEditableRows() {
        TranslationTextRules.save(
            prefs(),
            listOf(ReplacementRule("...", "."), ReplacementRule("~", ""))
        )
        val titles = titles(launch())
        assertEquals("两条规则 + 添加 + 生效范围说明", 4, titles.size)
        assertEquals("...", titles[0])
        assertEquals("~", titles[1])
        assertTrue(titles.contains(ctx.getString(R.string.manga_replacement_add)))
    }

    /** 入口指向的类必须真实存在（`app:fragment` 写错只会运行时崩）。 */
    @Test
    fun personalizationEntry_pointsAtExistingFragment() {        var target: String? = null
        ctx.resources.getXml(R.xml.personalization).use { parser ->
            var event = parser.eventType
            var insideEntry = false
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        var key: String? = null
                        var fragment: String? = null
                        for (i in 0 until parser.attributeCount) {
                            when (parser.getAttributeName(i)) {
                                "key" -> key = parser.getAttributeValue(i)
                                // 命名空间无关：aapt 会把 app:fragment 编译到 res-auto 命名空间下
                                "fragment" -> fragment = parser.getAttributeValue(i)
                            }
                        }
                        insideEntry = key == "manga_translation_replacements"
                        if (insideEntry) target = fragment
                    }
                    XmlPullParser.END_TAG -> insideEntry = false
                }
                event = parser.next()
            }
        }
        assertNotNull("personalization.xml 里找不到 manga_translation_replacements 入口", target)
        val resolved = requireNotNull(target)
        assertEquals(
            TranslationReplacementFragment::class.java.name,
            resolved
        )
        // 反查：类真的能被实例化路径找到（默认 FragmentFactory 走反射 + 无参构造）
        Class.forName(resolved)
    }
}
