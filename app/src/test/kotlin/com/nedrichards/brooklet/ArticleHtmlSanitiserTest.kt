package com.nedrichards.brooklet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleHtmlSanitiserTest {
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
}
