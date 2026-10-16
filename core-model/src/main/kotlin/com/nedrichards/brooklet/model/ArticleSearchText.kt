package com.nedrichards.brooklet.model

import java.util.Locale
import org.jsoup.Jsoup

/** Derived local search text. Never fetches resources or changes reader HTML. */
object ArticleSearchText {
    const val VERSION = 1
    private val whitespace = Regex("[\\s\\p{Z}]+")

    fun normalize(text: String): String = whitespace.replace(text, " ").trim().lowercase(Locale.ROOT)

    fun body(html: String): String {
        val document = Jsoup.parseBodyFragment(html)
        document.select("script,style,noscript,template").remove()
        // jsoup inserts separators for block elements and decodes entities, while
        // keeping inline text together. Attributes (including alt/URLs) are omitted.
        return normalize(document.body().text())
    }

    fun pattern(query: String): String = "%" + normalize(query)
        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
