package com.nedrichards.brooklet

import com.nedrichards.brooklet.model.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleSearchFilterTest {
    private val entries = listOf(
        entry(1, "Compose release", "Android Weekly", "Ada", read = false, feedId = 10),
        entry(2, "Kotlin update", "Mobile Notes", "Ben", read = true, feedId = 20),
        entry(3, "Room performance", "Android Weekly", "Cara", read = true, feedId = 10),
    )

    @Test fun searchesCachedTitleFeedAndAuthorCaseInsensitively() {
        assertEquals(listOf(1L), filterSearchEntries(entries, "compose").map { it.id })
        assertEquals(listOf(1L, 3L), filterSearchEntries(entries, "ANDROID WEEKLY").map { it.id })
        assertEquals(listOf(2L), filterSearchEntries(entries, "ben").map { it.id })
    }

    @Test fun combinesReadSavedAndFeedFilters() {
        assertEquals(
            listOf(3L),
            filterSearchEntries(
                entries = entries,
                query = "Android Weekly",
                readFilter = SearchReadFilter.READ,
                savedOnly = true,
                feedId = 10,
                savedEntryIds = setOf(2, 3),
            ).map { it.id },
        )
    }

    @Test fun blankQueryDoesNotTurnSearchIntoAnotherArticleBrowser() {
        assertEquals(emptyList<Entry>(), filterSearchEntries(entries, "   "))
    }

    private fun entry(
        id: Long,
        title: String,
        feedTitle: String,
        author: String,
        read: Boolean,
        feedId: Long,
    ) = Entry(
        id = id,
        accountId = 1,
        feedId = feedId,
        feedTitle = feedTitle,
        categoryTitle = "Category",
        title = title,
        url = "https://example.com/$id",
        author = author,
        publishedAt = id,
        html = "",
        blocks = emptyList(),
        read = read,
        starred = id == 2L,
        readingMinutes = 1,
    )
}
