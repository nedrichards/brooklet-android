package com.nedrichards.brooklet.model

/** Conservative, dependency-free HTML-to-text model. Original HTML is retained in Room. */
object HtmlDocumentParser {
    private val blocks = Regex("<(h[1-6]|p|blockquote|pre|li|figcaption|table)(?:\\s[^>]*)?>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val images = Regex("<img(?:\\s[^>]*)?>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val lineBreaks = Regex("<br(?:\\s[^>]*)?/?>", RegexOption.IGNORE_CASE)
    private val tags = Regex("<[^>]+>")
    private val spaces = Regex("\\s+")
    // Decode each entity in one pass: decoding &amp; first would accidentally turn
    // the literal text "&amp;#34;" into a quotation mark on the same parse.
    private val entities = Regex(
        "&(?:(nbsp|amp|lt|gt|quot);|#([xX]?[0-9A-Fa-f]+)(?:;|(?=[^0-9A-Za-z;]|$)))",
        RegexOption.IGNORE_CASE,
    )

    fun parse(html: String): List<DocumentBlock> {
        val blockMatches = blocks.findAll(html).toList()
        val imageMatches = images.findAll(html).toList()
        val textBlocks = blockMatches.mapNotNull { match ->
            val tag = match.groups[1]!!.value.lowercase()
            val content = match.groups[2]?.value.orEmpty()
            val block = when {
                tag.startsWith("h") -> DocumentBlock.Heading(tag.drop(1).toInt(), text(content), richHtml(content), links(content))
                tag == "p" -> DocumentBlock.Paragraph(text(content), richHtml(content), links(content))
                tag == "blockquote" -> DocumentBlock.Quote(text(content), richHtml(content), links(content))
                tag == "pre" -> DocumentBlock.Code(decode(tags.replace(content, "")))
                tag == "li" -> DocumentBlock.ListItem(text(content), ordered = isInsideOrderedList(html, match.range.first), html = richHtml(content), links = links(content))
                tag == "figcaption" -> DocumentBlock.Caption(text(content), richHtml(content), links(content))
                tag == "table" -> table(content)
                else -> null
            }
            block?.let { match.range.first to it }
        }
        // Images are scanned independently so an <img> nested inside a paragraph
        // is not swallowed by the paragraph match.
        val imageBlocks = imageMatches.mapNotNull { match ->
            val source = attribute(match.value, "src") ?: attribute(match.value, "data-src")
            source?.let { match.range.first to DocumentBlock.Image(it, attribute(match.value, "alt")) }
        }
        // Some feeds use <br>-separated prose rather than paragraph elements.
        // Keep each loose run, including its supported inline markup, while
        // masking recognised blocks and images so their content is not duplicated.
        val unstructuredHtml = mask(html, (blockMatches + imageMatches).map(MatchResult::range))
        val unstructuredBlocks = unstructuredParagraphs(unstructuredHtml)
        val result = (textBlocks + imageBlocks + unstructuredBlocks)
            .sortedBy { it.first }
            .map { it.second }
            .filterNot(::isBlank)
            .toList()
        return result.ifEmpty { listOf(DocumentBlock.Paragraph(text(html))) }
    }

    private fun isBlank(block: DocumentBlock): Boolean = when (block) {
        is DocumentBlock.Heading -> block.text.isBlank()
        is DocumentBlock.Paragraph -> block.text.isBlank()
        is DocumentBlock.Quote -> block.text.isBlank()
        is DocumentBlock.Code -> block.text.isBlank()
        is DocumentBlock.ListItem -> block.text.isBlank()
        is DocumentBlock.Caption -> block.text.isBlank()
        is DocumentBlock.Table -> block.rows.isEmpty()
        is DocumentBlock.Image -> block.url.isBlank()
    }
    private fun attribute(tag: String, name: String) = Regex("\\b$name\\s*=\\s*(['\"])(.*?)\\1", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(2)?.let(::decode)
    private fun text(value: String) = decode(tags.replace(value, " ")).replace(spaces, " ").trim()
    private fun richHtml(value: String) = value.takeIf { Regex("</?(a|strong|b|em|i|code|br)(?:\\s|>|/)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    private fun links(value: String): List<DocumentLink> = Regex(
        "<a(?:\\s[^>]*)?href\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).findAll(value).mapNotNull { match ->
        val url = decode(match.groups[2]?.value.orEmpty()).trim()
        val label = text(match.groups[3]?.value.orEmpty()).ifBlank { url }
        url.takeIf { it.isNotEmpty() }?.let { DocumentLink(label, it) }
    }.toList()

    private fun mask(value: String, ranges: List<IntRange>): String {
        val masked = value.toCharArray()
        ranges.forEach { range -> range.forEach { index -> masked[index] = ' ' } }
        return masked.concatToString()
    }

    private fun unstructuredParagraphs(value: String): List<Pair<Int, DocumentBlock.Paragraph>> {
        val result = mutableListOf<Pair<Int, DocumentBlock.Paragraph>>()
        var start = 0
        fun add(end: Int) {
            val html = value.substring(start, end)
            val plainText = text(html)
            if (plainText.isNotBlank()) {
                result += start + firstTextPosition(html) to DocumentBlock.Paragraph(plainText, richHtml(html))
            }
        }
        lineBreaks.findAll(value).forEach { match ->
            add(match.range.first)
            start = match.range.last + 1
        }
        add(value.length)
        return result
    }

    private fun table(value: String): DocumentBlock.Table {
        val rows = Regex("<tr(?:\\s[^>]*)?>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(value).map { row ->
            Regex("<t[hd](?:\\s[^>]*)?>(.*?)</t[hd]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(row.groups[1]!!.value).map { text(it.groups[1]!!.value) }.filter(String::isNotBlank).toList()
        }.filter(List<String>::isNotEmpty).toList()
        return DocumentBlock.Table(rows)
    }
    private fun isInsideOrderedList(html: String, position: Int): Boolean {
        val before = html.substring(0, position)
        return before.lastIndexOf("<ol", ignoreCase = true) > before.lastIndexOf("</ol", ignoreCase = true)
    }
    private fun decode(value: String) = entities.replace(value) { match ->
        when (match.groups[1]?.value?.lowercase()) {
            "nbsp" -> " "
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            null -> {
                val encoded = match.groups[2]!!.value
                val radix = if (encoded.startsWith('x', ignoreCase = true)) 16 else 10
                val digits = if (radix == 16) encoded.drop(1) else encoded
                val codePoint = digits.toIntOrNull(radix)
                if (codePoint != null && Character.isValidCodePoint(codePoint) && codePoint !in 0xD800..0xDFFF) {
                    String(Character.toChars(codePoint))
                } else {
                    match.value
                }
            }
            else -> match.value
        }
    }

    private fun firstTextPosition(value: String): Int {
        var inTag = false
        value.forEachIndexed { index, character ->
            when (character) {
                '<' -> inTag = true
                '>' -> inTag = false
                else -> if (!inTag && !character.isWhitespace()) return index
            }
        }
        return Int.MAX_VALUE
    }
}
