package com.moe.starflow.mangaimport.data

import com.moe.starflow.library.LibraryIdentityException
import com.moe.starflow.library.LibraryStorageException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 导入失败归因契约（真机反馈：`书库管理目录缺失` 被显示成「压缩包结构无法解析」）。
 *
 * 书库自身的存储结构错误必须归到 [ImportFailureReason.LIBRARY_STORAGE_UNAVAILABLE]，
 * 让弹窗指向「书库存储目录」，而不是让用户以为下载/解压坏了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportFailureClassificationTest {

    @Test
    fun `library storage errors are not reported as broken archives`() {
        assertEquals(
            ImportFailureReason.LIBRARY_STORAGE_UNAVAILABLE,
            ImportManager.classifyArchive(LibraryStorageException("书库管理目录缺失：.starflow/books")),
        )
        // 身份类错误同样是书库一侧的问题（覆盖重建的提示在弹窗里另有入口）
        assertEquals(
            ImportFailureReason.LIBRARY_STORAGE_UNAVAILABLE,
            ImportManager.classifyArchive(LibraryIdentityException("本机书库记录缺失，目录中仍有书库管理信息")),
        )
    }

    @Test
    fun `real archive corruption is still reported as archive invalid`() {
        assertEquals(
            ImportFailureReason.ARCHIVE_INVALID,
            ImportManager.classifyArchive(IOException("bad central directory")),
        )
    }
}
