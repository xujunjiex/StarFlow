package com.moe.starflow.llamacpp

import android.content.Context
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelKey
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * LlamaCpp 模型清单（内置 Hy-MT2 + 用户导入的 GGUF）的唯一数据源。
 *
 * 存储选择：JSON 清单文件（`filesDir/llamacpp_models.json`）而不是 Room ——
 * 模型数量是「个位数~几十」，写操作只有导入/删除/切换激活，JSON 足够且**免掉数据库迁移**。
 * 文件本体放 `getExternalFilesDir(null)/llamacpp/`（与其它模型/漫画导入同层）。
 *
 * ⚠️ 加载必须支持**同步**路径：`TranslatorFactory.create()`（服务/页面同步调用）需要立刻拿到
 * 当前激活模型。清单是几 KB 的小文件，同步读没问题（见 [ensureLoadedSync]）。
 */
object LlamaCppModelStore {

    private const val TAG = "LlamaCppModelStore"

    /** 当前激活模型 id（写进 prefs，供引擎快路径读取） */
    const val PREF_ACTIVE_ID = "LlamaCpp_Active_Model_Id"

    /**
     * 激活的是不是内置 Hy-MT2（布尔镜像，供**只有 prefs、拿不到 Context** 的调用点判断，
     * 例如 `TranslateTools.getDisabledTargetLangs` / 阅读器菜单）。
     */
    const val PREF_ACTIVE_IS_BUILTIN = "LlamaCpp_Active_Is_Builtin"

    /** 内置 Hy-MT2 1.25-bit 的稳定 id */
    const val BUILTIN_HYMT2_ID = "builtin:hymt2"

    /** 内置 Hy-MT2 Q4_K_M 的稳定 id */
    const val BUILTIN_HYMT2_Q4KM_ID = "builtin:hymt2-q4km"

    /** downloadinfo.json 读不到时的兜底文件名 */
    private const val BUILTIN_HYMT2_FILE_FALLBACK = "Hy-MT2-1.8B-1.25Bit.gguf"
    private const val BUILTIN_HYMT2_Q4KM_FILE_FALLBACK = "Hy-MT2-1.8B-Q4_K_M.gguf"

    private var ctx: Context? = null
    private var prefs: CustomPreference? = null

    private val _models = MutableStateFlow<List<LlamaCppModel>>(emptyList())
    val models: StateFlow<List<LlamaCppModel>> = _models.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    @Volatile private var loaded = false

    fun init(context: Context) {
        val app = context.applicationContext
        if (ctx === app && LlamaCppPaths.isInitialized()) return
        ctx = app
        prefs = CustomPreference.getInstance(app)
        runCatching { LlamaCppPaths.init(app) }
            .onFailure { LogCollector.e(TAG, "初始化路径失败：${it.message}", it) }
    }

    // ───────────────────────── 读取 ─────────────────────────

    /** 同步加载清单（首次调用时补种内置模型）。几 KB 文件，可安全在调用线程执行。 */
    @Synchronized
    fun ensureLoadedSync() {
        if (loaded) return
        val c = ctx
        if (c == null) {
            LogCollector.w(TAG, "ensureLoadedSync：store 未 init，跳过")
            return
        }
        loadInternal(c)
        loaded = true
    }

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) { ensureLoadedSync() }

    /** 当前激活模型（不检查文件是否存在）。 */
    fun active(): LlamaCppModel? {
        ensureLoadedSync()
        val id = _activeId.value ?: return null
        return _models.value.firstOrNull { it.id == id }
    }

    fun byId(id: String): LlamaCppModel? {
        ensureLoadedSync()
        return _models.value.firstOrNull { it.id == id }
    }

    /** 文件是否缺失（外部删除 / 未下载）。 */
    fun fileMissing(model: LlamaCppModel): Boolean = !model.absoluteFile.isFile

    fun builtinHymt2(): LlamaCppModel? = byId(BUILTIN_HYMT2_ID)

    /** 所有**内置可下载模型**的下载 key（模型管理页刷新磁盘状态时逐个查）。 */
    fun builtinModelKeys(): List<ModelKey> = BUILTINS.map { it.modelKey }

    /** 当前激活的是不是内置 Hy-MT2（决定语言白名单是否套用 38 种限制）。 */
    fun isHyMt2Active(): Boolean = active()?.hyProfile == true

    /**
     * 某个模型是不是内置 Hy-MT2（= 走 hy 专用 prompt 通道）。
     *
     * ⚠️ 内置模型**不止一个**（1.25-bit / Q4_K_M，之后可能还有），所以判据是模型自身的
     * `hyProfile`，不能再写死某一个 id —— 写死会让第二个内置模型被当成通用模型，
     * 走错 prompt 通道 / 语言白名单失效。
     */
    private fun hyProfileOf(id: String?): Boolean =
        id != null && _models.value.firstOrNull { it.id == id }?.hyProfile == true

    /**
     * 只有 prefs 的调用点的等价判断：引擎是 LlamaCpp 且激活模型是内置 Hy-MT2。
     * 读的是 [PREF_ACTIVE_IS_BUILTIN] 镜像（由本 store 在切换/载入时写）。
     *
     * ⚠️ 镜像缺失时默认 **false**（= 当成通用模型，不套 Hy-MT2 的 38 种白名单）。方向是刻意选的：
     * 镜像在 `loadInternal` 里写，而 `TranslateTools.getLanguagesList` 会据此**换掉整个目标语言列表**
     * （38 种 vs 68 种）—— 若默认 true，某个在 store 载入前先问语言的路径会把通用模型的 30 种语言
     * 直接藏掉；默认 false 的代价只是「白名单短暂不生效」，用户仍能自己选。
     */
    fun isHyMt2ActiveFromPrefs(prefs: CustomPreference): Boolean {
        val api = prefs.getInt("Text_API", com.moe.starflow.utils.Constants.TextApi.BING.id)
        val ai = prefs.getInt("Text_AI", com.moe.starflow.utils.Constants.TextAI.NLLB.id)
        return api == com.moe.starflow.utils.Constants.TextApi.AI.id &&
            ai == com.moe.starflow.utils.Constants.TextAI.HYMT2.id &&
            prefs.getBoolean(PREF_ACTIVE_IS_BUILTIN, false)
    }

    // ───────────────────────── 写入 ─────────────────────────

    @Synchronized
    fun setActive(id: String) {
        ensureLoadedSync()
        prefs?.setString(PREF_ACTIVE_ID, id)
        prefs?.setBoolean(PREF_ACTIVE_IS_BUILTIN, hyProfileOf(id))
        _activeId.value = id
        LogCollector.d(TAG, "激活模型：$id")
    }

    @Synchronized
    fun upsert(model: LlamaCppModel) {
        ensureLoadedSync()
        val list = _models.value.toMutableList()
        val idx = list.indexOfFirst { it.id == model.id }
        if (idx >= 0) list[idx] = model else list.add(model)
        persist(list)
    }

    @Synchronized
    fun updateParams(id: String, params: LlamaCppParams) {
        ensureLoadedSync()
        val list = _models.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(params = params)
            persist(list)
        }
    }

    @Synchronized
    fun markRetagged(id: String, retaggedMd5: String) {
        ensureLoadedSync()
        val list = _models.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0 && list[idx].retaggedMd5 != retaggedMd5) {
            list[idx] = list[idx].copy(retaggedMd5 = retaggedMd5)
            persist(list)
        }
    }

    /** 删除条目（**不删文件** —— 文件删除由调用方决定，避免误删内置模型）。 */
    @Synchronized
    fun remove(id: String) {
        ensureLoadedSync()
        persist(_models.value.filterNot { it.id == id })
        if (_activeId.value == id) {
            prefs?.setString(PREF_ACTIVE_ID, "")
            _activeId.value = null
        }
    }

    /**
     * 清理磁盘上已无对应条目的模型文件（外部删除后的对账）。
     *
     * ⚠️ 目前**没有调用者**（保留的预留接口）。谁要接上它，先想清楚两件事：
     *  1. 它会 `delete()` 用户的模型文件 —— 只能清理 [LlamaCppPaths.modelsDir] 下的 `.gguf`
     *     （内置模型在下载流水线的 `models/` 里，不在这个目录），别把范围放大；
     *  2. 判据是「文件名不在清单里」，而清单是异步载入的 —— 载入完成前调用会把所有文件当孤儿删光。
     */
    @Synchronized
    fun pruneOrphanFiles(): Int {
        ensureLoadedSync()
        val keep = _models.value.map { it.fileName }.toSet()
        var removed = 0
        LlamaCppPaths.modelsDir().listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && f.name !in keep) {
                LogCollector.d(TAG, "清理无主模型文件：${f.name}")
                if (f.delete()) removed++
            }
        }
        return removed
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun loadInternal(c: Context) {
        val manifest = LlamaCppPaths.manifestFile()
        val fromDisk = if (manifest.isFile) {
            runCatching { LlamaCppJson.decode(manifest.readText()) }
                .onFailure { LogCollector.e(TAG, "清单解析失败：${it.message}", it) }
                .getOrDefault(emptyList())
        } else emptyList()

        val list = fromDisk.toMutableList()
        seedBuiltinIfMissing(c, list)
        migrateLegacyDefaultPrompt(list)
        // 清单与磁盘逐字一致（最常见：老用户没改过任何参数）就不重写 ——
        // ensureLoadedSync 是从 active() 同步调进来的（调用点含主线程），每次启动白写一遍没有意义。
        if (list != fromDisk) {
            persist(list)
        } else {
            _models.value = list
        }

        val stored = prefs?.getString(PREF_ACTIVE_ID, "").orEmpty()
        val legacy = prefs?.getString("Llama_Model_Name", "").orEmpty()
        val active = when {
            stored.isNotBlank() && list.any { it.id == stored } -> stored
            legacy.isNotBlank() -> BUILTIN_HYMT2_ID
            else -> list.firstOrNull()?.id
        }
        _activeId.value = active
        if (!active.isNullOrBlank()) {
            prefs?.setString(PREF_ACTIVE_ID, active)
            prefs?.setBoolean(PREF_ACTIVE_IS_BUILTIN, hyProfileOf(active))
        }
        LogCollector.d(TAG, "载入清单：${list.size} 个模型（激活=${active ?: "无"}）")
    }

    /**
     * 补种**可下载的内置模型**（不打进 APK，只是内置于「模型管理」里可一键下载的条目）。
     *
     * 目前两个都是腾讯 Hy-MT2 1.8B，来自官方 HuggingFace 仓库
     * `https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF`：
     *  - 1.25-bit（私有量化，下载后由 GgufTypeRetag 重打标 42→43）
     *  - Q4_K_M（标准量化，无需重打标）
     * 文件信息（文件名/大小/MD5）一律取自 `assets/models/downloadinfo.json` —— 与下载流水线同一份真值。
     */
    private data class BuiltinSpec(
        val id: String,
        val displayName: String,
        val modelKey: ModelKey,
        val fallbackFileName: String,
    )

    private val BUILTINS = listOf(
        BuiltinSpec(BUILTIN_HYMT2_ID, "Hy-MT2 1.8B 1.25-bit", ModelKey.HY_MT2_GROUP, BUILTIN_HYMT2_FILE_FALLBACK),
        BuiltinSpec(BUILTIN_HYMT2_Q4KM_ID, "Hy-MT2 1.8B Q4_K_M", ModelKey.HY_MT2_Q4_KM, BUILTIN_HYMT2_Q4KM_FILE_FALLBACK),
    )

    private fun seedBuiltinIfMissing(c: Context, list: MutableList<LlamaCppModel>) {
        var added = false
        BUILTINS.forEachIndexed { index, spec ->
            if (list.any { it.id == spec.id }) return@forEachIndexed
            val fileInfo = runCatching {
                ModelDownloadRepository.getInstance(c).getModelInfo(spec.modelKey)?.files?.firstOrNull()
            }.getOrNull()
            val fileName = fileInfo?.fileName ?: spec.fallbackFileName
            list.add(
                // 顺序即展示顺序：1.25-bit 在前
                minOf(index, list.size),
                LlamaCppModel(
                    id = spec.id,
                    displayName = spec.displayName,
                    fileName = fileName,
                    sizeBytes = fileInfo?.fileSize ?: 0L,
                    md5 = fileInfo?.checksum?.takeIf { it.isNotBlank() },
                    source = LlamaCppModelSource.BUILTIN,
                    // 内置模型的 gguf 由下载流水线放在 files/models/（与 baseDirFor 一致）
                    dirName = "models",
                    builtinModelKey = spec.modelKey.name,
                    hyProfile = true, // Hy-MT2 系列 vocab 里都有 hy_ 角色标记 → 用专用 prompt 通道
                    params = LlamaCppParams.forSource(LlamaCppModelSource.BUILTIN),
                ),
            )
            added = true
            LogCollector.d(TAG, "补种内置模型：${spec.displayName}（$fileName）")
        }
        // 老用户全局参数迁移到内置模型（只在第一次补种时做，之后以 per-model 参数为准）
        if (added) migrateLegacyParams(list)
    }

    /**
     * 默认提示词升级：新默认值自带完整要求（原来只有内置 Hy-MT2 的 system 段里有要求，
     * 导入模型和隐藏了 system 输入框的内置模型都拿不到）。只改**仍是旧默认值**的条目 ——
     * 用户自己改过的提示词一律尊重，不动。
     */
    private fun migrateLegacyDefaultPrompt(list: MutableList<LlamaCppModel>) {
        var changed = 0
        for (i in list.indices) {
            val p = list[i].params
            if (p.promptTemplate == LlamaCppParams.LEGACY_DEFAULT_PROMPT) {
                list[i] = list[i].copy(params = p.copy(promptTemplate = LlamaCppParams.DEFAULT_PROMPT))
                changed++
            }
            // 内置 Hy-MT2 的旧默认采样对（temp 0.7 / top_p 0.6）对齐到模型自带元数据的 0.8；
            // 只在这个精确组合下改，用户自己调过的值不动
            if (list[i].source == LlamaCppModelSource.BUILTIN &&
                list[i].params.temperature == 0.7f && list[i].params.topP == 0.6f
            ) {
                list[i] = list[i].copy(params = list[i].params.copy(topP = 0.8f))
                changed++
            }
            // 旧的通用默认 system 提示词（长规则版）也一并收敛成短角色句，避免与新模板重复
            if (list[i].params.systemPrompt == LEGACY_LONG_SYSTEM_PROMPT) {
                list[i] = list[i].copy(params = list[i].params.copy(systemPrompt = LlamaCppParams.DEFAULT_SYSTEM_PROMPT))
                changed++
            }
        }
        if (changed > 0) LogCollector.d(TAG, "默认提示词已升级：$changed 项")
    }

    /** 旧的通用默认 system 提示词（把完整规则写在 system 段里那版） */
    private const val LEGACY_LONG_SYSTEM_PROMPT =
        "你是一名专业翻译。你的任务是准确、自然地翻译给定的文本。\n" +
            "具体规则如下：\n1、根据用户的要求，将文本翻译成指定的目标语言；\n" +
            "2、保持原意和语气；\n3、尽可能保持格式和结构；\n" +
            "4、直接返回翻译后的文本，不要有任何解释或附加内容；\n" +
            "5、如果文本已经是目标语言，请按原样返回。"

    /** 把老的全局 Hy-MT2 参数（hymt2_* prefs）迁移成内置模型的初始参数。 */
    private fun migrateLegacyParams(list: MutableList<LlamaCppModel>) {
        val p = prefs ?: return
        val idx = list.indexOfFirst { it.id == BUILTIN_HYMT2_ID }
        if (idx < 0) return
        val cur = list[idx].params
        list[idx] = list[idx].copy(
            params = cur.copy(
                promptTemplate = p.getString("hymt2_prompt_template", cur.promptTemplate),
                threads = p.getInt("hymt2_threads", cur.threads),
                batchThreads = p.getInt("hymt2_batch_threads", cur.batchThreads),
                contextSize = p.getInt("hymt2_context_size", cur.contextSize),
                temperature = p.getFloat("hymt2_temperature", cur.temperature),
                topP = p.getFloat("hymt2_top_p", cur.topP),
                topK = p.getInt("hymt2_top_k", cur.topK),
                repetitionPenalty = p.getFloat("hymt2_rep_penalty", cur.repetitionPenalty),
                maxTokens = p.getInt("hymt2_max_tokens", cur.maxTokens),
            )
        )
    }

    private fun persist(list: List<LlamaCppModel>) {
        _models.value = list
        runCatching { LlamaCppPaths.manifestFile().writeText(LlamaCppJson.encode(list)) }
            .onFailure { LogCollector.e(TAG, "清单写入失败：${it.message}", it) }
    }
}
