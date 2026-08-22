package com.nedrichards.brooklet.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlDocumentParserTest {
    @Test fun `parses useful blocks and decodes text`() {
        val blocks = HtmlDocumentParser.parse("<h2>News &amp; notes</h2><p>Hello <b>Brooklet</b>.</p><img src=\"https://e/x.jpg\" alt=\"A view\">")
        assertEquals(DocumentBlock.Heading(2, "News & notes"), blocks[0])
        assertEquals(DocumentBlock.Paragraph("Hello Brooklet ."), blocks[1])
        assertTrue(blocks[2] is DocumentBlock.Image)
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
}
