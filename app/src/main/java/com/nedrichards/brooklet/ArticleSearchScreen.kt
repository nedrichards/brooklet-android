package com.nedrichards.brooklet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nedrichards.brooklet.model.Entry
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import com.nedrichards.brooklet.sync.EntryRepository
import com.nedrichards.brooklet.sync.ArticleSearchState

internal enum class SearchReadFilter { ANY, UNREAD, READ }

@Composable
internal fun ArticleSearchScreen(
    workspaceLabel: String,
    accountId: Long,
    scope: String,
    repository: EntryRepository,
    showLibraryFilters: Boolean,
    padding: PaddingValues,
    floatingUiBlocked: Boolean,
    onOpen: (Entry, List<Entry>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var readFilter by rememberSaveable { mutableStateOf(SearchReadFilter.ANY) }
    var savedOnly by rememberSaveable { mutableStateOf(false) }
    var feedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var feedMenuOpen by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val feedsFlow = remember(accountId, scope, repository) { repository.searchFeeds(accountId, scope) }
    val availableFeeds by feedsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        debouncedQuery = query
    }
    val searchFlow = remember(accountId, scope, repository, debouncedQuery, readFilter, savedOnly, feedId) {
        repository.search(accountId, debouncedQuery, scope, when (readFilter) {
            SearchReadFilter.ANY -> null
            SearchReadFilter.UNREAD -> false
            SearchReadFilter.READ -> true
        }, savedOnly, feedId)
    }
    val state by searchFlow.collectAsStateWithLifecycle(initialValue = ArticleSearchState())
    val results = if (query.isBlank()) emptyList() else state.entries

    LaunchedEffect(Unit) { if (query.isBlank()) focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize().padding(padding)) {
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .keyboardEditing().focusRequester(focusRequester)
                .testTag("article-search-field"),
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, "Clear search")
                    }
                }
            } else {
                null
            },
            placeholder = { Text("Search stored $workspaceLabel articles") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        if (showLibraryFilters || availableFeeds.size > 1 || feedId != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                if (showLibraryFilters) {
                    FilterChip(
                        selected = readFilter == SearchReadFilter.UNREAD,
                        onClick = {
                            readFilter = if (readFilter == SearchReadFilter.UNREAD) {
                                SearchReadFilter.ANY
                            } else {
                                SearchReadFilter.UNREAD
                            }
                        },
                        label = { Text("Unread") },
                        modifier = Modifier.padding(end = 8.dp).testTag("search-filter-unread"),
                    )
                    FilterChip(
                        selected = readFilter == SearchReadFilter.READ,
                        onClick = {
                            readFilter = if (readFilter == SearchReadFilter.READ) {
                                SearchReadFilter.ANY
                            } else {
                                SearchReadFilter.READ
                            }
                        },
                        label = { Text("Read") },
                        modifier = Modifier.padding(end = 8.dp).testTag("search-filter-read"),
                    )
                    FilterChip(
                        selected = savedOnly,
                        onClick = { savedOnly = !savedOnly },
                        label = { Text("Saved") },
                        modifier = Modifier.padding(end = 8.dp).testTag("search-filter-saved"),
                    )
                }
                if (availableFeeds.size > 1 || feedId != null) {
                    Box {
                        val selectedFeed = availableFeeds.firstOrNull { it.id == feedId }
                        FilterChip(
                            selected = feedId != null,
                            onClick = { feedMenuOpen = true },
                            label = { Text(selectedFeed?.title ?: "Feed") },
                            modifier = Modifier.widthIn(max = 220.dp),
                        )
                        DropdownMenu(
                            expanded = feedMenuOpen,
                            onDismissRequest = { feedMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Any feed") },
                                onClick = {
                                    feedId = null
                                    feedMenuOpen = false
                                },
                            )
                            availableFeeds.forEach { feed ->
                                DropdownMenuItem(
                                    text = { Text(feed.title) },
                                    onClick = {
                                        feedId = feed.id
                                        feedMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (query.isBlank()) {
                Text(
                    "Search titles and article text stored in $workspaceLabel",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else if (state.failed) {
                Text("Couldn’t search stored articles. Reopen search to retry.", modifier = Modifier.padding(24.dp))
            } else if (state.preparing || (results.isEmpty() && query != debouncedQuery)) {
                Column(Modifier.padding(24.dp)) {
                    if (state.preparing) Text("Preparing stored articles for search…")
                    CircularProgressIndicator()
                }
            } else {
                SearchEntryResults(results, listState) { onOpen(it, results) }
            }
            ScrollToTopButton(
                listState,
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                enabled = !floatingUiBlocked && results.isNotEmpty(),
            )
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 125L

@Composable
private fun SearchEntryResults(
    entries: List<Entry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpen: (Entry) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            "No stored articles match. Only content retained on this device is searched.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    } else {
        KeyboardArticleList(
            entries, onOpen, Modifier.fillMaxSize().testTag("entry-list"),
            state = listState,
        ) {
            articleItems(entries) { entry ->
                Column(Modifier.animateItem()) {
                    InputHeadlineRow(
                        entry = entry,
                        title = entry.title,
                        metadata = listOf(
                            entry.feedTitle,
                            if (entry.read) "Read" else "Unread",
                        ).filter(String::isNotBlank).joinToString(" · "),
                        onClick = { onOpen(entry) },
                        isUnread = !entry.read,
                        modifier = Modifier.testTag("entry-${entry.id}"),
                    )
                    androidx.compose.material3.HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f),
                    )
                }
            }
        }
    }
}
