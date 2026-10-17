package com.moe.starflow.novel.parser

import java.util.Locale
import kotlin.math.abs

/** Extracts the same plain text as [HtmlTextExtractor], while retaining CSS font-size ranges. */
internal object EpubFontSizeExtractor {

    data class ExtractedChapter(
        val text: String,
        val fontSizeSpans: List<NovelFontSizeSpan>,
        val imageSources: List<String>,
    )

    private val blockTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote", "section", "article", "dd", "dt", "pre")
    private val blockMarkup = Regex(
        """</?(p|div|h[1-6]|li|tr|blockquote|section|article|dd|dt|pre)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val brMarkup = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val imageMarkup = Regex("""<(?:img|image)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val linkMarkup = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val styleBlock = Regex("""<style\b[^>]*>(.*?)</style\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val attribute = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val rule = Regex("""([^{}]+)\{([^{}]*)\}""")
    private val declaration = Regex("""([\w-]+)\s*:\s*([^;]+)""")

    private data class Element(val name: String, val attrs: Map<String, String>, val scale: Float)
    private data class ParsedTag(val name: String, val closing: Boolean, val selfClosing: Boolean, val attrs: Map<String, String>)
    private data class SelectorPart(val simple: String, val directParent: Boolean)
    private data class FontDeclaration(
        val value: String,
        val important: Boolean,
        val specificity: Int,
        val order: Int,
    )
    private data class CssRule(val selector: List<SelectorPart>, val specificity: Int, val order: Int, val declaration: FontDeclaration)

    /** Stylesheet hrefs in document order. The caller resolves them relative to the XHTML entry. */
    fun stylesheetHrefs(html: String): List<String> = linkMarkup.findAll(html).mapNotNull { match ->
        val attrs = attrsOf(match.value)
        val rel = attrs["rel"].orEmpty().split(Regex("\\s+")).any { it.equals("stylesheet", true) }
        val href = attrs["href"]?.trim()?.takeIf(String::isNotEmpty)
        href?.takeIf { rel }
    }.toList()

    fun extract(html: String, stylesheets: List<String>): ChapterText =
        extractChapter(html, stylesheets).let { ChapterText(it.text, it.fontSizeSpans) }

    fun extractChapter(html: String, stylesheets: List<String>): ExtractedChapter {
        val rules = parseRules(stylesheets + styleBlock.findAll(html).map { it.groupValues[1] }.toList())
        val source = HtmlTextExtractor.stripClosedBlock(
            HtmlTextExtractor.stripClosedBlock(
                HtmlTextExtractor.stripClosedBlock(html, "script"), "style",
            ), "head",
        )
        val output = StyledTextBuilder()
        val imageSources = mutableListOf<String>()
        val stack = mutableListOf<Element>()
        var cursor = 0
        while (cursor < source.length) {
            if (source[cursor] != '<') {
                val end = source.indexOf('<', cursor).let { if (it < 0) source.length else it }
                val raw = source.substring(cursor, end)
                output.append(HtmlTextExtractor.decodeEntities(raw), stack.lastOrNull()?.scale ?: 1f)
                cursor = end
                continue
            }
            val end = source.indexOf('>', cursor + 1)
            if (end < 0) {
                output.append("<", stack.lastOrNull()?.scale ?: 1f)
                cursor++
                continue
            }
            val markup = source.substring(cursor, end + 1)
            val tag = parseTag(markup)
            cursor = end + 1
            if (tag == null) continue
            val name = tag.name
            val closing = tag.closing
            val selfClosing = tag.selfClosing
            val attrs = tag.attrs
            if (name == "br" && brMarkup.matches(markup)) {
                output.append("\n", 1f)
                continue
            }
            if ((name == "img" || name == "image") && imageMarkup.matches(markup)) {
                imageSources.add(HtmlTextExtractor.firstImageSource(markup).orEmpty())
                output.append("\n\n${HtmlTextExtractor.IMAGE_MARK}\n\n", 1f)
                continue
            }
            if (name in blockTags && blockMarkup.matches(markup)) {
                output.append("\n\n", 1f)
                continue
            }
            if (closing) {
                val found = stack.indexOfLast { it.name == name }
                if (found >= 0) while (stack.size > found) stack.removeAt(stack.lastIndex)
                continue
            }
            val parentScale = stack.lastOrNull()?.scale ?: 1f
            val element = Element(name, attrs, computedScale(name, attrs, stack, rules, parentScale))
            if (!selfClosing && name !in VOID_TAGS) stack.add(element)
        }
        val chapter = output.finish()
        // Keep extraction resilient to malformed XHTML: if our style-aware scan ever differs from
        // the established text extractor, retain the known-good text and simply omit styling.
        val plain = HtmlTextExtractor.extract(html)
        return if (chapter.text == plain) {
            ExtractedChapter(chapter.text, chapter.fontSizeSpans, imageSources)
        } else {
            val extracted = HtmlTextExtractor.extractWithImages(html)
            ExtractedChapter(extracted.text, emptyList(), extracted.imageSources)
        }
    }

    private fun parseTag(markup: String): ParsedTag? {
        if (markup.length < 3) return null
        var start = 1
        while (start < markup.length - 1 && markup[start].isWhitespace()) start++
        val closing = start < markup.length - 1 && markup[start] == '/'
        if (closing) start++
        val nameStart = start
        while (start < markup.length - 1 && (markup[start].isLetterOrDigit() || markup[start] in "-:_")) start++
        if (start == nameStart) return null
        val name = markup.substring(nameStart, start).lowercase(Locale.ROOT)
        val selfClosing = markup.dropLast(1).trimEnd().endsWith('/')
        return ParsedTag(name, closing, selfClosing, if (closing) emptyMap() else attrsOf(markup))
    }

    private fun attrsOf(markup: String): Map<String, String> = attribute.findAll(markup).associate {
        it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
    }

    private fun parseRules(stylesheets: List<String>): List<CssRule> {
        val out = mutableListOf<CssRule>()
        var order = 0
        for (css in stylesheets) {
            val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            for (match in rule.findAll(clean)) {
                val selectors = match.groupValues[1].split(',')
                val font = declaration.findAll(match.groupValues[2]).firstNotNullOfOrNull { d ->
                    if (d.groupValues[1].equals("font-size", true)) d.groupValues[2].trim() else null
                } ?: continue
                val important = font.endsWith("!important", true)
                val value = font.replace(Regex("\\s*!important\\s*$", RegexOption.IGNORE_CASE), "").trim()
                for (selectorText in selectors) {
                    val parsed = parseSelector(selectorText.trim()) ?: continue
                    out.add(CssRule(parsed.first, parsed.second, order++, FontDeclaration(value, important, parsed.second, order)))
                }
            }
        }
        return out
    }

    private fun parseSelector(source: String): Pair<List<SelectorPart>, Int>? {
        if (source.isEmpty() || source.startsWith('@')) return null
        val parts = mutableListOf<SelectorPart>()
        val token = StringBuilder()
        var pendingDirect = false
        var quote: Char? = null
        var bracketDepth = 0
        fun flush() {
            val simple = token.toString().trim()
            if (simple.isNotEmpty()) {
                parts.add(SelectorPart(simple, if (parts.isEmpty()) false else pendingDirect))
                token.setLength(0)
                pendingDirect = false
            }
        }
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (quote != null) {
                token.append(c)
                if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> { quote = c; token.append(c) }
                '[' -> { bracketDepth++; token.append(c) }
                ']' -> { bracketDepth--; token.append(c) }
                '>' -> if (bracketDepth == 0) { flush(); pendingDirect = true }
                    else token.append(c)
                else -> if (c.isWhitespace() && bracketDepth == 0) flush() else token.append(c)
            }
            i++
        }
        flush()
        if (parts.isEmpty()) return null
        val specificity = parts.sumOf { part ->
            val s = part.simple
            val ids = Regex("#[\\w-]+").findAll(s).count()
            val classes = Regex("\\.[\\w-]+|\\[[^]]+]").findAll(s).count()
            val tag = Regex("^[a-zA-Z][\\w-]*").containsMatchIn(s)
            ids * 100 + classes * 10 + if (tag) 1 else 0
        }
        return parts to specificity
    }

    private fun computedScale(
        name: String,
        attrs: Map<String, String>,
        ancestors: List<Element>,
        rules: List<CssRule>,
        parentScale: Float,
    ): Float {
        val current = Element(name, attrs, parentScale)
        val all = ancestors + current
        val winner = rules.asSequence()
            .filter { matches(it.selector, all) }
            .map { it.declaration }
            .maxWithOrNull(compareBy<FontDeclaration> { it.important }.thenBy { it.specificity }.thenBy { it.order })
        val inline = attrs["style"]?.let { inlineFontDeclaration(it) }
        val selected = when {
            inline == null -> winner
            winner == null -> inline
            inline.important && !winner.important -> inline
            winner.important && !inline.important -> winner
            else -> inline
        } ?: return parentScale
        val rootScale = all.firstOrNull { it.name == "html" }?.scale ?: 1f
        val scale = parseSize(selected.value, parentScale, rootScale) ?: return parentScale
        return scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun inlineFontDeclaration(style: String): FontDeclaration? = declaration.findAll(style)
        .firstNotNullOfOrNull { d ->
            if (!d.groupValues[1].equals("font-size", true)) return@firstNotNullOfOrNull null
            val raw = d.groupValues[2].trim()
            FontDeclaration(
                raw.replace(Regex("\\s*!important\\s*$", RegexOption.IGNORE_CASE), "").trim(),
                raw.endsWith("!important", true), 1000, Int.MAX_VALUE,
            )
        }

    private fun parseSize(value: String, parentScale: Float, rootScale: Float): Float? {
        val v = value.trim().lowercase(Locale.ROOT)
        return when (v) {
            "inherit", "unset" -> parentScale
            "initial", "medium" -> 1f
            "xx-small" -> 0.6f
            "x-small" -> 0.75f
            "small" -> 0.89f
            "large" -> 1.2f
            "x-large" -> 1.5f
            "xx-large" -> 2f
            "smaller" -> parentScale * 0.8f
            "larger" -> parentScale * 1.2f
            else -> {
                val m = Regex("^([0-9]*\\.?[0-9]+)(px|pt|pc|em|rem|%)?$").matchEntire(v) ?: return null
                val n = m.groupValues[1].toFloatOrNull()?.takeIf { it > 0f } ?: return null
                when (m.groupValues[2]) {
                    "px" -> n / 16f
                    "pt" -> n / 12f
                    "pc" -> n
                    "em" -> n * parentScale
                    "rem" -> n * rootScale
                    "%" -> n / 100f * parentScale
                    else -> n
                }
            }
        }
    }

    private fun matches(selector: List<SelectorPart>, elements: List<Element>): Boolean {
        var elementIndex = elements.lastIndex
        if (elementIndex < 0 || !matchesSimple(selector.last().simple, elements[elementIndex])) return false
        for (partIndex in selector.lastIndex - 1 downTo 0) {
            val part = selector[partIndex + 1]
            if (part.directParent) {
                elementIndex--
                if (elementIndex < 0 || !matchesSimple(selector[partIndex].simple, elements[elementIndex])) return false
            } else {
                var found = -1
                for (i in elementIndex - 1 downTo 0) {
                    if (matchesSimple(selector[partIndex].simple, elements[i])) { found = i; break }
                }
                if (found < 0) return false
                elementIndex = found
            }
        }
        return true
    }

    private fun matchesSimple(selector: String, element: Element): Boolean {
        if (selector.isEmpty() || selector.contains(':') || selector.contains('[')) return false
        val tag = Regex("^[a-zA-Z][\\w-]*").find(selector)?.value
        if (tag != null && !tag.equals(element.name, true)) return false
        for (id in Regex("#([\\w-]+)").findAll(selector)) if (element.attrs["id"] != id.groupValues[1]) return false
        val classes = element.attrs["class"].orEmpty().split(Regex("\\s+")).filter(String::isNotEmpty).toSet()
        for (className in Regex("\\.([\\w-]+)").findAll(selector)) if (className.groupValues[1] !in classes) return false
        return true
    }

    private class StyledTextBuilder {
        private val text = StringBuilder()
        private val spans = mutableListOf<NovelFontSizeSpan>()
        private var pendingSpace: Float? = null
        private var afterNewline = false
        private var newlineCount = 0

        fun append(value: String, scale: Float) {
            for (c in value) {
                when {
                    c == ' ' || c == '\t' -> {
                        if (!afterNewline && pendingSpace == null) pendingSpace = scale
                    }
                    c == '\n' -> {
                        pendingSpace = null
                        if (newlineCount < 2) appendChar('\n', 1f)
                        newlineCount++
                        afterNewline = true
                    }
                    else -> {
                        if (!afterNewline) pendingSpace?.let { appendChar(' ', it) }
                        pendingSpace = null
                        afterNewline = false
                        newlineCount = 0
                        appendChar(c, scale)
                    }
                }
            }
        }

        fun finish(): ChapterText {
            if (!afterNewline) pendingSpace?.let { appendChar(' ', it) }
            var start = 0
            var end = text.length
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            if (start == 0 && end == text.length) return ChapterText(text.toString(), spans.toList())
            val clipped = spans.mapNotNull { span ->
                val from = maxOf(span.start, start)
                val to = minOf(span.end, end)
                if (from >= to) null else NovelFontSizeSpan(from - start, to - start, span.scale)
            }
            return ChapterText(text.substring(start, end), clipped)
        }

        private fun appendChar(char: Char, scale: Float) {
            val start = text.length
            text.append(char)
            if (abs(scale - 1f) <= 0.0001f) return
            val last = spans.lastOrNull()
            if (last != null && last.end == start && abs(last.scale - scale) <= 0.0001f) {
                spans[spans.lastIndex] = last.copy(end = start + 1)
            } else {
                spans.add(NovelFontSizeSpan(start, start + 1, scale))
            }
        }
    }

    private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr", "image")
    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 3f
}
