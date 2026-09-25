package com.moe.starflow.manga.config

import android.content.Context
import androidx.preference.PreferenceManager
import com.moe.starflow.manga.engine.DetectionBridge
import com.moe.starflow.manga.types.DetEngine
import com.moe.starflow.manga.types.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「RT-DETR 渲染方向」设置守卫。
 *
 * 存在意义：这条设置是**用"不做判断"换正确率**（RT-DETR 只给矩形气泡框，`h > w` 猜方向对
 * 多列竖排的宽气泡必反）。它有三条容易写错的契约：
 * ① 默认竖排右→左，坏值也回退竖排（不能出现"没设过就变成横排"）；
 * ② **只对 RT-DETR-V2 生效**：PP-OCRv5/v6、ML Kit 仍用 `Manga_Text_Direction`（PP 自己判横竖）；
 * ③ RT 路径连"竖排方向=左→右"都不看（日文竖排恒右→左）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RtTextDirectionTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(ctx)

    @Test
    fun `prefs 值解析：只有 1 是横排，其余全是竖排右到左`() {
        assertEquals(TextDirection.VERTICAL_RL, RtTextDirection.fromPref(null))
        assertEquals(TextDirection.VERTICAL_RL, RtTextDirection.fromPref(""))
        assertEquals(TextDirection.VERTICAL_RL, RtTextDirection.fromPref(RtTextDirection.VALUE_VERTICAL_RL))
        assertEquals(TextDirection.HORIZONTAL, RtTextDirection.fromPref(RtTextDirection.VALUE_HORIZONTAL))
        // 坏值（手改备份 / 老版本残留）→ 竖排，不能变成横排
        assertEquals(TextDirection.VERTICAL_RL, RtTextDirection.fromPref("vertical"))
        assertEquals(TextDirection.VERTICAL_RL, RtTextDirection.fromPref("2"))
    }

    @Test
    fun `load：未设置默认竖排右到左，设置后读回横排`() {
        prefs.edit().remove(RtTextDirection.KEY).commit()
        assertEquals(TextDirection.VERTICAL_RL, RtTextDirection.load(prefs))

        prefs.edit().putString(RtTextDirection.KEY, RtTextDirection.VALUE_HORIZONTAL).commit()
        assertEquals(TextDirection.HORIZONTAL, RtTextDirection.load(prefs))

        prefs.edit().putString(RtTextDirection.KEY, RtTextDirection.VALUE_VERTICAL_RL).commit()
        assertEquals(TextDirection.VERTICAL_RL, RtTextDirection.load(prefs))
    }

    @Test
    fun `resolve：只有 RT-DETR 用 RT 方向，PP 与 ML Kit 用竖排方向`() {
        // RT：设置优先，竖排方向（LR）不影响
        assertEquals(
            TextDirection.HORIZONTAL,
            RtTextDirection.resolve(DetEngine.RT_DETR_V2, TextDirection.HORIZONTAL, TextDirection.VERTICAL_LR)
        )
        assertEquals(
            TextDirection.VERTICAL_RL,
            RtTextDirection.resolve(DetEngine.RT_DETR_V2, TextDirection.VERTICAL_RL, TextDirection.VERTICAL_LR)
        )
        // PP：用竖排方向；RT 设置整体忽略
        assertEquals(
            TextDirection.VERTICAL_LR,
            RtTextDirection.resolve(DetEngine.PP_OCR_V5, TextDirection.HORIZONTAL, TextDirection.VERTICAL_LR)
        )
        assertEquals(
            TextDirection.VERTICAL_LR,
            RtTextDirection.resolve(DetEngine.PP_OCR_V6, TextDirection.HORIZONTAL, TextDirection.VERTICAL_LR)
        )
        assertEquals(
            TextDirection.VERTICAL_RL,
            RtTextDirection.resolve(DetEngine.MLKIT, TextDirection.HORIZONTAL, TextDirection.VERTICAL_RL)
        )
    }

    @Test
    fun `renderTextDirection 扩展：MangaModeConfig 按引擎取方向`() {
        val rt = MangaModeConfig(
            detEngine = DetEngine.RT_DETR_V2,
            textDirection = TextDirection.VERTICAL_LR,
            rtTextDirection = TextDirection.VERTICAL_RL,
        )
        assertEquals(TextDirection.VERTICAL_RL, rt.renderTextDirection)

        val pp = MangaModeConfig(
            detEngine = DetEngine.PP_OCR_V6,
            textDirection = TextDirection.VERTICAL_LR,
            rtTextDirection = TextDirection.HORIZONTAL,
        )
        assertEquals(TextDirection.VERTICAL_LR, pp.renderTextDirection)

        // data class 默认值 = 竖排右→左（新装用户/漏传参数都不会变横排）
        assertEquals(TextDirection.VERTICAL_RL, MangaModeConfig().rtTextDirection)
    }

    @Test
    fun `DetectionBridge 解析：显式优先，否则读设置且默认竖排`() {
        // 显式优先（调用方传了 config.rtTextDirection 就用它）
        assertEquals(
            TextDirection.HORIZONTAL,
            DetectionBridge.resolveRtTextDirection(ctx, TextDirection.HORIZONTAL)
        )
        // 未传 → 读设置；未设置 → 竖排右→左
        prefs.edit().remove(RtTextDirection.KEY).commit()
        assertEquals(TextDirection.VERTICAL_RL, DetectionBridge.resolveRtTextDirection(ctx, null))
        // 设置成横排 → 读回横排
        prefs.edit().putString(RtTextDirection.KEY, RtTextDirection.VALUE_HORIZONTAL).commit()
        assertEquals(TextDirection.HORIZONTAL, DetectionBridge.resolveRtTextDirection(ctx, null))
        // 清理，避免污染同进程其它测试
        prefs.edit().remove(RtTextDirection.KEY).commit()
    }

    /**
     * 设置页绑定：key 用常量、默认值 = 竖排、只有两项，且**紧跟在「识别自由文字」下面**。
     *
     * 位置是用户明确要求的（同一类的 RT 设置放一起）；key 写错或默认值写成横排都会静默改变行为。
     */
    @Test
    fun `personalization_xml 绑定同一个 key 且排在识别自由文字下面`() {
        val keys = mutableListOf<String>()
        val defaults = mutableMapOf<String, String?>()
        ctx.resources.getXml(com.moe.starflow.R.xml.personalization).use { parser ->
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    parser.getAttributeValue("http://schemas.android.com/apk/res/android", "key")?.let {
                        keys.add(it)
                        defaults[it] = parser.getAttributeValue(
                            "http://schemas.android.com/apk/res/android", "defaultValue"
                        )
                    }
                }
                event = parser.next()
            }
        }
        val iKeep = keys.indexOf(MangaModeConfig.KEY_KEEP_TEXT_FREE)
        val iRt = keys.indexOf(RtTextDirection.KEY)
        assertTrue("personalization.xml 里没有 ${RtTextDirection.KEY}（键：$keys）", iRt >= 0)
        assertTrue("「识别自由文字」没找到，位置断言失效（键：$keys）", iKeep >= 0)
        assertEquals(
            "本项必须紧跟在「识别自由文字」下面",
            iKeep + 1, iRt
        )
        assertEquals(
            "默认值必须是竖排右→左（${RtTextDirection.VALUE_VERTICAL_RL}）",
            RtTextDirection.VALUE_VERTICAL_RL, defaults[RtTextDirection.KEY]
        )
        // 两态、且值能反解回方向（数组与解析必须一致）
        val values = ctx.resources.getStringArray(com.moe.starflow.R.array.manga_rt_text_direction_values)
        val entries = ctx.resources.getStringArray(com.moe.starflow.R.array.manga_rt_text_direction_entries)
        assertEquals(2, values.size)
        assertEquals(values.size, entries.size)
        assertEquals(
            listOf(TextDirection.VERTICAL_RL, TextDirection.HORIZONTAL),
            values.map { RtTextDirection.fromPref(it) }
        )
    }
}
