package com.nedrichards.brooklet

import com.nedrichards.brooklet.model.Entry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class ArticleListSectionsTest {
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 8, 24)

    @Test fun groupsArticlesUnderFriendlyDateLabelsWithoutReorderingThem() {
        val entries = listOf(
            entry(3, "2026-08-24T18:00:00Z"),
            entry(2, "2026-08-24T09:00:00Z"),
            entry(1, "2026-08-23T20:00:00Z"),
            entry(0, "2025-12-31T20:00:00Z"),
        )

        val sections = articleDateSections(entries, today, utc, Locale.UK)

        assertEquals(listOf("Today", "Yesterday", "31 December 2025"), sections.map { it.label })
        assertEquals(listOf(3L, 2L), sections.first().entries.map { it.id })
    }

    @Test fun lazyListIndexIncludesStickyHeadersForPositionRestoration() {
        val entries = listOf(
            entry(3, "2026-08-24T18:00:00Z"),
            entry(2, "2026-08-24T09:00:00Z"),
            entry(1, "2026-08-23T20:00:00Z"),
        )

        assertEquals(1, articleLazyListIndex(entries, 0, utc))
        assertEquals(2, articleLazyListIndex(entries, 1, utc))
        assertEquals(4, articleLazyListIndex(entries, 2, utc))
    }

    private fun entry(id: Long, publishedAt: String) = Entry(
        id = id,
        accountId = 1,
        feedId = 1,
        feedTitle = "Feed",
        categoryTitle = "Category",
        title = "Entry $id",
        url = "https://example.com/$id",
        author = null,
        publishedAt = Instant.parse(publishedAt).toEpochMilli(),
        html = "",
        blocks = emptyList(),
        read = false,
        starred = false,
        readingMinutes = 1,
    )
}
