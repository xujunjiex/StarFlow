package com.moe.starflow.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「模型清单必须能自愈」的守卫（源码级，纯 JVM）。
 *
 * ## 这条也是真机反馈换来的
 * > 「我明明下载好了对应的超分模型，阅读器这边后面还是提示未下载」
 *
 * 根因：`ModelDownloadRepository.getModelInfo` 只读那个内存缓存，而清单原本只在
 * `StarFlowApplication` 里 `GlobalScope.launch { loadModelList() }` **异步载入一次**。
 * 缓存还没填上时（冷启动后马上进阅读器 / 那次协程失败），所有
 * `getModelInfo(...) == null` 的调用都会把**已经下载好的模型**判成「未下载」——
 * 于是阅读器面板显示「未下载」、超分还会报「模型文件不存在」，而模型文件其实好好躺在磁盘上。
 *
 * `loadModelList` 本身只是读 assets 里的几 KB JSON（纯同步），按需同步补一次既安全又彻底。
 */
class ModelDownloadRepositorySelfHealingTest {

    private fun src() = File("src/main/java/com/moe/starflow/download/ModelDownloadRepository.kt")
        .readText().replace("\r\n", "\n")

    @Test
    fun getModelInfoEnsuresListLoaded() {
        val s = src()
        assertTrue("getModelInfo 必须先确保清单已加载", s.contains("fun getModelInfo(modelKey: ModelKey): ModelInfo? {"))
        assertTrue(s.contains("ensureModelListLoaded()"))
        assertTrue("要有幂等的同步补加载", s.contains("private fun ensureModelListLoaded()"))
        assertTrue("失败只能记日志，不许把调用方炸掉", s.contains("onFailure { LogCollector.e(TAG, \"同步加载模型清单失败"))
    }

    @Test
    fun loadBodyIsSharedBetweenAsyncAndSyncPaths() {
        val s = src()
        // suspend 版只是壳，真正干活的是同步实现 —— 否则两条路会漂移
        assertTrue("suspend 入口要复用同一份同步实现", s.contains("suspend fun loadModelList() = loadModelListBlocking()"))
        assertTrue(s.contains("private fun loadModelListBlocking()"))
        assertFalse(
            "解析逻辑不许再留第二份（两份必然漂移）",
            s.contains("private suspend fun loadModelList()"),
        )
    }
}
