package com.moe.starflow.mangaimport.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.moe.starflow.utils.LogCollector
import java.io.Closeable
import java.io.File

/** PDF 打不开的原因（导入侧据此给用户一句有用的提示）。 */
enum class PdfOpenError {
    /** 没出错（或还没试过）。 */
    NONE,

    /** 需要密码 —— Android 的 `PdfRenderer` 没有密码接口，只能请用户先解密。 */
    ENCRYPTED,

    /** 损坏 / 不是 PDF / pdfium 不支持。 */
    CORRUPT,

    /** IO 层失败（文件不见了 / 没权限）。 */
    IO,
}

/**
 * PDF 页面的**渲染器**：一个文件一个实例，懒打开、用完 [close]。
 *
 * ## 为什么用系统自带 `PdfRenderer`（pdfium）
 * - minSdk 29 就有，**零第三方依赖**（不引 PDFBox/PDFium 包）
 * - 页码索引 + 随机取页是 pdfium 的原生能力 → 不需要"导入时全量转图"
 * - 扫描 PDF（每页一张大图）与矢量 PDF（有文本层）走**同一条**渲染路径：
 *   我们只认"渲染出来的位图"，OCR/气泡/渲染链路完全不用分情况
 *
 * ## 线程与生命周期
 * - `PdfRenderer` **同一时刻只能开一个 Page**，且不是线程安全的 → 本类所有方法
 *   `@Synchronized`（渲染本来也不便宜，串行没有额外损失）
 * - 持有一个 `ParcelFileDescriptor`（FD 是有限资源）→ **必须 [close]**；
 *   `ReaderPageSource.close()` 会调它，阅读器销毁 / 控制器回收时释放
 * - 打开失败只记一次（[openError]），不会每次调用都重试解析整个文件
 *
 * ⚠️ **绝不要在 `loadFull` 之外的地方自己 new 一个**：`ReaderPageSource` 持有一份即可，
 *    多开一份就多一个 FD，也失去本类的串行保护。
 */
class PdfPageRenderer(private val file: File) : Closeable {

    private companion object {
        const val TAG = "PdfPageRenderer"
    }

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    /** 打不开时置 true，之后所有调用直接返回 null/0（不反复解析大文件）。 */
    private var failed = false

    /**
     * **已关闭 = 终态**（[close] 之后置位，不会再自动重开）。
     *
     * ⚠️ 这条不是洁癖：关闭发生在"退出阅读器 / 控制器被回收"时，而那一刻**在途的 `loadFull`
     *    可能还在跑**（读的是关闭前捕获的 lambda）。如果允许重开，那次在途调用就会把 PDF 又打开一次，
     *    而这个数据源此后再也没人 close → **每退出一次漏一个 FD**。
     *    关掉之后本就该没人再用（阅读器重进会新建数据源、控制器重建会新建实例），所以"拒绝服务 + 日志"
     *    比"悄悄重开"正确。
     */
    private var closed = false

    var openError: PdfOpenError = PdfOpenError.NONE
        private set

    /** 页数（打不开/已关闭 = 0）。这就是 PDF 的**分页真值**：page tree 里有多少页就是多少页。 */
    val pageCount: Int
        get() = ensureOpen()?.pageCount ?: 0

    /** 是否可用（能打开且至少一页）。 */
    val isUsable: Boolean
        get() = pageCount > 0

    @Synchronized
    private fun ensureOpen(): PdfRenderer? {
        renderer?.let { return it }
        if (closed) {
            LogCollector.w(TAG, "PDF 渲染器已关闭，拒绝重新打开: ${file.absolutePath}（调用方在 close 之后又用了它）")
            return null
        }
        if (failed) return null
        if (!file.isFile) {
            failed = true
            openError = PdfOpenError.IO
            LogCollector.w(TAG, "PDF 文件不存在: ${file.absolutePath}")
            return null
        }
        return try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val opened = try {
                PdfRenderer(descriptor)
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
            pfd = descriptor
            renderer = opened
            LogCollector.i(TAG, "打开 PDF: ${file.name}（${opened.pageCount} 页）")
            opened
        } catch (e: Exception) {
            failed = true
            openError = classify(e)
            // 这条日志是"PDF 导入失败/打不开"的第一现场：加密与损坏在用户侧看起来一模一样
            LogCollector.e(TAG, "打开 PDF 失败: ${file.absolutePath}（${openError}）", e)
            null
        }
    }

    /**
     * 加密与损坏的区分只能**嗅探异常文本**：`PdfRenderer` 对两者都抛
     * `IOException("cannot create PdfRenderer")`/`SecurityException`，没有错误码。
     * 判不出来就是 CORRUPT（提示语里两种可能都会写上）。
     */
    private fun classify(e: Exception): PdfOpenError {
        val text = (e.message.orEmpty() + " " + e.javaClass.simpleName).lowercase()
        return when {
            text.contains("password") || text.contains("encrypt") -> PdfOpenError.ENCRYPTED
            e is java.io.FileNotFoundException || e is SecurityException -> PdfOpenError.IO
            else -> PdfOpenError.CORRUPT
        }
    }

    /** 该页的**点尺寸** `[宽pt, 高pt]`（pt = 1/72 英寸）；取不到返回 null。 */
    @Synchronized
    fun pageSizePoints(index: Int): IntArray? {
        val r = ensureOpen() ?: return null
        if (index < 0 || index >= r.pageCount) return null
        var page: PdfRenderer.Page? = null
        return try {
            val opened = r.openPage(index)
            page = opened
            intArrayOf(opened.width, opened.height)
        } catch (e: Exception) {
            LogCollector.w(TAG, "读取页面尺寸失败 index=$index: ${e.message}")
            null
        } finally {
            runCatching { page?.close() }
        }
    }

    /**
     * 按**目标宽度**渲染上屏图（Webtoon 连续滚动用）。
     *
     * 一次 `openPage` 内完成"读页尺寸 → 算上屏尺寸 → 渲染"：曾经的写法是
     * `canonicalSize()` 开一次 + `render()` 再开一次（真机实测 `openPage` 0.9~27ms/页，
     * 而 Webtoon 一次要绑好几页）。
     *
     * ⚠️ 尺寸走 [PdfRasterPolicy.webtoonSize]（**精确目标宽**，不再是 2 的幂采样）：
     *    页面比屏幕宽一点时不会再被砍成半宽。
     */
    fun renderSampledToWidth(index: Int, targetWidthPx: Int): Bitmap? =
        renderScaled(index) { canonical ->
            PdfRasterPolicy.webtoonSize(
                canonical.getOrElse(0) { 1 },
                canonical.getOrElse(1) { 1 },
                targetWidthPx,
            )
        }

    /** 按整数采样率渲染缩略图（预览网格用）。同上：一次 `openPage`。 */
    fun renderScaledBySample(index: Int, sample: Int): Bitmap? =
        renderScaled(index) { canonical -> PdfRasterPolicy.downscale(canonical, sample) }

    /**
     * 该页的**权威像素尺寸**（见 [PdfRasterPolicy]）。所有坐标都活在这个空间里。
     *
     * ⚠️ 会 `openPage`（真机实测 0.9~27ms）→ **别在渲染前后顺手多调几次**，
     * 渲染路径请用 [renderCanonical] / [renderSampledToWidth] / [renderScaledBySample]（各自只开一次页）。
     */
    fun canonicalSize(index: Int): IntArray? =
        pageSizePoints(index)?.let { PdfRasterPolicy.canonicalSizeOf(it) }

    /** 渲染到"由权威尺寸算出来"的某个尺寸（[sizeOf] 是纯函数，决定最终像素空间）。 */
    private fun renderScaled(index: Int, sizeOf: (IntArray) -> IntArray): Bitmap? {
        val r = ensureOpen() ?: return null
        if (index < 0 || index >= r.pageCount) return null
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        return try {
            val opened = r.openPage(index)
            page = opened
            val size = sizeOf(PdfRasterPolicy.canonicalSize(opened.width, opened.height))
            val w = size[0].coerceAtLeast(1)
            val h = size[1].coerceAtLeast(1)
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bm
            bm.eraseColor(Color.WHITE)
            opened.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bm
        } catch (e: Throwable) {
            runCatching { bitmap?.recycle() }
            LogCollector.e(TAG, "渲染 PDF 采样页失败 index=$index", e)
            null
        } finally {
            runCatching { page?.close() }
        }
    }

    /**
     * 按权威尺寸渲染一页。**返回的位图归调用方所有**（要 `recycle`）。
     *
     * ⚠️ 只开一次 Page：曾经是"`canonicalSize()` 开一次 + `render()` 再开一次"（每页多一次
     *    `openPage`，而 Webtoon 一次要绑好几页）—— 尺寸在与渲染**同一次**开页里读掉。
     */
    fun renderCanonical(index: Int): Bitmap? {
        val r = ensureOpen() ?: return null
        if (index < 0 || index >= r.pageCount) return null
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        return try {
            val opened = r.openPage(index)
            page = opened
            val size = PdfRasterPolicy.canonicalSize(opened.width, opened.height)
            val w = size[0]
            val h = size[1]
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bm
            bm.eraseColor(Color.WHITE)
            opened.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bm
        } catch (e: Throwable) {
            runCatching { bitmap?.recycle() }
            LogCollector.e(TAG, "渲染 PDF 页失败 index=$index（canonical）", e)
            null
        } finally {
            runCatching { page?.close() }
        }
    }

    /**
     * 渲染一页到指定像素尺寸。
     *
     * ⚠️ **分辨率只由位图尺寸决定，`transform` 必须传 null**（2026-10-04 真机实测结论，别再"顺手加个缩放矩阵"）：
     * 框架内部就是把页面**缩放到填满目标位图**，于是"位图给多大，就渲多清晰"；再叠一个
     * `Matrix().setScale(w/page.width, …)` 是**无效的** —— 实测 `transform = null` 与"带缩放矩阵"
     * 渲出来的 PNG **逐字节相同**（同一页 1750×2479、墨迹包围盒都是 x 2.1%~97.9% / y 1.5%~98.5%、
     * dark=37066），也就是说"传 null 会退化成 72dpi 画在左上角"这个担心是**不成立**的。
     * 留个矩阵在那儿只会让下一个人以为"分辨率靠它"，改错方向。
     *
     * 实测方法（不需要装 App）：`tools/pdf-render-probe/` 用 `app_process` 在设备上跑一次性探针。
     *
     * ⚠️ **必须先用白色填充**：PDF 页本身没有背景色（矢量页是透明的），
     * 透明位图在 OCR / 覆盖层渲染里会被当黑处理（或被当"无内容"），白底才是纸的语义。
     *
     * ⚠️ 尺寸必须由 [PdfRasterPolicy] 给出（`canonicalSize` / `downscale`）：
     * 随手传一个尺寸 = 坐标空间与 OCR 不一致。
     */
    @Synchronized
    fun render(index: Int, widthPx: Int, heightPx: Int): Bitmap? {
        val r = ensureOpen() ?: return null
        if (index < 0 || index >= r.pageCount) return null
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        return try {
            val opened = r.openPage(index)
            page = opened
            // ⚠️ 用 `val` 而不是可空 var：下面 finally 里的 lambda 捕获了 `page`，
            //    可空 var 在捕获后会失去智能转换
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bm
            bm.eraseColor(Color.WHITE)
            // destClip = null → 整张位图；transform = null → 页面按位图尺寸缩放（见上方实测结论）
            opened.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bm
        } catch (e: Throwable) {
            // ⚠️ 失败必须回收：一页 4~48MB，反复失败会直接把堆吃满
            runCatching { bitmap?.recycle() }
            LogCollector.e(TAG, "渲染 PDF 页失败 index=$index size=${w}x$h", e)
            null
        } finally {
            runCatching { page?.close() }
        }
    }

    /**
     * 关掉 FD 与渲染器。**这是终态**：之后所有查询/渲染都返回 null（见 [closed]），不再自动重开。
     *
     * ⚠️ 与 `@Synchronized` 的渲染互斥：在途渲染跑完之前 close 会等它 —— 绝不会出现
     *    "渲染进行中底层 FD 被关"（那会直接 native 崩）。
     */
    @Synchronized
    override fun close() {
        closed = true
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        renderer = null
        pfd = null
    }
}
