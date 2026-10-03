package com.nedrichards.brooklet.model

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Parses retained article HTML without fetching resources or executing source content. */
object HtmlDocumentParser {
    private val ignored = setOf("script", "style", "noscript", "template")
    private val containers = setOf("div", "article", "section", "main", "body", "html", "figure", "header", "footer")
    private val richTags = setOf("a", "strong", "b", "em", "i", "code", "br", "sup", "sub", "s", "del")

    fun parse(html: String): List<DocumentBlock> {
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        document.select(ignored.joinToString(",")).remove()
        return buildList { walk(document.body().childNodes(), this) }
    }

    private fun walk(nodes: List<Node>, out: MutableList<DocumentBlock>, quote: Boolean = false, depth: Int = 0) {
        val loose = mutableListOf<Node>()
        fun flush() {
            if (loose.isNotEmpty()) {
                paragraph(loose, quote)?.let(out::add)
                assets(loose, out)
                loose.clear()
            }
        }
        for (node in nodes) {
            if (node !is Element) { if (node is TextNode) loose.add(node); continue }
            val tag = node.normalName()
            when {
                tag in ignored -> Unit
                tag == "br" -> flush()
                tag in containers -> { flush(); walk(node.childNodes(), out, quote, depth) }
                tag == "blockquote" -> { flush(); walk(node.childNodes(), out, true, depth) }
                tag == "ol" || tag == "ul" -> { flush(); list(node, out, depth) }
                tag == "table" -> {
                    flush()
                    if (node.select("table").any { it !== node }) walk(node.childNodes(), out, quote, depth)
                    else { table(node)?.let(out::add); assets(node.childNodes(), out) }
                }
                tag in setOf("tbody", "thead", "tfoot", "tr", "td", "th") -> { flush(); walk(node.childNodes(), out, quote, depth) }
                tag == "img" -> { flush(); image(node)?.let(out::add) }
                tag in setOf("iframe", "audio", "video") -> { flush(); media(node)?.let(out::add) }
                tag == "pre" -> { flush(); out += DocumentBlock.Code(node.wholeText()) }
                tag.matches(Regex("h[1-6]")) -> {
                    flush(); val text = text(node.childNodes())
                    if (text.isNotBlank()) out += DocumentBlock.Heading(tag.drop(1).toInt(), text, rich(node.childNodes()), links(node.childNodes()))
                    assets(node.childNodes(), out)
                }
                tag == "figcaption" -> {
                    flush(); val text = text(node.childNodes())
                    if (text.isNotBlank()) out += DocumentBlock.Caption(text, rich(node.childNodes()), links(node.childNodes()))
                    assets(node.childNodes(), out)
                }
                tag == "p" -> { flush(); paragraph(node.childNodes(), quote)?.let(out::add); assets(node.childNodes(), out) }
                else -> loose.add(node)
            }
        }
        flush()
    }

    private fun paragraph(nodes: List<Node>, quote: Boolean): DocumentBlock? {
        val text = text(nodes)
        if (text.isBlank()) return null
        return if (quote) DocumentBlock.Quote(text, rich(nodes), links(nodes))
        else DocumentBlock.Paragraph(text, rich(nodes), links(nodes))
    }

    private fun text(nodes: List<Node>): String = nodes.joinToString("") { node ->
        when (node) {
            is TextNode -> node.wholeText
            is Element -> if (node.normalName() in ignored || node.normalName() in setOf("img", "iframe", "audio", "video")) ""
                else " " + text(node.childNodes()) + " "
            else -> ""
        }
    }.replace(Regex("[\\s\\u00a0]+"), " ").trim()

    private fun rich(nodes: List<Node>): String? = nodes.joinToString("") { it.outerHtml() }
        .takeIf { nodes.any { node -> node is Element && (node.normalName() in richTags || node.select(richTags.joinToString(",")).isNotEmpty()) } }

    private fun links(nodes: List<Node>): List<DocumentLink> = nodes.flatMap { node ->
        if (node is Element) node.select("a[href]").map { DocumentLink(it.text(), it.attr("href")) } else emptyList()
    }

    private fun assets(nodes: List<Node>, out: MutableList<DocumentBlock>) {
        nodes.filterIsInstance<Element>().forEach { node ->
            node.select("img,iframe,audio,video").forEach { asset ->
                if (asset.normalName() == "img") image(asset)?.let(out::add) else media(asset)?.let(out::add)
            }
        }
    }

    private fun image(element: Element): DocumentBlock.Image? {
        val source = sequenceOf("src", "data-src", "data-original").map { element.attr(it) }.firstOrNull { it.isNotBlank() } ?: return null
        return DocumentBlock.Image(source, element.attr("alt").takeIf { element.hasAttr("alt") })
    }

    private fun media(element: Element): DocumentBlock.Paragraph? {
        val source = element.attr("src").ifBlank { element.select("source[src]").firstOrNull()?.attr("src").orEmpty() }
        if (source.isBlank()) return null
        val label = when (element.normalName()) { "audio" -> "Listen to audio"; "video" -> "Open video"; else -> "Open embedded content" }
        val anchor = Element("a").attr("href", source).text(label)
        return DocumentBlock.Paragraph(label, anchor.outerHtml(), listOf(DocumentLink(label, source)))
    }

    private fun list(element: Element, out: MutableList<DocumentBlock>, depth: Int) {
        val ordered = element.normalName() == "ol"
        var ordinal = element.attr("start").toIntOrNull() ?: 1
        element.children().filter { it.normalName() == "li" }.forEach { item ->
            ordinal = item.attr("value").toIntOrNull() ?: ordinal
            // Flush parent prose before each nested list, preserving source order.
            val run = mutableListOf<Node>()
            var first = true
            fun flush() {
                val text = text(run)
                if (text.isNotBlank()) {
                    if (first) out += DocumentBlock.ListItem(text, ordered, rich(run), links(run), if (ordered) ordinal else null, depth)
                    else out += DocumentBlock.Paragraph(text, rich(run), links(run))
                    first = false
                }
                assets(run, out); run.clear()
            }
            item.childNodes().forEach { child ->
                if (child is Element && child.normalName() in setOf("ol", "ul")) { flush(); list(child, out, depth + 1) }
                else run.add(child)
            }
            flush(); ordinal++
        }
    }

    private fun table(element: Element): DocumentBlock.Table? {
        val rows = element.select("tr").filter { it.closest("table") === element }
        val occupied = mutableSetOf<Pair<Int, Int>>()
        val cells = mutableListOf<TableCell>()
        val plain = mutableListOf<List<String>>()
        rows.forEachIndexed { rowIndex, row ->
            var column = 0
            val rowText = mutableListOf<String>()
            row.children().filter { it.normalName() in setOf("td", "th") }.forEach { cell ->
                val columnSpan = (cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 100)
                while ((0 until columnSpan).any { rowIndex to (column + it) in occupied }) column++
                val remainingGroupRows = rows.drop(rowIndex).takeWhile { it.parent() === row.parent() }.size
                val requestedSpan = cell.attr("rowspan").toIntOrNull() ?: 1
                val rowSpan = if (requestedSpan == 0) remainingGroupRows else requestedSpan.coerceIn(1, remainingGroupRows.coerceAtLeast(1))
                val text = text(cell.childNodes())
                cells += TableCell(rowIndex, column, text, rich(cell.childNodes()), cell.normalName() == "th", rowSpan, columnSpan)
                rowText += text
                for (r in rowIndex until rowIndex + rowSpan) for (c in column until column + columnSpan) occupied += r to c
                column += columnSpan
            }
            plain += rowText
        }
        return if (cells.isEmpty()) null else DocumentBlock.Table(plain, cells)
    }
}
