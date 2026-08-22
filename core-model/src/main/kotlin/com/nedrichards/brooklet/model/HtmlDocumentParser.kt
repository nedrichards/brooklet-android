package com.nedrichards.brooklet.model

/** Conservative, dependency-free HTML-to-text model. Original HTML is retained in Room. */
object HtmlDocumentParser {
    private val blocks = Regex("<(h[1-6]|p|blockquote|pre|li)(?:\\s[^>]*)?>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val images = Regex("<img(?:\\s[^>]*)?>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tags = Regex("<[^>]+>")
    private val spaces = Regex("\\s+")

    fun parse(html: String): List<DocumentBlock> {
        val textBlocks = blocks.findAll(html).mapNotNull { match ->
            val tag = match.groups[1]!!.value.lowercase()
            val content = match.groups[2]?.value.orEmpty()
            val block = when {
                tag.startsWith("h") -> DocumentBlock.Heading(tag.drop(1).toInt(), text(content))
                tag == "p" -> DocumentBlock.Paragraph(text(content))
                tag == "blockquote" -> DocumentBlock.Quote(text(content))
                tag == "pre" -> DocumentBlock.Code(decode(tags.replace(content, "")))
                tag == "li" -> DocumentBlock.ListItem(text(content), ordered = false)
                else -> null
            }
            block?.let { match.range.first to it }
        }
        // Images are scanned independently so an <img> nested inside a paragraph
        // is not swallowed by the paragraph match.
        val imageBlocks = images.findAll(html).mapNotNull { match ->
            val source = attribute(match.value, "src") ?: attribute(match.value, "data-src")
            source?.let { match.range.first to DocumentBlock.Image(it, attribute(match.value, "alt")) }
        }
        // Some feeds put their prose directly after an image or <br> rather than
        // wrapping it in paragraphs. Keep that text too: an image must not make
        // the reader discard the rest of an entry's content.
        val unstructuredHtml = blocks.replace(html) { " ".repeat(it.value.length) }
        val unstructuredText = text(unstructuredHtml)
        val unstructuredBlock = unstructuredText.takeIf(String::isNotBlank)?.let {
            firstTextPosition(unstructuredHtml) to DocumentBlock.Paragraph(it)
        }
        val result = (textBlocks + imageBlocks + listOfNotNull(unstructuredBlock))
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
        is DocumentBlock.Image -> block.url.isBlank()
    }
    private fun attribute(tag: String, name: String) = Regex("\\b$name\\s*=\\s*(['\"])(.*?)\\1", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(2)?.let(::decode)
    private fun text(value: String) = decode(tags.replace(value, " ")).replace(spaces, " ").trim()
    private fun decode(value: String) = value.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

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
