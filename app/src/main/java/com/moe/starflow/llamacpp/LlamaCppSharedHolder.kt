package com.moe.starflow.llamacpp

import android.content.Context
import com.moe.starflow.utils.LogCollector

/**
 * LlamaCpp 引擎的进程级共享实例持有器（沿用老 `HyMT2SharedHolder` 的语义）。
 *
 * - 模型 0.4~3GB，全 app（游戏/漫画/文本/聊天）共享**同一个热实例**：复用前缀 KV 缓存、
 *   避免每次页面切换冷加载（冷加载后解码明显变慢）。
 * - 实例 `keepAlive=true`：各调用方 `release()` 只取消在途任务、不释放模型。
 * - 换模型 / 换参数（提示词、线程、上下文等）时重建实例：key = 模型 id + 参数指纹。
 * - `releaseIfNotCurrent()` 由 `TranslatorFactory.create` 在每次创建引擎前调用：
 *   当前引擎不再是 LlamaCpp（或换了模型）时，把旧模型换出内存。
 */
object LlamaCppSharedHolder {

    private const val TAG = "LlamaCppSharedHolder"

    @Volatile private var instance: LlamaCppTranslation? = null
    @Volatile private var instanceKey: String? = null

    /**
     * 实例指纹 = **只在加载时生效**的参数（模型文件、上下文、线程数）。
     *
     * ⚠️ 提示词 / 温度 / top_p / top_k / 重复惩罚 / 最大输出 / 思考开关**不进指纹**：
     * 这些是每次推理现读的（见 `LlamaCppTranslation.currentParams()`），改了立即生效，
     * 没必要为了改个温度把几百 MB 的模型重新加载一遍。
     */
    private fun keyOf(model: LlamaCppModel): String {
        val p = model.params
        return buildString {
            append(model.id).append('|')
            append(model.fileName).append('|')
            append(p.contextSize).append('|')
            append(p.threads).append('|')
            append(p.batchThreads)
        }
    }

    @Synchronized
    fun get(context: Context, model: LlamaCppModel): LlamaCppTranslation {
        val key = keyOf(model)
        val cur = instance
        if (cur != null && !cur.released && key == instanceKey) return cur
        // 模型/参数变了，或实例已释放 → 先彻底释放旧实例（临时关掉 keepAlive）
        cur?.let {
            it.keepAlive = false
            it.release()
        }
        val created = LlamaCppTranslation(context.applicationContext, model).also { it.keepAlive = true }
        instance = created
        instanceKey = key
        LogCollector.d(TAG, "新建共享实例：${model.displayName}")
        return created
    }

    /**
     * 当前不需要 LlamaCpp 引擎（或换成了别的模型）时释放缓存实例。
     * 只在真换了 key 时释放；key 相同不动（保住热模型）。
     */
    @Synchronized
    fun releaseIfNotCurrent(model: LlamaCppModel?) {
        val cur = instance ?: return
        if (cur.released) return
        val wantKey = model?.let { keyOf(it) }
        if (wantKey == instanceKey) return
        LogCollector.d(TAG, "引擎切换：释放旧实例（$instanceKey → ${wantKey ?: "无"}）")
        instance = null
        instanceKey = null
        cur.keepAlive = false
        // 后台完整释放（join + nativeRelease + 状态浮层），不阻塞调用线程
        Thread { cur.release() }.start()
    }

    /** 后台预加载（把加载耗时挪到用户第一次翻译之前）。 */
    fun warmUp(context: Context, model: LlamaCppModel?) {
        if (model == null) return
        get(context, model).warmUp()
    }

    /** 当前是否有活着的实例（诊断/测试用）。 */
    fun hasInstance(): Boolean = instance?.released == false
}
