package com.moe.starflow.novel.translate

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.data.NovelStorageDir
import com.moe.starflow.novel.reader.NovelChapterRepository
import com.moe.starflow.novel.reader.NovelPanelStyle
import com.moe.starflow.novel.reader.isTranslatable
import com.moe.starflow.novel.translate.NovelImageHost
import com.moe.starflow.novel.translate.NovelParagraphType
import com.moe.starflow.translate.batch.ActiveChapterJob
import com.moe.starflow.translate.batch.ChapterJob
import com.moe.starflow.translate.batch.ChapterJobRunner
import com.moe.starflow.translate.batch.ChapterJobSource
import com.moe.starflow.translate.batch.ChapterJobState
import com.moe.starflow.translate.batch.JobAction
import com.moe.starflow.translate.batch.TranslationJobKind
import com.moe.starflow.translate.batch.TranslationJobRegistry
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.TranslationConcurrency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import translationapi.TranslatorFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一章里的一批（单元）。面板记录列表里那些标「等待」的行就是它 —— **纯内存态**，任务没了就消失。
 *
 * @param ordinal 该批在本章里的序号（0 起，用于「第 N 批」文案）
 * @param paraIndexes 这一批要翻的段号（连续、不跨章、已滤掉有译文的段）
 * @param isImageUnit 这一批是**一张插图**（「同时翻译图片」开着时，一张图 = 一个段 = 一个单位）。
 *   面板那行要显示「第 N 张图」而不是「第 x–y 段」—— 后者在图片单位上只是**同一个数字写两遍**
 *   （x == y），用户看不出这是一张图。
 */
data class NovelWaitingBatch(
    val chapterIndex: Int,
    val ordinal: Int,
    val paraIndexes: List<Int>,
    val isImageUnit: Boolean = false,
) {
    val firstPara: Int get() = paraIndexes.firstOrNull() ?: 0
    val lastPara: Int get() = paraIndexes.lastOrNull() ?: firstPara
}

/**
 * 小说「章批量翻译」的**应用级宿主**（一本书一个，由 [NovelTranslationHub] 按 novelId 缓存）。
 *
 * 为什么要有它（用户口径「翻译本章要能后台跑、除非清后台否则继续翻；结果要能实时上屏」）：
 * 章任务原来挂在阅读器的 `lifecycleScope` 上（`chapterSweepJob`），退出阅读器就随 Activity 没了。
 * 这里把整章任务提到**进程级 scope**，复用漫画那套已经冻结的共享流水线 [ChapterJobRunner]：
 * - **单位是「批」**（不是页）：小说没有 OCR，`ocr` 阶段只是串行的"准备"（原样返回）；
 *   `translate` 阶段**并发**（N = `TranslationConcurrency.novelConcurrency`，本地引擎恒 1 → 串行）
 * - **暂停**只停"取下一批"，队列原样留着（＝面板上的「等待」）；**取消**丢掉还没开始翻的批，
 *   **已翻好的译文保留**（都在 [ChapterJobRunner] 里，与漫画同一套语义）
 * - 进度快照通过 [TranslationJobRegistry] 汇总给前台服务（`TranslationJobService` 每章一条通知）
 *
 * ⚠️ **单位号必须全局唯一**：[ChapterJobRunner] 内部按**自增 `Task.id`** 记在途项（页号只是 Task 的字段），
 * 多章排队时各章的批序号会撞车（第 3 章的第 0 批与第 5 章的第 0 批都是 0）——
 * 所以这里发的是**自增 unitId**，再映射回 (章, 批序号, 段号)。
 */
class NovelChapterJobHost(
    context: Context,
    val book: ImportedNovel,
    private val scope: CoroutineScope,
) : ChapterJobSource {

    private companion object {
        const val TAG = "NovelChapterJob"
    }

    override val key: String get() = "novel:${book.id}"

    private val app: Context = context.applicationContext
    private val appPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(app)
    private val readerPrefs: SharedPreferences =
        app.getSharedPreferences(NovelPanelStyle.PREFS_NAME, Context.MODE_PRIVATE)

    /** 段落 / 目录缓存（宿主自己一份：章任务只用到被提交的那几章）。 */
    private val repository = NovelChapterRepository()

    // ===== 单元账本（unitId → 章/批/段）=====

    private val unitLock = Any()
    private val units = HashMap<Int, NovelWaitingBatch>()
    private val chapterUnits = HashMap<Int, List<Int>>()
    private val nextUnit = AtomicInteger(0)

    // ===== UI 挂载（没有 UI 时照样后台翻） =====

    /** 超长批确认弹窗；**没挂 UI（阅读器已关）时为 null → 预警直接放行**（不能把后台任务卡死）。 */
    @Volatile
    private var uiConfirmOversize: (suspend (estimate: Int, threshold: Int) -> Boolean)? = null

    /** 一批落定后的"刷新一下"回调（宿主把它包成 `runOnUiThread { refreshTranslations() }`）。 */
    @Volatile
    private var uiChanged: (() -> Unit)? = null

    val uiAttached: Boolean get() = uiChanged != null

    /**
     * 是否**曾经**被阅读器挂上过。
     *
     * ⚠️ 与漫画 `ReaderTranslationHub.everAttached` 同一个理由：宿主创建后收集协程会**立刻收到一次**
     * 当前值，而那一刻 Activity 还在 `bindUi()` 之前（`uiAttached` 仍 false）→ 不加这道闸，
     * 刚建好的宿主会被当场 `releaseIfIdle` 回收，Activity 手上留一个已 `shutdownAll()` 的实例，
     * 「翻译本章」从此点不动。
     */
    @Volatile
    var everAttached: Boolean = false
        private set

    private val finishedListeners = CopyOnWriteArrayList<(Int, Int, Int, Boolean) -> Unit>()

    /** 「同一轮只打扰一次」的超长确认器（用户拒过一次 → 同轮后续超长批静默跳过）。 */    private val oversize = NovelOversizeConfirmer { estimate, threshold ->
        uiConfirmOversize?.invoke(estimate, threshold) ?: true
    }

    /**
     * 前台队列**在飞**的段（由阅读器注入）—— 章任务排批时要跳过它们。
     *
     * ⚠️ 去重原先只有**单向**（队列躲章任务；章任务看不见队列）→ 同一段会被两边各发一次请求
     * （用户口径 2026-10-03：「两套任务不要重复烧额度」）。默认空 = 没有前台队列（后台任务场景）。
     */
    @Volatile
    var queueInFlightParas: (chapterIndex: Int) -> Set<Int> = { emptySet() }

    // ===== 翻译器（按值缓存：本地引擎每造一次都要重载模型，别每批现造）=====

    private var translatorCache: NovelChapterTranslator? = null

    private fun translator(): NovelChapterTranslator = translatorCache ?: run {
        val api = TranslatorFactory.createForText(app, CustomPreference.getInstance(app))
            ?: throw IllegalStateException("no translation engine")
        NovelChapterTranslator(
            dao = com.moe.starflow.library.LibrarySessions.current(app).novelParagraphTranslationDao(),
            engine = NovelTranslationEngine(TranslationTextApiAdapter(api)),
            splitVersion = NovelParagraphSplitter.SPLIT_VERSION,
            warnGate = NovelBatchWarnGate(
                threshold = { NovelPanelStyle.batchWarnThreshold(readerPrefs) },
                confirm = { estimate, threshold -> oversize.confirm(estimate, threshold) },
            ),
            // 图片段：整章任务也要能翻插图（开关关着时返回 null = 老行为）
            imageHost = { imageHostOrNull() },
            // 批内插图进度（串行 OCR，一张几秒）→ 通知栏 + 阅读器浮层都要显示"第 i/N 张"
            onImageProgress = { ch, i, n -> onImageStageInternal(ch, i, n) },
            // 一个请求单位落库 → 阅读器立刻上屏（先完成先显示：一张插图 OCR 完就先画出来）
            onUnitSettled = { ch, paras -> onUnitSettled?.invoke(ch, paras) },
        ).also { translatorCache = it }
    }

    /**
     * 插图进度上报（**阅读器挂上来的**，阅读器不在 = null → 只记日志）。
     *
     * ⚠️ 宿主是**应用级**的（阅读器关掉后整章任务继续跑），所以这里**只能**放一个不持有
     * Activity 的回调字段：阅读器 `onStart` 时赋值、`onStop` 时清空（否则就是内存泄漏 +
     * 往已销毁的 Activity 上刷 UI）。null 时整章任务照常跑，只是没有"第 i/N 张"的浮层。
     */
    @Volatile
    var onImageStage: ((chapter: Int, index: Int, total: Int) -> Unit)? = null

    /**
     * **一个请求单位翻完并落库**（`(chapterIndex, 段号)`）→ 阅读器立刻把那一段/那张图上屏。
     *
     * ⚠️ 与 [onImageStage] 同一套纪律：宿主是**应用级**的，这里只能放一个不持有 Activity 的回调，
     * 阅读器 `onStart` 赋值 / `onStop` 清空。null 时任务照跑，只是不实时上屏（回到阅读器读库即可）。
     *
     * 用途见 [NovelChapterTranslator] 的 `onUnitSettled`：整章任务里一批可能发好几个请求
     * （连续文本一个、每张插图各一个），用户要**先完成先显示**，而不是等整批跑完一起出现。
     */
    @Volatile
    var onUnitSettled: ((chapter: Int, paraIndexes: List<Int>) -> Unit)? = null

    /**
     * 图片翻译宿主（**面板开关关着时返回 null** —— 那时整章任务照旧只翻文字）。
     *
     * ⚠️ 每次现读 prefs：开关随时会变，而这份 translator 是按值缓存的。
     * 整章任务在**阅读器已关**时也能跑，所以这里不能依赖任何 UI。
     */
    private fun imageHostOrNull(): NovelImageHost? {
        if (!NovelPanelStyle.translateImages(readerPrefs)) return null
        val pipeline = imagePipeline ?: NovelImagePipeline(app) {
            runCatching { TranslatorFactory.createForText(app, CustomPreference.getInstance(app)) }.getOrNull()
        }.also { imagePipeline = it }
        return NovelImageHost(
            fileOf = { b, ch, para -> imageFileOf(b, ch, para) },
            translate = { file, src, tgt -> pipeline.translate(file, src, tgt) },
        )
    }

    /** 某段插图落盘后的文件（整章任务自己一份仓库：它可能在阅读器关掉之后跑）。 */
    private suspend fun imageFileOf(book: ImportedNovel, chapterIndex: Int, paraIndex: Int): java.io.File? =
        runCatching { imagesRepository.imagesOf(book, chapterIndex)[paraIndex]?.file }.getOrNull()

    /**
     * 插图仓库（**带 `imagesRoot`**：没有它 `imagesOf` 恒返回空表，整章任务就翻不了图）。
     * 与 [repository]（只要段落、不解图）分开，免得每次排批都去碰压缩包。
     */
    private val imagesRepository by lazy {
        NovelChapterRepository(imagesRoot = NovelStorageDir.root(app))
    }

    @Volatile
    private var imagePipeline: NovelImagePipeline? = null

    /** 模型 / 引擎配置变了 → 丢掉缓存的那份（下次用到时按新配置重建）。 */
    fun invalidateTranslator() {
        translatorCache = null
    }

    // ===== 流水线 =====

    private val runner = ChapterJobRunner<Unit>(
        scope = scope,
        concurrency = { TranslationConcurrency.novelConcurrency(app, appPrefs) },
        // 小说没有 OCR：这一段是**串行的准备阶段**，产物就是单位号本身
        ocr = { },
        translate = { unit, _ -> translateUnit(unit) },
        onJobFinished = { chapterIndex, ok, total, cancelled ->
            // 取消后残留的「翻译中」退回未翻译（行是翻之前标的，取消/关进程会把它永久留下）
            if (cancelled) resetStaleTranslating()
            forgetChapter(chapterIndex)
            finishedListeners.forEach { runCatching { it(chapterIndex, ok, total, cancelled) } }
            notifyUiChanged()
        },
    )

    /** 每章一个任务（含暂停/取消状态）——面板据此切按钮文案与「等待」行。 */
    val chapterJobs: StateFlow<List<ChapterJob>> = runner.jobs

    /** 排队中（还没开始翻）的单元号。 */
    val waitingPages: StateFlow<Set<Int>> = runner.waitingPages

    fun jobOf(chapterIndex: Int): ChapterJob? = runner.jobOf(chapterIndex)

    fun isRunning(): Boolean = runner.isBusy()

    /** 身份指纹（= `book.addedAt`）：宿主用它判断"同一个 id 是否已经换了一本书"。 */
    fun translationKeyOf(): String = book.translationKey

    // ===== UI 挂载 =====

    fun bindUi(
        /** 超长批确认（返回 false = 取消这一批）。没挂 UI 时预警直接放行。 */
        confirmOversize: suspend (Int, Int) -> Boolean,
        /** 一批落定 / 任务收尾时回调（宿主负责切主线程刷新界面）。 */
        onChanged: () -> Unit,
    ) {
        uiConfirmOversize = confirmOversize
        uiChanged = onChanged
        everAttached = true
    }

    fun unbindUi() {
        uiConfirmOversize = null
        uiChanged = null
    }

    fun addJobFinishedListener(listener: (Int, Int, Int, Boolean) -> Unit) {
        finishedListeners += listener
    }

    fun removeJobFinishedListener(listener: (Int, Int, Int, Boolean) -> Unit) {
        finishedListeners -= listener
    }

    private fun notifyUiChanged() {
        val cb = uiChanged ?: return
        runCatching { cb() }
            .onFailure { LogCollector.w(TAG, "刷新阅读器界面失败", it) }
    }

    // ===== 章节任务 API =====

    /**
     * 提交一章的批量翻译：把「还没翻的段」按面板的「每批段数」切成批，交给流水线。
     *
     * @param retranslateAll true = 连已有译文一起重翻（覆盖）；false = 只翻没翻成的
     * @param label 章标题（通知栏文案要用；阅读器关掉后拿不到目录）
     * @param onEmpty 这一章没有可翻的段时回调（**在后台线程**调，调用方自己切主线程）
     */
    fun startChapter(
        chapterIndex: Int,
        label: String,
        retranslateAll: Boolean = false,
        onEmpty: (() -> Unit)? = null,
    ) {
        val b = book
        scope.launch {
            val paragraphs = runCatching { repository.paragraphsOf(b, chapterIndex) }
                .getOrDefault(emptyList())
            // 「同时翻译图片」开着时，插图段也算一个可翻单位（一张图 = 一个段 = 一次 OCR + 一次请求）
            val includeImages = NovelPanelStyle.translateImages(readerPrefs)
            val paras = paragraphs
                .filter { it.isTranslatable() || (includeImages && it.type == NovelParagraphType.IMAGE) }
                .map { it.index }
            val imageParas = if (includeImages) {
                paragraphs.filter { it.type == NovelParagraphType.IMAGE }.map { it.index }.toSet()
            } else {
                emptySet()
            }
            val done = if (retranslateAll) {
                emptySet()
            } else {
                runCatching { translator().loadTranslations(b, chapterIndex) }
                    .getOrDefault(emptyMap()).keys +
                    // ⚠️ **反向去重**（用户口径 2026-10-03）：原先只有"队列躲章任务"这一向，
                    // 章任务排批时看不见前台队列正在翻的段 → 同一段被两边各发一次请求（白烧额度）。
                    runCatching { queueInFlightParas(chapterIndex) }.getOrDefault(emptySet())
            }
            val plan = NovelBatchPlanner.planBatches(
                chapterParaIndexes = paras,
                translated = done,
                batchSize = NovelPanelStyle.batchSize(readerPrefs),
                // ⚠️ **插图不拆批**（用户口径 2026-10-04：「图片算段落的一段！每批 3 段，
                // 哪怕三张都是图片，也要先后翻译三张图片」）：批按每批段数切，
                // 批内的插图由 `NovelChapterTranslator` 串行 OCR（它自己拿 OCR 锁）
            )
            if (plan.isEmpty()) {
                LogCollector.i(TAG, "第 $chapterIndex 章没有待翻的段落")
                runCatching { onEmpty?.invoke() }
                notifyUiChanged()
                return@launch
            }
            // 用户明确要求翻 → 新一轮任务，超长确认重新允许弹窗
            oversize.reset()
            val ids = ArrayList<Int>(plan.size)
            synchronized(unitLock) {
                // 同一章重新提交：旧单元作废（unitId 自增，不会与新的撞）
                chapterUnits.remove(chapterIndex)?.forEach { units.remove(it) }
                plan.forEachIndexed { ordinal, batch ->
                    val id = nextUnit.incrementAndGet()
                    // 批里**全是插图**时面板行标「一张插图」；混着文本就照旧报段号区间
                    units[id] = NovelWaitingBatch(
                        chapterIndex, ordinal, batch,
                        isImageUnit = batch.isNotEmpty() && batch.all { it in imageParas },
                    )
                    ids += id
                }
                chapterUnits[chapterIndex] = ids
            }
            LogCollector.i(TAG, "章任务提交 ch=$chapterIndex 共 ${ids.size} 批（每批 ${NovelPanelStyle.batchSize(readerPrefs)} 段）")
            runner.submit(chapterIndex, ids, label, startPage = 0)
        }
    }

    fun pause(chapterIndex: Int) = runner.pause(chapterIndex)

    fun resume(chapterIndex: Int) = runner.resume(chapterIndex)

    fun cancel(chapterIndex: Int) = runner.cancel(chapterIndex)

    suspend fun shutdownAndJoin() {
        runner.shutdownAndJoin()
        shutdownAll()
    }

    /** 停掉这本书的全部章节任务（换书 / 退出且不留任务）。 */
    fun shutdownAll() {
        runner.shutdown()
        synchronized(unitLock) {
            units.clear()
            chapterUnits.clear()
        }
    }

    // ===== 「等待」的批（面板记录列表用）=====

    /** 该章**还没开始翻**的批，按序号排。纯内存：任务结束/取消后自然为空。 */
    fun waitingBatches(chapterIndex: Int): List<NovelWaitingBatch> {
        val ids = synchronized(unitLock) { chapterUnits[chapterIndex] } ?: return emptyList()
        // 「等待」= 队列里还没取的 + **已进流水线但还没轮到准备的**（`QUEUED`，见 ChapterTaskStage）
        // —— 后者以前被算成"在飞"，面板会把它显示成「翻译中」，与"还没开始"直接矛盾
        val waiting = runner.waitingPages.value + runner.queuedPages()
        return ids.filter { it in waiting }
            .mapNotNull { id -> synchronized(unitLock) { units[id] } }
    }

    /** 所有正在跑的章各自的「等待」批（面板一次性取用）。 */
    fun waitingByChapter(): Map<Int, List<NovelWaitingBatch>> {
        val chapters = runner.jobs.value.filter { it.isActive }.map { it.chapterIndex }
        if (chapters.isEmpty()) return emptyMap()
        return chapters.associateWith { waitingBatches(it) }.filterValues { it.isNotEmpty() }
    }

    /**
     * 该章**正在提交/等待服务端返回**的批（请求已发出或正等响应）。
     *
     * 用户口径（2026-09-27）：「正在提交等待返回的批次片段**背景要高亮处理**」——
     * 这些批既不在「等待」里（队列已经取走）、也还没写库（没有译文），
     * 只靠章卡片上的进度数字看不出"现在轮到哪几批"，所以单独暴露给面板。
     *
     * ⚠️ 判据是 **`translatingPages()`（翻译段）**，不是 `inFlightPages()`（含"已预取还没开始"）：
     * 小说没有 OCR，准备阶段是个空操作，真正该高亮的只有**已经进翻译段**的那几批
     * （与漫画面板「识别中 / 翻译中」分开显示同一口径，2026-09-28）。
     */
    fun activeBatches(chapterIndex: Int): List<NovelWaitingBatch> {
        val ids = synchronized(unitLock) { chapterUnits[chapterIndex] } ?: return emptyList()
        val inflight = runner.translatingPages()
        return ids.filter { it in inflight }
            .mapNotNull { id -> synchronized(unitLock) { units[id] } }
    }

    /**
     * 该章**已被章节任务占住**的段（在途 + 排队中）。
     *
     * ⚠️ 给阅读器的自动/增量队列做去重用：它们只看库里的 SUCCESS 行，而章任务的段在翻完前
     * 库里还是 IDLE —— 不排除就会**同一段同时发两次请求**（白烧额度、两倍内存）。
     */
    fun inFlightAndWaitingParas(chapterIndex: Int): Set<Int> {
        val ids = synchronized(unitLock) { chapterUnits[chapterIndex] } ?: return emptySet()
        // `ChapterJobRunner` 自带锁，这里不用 host 的 unitLock 包它（避免锁序交叉）
        val owned = runner.runningOwnedPages() + runner.waitingPages.value
        return ids.filter { it in owned }
            .flatMap { id -> synchronized(unitLock) { units[id]?.paraIndexes.orEmpty() } }
            .toSet()
    }

    /** 正在翻的批（所有章）—— 面板一次性取用，与 [waitingByChapter] 同一套形状。 */
    fun activeByChapter(): Map<Int, List<NovelWaitingBatch>> {
        val chapters = runner.jobs.value.filter { it.isActive }.map { it.chapterIndex }
        if (chapters.isEmpty()) return emptyMap()
        return chapters.associateWith { activeBatches(it) }.filterValues { it.isNotEmpty() }
    }

    // ===== 内部 =====

    /**
     * 翻**一个批**。返回 true = 真的翻出来了（成功计数用）。
     *
     * ⚠️ 这里**不持 `OcrLock`**：与漫画的 `translatePhase` 同一口径 —— 那把锁是 **OCR 引擎**锁，
     * 小说的翻译请求不碰 OCR。本地引擎的"串行"由 `novelConcurrency() == 1` 保证。
     */
    /** 含插图批的门闩（见 [translateUnit] 里的说明）：保证插图 OCR 一批一批来。 */
    private val imageGate = kotlinx.coroutines.sync.Mutex()

    /** 这一批里有没有插图（含图就要走 [imageGate]）。 */
    private suspend fun unitHasImage(unit: NovelWaitingBatch): Boolean {
        if (!NovelPanelStyle.translateImages(readerPrefs)) return false
        return runCatching {
            repository.paragraphsOf(book, unit.chapterIndex)
                .any { it.index in unit.paraIndexes && it.type == NovelParagraphType.IMAGE }
        }.getOrDefault(false)
    }

    private suspend fun translateUnit(unitId: Int): Boolean {
        val unit = synchronized(unitLock) { units[unitId] } ?: return false
        // ⚠️ 开翻前先推一次 UI：用户要求「正在提交/等待返回的批次**背景高亮**」——
        // 只在"批结束"时推的话，5 个批同时起飞要等第一个返回才看得见高亮（面板像是卡住了）。
        notifyUiChanged()
        // ⚠️ **含插图的批之间串行**（整章任务的并发 N 会同时起好几个批）：插图 OCR 用的是
        // 进程级单例引擎（`OcrLock`），同时起 3 个含图批只会让后两个在锁上干等；等满 20s 拿不到
        // 就会被当成失败（`NovelImagePipeline`），而一条正常路径不该出现"因为排队而失败"。
        // 这里用一把门闩把含图批排成队（**不影响纯文本批的并发**；锁在 try/finally 里成对放）。
        val hasImage = unitHasImage(unit)
        val paras = repository.paragraphsOf(book, unit.chapterIndex)
        val src = CustomPreference.getInstance(app).getString("Source_Language", "auto")
        val tgt = CustomPreference.getInstance(app).getString("Target_Language", "zh")
        val result = try {
            if (hasImage) imageGate.lock()
            try {
                translator().translateBatch(
                    book = book,
                    chapterIndex = unit.chapterIndex,
                    paragraphs = paras,
                    paraIndexes = unit.paraIndexes,
                    sourceLang = src,
                    targetLang = tgt,
                    translatorName = "novel",
                )
            } finally {
                if (hasImage) imageGate.unlock()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "第 ${unit.chapterIndex} 章一批翻译失败", e)
            NovelBatchResult(emptyMap(), "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        }
        // 结果**实时上屏**：挂着的阅读器立刻重读本章译文（没挂 UI 就直接返回）
        notifyUiChanged()
        if (result.isEmpty && result.error != null) {
            LogCollector.w(TAG, "第 ${unit.chapterIndex} 章一批没翻成：${result.error}")
        }
        // ⚠️ **用户在前面的超长预警里选了「取消本次翻译」**（用户口径 2026-10-03：
        // 「可以取消本次翻译（关闭当前翻译进程，回退手动）」）：预警器会记住"这一轮已被拒绝"，
        // 本轮后续超长批**静默跳过** —— 不在这里收尾的话，整章会被一批批跳过、任务却报"翻完"。
        if (result.isEmpty && oversize.hasDeclined()) {
            LogCollector.i(TAG, "用户在超长预警里取消 → 关闭第 ${unit.chapterIndex} 章任务")
            cancel(unit.chapterIndex)
        }
        return !result.isEmpty
    }

    private fun forgetChapter(chapterIndex: Int) {
        // ⚠️ **插图进度也要跟着忘掉**：批在插图 OCR 中途被取消/关进程时，那次"清零上报"
        // 不会执行 —— 留着的话下一次这一章的任务会把上一轮的"正在识别插图 2/3…"重新发到通知栏。
        imageStage.remove(chapterIndex)
        synchronized(unitLock) {
            chapterUnits.remove(chapterIndex)?.forEach { units.remove(it) }
        }
    }

    /**
     * 取消后的清理：把残留的「翻译中」退回未翻译。
     *
     * ⚠️ 用的是**整本**口径的 `resetTranslating`（DAO 没有按章的重置，而 data/ 不在本次改动范围内）——
     * 所以只在**这本书没有别的活动任务**时才清，免得把别的章正在跑的批的「翻译中」标记一起抹掉。
     * 漏掉的那些行不影响正确性：读回只认 SUCCESS，进阅读器时 `resetStale` 还会再清一次。
     */
    private fun resetStaleTranslating() {
        if (runner.jobs.value.any { it.isActive }) return
        scope.launch {
            runCatching {
                com.moe.starflow.library.LibrarySessions.current(app).novelParagraphTranslationDao()
                    .resetTranslating(book.id, book.translationKey)
            }.onFailure { LogCollector.w(TAG, "清理残留翻译中状态失败", it) }
        }
    }

    // ===== ChapterJobSource（由 NovelTranslationHub 聚合）=====

    fun snapshotOfBook(): List<ActiveChapterJob> = runner.jobs.value.map { job ->
        ActiveChapterJob(
            kind = TranslationJobKind.NOVEL,
            bookId = book.id,
            libraryLocationId = book.libraryLocationId,
            bookUuid = book.bookUuid,
            bookTitle = book.title,
            chapterIndex = job.chapterIndex,
            chapterLabel = job.label,
            total = job.total,
            done = job.done,
            state = job.state,
            startPage = 0,
            // 这一章正在逐张 OCR 插图 → 通知栏报「正在识别插图 i/N…」（阅读器关掉时也看得到）
            stageText = imageStageTextOf(job.chapterIndex),
        )
    }

    /**
     * 插图进度文案（`章 → 文案`）。
     *
     * ⚠️ **按章存**：整章任务可以多章并行，共用一个字段会让 A 章的"第 2/3 张"串到 B 章的通知上。
     * 由 `onImageProgress` 写入（`index = 0` = 这一批插图跑完 → 删掉这一章的键）。
     */
    private val imageStage = ConcurrentHashMap<Int, String>()

    /** 这一章当前的插图进度文案（没有 = null）。阅读器的整章芯片也读它。 */
    fun imageStageTextOf(chapterIndex: Int): String? = imageStage[chapterIndex]

    /** 插图进度：写文案 + 让通知栏/面板立刻刷新（[TranslationJobRegistry.notifyChanged]）。 */
    private fun onImageStageInternal(chapterIndex: Int, index: Int, total: Int) {
        if (index <= 0 || total <= 0) {
            imageStage.remove(chapterIndex)
        } else {
            imageStage[chapterIndex] = "正在识别插图 $index/$total…"
        }
        runCatching { TranslationJobRegistry.notifyChanged() }
        // 阅读器挂着的话，它自己的芯片也要跟上（宿主只负责通知栏那条）
        onImageStage?.invoke(chapterIndex, index, total)
    }

    override fun snapshot(): List<ActiveChapterJob> = snapshotOfBook()

    override fun onAction(action: JobAction, job: ActiveChapterJob): Boolean {
        if (job.kind != TranslationJobKind.NOVEL || job.bookId != book.id) return false
        val known = runner.jobOf(job.chapterIndex) ?: return false
        when (action) {
            JobAction.PAUSE -> runner.pause(job.chapterIndex)
            JobAction.RESUME -> runner.resume(job.chapterIndex)
            JobAction.CANCEL -> runner.cancel(job.chapterIndex)
        }
        LogCollector.d(TAG, "通知动作 $action ch=${job.chapterIndex}（任务 ${known.state}）")
        return true
    }
}

/**
 * 小说章节翻译的**应用级宿主注册表**（与漫画 `ReaderTranslationHub` 同一套结构）。
 *
 * - 按 novelId 缓存 [NovelChapterJobHost]（同一本书永远只有一份实例：任务/记录不会分裂）
 * - 实现 [ChapterJobSource] 并注册进 [TranslationJobRegistry] → 前台服务/通知栏自动出现进度
 * - 任务跑完且**没有 UI 挂着** → [releaseIfIdle] 回收（连同收集协程一起取消）
 */
object NovelTranslationHub : ChapterJobSource {

    private const val TAG = "NovelTranslationHub"

    override val key: String = "novel"

    /** 应用级 scope：进程活多久它活多久（后台任务靠的就是它）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        LogCollector.e(TAG, "书库任务已结束或提交失败", error)
    })

    private val hosts = ConcurrentHashMap<String, NovelChapterJobHost>()

    /** 每个宿主一个「任务状态变化」收集协程；回收宿主时必须一并取消（照漫画那份）。 */
    private val collectors = ConcurrentHashMap<String, Job>()

    init {
        TranslationJobRegistry.register(this)
    }

    /** 取（或创建）某本书的宿主。旧实例里的书标题可能过期（书架改名），这里以调用方传的为准。 */
    @Synchronized
    fun hostFor(context: Context, book: ImportedNovel): NovelChapterJobHost {
        // ⚠️ 与漫画侧同一个理由：小说 id 也是"清单最大 id + 1"，删书后重导会**复用 id**，
        // 而译文身份是 `addedAt`（novelKey）。只按 id 命中会把旧宿主（连同旧书的 repo 缓存、
        // 旧 novelKey）交给新书 → 新书按旧指纹读写译文（看着正常、重启后译文"消失"且清不掉）。
        hosts[book.translationKey]?.let { cached ->
            if (cached.translationKeyOf() == book.translationKey) return cached
            LogCollector.i("NovelChapterJob", "novelId=${book.id} 身份指纹变了（删除后复用 id）→ 重建宿主")
            // ⚠️ 必须**无条件**摘掉：走 releaseIfIdle 的话，旧宿主正在跑任务/还挂着 UI 时它会直接
            // return，而我们紧接着就把 hosts/collectors 覆盖掉 —— 旧宿主从此不可达、它的 runner
            // 还在烧额度、收集协程永远活着（漫画侧 ReaderTranslationHub 就是无条件 remove+cancel）。
            hosts.remove(book.translationKey)
            collectors.remove(book.translationKey)?.cancel()
            cached.shutdownAll()
        }
        val host = NovelChapterJobHost(com.moe.starflow.library.LibrarySessions.forLocation(context, book.libraryLocationId).context(), book, scope)
        hosts[book.translationKey] = host
        collectors[book.translationKey] = scope.launch {
            host.chapterJobs.collect {
                TranslationJobRegistry.notifyChanged()
                if (!host.everAttached) return@collect
                if (!host.uiAttached && !host.isRunning()) releaseIfIdle(book.id, book.translationKey)
            }
        }
        LogCollector.i(TAG, "创建小说章节翻译宿主 id=${book.id} title=${book.title}")
        return host
    }


    /**
     * 没有 UI 挂着、也没有任务在跑 → 回收宿主（释放段落/目录缓存与单元账本）。
     * ⚠️ 必须判 `uiAttached`：阅读器正开着时不能回收。
     */
    @Synchronized
    fun releaseIfIdle(novelId: Long, bookKey: String? = null) {
        val mapKey = bookKey ?: hosts.values.firstOrNull { it.snapshotOfBook().any { job -> job.bookId == novelId } }?.translationKeyOf() ?: return
        val host = hosts[mapKey] ?: return
        if (host.uiAttached || host.isRunning()) return
        hosts.remove(mapKey)
        collectors.remove(mapKey)?.cancel()
        host.shutdownAll()
        TranslationJobRegistry.notifyChanged()
        LogCollector.i(TAG, "回收小说章节翻译宿主 id=$novelId（无 UI 且无任务）")
    }

    // ===== ChapterJobSource =====

    suspend fun shutdownBook(key: String) {
        val host = synchronized(this) {
            collectors.remove(key)?.cancel()
            hosts.remove(key)
        } ?: return
        host.shutdownAndJoin()
        TranslationJobRegistry.notifyChanged()
    }

    override fun snapshot(): List<ActiveChapterJob> = hosts.values.flatMap { it.snapshotOfBook() }

    override fun onAction(action: JobAction, job: ActiveChapterJob): Boolean {
        if (job.kind != TranslationJobKind.NOVEL) return false
        val host = hosts[job.runtimeKey] ?: return false
        val handled = host.onAction(action, job)
        if (action == JobAction.CANCEL) releaseIfIdle(job.bookId, job.runtimeKey)
        return handled
    }

}
