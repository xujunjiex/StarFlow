package com.moe.starflow.library

import com.moe.starflow.utils.LogCollector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

/**
 * 书库目录关系的容错契约（0.12.2 真机事故：改个文件夹名就再也进不了书库）。
 *
 * 现场：旧目录 `primary:导入文件` 被改名成 `漫画导入` 后，提供器对旧树抛
 * `FileNotFoundException("Missing file for primary:导入文件 at …")`，而
 * `DocumentsContract.isChildDocument` 把它包成 `RuntimeException("Failed to determine if …")`；
 * 异常一路冒到「覆盖并重建」弹窗，把切换目录卡死。
 *
 * 约定：**关系问不出来 = 按不嵌套处理**（可继续选目录），并留一条 W 日志；
 * 提供器能正常回答时，真正的嵌套必须照旧拦截。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryDocumentRelationTest {

    @Test
    fun `missing folder is treated as not nested and logged`() {
        val result = childRelationOrFalse {
            throw RuntimeException(
                "Failed to determine if primary:导入文件 is child of primary:漫画导入",
                FileNotFoundException("Missing file for primary:导入文件 at /storage/emulated/0/导入文件"),
            )
        }
        assertFalse("问不出来不能当成不能切换", result)
        assertTrue(
            "失效目录要留一条 W 日志，用户反馈时能定位",
            LogCollector.getAllLogs().any { it.tag == "LibraryStorage" && it.message.contains("无法判断") },
        )
    }

    @Test
    fun `readable provider still blocks nested folders`() {
        assertTrue("真正的嵌套必须照旧拦截", childRelationOrFalse { true })
    }

    @Test
    fun `provider answering not nested is respected`() {
        assertFalse(childRelationOrFalse { false })
    }
}
