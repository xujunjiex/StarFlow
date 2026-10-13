package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 书籍 id 被复用时，不同身份指纹的超分结果必须隔离，删除也只能作用于所属书籍。 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [SrStoreTestOs::class])
class SrStoreOwnershipTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val oldBookId = 7L
    private val oldBookKey = "1000"      // = ImportedManga.translationKey（addedAt）
    private val newBookKey = "2000"

    private fun bmp(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private fun srCacheDir(): File =
        File(ctx.getExternalFilesDir(null), SrStore.DIR_NAME).apply { mkdirs() }

    @org.junit.Before
    fun setUp() {
        // 每个用例干净起步（SR 缓存是真实目录，跨用例会残留）
        srCacheDir().listFiles()?.forEach { it.delete() }
    }

    private fun saveForOldBook(page: Int = 0, key: String = oldBookKey): Boolean =
        SrStore.save(ctx, oldBookId, page, bmp(), "upconv7", key)

    /** 指纹不同 = **不是这本书的**，一律当作没有超分结果。 */
    @Test
    fun resultSavedByAnotherBookIsNotVisibleToTheNewBook() {
        assertTrue("先替旧书写一份", saveForOldBook())

        assertTrue("旧书自己认得出来", SrStore.exists(ctx, oldBookId, 0, oldBookKey))
        assertFalse("**新书（同 id 不同指纹）必须看不见它**", SrStore.exists(ctx, oldBookId, 0, newBookKey))
        assertNull("新书也不许读到这张图", SrStore.load(ctx, oldBookId, 0, newBookKey))
        assertNull("新书也不该认为这一页已超分", SrStore.storedModelOrNull(ctx, oldBookId, 0, newBookKey))

        // 旧书自己读得到、模型名也对
        assertNotNull(SrStore.load(ctx, oldBookId, 0, oldBookKey))
        assertEquals("upconv7", SrStore.storedModelOrNull(ctx, oldBookId, 0, oldBookKey))
    }

    /** 新书即使复用了 id，保存和清理也不能影响旧书的结果。 */
    @Test
    fun reUpscalingForTheNewBookKeepsBothBooksIsolated() {
        saveForOldBook(page = 0, key = oldBookKey)
        assertFalse(SrStore.exists(ctx, oldBookId, 0, newBookKey))

        // 新书自己重超一次这一页
        SrStore.save(ctx, oldBookId, 0, bmp(), "cunet", newBookKey)

        assertTrue("新书现在认领得到", SrStore.exists(ctx, oldBookId, 0, newBookKey))
        assertEquals("cunet", SrStore.storedModelOrNull(ctx, oldBookId, 0, newBookKey))
        assertTrue("旧书的独立结果仍然保留", SrStore.exists(ctx, oldBookId, 0, oldBookKey))
        assertEquals("upconv7", SrStore.storedModelOrNull(ctx, oldBookId, 0, oldBookKey))

        SrStore.deleteManga(ctx, oldBookId, oldBookKey)
        assertFalse(SrStore.exists(ctx, oldBookId, 0, oldBookKey))
        assertTrue("旧书异步删除不能影响新书", SrStore.exists(ctx, oldBookId, 0, newBookKey))
    }

    /** 标记缺失/损坏 = 无法证伪 → 不算这本书的（宁可回落原图，也不冒渲染别人页面的风险）。 */
    @Test
    fun missingOrCorruptMarkerIsNotClaimed() {
        saveForOldBook()
        // 把标记删掉（模拟清理/损坏），图还在
        markerForOldBook().delete()
        assertFalse("标记没了就不能认领", SrStore.exists(ctx, oldBookId, 0, oldBookKey))
        assertNull(SrStore.load(ctx, oldBookId, 0, oldBookKey))

        // 再写一份，然后把标记写成坏 JSON
        saveForOldBook()
        markerForOldBook().writeText("{ not json")
        assertFalse("标记损坏也不能认领", SrStore.exists(ctx, oldBookId, 0, oldBookKey))
    }

    private fun markerForOldBook(): File = srCacheDir().listFiles().orEmpty().single {
        it.extension == "json" && JSONObject(it.readText()).optString("key") == oldBookKey
    }

    /** 没有指纹就不该落盘，避免留下无归属的文件。 */
    @Test
    fun saveWithoutFingerprintIsRefusedAndLeavesNoOrphan() {
        assertFalse("空指纹必须被拒", SrStore.save(ctx, oldBookId, 0, bmp(), "upconv7", ""))
        assertFalse("空指纹也认领不了", SrStore.exists(ctx, oldBookId, 0, ""))
        assertFalse(
            "不许留下认领不了的孤儿文件",
            srCacheDir().listFiles().orEmpty().isNotEmpty(),
        )
    }
}
