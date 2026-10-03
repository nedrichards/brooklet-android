package com.nedrichards.brooklet.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredReaderTest {
    @Test fun nestedListsKeepDepthNumberingAndDoNotDuplicateProse() {
        val items = HtmlDocumentParser.parse("<ol start='3'><li>Parent<ul><li>Child <b>bold</b><ol start='8'><li>Grandchild</li></ol></li></ul></li><li>Next</li></ol>").filterIsInstance<DocumentBlock.ListItem>()
        assertEquals(listOf("Parent", "Child bold", "Grandchild", "Next"), items.map { it.text })
        assertEquals(listOf(0, 1, 2, 0), items.map { it.depth })
        assertEquals(listOf(3, null, 8, 4), items.map { it.ordinal })
    }

    @Test fun quotationParagraphsAndAssetsKeepTheirOrder() {
        val blocks = HtmlDocumentParser.parse("<blockquote><p>First <a href='/note'>note</a></p><p>Second</p><figure><img src='/photo'><figcaption>Caption</figcaption></figure></blockquote><p>Outside</p>")
        assertEquals(listOf("First note", "Second"), blocks.filterIsInstance<DocumentBlock.Quote>().map { it.text })
        assertEquals(DocumentBlock.Image("/photo", null), blocks[2])
        assertEquals(DocumentBlock.Caption("Caption"), blocks[3])
        assertEquals(DocumentBlock.Paragraph("Outside"), blocks[4])
    }

    @Test fun tablesKeepEmptyCellsHeadersLinksAndMergedPositions() {
        val table = HtmlDocumentParser.parse("<table><tr><th colspan='2'>Heading</th><th>Other</th></tr><tr><td rowspan='2'><a href='/a'>A</a></td><td></td><td>B</td></tr><tr><td colspan='2'>C</td></tr></table>").single() as DocumentBlock.Table
        assertEquals(listOf("A", "", "B"), table.rows[1])
        assertTrue(table.cells[0].header)
        assertEquals(2, table.cells[0].columnSpan)
        assertEquals(2, table.cells[2].rowSpan)
        assertTrue(table.cells[2].html!!.contains("href=\"/a\""))
        assertEquals(1, table.cells.last().column)
        assertEquals(2, table.cells.last().columnSpan)
    }

    @Test fun rowspanZeroStopsAtRowGroupBoundary() {
        val table = HtmlDocumentParser.parse("<table><tbody><tr><td rowspan='0'>A</td><td>B</td></tr><tr><td>C</td></tr></tbody><tbody><tr><td>D</td></tr></tbody></table>").single() as DocumentBlock.Table
        assertEquals(2, table.cells.first().rowSpan)
        assertEquals(0, table.cells.last().column)
    }

    @Test fun mediaSourcesBecomeExplicitLinks() {
        val blocks = HtmlDocumentParser.parse("<p>Before</p><iframe src='/embed'></iframe><audio><source src='/episode.mp3'></audio><video src='https://example.com/v'></video><p>After</p>")
        assertEquals(listOf("Before", "Open embedded content", "Listen to audio", "Open video", "After"), blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text })
        assertEquals(listOf(DocumentLink("Listen to audio", "/episode.mp3")), (blocks[2] as DocumentBlock.Paragraph).links)
    }

    @Test fun extraInlineFormattingIsRetainedAndHiddenContentIsExcluded() {
        val paragraph = HtmlDocumentParser.parse("<script>bad()</script><style>bad</style><template>hidden</template><noscript>hidden</noscript><p>x<sup>2</sup> H<sub>2</sub>O <del>old</del></p>").single() as DocumentBlock.Paragraph
        val html = requireNotNull(paragraph.html)
        assertTrue(html.contains("<sup>2</sup>"))
        assertTrue(html.contains("<sub>2</sub>"))
        assertTrue(html.contains("<del>old</del>"))
        assertTrue(!paragraph.text.contains("hidden"))
    }

    @Test fun omittedClosingTagsAreRepaired() {
        val blocks = HtmlDocumentParser.parse("<p>First<p>Second<ul><li>One<li>Two</ul>")
        assertEquals(listOf("First", "Second"), blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text })
        assertEquals(listOf("One", "Two"), blocks.filterIsInstance<DocumentBlock.ListItem>().map { it.text })
    }
}
