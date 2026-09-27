package com.moe.starflow.novel.translate

import com.moe.starflow.manga.config.TranslationTextRules

/**
 * 一个待翻单元：**真实 paraIndex** + 原文。
 *
 * ⚠️ 为什么要专门包一层，而不是传两个平行数组（`texts` + `paraIndices`）：平行数组太容易
 * 传反，而一旦传反，译文会被写到**错误的段落下标**上 —— 这是静默错位，显示出来只是
 * 「某几段翻译得不对」，极难定位。用数据类把两者绑死，从类型上消灭这个错误。
 *
 * ⚠️ 更要紧的是**不能用「在文本列表里的位置」当 paraIndex**：一章里只要出现图片段或过短段
 * （占 index 但不参与翻译），位置与真实 paraIndex 就错开了，译文会整体偏移。
 */
data class NovelUnit(val paraIndex: Int, val text: String)

/**
 * 小说翻译的**编号协议**（拼提示词 / 解析回复）。
 *
 * ### 分批规则不在这里
 * 成批规则是 `NovelBatchPlanner.nextBatch`（按面板的「每批段数」切），批次大小由用户在
 * 面板里配。这个类只管「怎么把一批段拼成请求」和「怎么把回复对应回段号」。
 *
 * ⚠️ 这里曾经有一套自己的 `buildBatches`（默认 8 段一批 + 超长段独占一批），
 * 生产路径从来没调用过它 —— 于是类注释写的「默认 8 段一批（可配）」和实际默认值
 * （面板的每批段数，默认 3）长期不一致，读注释的人会按错的口径理解。已删。
 *
 * ### 为什么分批规则要单独拎成纯函数
 * 参考实现 (Kototoro) 的 20 段批次被一条「文本长度 ≥ 28 就单独成批」的规则拆成了
 * **每段一个 HTTP 请求且串行** —— 那是为漫画 OCR 的噪声短文本设计的启发式，套到小说上
 * 让批次形同虚设，是它小说翻译慢的根因。所以成批规则必须能被单测钉住（见 `NovelBatchPlannerTest`）。
 *
 * ### 与漫画链路的关系
 * 漫画的 `TranslateUtils.parseNumberedTranslations(text, expectedCount)` 是**按序号补位**
 * 的（第 n 条译文归第 n 个气泡），因为它假设模型按顺序回。小说的语义不同：编号用的是
 * **真实 `paraIndex`**，要按编号**对应**回去。所以解析自己写；点串归一化仍复用
 * [TranslationTextRules.normalizeEllipsis]（那是唯一来源，别处不要另写一份）。
 */
object NovelTranslationBatch {

    /**
     * 位置兜底时的长度比下限（原文字符数 ≥ [MIN_RATIO_SOURCE_MIN] 才启用）。
     *
     * 见 [parseByPosition] 的说明：拒绝语远短于长段落，这个比值能把它们挡掉。
     * 3 是留了余量取的（中/日/英互译的译文长度都在原文的 1/3~3 倍之间）。
     */
    private const val MIN_POSITIONAL_LENGTH_RATIO = 3

    /** 短于此长度的原文不做长度比判断（那个量级上比值没有区分力）。 */
    private const val MIN_RATIO_SOURCE_MIN = 20

    /**
     * 拼成 `[5] 第一段\n[9] 第二段`。
     *
     * 编号用**真实 paraIndex**：模型回什么号我们就写回哪一段，不依赖它保持顺序。
     * [paraIndices] 里出现而 [units] 里没有的号会被跳过（不会拼出空条目）。
     */
    fun buildPrompt(units: List<NovelUnit>, paraIndices: List<Int>): String {
        val byIndex = units.associateBy { it.paraIndex }
        return paraIndices.mapNotNull { pi ->
            byIndex[pi]?.let { "[$pi] ${it.text}" }
        }.joinToString("\n")
    }

    /**
     * 把模型回复解析回 `paraIndex -> 译文`。
     *
     * ⚠️ **缺号不补空串**：空串会被上层当成「成功译文」写进库，之后该段永远显示空白
     * 且再也不会被重试。缺的号就是不返回，由上层保持 IDLE 让下一轮重挑。
     *
     * 多返回的编号（模型串号 / 幻觉）一律忽略 —— 只认 [paraIndices] 里出现过的。
     */
    fun parse(reply: String, paraIndices: List<Int>): Map<Int, String> {
        if (reply.isBlank() || paraIndices.isEmpty()) return emptyMap()
        val allowed = paraIndices.toSet()
        val out = linkedMapOf<Int, String>()
        for (m in NUMBERED.findAll(reply)) {
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            if (idx !in allowed) continue
            val text = TranslationTextRules.normalizeEllipsis(m.groupValues[2].trim())
            if (text.isNotEmpty()) out[idx] = text
        }
        return out
    }

    /**
     * 容错解析：先按编号对应；模型**完全丢掉编号**时才按位置兜底。
     *
     * 位置兜底**必须条数完全一致**才接受：模型少回/多回/合并了段落时条数就对不上，
     * 此时宁可返回空让上层标记失败重试，也不能猜着对应 —— 猜错就是把 A 段译文写到 B 段，
     * 用户看到的只是「某几段翻了但不对」，比整章未翻译难查得多。
     *
     * ⚠️ 条数一致**还不够**（真实踩过）：只请求一段时条数一致不携带任何信息 ——
     * 任何非空回复切出来都是 1 条。所以位置兜底还要过 [looksLikeTranslation] 的合理性闸门，
     * 否则模型那句「抱歉，我无法翻译这段内容。」会被当成译文写进库。
     *
     * （漫画链路同样有这层兜底，见 `TranslateUtils.parseNumberedTranslations` 的降级分支。）
     *
     * @param sourceOf 取某段的原文（合理性闸门用；拿不到就传空串）
     */
    fun parseTolerant(
        reply: String,
        paraIndices: List<Int>,
        sourceOf: (Int) -> String = { "" },
    ): Map<Int, String> {
        parse(reply, paraIndices).let { if (it.isNotEmpty()) return it }
        return parseByPosition(reply, paraIndices, sourceOf)
    }

    /**
     * 按位置兜底。**全部条目通过合理性闸门才返回**，否则返回空表。
     *
     * 返回空表的上层行为是「标失败 + 把模型原话报给用户」（见
     * `NovelTranslationEngine.requestAndParse`），这正是想要的：一句拒绝语被报成
     * "这段翻译失败：返回内容无法解析为编号段落：抱歉，我无法翻译这段内容。" 用户可以处理；
     * 而被当成成功译文写进库里则**永久错误且不会再重试**。
     */
    fun parseByPosition(
        reply: String,
        paraIndices: List<Int>,
        sourceOf: (Int) -> String,
    ): Map<Int, String> {
        if (paraIndices.isEmpty()) return emptyMap()
        for (chunks in listOf(splitByBlankLine(reply), splitByLine(reply))) {
            if (chunks.size != paraIndices.size) continue
            val out = paraIndices.zip(chunks).toMap()
            if (out.all { (pi, t) -> looksLikeTranslation(sourceOf(pi), t) }) return out
        }
        return emptyMap()
    }

    /**
     * 这条回复像不像**译文**（位置兜底的合理性闸门）。
     *
     * 判据只有长度比：真实译文与原文同量级（中/日/英互译在 1/3~3 倍之间），
     * 而模型的拒绝语/元回答（「抱歉，我无法翻译这段内容。」）远短于一段小说正文。
     *
     * ⚠️ 这是**启发式，不是证明**：原文短到几字时长度比没有区分力，所以
     * [MIN_RATIO_SOURCE_MIN] 以下直接放行。彻底解决要靠上层把回复判失败，
     * 但那条路会把「模型不重复编号」的正常回复也判掉（每批 1 段时很常见），代价更大。
     */
    fun looksLikeTranslation(source: String, translation: String): Boolean {
        if (translation.isBlank()) return false
        if (source.length < MIN_RATIO_SOURCE_MIN) return true
        return translation.length * MIN_POSITIONAL_LENGTH_RATIO >= source.length
    }

    private fun splitByBlankLine(reply: String): List<String> =
        reply.split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun splitByLine(reply: String): List<String> =
        reply.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** `[12] 译文`，跨行捕获（模型可能把一条译文写成多行）。 */
    private val NUMBERED = Regex("""\[(\d+)]\s*([\s\S]*?)(?=\n\s*\[\d+]|\s*$)""")
}
