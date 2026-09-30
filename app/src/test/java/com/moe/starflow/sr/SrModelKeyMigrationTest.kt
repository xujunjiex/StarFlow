package com.moe.starflow.sr

import android.content.Context
import com.moe.starflow.download.ModelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `sr_active_model_key` 的档位迁移守卫（2026-10 upconv_7 精简）。
 *
 * upconv_7 从 4 档（动漫 M1/N3 + 照片 M1/N3）精简为 2 档（动漫 M1 + 动漫 N2）：
 * 用户 prefs 里存着被下架的档位名时，[SrModelManager.getActiveKey] 必须**就近迁移**
 * 而不是当作"未选择" —— 否则超分开着却没有模型，用户看到的是功能坏了。
 */
@RunWith(RobolectricTestRunner::class)
class SrModelKeyMigrationTest {

    private val prefs get() =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("sr_migration_test", Context.MODE_PRIVATE)

    private fun store(raw: String) {
        prefs.edit().putString(SrModelManager.PREF_ACTIVE_KEY, raw).commit()
    }

    @Test
    fun activeKeyRoundTrips() {
        SrModelManager.setActive(prefs, ModelKey.SR_W2X_UP7_ANIME_N2)
        assertEquals(ModelKey.SR_W2X_UP7_ANIME_N2, SrModelManager.getActiveKey(prefs))
    }

    /** 旧的「极强降噪 N3」→ 现行的「强力降噪 N2」（官方示例档），并回写 prefs。 */
    @Test
    fun legacyN3MigratesToN2() {
        store("SR_W2X_UP7_ANIME_N3")
        assertEquals(ModelKey.SR_W2X_UP7_ANIME_N2, SrModelManager.getActiveKey(prefs))
        assertEquals(
            "迁移结果要回写，别每次读都再迁一遍",
            ModelKey.SR_W2X_UP7_ANIME_N2.name,
            prefs.getString(SrModelManager.PREF_ACTIVE_KEY, null),
        )
    }

    /** 照片族已下架：不降噪 → 动漫不降噪，极强降噪 → 动漫强力降噪。 */
    @Test
    fun legacyPhotoKeysMigrateToAnimeTiers() {
        store("SR_W2X_UP7_PHOTO_M1")
        assertEquals(ModelKey.SR_W2X_UP7_ANIME_M1, SrModelManager.getActiveKey(prefs))
        store("SR_W2X_UP7_PHOTO_N3")
        assertEquals(ModelKey.SR_W2X_UP7_ANIME_N2, SrModelManager.getActiveKey(prefs))
    }

    /** 完全不认识的值仍然是「未选择」（走得通 [SrOutcome] 的 no-model 分支）。 */
    @Test
    fun unknownKeyStaysNull() {
        store("NOT_A_MODEL_KEY")
        assertNull(SrModelManager.getActiveKey(prefs))
    }

    /** upconv_7 族只剩「不降噪 + 强力降噪」两档（用户口径：一个降噪、一个不降噪就够）。 */
    @Test
    fun upconvFamilyIsTrimmedToTwoTiers() {
        val up7 = SrModelManager.allKeys.filter { it.name.startsWith("SR_W2X_UP7_") }
        assertEquals(
            listOf(ModelKey.SR_W2X_UP7_ANIME_M1, ModelKey.SR_W2X_UP7_ANIME_N2),
            up7,
        )
    }

    /** 每一档都必须有显示名（漏了名字在设置页/下载通知里就是空标题）。 */
    @Test
    fun everyKeyHasDisplayName() {
        for (key in SrModelManager.allKeys) {
            assertNotEquals("$key 缺显示名", 0, SrModelManager.nameResOf(key))
        }
    }
}