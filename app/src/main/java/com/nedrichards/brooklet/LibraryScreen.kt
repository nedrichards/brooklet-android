package com.nedrichards.brooklet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nedrichards.brooklet.designsystem.BrookletHeadlineRow
import com.nedrichards.brooklet.model.Category
import com.nedrichards.brooklet.model.Entry
import com.nedrichards.brooklet.model.Feed
import com.nedrichards.brooklet.sync.EntryRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class EntryScope { ALL, UNREAD, READ }

@Composable
fun LibraryScreen(
    accountId: Long,
    repository: EntryRepository,
    categories: List<Category>,
    feeds: List<Feed>,
    padding: PaddingValues,
    floatingUiBlocked: Boolean = false,
    onOpen: (Entry, List<Entry>) -> Unit,
) {
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var feedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var scope by rememberSaveable { mutableStateOf<EntryScope?>(null) }
    val nested = categoryId != null || feedId != null || scope != null
    val goBack = {
        when {
            feedId != null -> feedId = null
            categoryId != null -> categoryId = null
            else -> scope = null
        }
    }
    BackHandler(enabled = nested, onBack = goBack)
    val countsFlow = remember(accountId, repository) { repository.libraryCounts(accountId) }
    val counts by countsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val feedEntryCounts = remember(counts) { counts.associate { it.feedId to it.total } }
    val feedsByCategory = remember(feeds) { feeds.groupBy { it.categoryId } }
    val categoryEntryCounts = remember(feedEntryCounts, feedsByCategory) {
        feedsByCategory.mapValues { (_, values) -> values.sumOf { feedEntryCounts[it.id] ?: 0 } }
    }
    val entryCount = remember(counts) { counts.sumOf { it.total } }
    val unreadCount = remember(counts) { counts.sumOf { it.unread } }
    val rootListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    val articleListState = rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(padding)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val activeListState = when {
                feedId != null || scope != null -> articleListState
                categoryId != null -> categoryListState
                else -> rootListState
            }
            when {
                feedId != null || scope != null -> Column {
                    // A new scope owns its result state, so a previous query's
                    // articles cannot briefly appear under the new breadcrumb.
                    val visibleEntries = androidx.compose.runtime.key(feedId, scope) {
                        val entriesFlow = remember(accountId, repository, feedId, scope) {
                            repository.libraryEntries(accountId, feedId, when (scope) {
                                EntryScope.UNREAD -> false
                                EntryScope.READ -> true
                                else -> null
                            })
                        }
                        entriesFlow.collectAsStateWithLifecycle(initialValue = emptyList()).value
                    }
                    LibraryBreadcrumb(
                        title = feedId?.let { id -> feeds.firstOrNull { it.id == id }?.title } ?: when (scope) {
                            EntryScope.ALL -> "All articles"
                            EntryScope.UNREAD -> "Unread articles"
                            EntryScope.READ -> "Read articles"
                            null -> "Articles"
                        },
                        onBack = goBack,
                    )
                    EntryResults(visibleEntries, "No cached articles here", articleListState) { onOpen(it, visibleEntries) }
                }
                categoryId != null -> Column {
                    val category = categories.firstOrNull { it.id == categoryId }
                    LibraryBreadcrumb(category?.title ?: "Category", goBack)
                    val categoryFeeds = feedsByCategory[categoryId].orEmpty()
                    KeyboardList(
                        ids = categoryFeeds.map { it.id },
                        onOpen = { feedId = it },
                        indexOf = { id -> categoryFeeds.indexOfFirst { it.id == id }.coerceAtLeast(0) },
                        modifier = Modifier.fillMaxSize(), state = categoryListState,
                    ) {
                        items(categoryFeeds, key = { it.id }) { feed ->
                            Column(Modifier.animateItem()) {
                                LibraryNavRow(feed.id, Icons.Rounded.RssFeed, feed.title, "${feedEntryCounts[feed.id] ?: 0} cached") { feedId = feed.id }
                            }
                        }
                    }
                }
                else -> LibraryRoot(
                    entryCount = entryCount,
                    unreadCount = unreadCount,
                    categories = categories,
                    feedsByCategory = feedsByCategory,
                    categoryEntryCounts = categoryEntryCounts,
                    listState = rootListState,
                    onScope = { scope = it },
                    onCategory = { categoryId = it },
                )
            }
            ScrollToTopButton(
                activeListState,
                Modifier.align(Alignment.TopCenter),
                enabled = !floatingUiBlocked,
            )
        }
    }
}

@Composable
private fun LibraryRoot(
    entryCount: Int,
    unreadCount: Int,
    categories: List<Category>,
    feedsByCategory: Map<Long, List<Feed>>,
    categoryEntryCounts: Map<Long, Int>,
    listState: LazyListState,
    onScope: (EntryScope) -> Unit,
    onCategory: (Long) -> Unit,
) {
    val scopes = EntryScope.entries
    val ids = scopes.map { Long.MIN_VALUE + it.ordinal } + categories.map { it.id }
    KeyboardList(
        ids = ids,
        onOpen = { id ->
            val scope = scopes.firstOrNull { Long.MIN_VALUE + it.ordinal == id }
            if (scope != null) onScope(scope) else onCategory(id)
        },
        indexOf = { id -> val index = ids.indexOf(id); if (index < 3) index + 1 else index + 2 },
        modifier = Modifier.fillMaxSize(), state = listState,
    ) {
        item { LibraryLabel("Browse") }
        item(key = Long.MIN_VALUE) { LibraryNavRow(Long.MIN_VALUE, Icons.AutoMirrored.Rounded.LibraryBooks, "All articles", "$entryCount cached") { onScope(EntryScope.ALL) } }
        item(key = Long.MIN_VALUE + 1) { LibraryNavRow(Long.MIN_VALUE + 1, Icons.Rounded.MarkEmailUnread, "Unread", "$unreadCount articles") { onScope(EntryScope.UNREAD) } }
        item(key = Long.MIN_VALUE + 2) { LibraryNavRow(Long.MIN_VALUE + 2, Icons.Rounded.MarkEmailRead, "Read", "${entryCount - unreadCount} articles") { onScope(EntryScope.READ) } }
        item { LibraryLabel("Categories") }
        items(categories, key = { it.id }) { category ->
            Column(Modifier.animateItem()) {
                val categoryFeedCount = feedsByCategory[category.id]?.size ?: 0
                LibraryNavRow(
                    category.id, Icons.Rounded.Folder,
                    category.title,
                    "$categoryFeedCount feeds · ${categoryEntryCounts[category.id] ?: 0} cached",
                ) { onCategory(category.id) }
            }
        }
    }
}

@Composable
private fun LibraryLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
)

@Composable
private fun LibraryNavRow(id: Long, icon: ImageVector, title: String, supporting: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().keyboardListItem(id).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(supporting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 58.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
}

@Composable
private fun LibraryBreadcrumb(title: String, onBack: () -> Unit) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    Text(title, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun EntryResults(entries: List<Entry>, emptyText: String, listState: LazyListState, onOpen: (Entry) -> Unit) {
    if (entries.isEmpty()) {
        Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
    } else {
        KeyboardArticleList(entries, onOpen, Modifier.fillMaxSize().testTag("entry-list"), state = listState) {
            articleItems(entries) { entry ->
                Column(Modifier.animateItem()) {
                    InputHeadlineRow(
                        entry = entry,
                        title = entry.title,
                        metadata = listOf(entry.feedTitle, if (entry.read) "Read" else "Unread").filter(String::isNotBlank).joinToString(" · "),
                        onClick = { onOpen(entry) },
                        isUnread = !entry.read,
                        modifier = Modifier.testTag("entry-${entry.id}"),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                }
            }
        }
    }
}
