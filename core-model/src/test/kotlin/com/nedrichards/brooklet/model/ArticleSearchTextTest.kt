package com.nedrichards.brooklet.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArticleSearchTextTest {
    @Test fun joinsVisibleTextAcrossInlineFormattingAndSeparatesBlocks() {
        assertEquals("a heat pump & solar energy cell one cell two code caption", ArticleSearchText.body(
            "<p>A <strong>heat</strong> pump &amp; solar&nbsp;energy</p>" +
                "<table><tr><td>cell one</td><td>cell two</td></tr></table><pre>code</pre><figcaption>caption</figcaption>"))
        assertEquals("pumping", ArticleSearchText.body("pump<em>ing</em>"))
    }

    @Test fun ignoresActiveContentAndInvisibleAttributes() {
        val body = ArticleSearchText.body("<script>secret</script><style>secret</style>" +
            "<noscript>secret</noscript><template>secret</template>" +
            "<p class='secret'>Visible <a href='https://secret'>link</a><img alt='secret' src='secret'></p>")
        assertEquals("visible link", body)
        assertFalse(body.contains("secret"))
    }

    @Test fun normalizesUnicodeCaseAndWhitespaceWithoutRemovingAccents() {
        assertEquals("été café αθήνα", ArticleSearchText.normalize("  ÉTÉ\nCAFÉ\u00a0ΑΘΉΝΑ  "))
        assertEquals("heat pump", ArticleSearchText.normalize("Heat\t\nPump"))
    }

    @Test fun escapesSqlWildcardsAndBackslashesAsLiteralText() {
        assertEquals("%100\\% a\\_b c\\\\d%", ArticleSearchText.pattern("100% a_b c\\d"))
        assertEquals("%a 'quote'%", ArticleSearchText.pattern("A 'quote'"))
    }
}
