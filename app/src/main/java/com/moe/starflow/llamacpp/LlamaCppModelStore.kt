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

    /** 内置 Hy-MT2 的稳定 id */
    const val BUILTIN_HYMT2_ID = "builtin:hymt2"

    /** downloadinfo.json 读不到时的兜底文件名 */
    private const val BUILTIN_HYMT2_FILE_FALLBACK = "Hy-MT2-1.8B-1.25Bit.gguf"

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

    /** 当前激活的是不是内置 Hy-MT2（决定语言白名单是否套用 38 种限制）。 */
    fun isHyMt2Active(): Boolean = active()?.id == BUILTIN_HYMT2_ID

    /**
     * 只有 prefs 的调用点的等价判断：引擎是 LlamaCpp 且激活模型是内置 Hy-MT2。
     * 读的是 [PREF_ACTIVE_IS_BUILTIN] 镜像（由本 store 在切换/载入时写）。
     */
    fun isHyMt2ActiveFromPrefs(prefs: CustomPreference): Boolean {
        val api = prefs.getInt("Text_API", com.moe.starflow.utils.Constants.TextApi.BING.id)
        val ai = prefs.getInt("Text_AI", com.moe.starflow.utils.Constants.TextAI.NLLB.id)
        return api == com.moe.starflow.utils.Constants.TextApi.AI.id &&
            ai == com.moe.starflow.utils.Constants.TextAI.HYMT2.id &&
            prefs.getBoolean(PREF_ACTIVE_IS_BUILTIN, true)
    }

    // ───────────────────────── 写入 ─────────────────────────

    @Synchronized
    fun setActive(id: String) {
        ensureLoadedSync()
        prefs?.setString(PREF_ACTIVE_ID, id)
        prefs?.setBoolean(PREF_ACTIVE_IS_BUILTIN, id == BUILTIN_HYMT2_ID)
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

    /** 清理磁盘上已无对应条目的模型文件（外部删除后的对账）。 */
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
        _models.value = list
        persist(list)

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
            prefs?.setBoolean(PREF_ACTIVE_IS_BUILTIN, active == BUILTIN_HYMT2_ID)
        }
        LogCollector.d(TAG, "载入清单：${list.size} 个模型（激活=${active ?: "无"}）")
    }

    /** 内置 Hy-MT2：文件信息取自 downloadinfo.json（与下载流水线同一份真值）。 */
    private fun seedBuiltinIfMissing(c: Context, list: MutableList<LlamaCppModel>) {
        if (list.any { it.id == BUILTIN_HYMT2_ID }) return
        val fileInfo = runCatching {
            ModelDownloadRepository.getInstance(c).getModelInfo(ModelKey.HY_MT2_GROUP)?.files?.firstOrNull()
        }.getOrNull()
        val fileName = fileInfo?.fileName ?: BUILTIN_HYMT2_FILE_FALLBACK
        list.add(
            0,
            LlamaCppModel(
                id = BUILTIN_HYMT2_ID,
                displayName = "Hy-MT2 1.8B 1.25-bit",
                fileName = fileName,
                sizeBytes = fileInfo?.fileSize ?: 0L,
                md5 = fileInfo?.checksum?.takeIf { it.isNotBlank() },
                source = LlamaCppModelSource.BUILTIN,
                // 内置模型的 gguf 由下载流水线放在 files/models/（与 baseDirFor(HY_MT2_GROUP) 一致）
                dirName = "models",
                builtinModelKey = ModelKey.HY_MT2_GROUP.name,
                hyProfile = true, // 内置 Hy-MT2 恒用专用 prompt 通道（vocab 里有 hy_ 角色标记）
                params = LlamaCppParams.forSource(LlamaCppModelSource.BUILTIN),
            )
        )
        // 老用户全局参数迁移到内置模型（只在第一次补种时做，之后以 per-model 参数为准）
        migrateLegacyParams(list)
        LogCollector.d(TAG, "补种内置模型：$fileName")
    }

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
