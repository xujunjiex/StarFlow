package com.moe.starflow.manga.engine

import android.content.Context
import androidx.preference.PreferenceManager
import com.moe.starflow.R
import com.moe.starflow.manga.config.MangaModeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「识别自由文字」（`Manga_Keep_Text_Free`）在**非分批路径**上的回归守卫。
 *
 * 背景（真实反馈）：阅读器用 RT-DETR-V2 + manga-ocr 翻译时自由文字（旁白/音效）从来不出现，
 * 看起来像「设置里那个开关被自动关了」。根因是 `DetectionBridge.runOCR` 的 RT 分支漏传
 * `keepTextFree`，走了 `detectWithRTDetrV2` 的默认值 `false`。而分批路径（气泡 > 6）是正常传的，
 * 所以表现为「同一页有时有旁白、有时没有」。
 *
 * 这里钉死三条契约：
 * 1. 不传（null）⇒ 读设置；
 * 2. 设置未写过 ⇒ 默认**开**（与 `MangaModeConfig.keepTextFree` 的 data class 默认一致）；
 * 3. 显式传入优先（分批路径把配置快照带过来时以它为准）。
 * 外加一条：设置页 XML 里的 key 必须就是那个常量（改名会静默断开开关，这里会红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectionBridgeKeepTextFreeTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    @Test
    fun unsetPref_defaultsToOn() {
        assertTrue(
            "设置从没写过时必须默认开启（否则自由文字会被静默丢掉）",
            DetectionBridge.resolveKeepTextFree(context, null)
        )
    }

    @Test
    fun nullReadsPref() {
        prefs().edit().putBoolean(MangaModeConfig.KEY_KEEP_TEXT_FREE, false).commit()
        assertFalse(DetectionBridge.resolveKeepTextFree(context, null))

        prefs().edit().putBoolean(MangaModeConfig.KEY_KEEP_TEXT_FREE, true).commit()
        assertTrue(DetectionBridge.resolveKeepTextFree(context, null))
    }

    @Test
    fun explicitValueWinsOverPref() {
        prefs().edit().putBoolean(MangaModeConfig.KEY_KEEP_TEXT_FREE, true).commit()
        assertFalse(DetectionBridge.resolveKeepTextFree(context, false))

        prefs().edit().putBoolean(MangaModeConfig.KEY_KEEP_TEXT_FREE, false).commit()
        assertTrue(DetectionBridge.resolveKeepTextFree(context, true))
    }

    /** `MangaModeConfig` 的 data class 默认值必须与 prefs 默认值一致（CLAUDE.md 的「两个默认值」）。 */
    @Test
    fun dataClassDefaultMatchesPrefDefault() {
        assertEquals(
            DetectionBridge.resolveKeepTextFree(context, null),
            MangaModeConfig().keepTextFree
        )
    }

    /** 设置页 personalization.xml 必须用同一个 key（改名/挪位置会静默断开开关）。 */
    @Test
    fun settingsScreenBindsSameKey() {
        val keys = mutableSetOf<String>()
        context.resources.getXml(R.xml.personalization).use { parser ->
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    parser.getAttributeValue(
                        "http://schemas.android.com/apk/res/android", "key"
                    )?.let { keys.add(it) }
                }
                event = parser.next()
            }
        }
        assertTrue(
            "personalization.xml 里没有 ${MangaModeConfig.KEY_KEEP_TEXT_FREE}（键：$keys）",
            keys.contains(MangaModeConfig.KEY_KEEP_TEXT_FREE)
        )
    }
}
