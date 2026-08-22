package com.nedrichards.brooklet.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlDocumentParserTest {
    @Test fun `parses useful blocks and decodes text`() {
        val blocks = HtmlDocumentParser.parse("<h2>News &amp; notes</h2><p>Hello <b>Brooklet</b>.</p><img src=\"https://e/x.jpg\" alt=\"A view\">")
        assertEquals(DocumentBlock.Heading(2, "News & notes"), blocks[0])
        assertEquals(DocumentBlock.Paragraph("Hello Brooklet .", "Hello <b>Brooklet</b>."), blocks[1])
        assertTrue(blocks[2] is DocumentBlock.Image)
    }

    @Test fun `decodes decimal and hexadecimal numeric entities without double decoding`() {
        val blocks = HtmlDocumentParser.parse("<p>Phoronix &#34;Sched&#x5f;ext&#34; &amp;#34; &#x1F680;</p>")

        assertEquals(DocumentBlock.Paragraph("Phoronix \"Sched_ext\" &#34; 🚀"), blocks.single())
    }

    @Test fun `keeps an image nested inside a paragraph`() {
        val blocks = HtmlDocumentParser.parse("<p>Before <img src='/photo.jpg' alt='A view'> after</p>")

        assertEquals(DocumentBlock.Paragraph("Before after"), blocks[0])
        assertEquals(DocumentBlock.Image("/photo.jpg", "A view"), blocks[1])
    }

    @Test fun `uses lazy image source when src is absent`() {
        val blocks = HtmlDocumentParser.parse("<img data-src=\"https://example.com/lazy.jpg\" alt=\"Lazy\">")

        assertEquals(DocumentBlock.Image("https://example.com/lazy.jpg", "Lazy"), blocks.single())
    }

    @Test fun `keeps unwrapped feed text alongside an image`() {
        val blocks = HtmlDocumentParser.parse("<a href=\"https://example.com/article\"><img src=\"https://example.com/article.jpg\"></a><br>Wine 11.16 is out with VA-API decoding and better ARM64 support.")

        assertEquals(DocumentBlock.Image("https://example.com/article.jpg", null), blocks[0])
        assertEquals(DocumentBlock.Paragraph("Wine 11.16 is out with VA-API decoding and better ARM64 support."), blocks[1])
    }

    @Test fun `keeps inline markup plus ordered lists captions and tables`() {
        val blocks = HtmlDocumentParser.parse("<ol><li>One</li><li><a href='https://example.com'>Two</a></li></ol><figure><img src='x'><figcaption>A <em>caption</em></figcaption></figure><table><tr><th>Version</th><th>Status</th></tr><tr><td>1</td><td>Ready</td></tr></table>")

        assertEquals(DocumentBlock.ListItem("One", ordered = true), blocks[0])
        assertEquals(DocumentBlock.ListItem("Two", ordered = true, html = "<a href='https://example.com'>Two</a>"), blocks[1])
        assertEquals(DocumentBlock.Caption("A caption", "A <em>caption</em>"), blocks[3])
        assertEquals(DocumentBlock.Table(listOf(listOf("Version", "Status"), listOf("1", "Ready"))), blocks[4])
    }
}
