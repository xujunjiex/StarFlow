package com.moe.starflow.library

import android.net.Uri
import com.moe.starflow.utils.LogCollector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 书库管理容器自愈契约（真机反馈：换过目录后 `.starflow/books` 缺失 → 所有导入失败）。
 *
 * 缺失必须按空目录补建，**不能**把书库判成只读；只有「同名但不是目录」才算结构冲突。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryBooksContainerTest {

    private class FakeStorage : LibraryStorage {
        val dirs = linkedSetOf("")
        val files = linkedSetOf<String>()
        override val locationId = "test"
        override val displayPath = "test"
        override fun list(path: String): List<LibraryNode> {
            val prefix = if (path.isEmpty()) "" else "$path/"
            fun childOf(candidate: String) =
                candidate != path && candidate.startsWith(prefix) && !candidate.removePrefix(prefix).contains('/')
            return dirs.filter(::childOf).map { LibraryNode(it, directory = true, size = 0) } +
                files.filter(::childOf).map { LibraryNode(it, directory = false, size = 0) }
        }
        override fun exists(path: String) = path.isEmpty() || path in dirs || path in files
        override fun mkdir(path: String) { dirs += path }
        override fun read(path: String): InputStream = throw IOException("not needed")
        override fun writeNew(path: String, mime: String): OutputStream = throw IOException("not needed")
        override fun delete(path: String) { dirs.remove(path); files.remove(path) }
        override fun uri(path: String): Uri = Uri.parse("content://test/$path")
        override fun availableBytes(): Long? = null
    }

    @Test
    fun `missing books folder is created instead of failing the library`() {
        val storage = FakeStorage().apply { dirs += ".starflow" }

        ensureBooksContainer(storage)

        assertTrue("缺失的 .starflow/books 要补建", storage.exists(".starflow/books"))
        assertTrue(
            "补建要留 W 日志，方便排查",
            LogCollector.getAllLogs().any { it.tag == "LibrarySession" && it.message.contains("按空目录重建") },
        )
    }

    @Test
    fun `existing books folder is left untouched`() {
        val storage = FakeStorage().apply { dirs += ".starflow"; dirs += ".starflow/books" }

        ensureBooksContainer(storage)

        assertTrue(storage.exists(".starflow/books"))
    }

    @Test
    fun `a file named books is still a structural conflict`() {
        val storage = FakeStorage().apply {
            dirs += ".starflow"
            files += ".starflow/books"
        }

        assertThrows(LibraryStorageException::class.java) { ensureBooksContainer(storage) }
    }

    @Test
    fun `uncreatable books folder is reported as library storage error`() {
        val storage = object : LibraryStorage by FakeStorage() {
            override fun list(path: String): List<LibraryNode> =
                if (path == ".starflow") emptyList() else emptyList()
            override fun mkdir(path: String) = Unit // 提供器拒绝创建
        }

        assertThrows(LibraryStorageException::class.java) { ensureBooksContainer(storage) }
    }
}
