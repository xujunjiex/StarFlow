package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * 长按底部进度条打开的缩略图预览网格 = **当前章**口径（用户口径 2026-11）：
 *
 * > 「阅读器底部进度条长按应该是选择当前章节的预览，而不是整部书所有的章节」
 *
 * 原来网格按**整本**页号铺开，而同一条进度条的拖拽/绿条/紫条早就是章内口径 —— 一个入口两套
 * 坐标域；多章漫画里整本网格还会混进别的章，几百页要滑很久才找得到本章那一页。
 *
 * 这里不 grep 源码：真建一个两章的数据源（章表由实际文件推导），真弹窗，断言
 * ① 格数 = 本章页数；② 格子序号 = 章内第几页；③ 点击回传的是**全书页号**（宿主直接跳页）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderPagePreviewDialogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var source: ReaderPageSource

    @Before
    fun setUp() {
        // 缩略图在 Dispatchers.Main 上落图；Robolectric 的主循环默认不跑，这里内联掉
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = temp.newFolder("book")
        val png = png()
        listOf("ch1/001.png", "ch1/002.png", "ch2/001.png", "ch2/002.png", "ch2/003.png").forEach { rel ->
            root.resolve(rel).apply { parentFile?.mkdirs() }.writeBytes(png)
        }
        source = ReaderPageSource(isArchive = false, localRoot = root.absolutePath)
    }

    @After
    fun tearDown() {
        if (::source.isInitialized) source.close()
        Dispatchers.resetMain()
    }

    private fun png(): ByteArray {
        val bmp = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    /** 章表由实际文件推导：ch1 = 全书 0..1、ch2 = 全书 2..4。 */
    private fun chapterScope(): ReaderProgressScope {
        assertEquals(2, source.chapters.size)
        val ch2 = source.chapters[1]
        assertEquals(2, ch2.startPage)
        assertEquals(3, ch2.pageCount)
        return ReaderProgressScope.of(ch2, currentPage = 3, totalPages = source.size)
    }

    private fun open(
        scope: ReaderProgressScope,
        currentPage: Int = 3,
        chapterLabel: String? = null,
        onSelect: (Int) -> Unit = {},
    ): AlertDialog = ReaderPagePreviewDialog(
        context = RuntimeEnvironment.getApplication(),
        source = source,
        pages = scope.pageRange(source.size),
        currentPage = currentPage,
        chapterLabel = chapterLabel,
        onSelect = onSelect,
    ).show()

    private fun bind(dialog: AlertDialog, position: Int): View {
        val grid = dialog.findViewById<RecyclerView>(R.id.rv_preview)!!
        val adapter = grid.adapter!!
        val holder = adapter.onCreateViewHolder(grid, 0)
        adapter.onBindViewHolder(holder, position)
        return holder.itemView
    }

    @Test
    fun gridListsOnlyTheCurrentChapter() {
        val dialog = open(chapterScope(), chapterLabel = "ch2")
        val grid = dialog.findViewById<RecyclerView>(R.id.rv_preview)!!
        assertEquals("网格只列本章 3 页（整本 5 页）", 3, grid.adapter!!.itemCount)
        assertEquals(
            "标题带上章名，才看得出网格只含本章",
            RuntimeEnvironment.getApplication()
                .getString(R.string.reader_preview_title_chapter, "ch2"),
            dialog.findViewById<TextView>(R.id.tv_preview_title)!!.text.toString(),
        )
    }

    @Test
    fun cellsNumberPagesWithinTheChapterAndSelectGlobalPages() {
        var selected = -1
        val dialog = open(chapterScope(), chapterLabel = "ch2") { selected = it }

        bind(dialog, 0).let { cell ->
            assertEquals(
                "格子序号 = 章内第几页（与顶部胶囊同一个口径）",
                "1",
                cell.findViewById<TextView>(R.id.tv_preview_page).text.toString(),
            )
            cell.performClick()
            assertEquals("点第 1 格 = 本章第 1 页 = 全书第 3 页（0 基 2）", 2, selected)
        }

        bind(dialog, 2).let { cell ->
            assertEquals("3", cell.findViewById<TextView>(R.id.tv_preview_page).text.toString())
            cell.performClick()
            assertEquals("点最后一格 = 全书第 5 页（0 基 4）", 4, selected)
        }
    }

    @Test
    fun unknownChapterFallsBackToTheWholeBook() {
        // 章表缺失/过期（旧书、外部改动）时 scope 退回整本 —— 网格不能因此变空
        val whole = ReaderProgressScope.of(null, currentPage = 1, totalPages = source.size)
        val dialog = open(whole, currentPage = 1)
        val grid = dialog.findViewById<RecyclerView>(R.id.rv_preview)!!
        assertEquals(5, grid.adapter!!.itemCount)
    }
}
