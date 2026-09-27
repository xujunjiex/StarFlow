package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译浮层组显示规格的守卫（用户口径，别再改错）。
 *
 * 这一块反复出过问题：
 * - 三态按钮按**整章**判过 → 翻到没译文的地方按钮还在，点了看不出变化
 * - 翻译按钮的图标按**整章**有没有译文判过（漫画那套「缓存命中就变重翻」）→
 *   本页一句译文都没有，按钮却是刷新图标
 * - 「都翻译过了」若把短行/图片也算进去 → 含 `……` 的页永远判不出翻完
 */
class NovelTranslateChromeTest {

    private fun para(index: Int, text: String, type: NovelParagraphType = NovelParagraphType.TEXT) =
        NovelParagraph(index, type, text)

    /** 短行（< 4 字）与图片标记**按设计永不翻译**，不能算进「待翻译」。 */
    @Test
    fun `短句与图片不算可翻译段`() {
        assertFalse(para(0, "……", NovelParagraphType.SKIP).isTranslatable())
        assertFalse(para(0, "嗯。", NovelParagraphType.SKIP).isTranslatable())
        assertFalse(para(0, "📷 [图片] 插图", NovelParagraphType.IMAGE).isTranslatable())
        assertFalse(para(0, "   ").isTranslatable())
        assertTrue(para(0, "He lit the lamp at dusk.").isTranslatable())
    }

    /**
     * 「清除译文」按钮的判据是**选中集里有没有译过的段**，与三态那套（当前屏幕有没有译文）不同：
     * 清除是对选中集动手，选中几段没翻过的段时不该出现（清无可清）。
     */
    @Test
    fun `选中的段里有译文才显示清除按钮`() {
        assertFalse(NovelTranslateChrome.showClear(translatedSelected = 0))
        assertTrue(NovelTranslateChrome.showClear(translatedSelected = 1))
        assertTrue(NovelTranslateChrome.showClear(translatedSelected = 5))
    }

    /** 页面还有没翻的段 → 翻译按钮（普通「翻译」语义）。 */
    @Test
    fun `页里还有没翻的段就有翻译按钮`() {
        assertEquals(NovelTranslateAction.TRANSLATE, NovelTranslateChrome.actionFor(untranslated = 3))
        assertEquals(NovelTranslateAction.TRANSLATE, NovelTranslateChrome.actionFor(untranslated = 1))
    }

    /** 页面全翻完了 → 没有可点的翻译按钮（重翻走长按选择，不做成按钮）。 */
    @Test
    fun `页里全翻完了就不显示翻译按钮`() {
        assertNull(NovelTranslateChrome.actionFor(untranslated = 0))
    }

    /** 三态按钮：当前视野有译文才显示。 */
    @Test
    fun `有译文才显示三态按钮`() {
        assertTrue(NovelTranslateChrome.showToggle(translated = 1))
        assertFalse(NovelTranslateChrome.showToggle(translated = 0))
    }

    /** 选择模式：选中集决定语义，与页面/整章的译文状态无关。 */
    @Test
    fun `选中集决定翻译按钮的语义`() {
        assertEquals(
            "全没翻 → 翻译",
            NovelTranslateAction.TRANSLATE,
            NovelTranslateChrome.actionForSelection(untranslated = 3, translated = 0),
        )
        assertEquals(
            "全翻过 → 重新翻译",
            NovelTranslateAction.RETRANSLATE,
            NovelTranslateChrome.actionForSelection(untranslated = 0, translated = 2),
        )
        assertEquals(
            "混合 → 翻译+重翻",
            NovelTranslateAction.TRANSLATE_AND_RETRANSLATE,
            NovelTranslateChrome.actionForSelection(untranslated = 2, translated = 1),
        )
    }

    /** 进了选择模式但一段都没选 → 退回普通语义（由调用方用 actionFor 算）。 */
    @Test
    fun `没选中任何段时没有选择语义`() {
        assertNull(NovelTranslateChrome.actionForSelection(untranslated = 0, translated = 0))
    }
}
