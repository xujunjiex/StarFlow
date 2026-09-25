package com.moe.starflow.llamacpp

import com.moe.starflow.download.DownloadState
import com.moe.starflow.me.model.RowButtons
import com.moe.starflow.me.model.rowButtonsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型卡片行「下载控制按钮」可见性的回归守卫。
 *
 * 为什么需要：行视图是**复用**的（`LlamaCppModelFragment.syncRows` 只在行集合变化时重新
 * inflate），所以每个状态都必须对四个按钮显式赋值。曾经（重新 inflate 的实现）漏写某个分支
 * 不会有任何症状 —— 新行天然是全 `gone` 的；改复用以同样的写法就会让上一轮的按钮留在卡片上
 * （下载中 [暂停][取消] → 下载完成后仍显示）。这里把「每个状态的完整可见性组合」钉死。
 */
class LlamaCppRowButtonsTest {

    private val missingStates = listOf(
        "Idle" to DownloadState.Idle,
        "Done" to DownloadState.Done,
        "Running" to running(),
        "Paused" to DownloadState.Paused(1L, 2L, 0, 1, "a.gguf", 1L, 2L),
        "Partial" to DownloadState.Partial(1L, 2L, 0, 1, "a.gguf", 1L, 2L),
    )

    private fun running() = DownloadState.Running(
        bytesDownloaded = 1L,
        totalBytes = 2L,
        speedBytesPerSec = 0L,
        currentFileIndex = 0,
        currentFileCount = 1,
        currentFileName = "a.gguf",
        currentFileProgress = 50,
        currentFileBytesDownloaded = 1L,
        currentFileTotalBytes = 2L,
    )

    /** 每个状态都有一组**完整**的期望值：漏写分支会表现为「该隐藏的没隐藏」。 */
    @Test
    fun `每个状态的按钮组合都是完整的`() {
        assertEquals(RowButtons(download = true), rowButtonsFor(DownloadState.Idle, missing = true))
        assertEquals(RowButtons(), rowButtonsFor(DownloadState.Idle, missing = false))
        assertEquals(RowButtons(download = true), rowButtonsFor(DownloadState.Done, missing = true))
        assertEquals(RowButtons(), rowButtonsFor(DownloadState.Done, missing = false))
        assertEquals(RowButtons(pause = true, cancel = true), rowButtonsFor(running(), missing = false))
        assertEquals(
            RowButtons(resume = true, cancel = true),
            rowButtonsFor(DownloadState.Paused(1L, 2L, 0, 1, "a.gguf", 1L, 2L), missing = false),
        )
        assertEquals(
            RowButtons(resume = true),
            rowButtonsFor(DownloadState.Partial(1L, 2L, 0, 1, "a.gguf", 1L, 2L), missing = false),
        )
    }

    /**
     * 核心回归：下载中会亮出 [暂停][取消]，**下载完成后必须收回去**。
     * 这正是「复用行视图 + 只在 Running 分支写 VISIBLE」会漏掉的那条。
     */
    @Test
    fun `下载完成后不再显示暂停与取消`() {
        val during = rowButtonsFor(running(), missing = false)
        assertTrue("下载中应显示暂停", during.pause)
        assertTrue("下载中应显示取消", during.cancel)

        val after = rowButtonsFor(DownloadState.Done, missing = false)
        assertFalse("下载完成后不该再显示暂停", after.pause)
        assertFalse("下载完成后不该再显示取消", after.cancel)
        assertFalse("文件在，不该显示下载按钮", after.download)
    }

    /** 暂停/半成品状态下不该冒出「下载」按钮（那是重新开始，语义不同）。 */
    @Test
    fun `非 Idle 状态不显示下载按钮`() {
        missingStates
            .filterNot { it.first == "Idle" || it.first == "Done" }
            .forEach { (name, state) ->
                assertFalse("$name 不应显示下载按钮", rowButtonsFor(state, missing = true).download)
            }
    }

    /** 缺文件时「已下载」那一档要退回「下载」按钮。 */
    @Test
    fun `文件缺失时 Idle 与 Done 都显示下载按钮`() {
        assertTrue(rowButtonsFor(DownloadState.Idle, missing = true).download)
        assertTrue(rowButtonsFor(DownloadState.Done, missing = true).download)
        assertFalse(rowButtonsFor(DownloadState.Idle, missing = false).download)
    }

    /** 同一状态下 missing 只影响「下载」按钮，不影响其余三个。 */
    @Test
    fun `missing 标志只影响下载按钮`() {
        missingStates.forEach { (name, state) ->
            val withFile = rowButtonsFor(state, missing = false)
            val without = rowButtonsFor(state, missing = true)
            assertEquals("$name: pause 不该受 missing 影响", withFile.pause, without.pause)
            assertEquals("$name: resume 不该受 missing 影响", withFile.resume, without.resume)
            assertEquals("$name: cancel 不该受 missing 影响", withFile.cancel, without.cancel)
        }
    }
}
