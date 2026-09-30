package com.moe.starflow.mangaimport.translate

import android.content.Context
import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import com.moe.starflow.mangaimport.data.ImportedManga
import com.moe.starflow.sr.SrModelManager
import com.moe.starflow.sr.SrSettings
import com.moe.starflow.sr.SrStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 右下角那三枚超分按钮背后的状态机里，**不依赖真实模型文件**的那几支
 * （`srActionOf` / `hasSrResult`）。
 *
 * ⚠️ `srActionOf` 的五支里这里只覆盖三支。「有结果 ⇄ 切原图」与「换模型要不要重超」
 * 需要先 seed `srModelByPage`，而唯一公开入口 `warmSrBase` 会先过 `resolveBaseKind`
 * → `SuperResolutionEngines.isSrModelUsable`（要求**模型文件真的在**）—— 夹具得先造出
 * 一份像样的 ncnn 模型（`.param` + `.bin`）才走得通，成本大于收益。
 * 那两条不变式仍由 `SrReaderWiringTest.togglingToOriginalNeverClearsTheSrResult`
 * 的**源码级守卫**兜着（"`srBaseFor` 里只允许一处清除、且只能在文件不存在那一支"）。
 */
@RunWith(RobolectricTestRunner::class)
class SrActionStateTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(ctx)

    private val bookId = 1L
    private val addedAt = 1000L          // translationKey = "1000"
    private val page = 0

    private val keyA = SrModelManager.allKeys[0]

    private fun controller() = ReaderTranslationController(
        ctx,
        ImportedManga(
            id = bookId, title = "t", localRoot = "/tmp/x", isArchive = false,
            coverPath = null, pageCount = 3, addedAt = addedAt,
        ),
        CoroutineScope(Dispatchers.Unconfined),
    )

    /** 只做尺寸，不需要真的像素：状态机看的是"有没有结果"，不是内容。 */
    private fun srcPage(): Bitmap = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888)
    private fun upscaledPage(): Bitmap = Bitmap.createBitmap(200, 280, Bitmap.Config.ARGB_8888)

    private fun srCacheDir(): File =
        File(ctx.getExternalFilesDir(null), SrStore.DIR_NAME).apply { mkdirs() }

    @org.junit.Before
    fun setUp() {
        srCacheDir().listFiles()?.forEach { it.delete() }
        SrSettings.setReaderEnabled(prefs, true)
        SrModelManager.setActive(prefs, keyA)
    }

    private fun ReaderTranslationController.bindPage() = bind(
        loadFull = { srcPage() },
        currentPage = { page },
        pageCount = { 3 },
    )

    @Test
    fun srSwitchOff_hidesTheWholeGroup() {
        SrSettings.setReaderEnabled(prefs, false)
        assertEquals(
            "超分关着按钮不该出现（用户口径）",
            ReaderTranslationController.SrAction.HIDDEN,
            controller().srActionOf(page),
        )
    }

    @Test
    fun modelSelectedButNoResult_yet_asksToEnhance() {
        val c = controller().apply { bindPage() }
        assertFalse("还没有结果", c.hasSrResult(page))
        assertEquals(
            "有模型、没结果 → 该超分",
            ReaderTranslationController.SrAction.ENHANCE,
            c.srActionOf(page),
        )
    }

    /**
     * 指纹不符的结果不算数 —— 与 `SrStoreOwnershipTest` 同一条不变式，
     * 这里从"按钮状态"这一侧再钉一遍：别的书（同 id）留下的文件不该让新书出现超分组。
     */
    @Test
    fun resultOwnedByAnotherBookDoesNotCount() {
        // 用**另一个指纹**落盘（模拟删书重导后复用了同一个 id）
        assertTrue(
            "夹具本身要能落盘（否则这条用例是假绿）",
            SrStore.save(ctx, bookId, page, upscaledPage(), keyA.name, "9999"),
        )
        val c = controller().apply { bindPage() }

        assertEquals(
            "不是这本书的 → 当作没超分，去超分即可",
            ReaderTranslationController.SrAction.ENHANCE,
            c.srActionOf(page),
        )
        assertFalse("按钮组也不该出现「已有结果」的那两枚", c.hasSrResult(page))
    }
}
