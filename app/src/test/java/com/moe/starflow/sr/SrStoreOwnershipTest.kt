package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 超分产物的**归属**守卫（2026-10 审查发现）。
 *
 * 复现路径（漫画 id 会被复用，代码里到处写着这条）：
 * ```
 * 书架有 id=7（addedAt=1000）→ 给它超分了几页，落盘 sr_cache/7_0.webp + 7_0.json
 * 删掉这本书（ShelfCleanup.deleteManga 是**异步**的）
 * 立刻重新导入一本新书 → 拿到同一个 id=7（addedAt=2000）
 *   · 若异步清理丢失/还在途中 → 旧文件还在
 *   · 只按 `7_0` 取图 → 新书第 1 页渲染出**上一本书的放大页**
 * ```
 * 用户看到的是完全不相干的图，零报错。修法：标记里记身份指纹，读取时校验
 * （与 `imported_page_translation.mangaKey` 同一套口径 —— 那边早就这么防了）。
 */
@RunWith(RobolectricTestRunner::class)
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

    /**
     * 重导入（同 id、新指纹）**覆盖**那一页之后，就归新书了 —— 指纹是跟着每次落盘写的，
     * 不会因为"文件被谁先写过"而锁死。
     */
    @Test
    fun reUpscalingForTheNewBookReclaimsTheSlot() {
        saveForOldBook(page = 0, key = oldBookKey)
        assertFalse(SrStore.exists(ctx, oldBookId, 0, newBookKey))

        // 新书自己重超一次这一页
        SrStore.save(ctx, oldBookId, 0, bmp(), "cunet", newBookKey)

        assertTrue("新书现在认领得到", SrStore.exists(ctx, oldBookId, 0, newBookKey))
        assertEquals("cunet", SrStore.storedModelOrNull(ctx, oldBookId, 0, newBookKey))
        assertFalse("旧书**不能**再认领这一页（指纹已换）", SrStore.exists(ctx, oldBookId, 0, oldBookKey))
    }

    /** 标记缺失/损坏 = 无法证伪 → 不算这本书的（宁可回落原图，也不冒渲染别人页面的风险）。 */
    @Test
    fun missingOrCorruptMarkerIsNotClaimed() {
        saveForOldBook()
        // 把标记删掉（模拟清理/损坏），图还在
        File(srCacheDir(), "${oldBookId}_0.json").delete()
        assertFalse("标记没了就不能认领", SrStore.exists(ctx, oldBookId, 0, oldBookKey))
        assertNull(SrStore.load(ctx, oldBookId, 0, oldBookKey))

        // 再写一份，然后把标记写成坏 JSON
        saveForOldBook()
        File(srCacheDir(), "${oldBookId}_0.json").writeText("{ not json")
        assertFalse("标记损坏也不能认领", SrStore.exists(ctx, oldBookId, 0, oldBookKey))
    }

    /** 没有指纹就不该落盘（写出来也没人能认领，还会占缓存额度）。 */
    @Test
    fun saveWithoutFingerprintIsRefusedAndLeavesNoOrphan() {
        assertFalse("空指纹必须被拒", SrStore.save(ctx, oldBookId, 0, bmp(), "upconv7", ""))
        assertFalse("空指纹也认领不了", SrStore.exists(ctx, oldBookId, 0, ""))
        assertFalse(
            "不许留下认领不了的孤儿文件",
            File(srCacheDir(), "${oldBookId}_0.webp").isFile,
        )
    }
}
