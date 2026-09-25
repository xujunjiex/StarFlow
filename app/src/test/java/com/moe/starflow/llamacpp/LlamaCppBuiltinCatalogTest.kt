package com.moe.starflow.llamacpp

import com.moe.starflow.download.ModelKey
import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 内置（可下载）模型目录的回归守卫。
 *
 * 背景：内置模型**从 1 个变成 2 个**（Hy-MT2 1.25-bit + Q4_K_M）时踩过的坑——
 * 凡是「写死某一个内置 id」的地方都会把第二个内置模型当成通用模型：走错 prompt 通道、
 * 语言白名单失效、激活镜像写错、下载状态串到同一行。这里把这些约束钉死：
 *
 * 1. `downloadinfo.json` 里每个 `builtinModelKeys()` 都必须有且只有 1 个 `.gguf` 文件条目，
 *    且带 32 位 MD5 / 正数大小 / https 地址（否则下载页会显示成「未下载」且永远校验失败）；
 * 2. Q4_K_M 的清单值钉死到官方 HuggingFace 发布的实测值（MD5 由整文件下载计算）；
 * 3. 补种出的两个内置模型各自带**自己的**下载 key、都是 `hyProfile`（= 走 hy 专用通道）；
 * 4. 选中**任一**内置模型都要把 `PREF_ACTIVE_IS_BUILTIN` 镜像置 true。
 *
 * ⚠️ 必须跑 Robolectric：要读 assets 里的 downloadinfo.json，且 Android 的 `org.json`
 *    在纯 JVM 单测里是桩实现。
 */
@RunWith(RobolectricTestRunner::class)
class LlamaCppBuiltinCatalogTest {

    private fun downloadInfo(): JSONObject {
        val text = RuntimeEnvironment.getApplication().assets
            .open("models/downloadinfo.json").bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun entries(): Map<String, JSONObject> {
        val arr = downloadInfo().getJSONArray("models")
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .associateBy { it.getString("model_key") }
    }

    @Test
    fun everyBuiltinKeyHasDownloadableGgufEntry() {
        val byKey = entries()
        val keys = LlamaCppModelStore.builtinModelKeys()
        assertTrue("内置模型 key 列表不应为空", keys.isNotEmpty())

        keys.forEach { key ->
            val entry = byKey[key.name] ?: error("downloadinfo.json 缺少内置模型条目：${key.name}")
            val files = entry.getJSONArray("files")
            assertEquals("内置模型 ${key.name} 应只有 1 个文件", 1, files.length())
            val f = files.getJSONObject(0)
            assertTrue(
                "内置模型 ${key.name} 必须是 .gguf：${f.getString("file_name")}",
                f.getString("file_name").endsWith(".gguf", ignoreCase = true),
            )
            assertEquals("内置模型 ${key.name} 的 MD5 必须是 32 位", 32, f.getString("checksum").length)
            assertTrue("内置模型 ${key.name} 的大小必须为正", f.getLong("file_size") > 0L)
            assertTrue(entry.getString("browser_url").startsWith("https://"))
            assertTrue(f.getString("download_url").startsWith("https://"))
        }
    }

    /** Q4_K_M 来自官方 HF 仓库（原始文件 MD5 实测 436f3ec…）；清单写错会「下载完成即被当成损坏删掉」。 */
    @Test
    fun q4KmPinnedToOfficialHuggingFaceRelease() {
        assertEquals(
            listOf(ModelKey.HY_MT2_GROUP, ModelKey.HY_MT2_Q4_KM),
            LlamaCppModelStore.builtinModelKeys(),
        )
        val entry = entries()["HY_MT2_Q4_KM"] ?: error("downloadinfo.json 缺少 HY_MT2_Q4_KM")
        assertEquals(
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/tree/main",
            entry.getString("browser_url"),
        )
        val f = entry.getJSONArray("files").getJSONObject(0)
        assertEquals("Hy-MT2-1.8B-Q4_K_M.gguf", f.getString("file_name"))
        assertEquals(1_133_080_448L, f.getLong("file_size"))
        assertEquals("436f3ec23b236b2ac1d05dd7a713f8ae", f.getString("checksum"))
        assertTrue(
            f.getString("download_url").endsWith("/Hy-MT2-1.8B-Q4_K_M.gguf"),
        )
    }

    @Test
    fun seedingRegistersBothBuiltinsWithOwnKeyAndHyProfile() {
        val app = RuntimeEnvironment.getApplication()
        LlamaCppModelStore.init(app)
        LlamaCppModelStore.ensureLoadedSync()

        val builtins = LlamaCppModelStore.models.value
            .filter { it.source == LlamaCppModelSource.BUILTIN }
        val a = builtins.firstOrNull { it.id == LlamaCppModelStore.BUILTIN_HYMT2_ID }
            ?: error("缺少 1.25-bit 内置条目")
        val b = builtins.firstOrNull { it.id == LlamaCppModelStore.BUILTIN_HYMT2_Q4KM_ID }
            ?: error("缺少 Q4_K_M 内置条目")

        // 各自带自己的下载 key（共用同一个 key 会让两行显示同一份下载状态）
        assertEquals("HY_MT2_GROUP", a.builtinModelKey)
        assertEquals("HY_MT2_Q4_KM", b.builtinModelKey)
        assertEquals("Hy-MT2-1.8B-Q4_K_M.gguf", b.fileName)
        // 内置模型的 gguf 由下载流水线放在 files/models/
        assertEquals("models", a.dirName)
        assertEquals("models", b.dirName)
        // 两个都走 hy 专用 prompt 通道
        assertTrue(a.hyProfile)
        assertTrue(b.hyProfile)
        // 内置默认参数同源
        val dflt = LlamaCppParams.forSource(LlamaCppModelSource.BUILTIN)
        assertEquals(dflt.topP, b.params.topP, 0.0001f)
        assertEquals(dflt.promptTemplate, b.params.promptTemplate)
    }

    /**
     * 激活**第二个**内置模型（Q4_K_M）同样要算「内置 Hy-MT2 激活」。
     * 否则 `TranslateTools` 的语言白名单与提示词通道都会按通用模型走。
     */
    @Test
    fun activatingSecondBuiltinAlsoMarksHyMt2Active() {
        val app = RuntimeEnvironment.getApplication()
        LlamaCppModelStore.init(app)
        LlamaCppModelStore.ensureLoadedSync()

        val prefs = CustomPreference.getInstance(app)
        prefs.setInt("Text_API", Constants.TextApi.AI.id)
        prefs.setInt("Text_AI", Constants.TextAI.HYMT2.id)

        LlamaCppModelStore.setActive(LlamaCppModelStore.BUILTIN_HYMT2_Q4KM_ID)
        assertTrue("选中 Q4_K_M 也算内置 Hy-MT2 激活", LlamaCppModelStore.isHyMt2Active())
        assertTrue(
            "prefs 镜像必须为 true（只有 prefs 的调用点靠它判断）",
            LlamaCppModelStore.isHyMt2ActiveFromPrefs(prefs),
        )

        // 导入的模型不该被算成内置
        val imported = LlamaCppModel(
            id = "imported:qwen.gguf",
            displayName = "Qwen",
            fileName = "qwen.gguf",
            sizeBytes = 0L,
            md5 = null,
            source = LlamaCppModelSource.IMPORTED,
            dirName = LlamaCppPaths.DIR_NAME,
            builtinModelKey = null,
            hyProfile = false,
        )
        LlamaCppModelStore.upsert(imported)
        LlamaCppModelStore.setActive(imported.id)
        assertTrue(!LlamaCppModelStore.isHyMt2Active())
        assertTrue(!LlamaCppModelStore.isHyMt2ActiveFromPrefs(prefs))
    }
}
