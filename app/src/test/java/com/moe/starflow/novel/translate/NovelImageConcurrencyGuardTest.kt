package com.moe.starflow.novel.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 插图翻译的**并发/生命周期接线守卫**（源码级，纯 JVM）。
 *
 * ⚠️ 为什么用源码断言：这一批约束全是"**不崩、不报错、单测也照过**"的类型 ——
 * `OcrLock` 自己等自己只是慢 + 报一句假错误；含图批并发只是白等；提示文案残留只是
 * 让你以为还在跑。真要发现它们只有人肉点一遍，而这个功能点一次要几秒到几十秒。
 * 与 `NovelImageEditWiringTest` / `SrRecordSystemWiringTest` 同一手法。
 *
 * 钉住的四条（都来自 2026-10-04 真机反馈与随后的审查）：
 * 1. **含插图的批绝不能在外层持 `OcrLock`**（不可重入 → 自锁 60s → 假"引擎被占用"）
 * 2. **含插图的批之间串行**（队列 `imageBusy` / 整章 `imageGate`）
 * 3. **强制退出要清进度文案 + 复位残留的「翻译中」**
 * 4. **通知栏优先显示"正在识别插图 i/N"**
 */
class NovelImageConcurrencyGuardTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到源码：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    private val queue by lazy { read("src/main/java/com/moe/starflow/novel/translate/NovelTranslationQueue.kt") }
    private val host by lazy { read("src/main/java/com/moe/starflow/novel/translate/NovelChapterJobHost.kt") }
    private val activity by lazy { read("src/main/java/com/moe/starflow/novel/reader/NovelReaderActivity.kt") }
    private val service by lazy { read("src/main/java/com/moe/starflow/translate/batch/TranslationJobService.kt") }
    private val pipeline by lazy { read("src/main/java/com/moe/starflow/novel/translate/NovelImagePipeline.kt") }

    /** 截出函数体（从签名到下一个同级函数）。 */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val rest = source.substring(start + signature.length)
        val next = listOf("\n    private fun ", "\n    internal fun ", "\n    fun ", "\n    override fun ", "\n    suspend fun ")
            .map { rest.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: rest.length
        return rest.substring(0, next)
    }

    /** ① 含图批不持外锁：`holdLock` 必须同时看 `serial` **和** `!batchHasImage`。 */
    @Test
    fun `文本队列不持外层 OCR 锁`() {
        assertFalse("文本队列不能占 OCR/SR 锁", queue.contains("OcrLock.acquire("))
        assertTrue("插图流水线自己拿 OCR 锁", pipeline.contains("OcrLock.acquire("))
        assertTrue("插图原生调用登记执行 Job", pipeline.contains("currentCoroutineContext()[Job]"))
    }

    /** ② 含图批串行：队列 `imageBusy`（按"批内含图"判）+ 整章 `imageGate`。 */
    @Test
    fun `含插图的批之间串行`() {
        assertTrue(
            "队列按'批内含图'置 imageBusy（不是只看首段）",
            queue.contains("if (batchHasImage && imageBusy.get())"),
        )
        assertTrue("队列置位", queue.contains("if (batchHasImage) imageBusy.set(true)"))
        assertTrue("队列清位在 finally 里", queue.contains("if (batchHasImage) imageBusy.set(false)"))
        assertTrue("整章任务有自己的门闩", host.contains("private val imageGate = kotlinx.coroutines.sync.Mutex()"))
        val body = bodyOf(host, "private suspend fun translateUnit(")
        assertTrue("含图批进门闩", body.contains("if (hasImage) imageGate.lock()"))
        assertTrue("门闩必须在 finally 放", body.contains("if (hasImage) imageGate.unlock()"))
    }

    /** ③ 强制退出：清进度文案 + 复位残留「翻译中」（否则面板永远显示"翻译中"）。 */
    @Test
    fun `强制退出要清进度并复位翻译中`() {
        val body = bodyOf(activity, "private fun pauseToManual(")
        assertTrue("清插图进度文案", body.contains("imageStageText = null"))
        assertTrue("复位残留的翻译中", body.contains("resetTranslating(b)"))
        val translator = read("src/main/java/com/moe/starflow/novel/translate/NovelChapterTranslator.kt")
        assertTrue(
            "翻译器提供只复位翻译中的入口",
            translator.contains("suspend fun resetTranslating(book: ImportedNovel)"),
        )
        // 队列一离开 TRANSLATING 也要清（批被取消时那次"清零上报"不会执行）
        assertTrue(
            "队列离开 TRANSLATING 时清进度文案",
            activity.contains("if (st.phase != NovelQueuePhase.TRANSLATING && imageStageText != null)"),
        )
    }

    /** ④ 通知栏优先显示图片进度（阅读器关掉后整章任务也看得到）。 */
    @Test
    fun `通知栏优先显示插图进度`() {
        assertTrue(
            "服务优先用 stageText",
            service.contains("!job.stageText.isNullOrBlank() -> job.stageText"),
        )
        assertTrue("宿主写 stageText", host.contains("stageText = imageStageTextOf(job.chapterIndex)"))
        assertTrue("宿主按章存（多章并行不串）", host.contains("imageStage[chapterIndex] ="))
        assertTrue("宿主上报时刷新注册表", host.contains("TranslationJobRegistry.notifyChanged()"))
        assertTrue(
            "收尾/取消要清这一章的进度（否则下一轮会把上一轮的'第 2/3 张'重新发出来）",
            bodyOf(host, "private fun forgetChapter(").contains("imageStage.remove(chapterIndex)"),
        )
        // 阅读器前台队列那条通知也要带上
        assertTrue(
            "阅读器通知带图片进度",
            activity.contains("stage ?: posText"),
        )
    }

    /** ⑤ 同一张图不许被两路同时翻：拦截 + 提示。 */
    @Test
    fun `同一张图不会被两路同时翻`() {
        assertTrue("有统一判断", activity.contains("private fun imageInFlightElsewhere("))
        val translate = bodyOf(activity, "private fun translateImageNow(")
        assertTrue("翻译前拦截", translate.contains("if (imageInFlightElsewhere(paraIndex))"))
        val delete = bodyOf(activity, "private fun deleteImageTranslation(")
        assertTrue("删除前拦截（否则那一批会把它写回来）", delete.contains("if (imageInFlightElsewhere(paraIndex))"))
        assertTrue(
            "手动翻的那张图要让队列躲开",
            activity.contains("+ manualImageInFlight(ch)"),
        )
        assertFalse(
            "不许在别处偷偷取消在途请求来'解决'冲突",
            translate.contains("abortAll()"),
        )
    }
}
