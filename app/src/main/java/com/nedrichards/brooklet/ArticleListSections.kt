package com.nedrichards.brooklet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nedrichards.brooklet.model.Entry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ArticleDateSection(
    val date: LocalDate,
    val label: String,
    val entries: List<Entry>,
)

internal fun articleDateSections(
    entries: List<Entry>,
    today: LocalDate = LocalDate.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<ArticleDateSection> {
    val currentYear = DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)
    val earlierYear = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
    return entries.groupBy { entry ->
        Instant.ofEpochMilli(entry.publishedAt).atZone(zoneId).toLocalDate()
    }.map { (date, dateEntries) ->
        val label = when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(if (date.year == today.year) currentYear else earlierYear)
        }
        ArticleDateSection(date, label, dateEntries)
    }
}

internal fun articleLazyListIndex(
    entries: List<Entry>,
    entryIndex: Int,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Int {
    if (entryIndex !in entries.indices) return 0
    val targetId = entries[entryIndex].id
    var lazyIndex = 0
    articleDateSections(entries, zoneId = zoneId).forEach { section ->
        lazyIndex += 1
        section.entries.forEach { entry ->
            if (entry.id == targetId) return lazyIndex
            lazyIndex += 1
        }
    }
    return 0
}

@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.articleItems(
    entries: List<Entry>,
    showLeadingHeader: Boolean = true,
    itemContent: @Composable LazyItemScope.(Entry) -> Unit,
) {
    articleDateSections(entries).forEachIndexed { index, section ->
        stickyHeader(key = "date-${section.date}") {
            if (index > 0 || showLeadingHeader) {
                Surface(
                    modifier = Modifier.animateItem().fillMaxWidth().semantics { heading() },
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Text(
                        section.label,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        items(section.entries, key = { it.id }, itemContent = itemContent)
    }
}
