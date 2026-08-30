package com.nedrichards.brooklet

import com.nedrichards.brooklet.model.DocumentBlock
import com.nedrichards.brooklet.model.HtmlDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleHtmlSanitiserTest {
    @Test fun preservesSafeLinksFromPhoronixStyleUnwrappedProse() {
        val paragraph = HtmlDocumentParser.parse(
            """<div class="content">See <a href="/news/next">the next article</a> and <a href="javascript:alert(1)">unsafe text</a>.<br></div>""",
        ).single() as DocumentBlock.Paragraph

        val result = sanitiseInlineArticleHtml(paragraph.html!!, "https://example.com/news/current")

        assertTrue(result.contains("<a href=\"https://example.com/news/next\">the next article</a>"))
        assertTrue(result.contains("<a>unsafe text</a>"))
        assertFalse(result.contains("javascript:"))
    }

    @Test fun removesSourceColoursBackgroundsAndActiveContent() {
        val result = sanitiseInlineArticleHtml(
            """<span style="color:black;background:white"><font color="#000">Text</font></span>""" +
                "<style>p { color: black }</style><script>alert('no')</script>",
        )

        assertEquals("Text", result)
    }

    @Test fun keepsSupportedEmphasisLineBreaksAndAbsoluteWebLinks() {
        val result = sanitiseInlineArticleHtml(
            """<strong>Bold</strong><br><em>em</em> <code>code</code> """ +
                """<a class="external" href="https://example.com/a?x=1&amp;y=2">link</a>""",
        )

        assertTrue(result.contains("<strong>Bold</strong><br><em>em</em> <tt>code</tt>"))
        assertTrue(result.contains("<a href=\"https://example.com/a?x=1&amp;y=2\">link</a>"))
    }

    @Test fun dropsUnsafeAndRelativeLinkDestinationsWithoutDroppingTheirText() {
        val result = sanitiseInlineArticleHtml(
            """<a href="javascript:alert(1)">unsafe</a> <a href="/relative">relative</a>""",
        )

        assertFalse(result.contains("href"))
        assertEquals("<a>unsafe</a> <a>relative</a>", result)
    }

    @Test fun resolvesRelativeLinksAgainstTheArticleUrl() {
        val result = sanitiseInlineArticleHtml(
            """<a href="/stories/next">next</a> <a href="related">related</a>""",
            "https://example.com/articles/current",
        )

        assertEquals(
            """<a href="https://example.com/stories/next">next</a> <a href="https://example.com/articles/related">related</a>""",
            result,
        )
    }

    @Test fun doesNotResolveUnsafeLinksAgainstTheArticleUrl() {
        val result = sanitiseInlineArticleHtml(
            """<a href="javascript:alert(1)">unsafe</a> <a href="//cdn.example.com/page">web</a>""",
            "https://example.com/article",
        )

        assertEquals("""<a>unsafe</a> <a href="https://cdn.example.com/page">web</a>""", result)
    }
}
