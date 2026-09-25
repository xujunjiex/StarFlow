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
 * 小说翻译的分批与编号协议。
 *
 * ### 为什么分批规则要单独拎成纯函数
 * 参考实现 (Kototoro) 的 20 段批次被一条「文本长度 ≥ 28 就单独成批」的规则拆成了
 * **每段一个 HTTP 请求且串行** —— 那是为漫画 OCR 的噪声短文本设计的启发式，套到小说上
 * 让批次形同虚设，是它小说翻译慢的根因。这里的规则必须能被单测钉住，避免回归。
 *
 * 规则：
 * - 默认 8 段一批（可配）
 * - **只有单段字符数超过 [MAX_BATCH_CHARS] 时才单独成批**（模型上下文压力）
 * - 普通长段落（几百字）照常成批 —— 这正是与参考实现的关键差别
 *
 * ### 与漫画链路的关系
 * 漫画的 `TranslateUtils.parseNumberedTranslations(text, expectedCount)` 是**按序号补位**
 * 的（第 n 条译文归第 n 个气泡），因为它假设模型按顺序回。小说的语义不同：编号用的是
 * **真实 `paraIndex`**，要按编号**对应**回去。所以解析自己写；点串归一化仍复用
 * [TranslationTextRules.normalizeEllipsis]（那是唯一来源，别处不要另写一份）。
 */
object NovelTranslationBatch {

    const val DEFAULT_BATCH_PARAGRAPHS = 8

    /** 单段字符上限，超过则独占一批。 */
    const val MAX_BATCH_CHARS = 1500

    /**
     * 按批分组。返回的每一批是**真实 paraIndex 的列表**（不是位置）。
     */
    fun buildBatches(units: List<NovelUnit>, batchSize: Int): List<List<Int>> {
        val size = batchSize.coerceAtLeast(1)
        val out = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for (u in units) {
            if (u.text.length > MAX_BATCH_CHARS) {
                if (current.isNotEmpty()) {
                    out.add(current)
                    current = mutableListOf()
                }
                out.add(listOf(u.paraIndex))
                continue
            }
            current.add(u.paraIndex)
            if (current.size >= size) {
                out.add(current)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

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

    /** `[12] 译文`，跨行捕获（模型可能把一条译文写成多行）。 */
    private val NUMBERED = Regex("""\[(\d+)]\s*([\s\S]*?)(?=\n\s*\[\d+]|\s*$)""")
}
