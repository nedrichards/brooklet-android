package com.nedrichards.brooklet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nedrichards.brooklet.model.Entry
import com.nedrichards.brooklet.model.DeliveryState
import com.nedrichards.brooklet.designsystem.BrookletHeadlineRow
import kotlinx.coroutines.flow.collect
import com.nedrichards.brooklet.sync.SyncActivityState
import com.nedrichards.brooklet.sync.EntryRepository
import com.nedrichards.brooklet.sync.SyncScheduler
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private enum class Destination(val label: String, val icon: ImageVector) {
    INBOX("Inbox", Icons.Rounded.Inbox),
    SAVED("Saved", Icons.Rounded.Bookmark),
    LIBRARY("Library", Icons.AutoMirrored.Rounded.LibraryBooks),
}

private data class InboxListPosition(
    val entryId: Long,
    val fallbackIndex: Int,
    val scrollOffset: Int,
)

private data class UndoViewportAnchor(
    val entryId: Long,
    val itemOffset: Int,
    val restoredIds: Set<Long>,
)

internal fun countNewLeadingInboxEntries(observedIds: Set<Long>?, currentIds: List<Long>): Int =
    observedIds?.let { observed -> currentIds.takeWhile { it !in observed }.size } ?: 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(application: BrookletApplication, accountId: Long) {
    val undoFactory = remember(accountId, application.repository) {
        viewModelFactory {
            initializer {
                InboxUndoViewModel(
                    accountId = accountId,
                    repository = application.repository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
    val undoViewModel: InboxUndoViewModel = viewModel(
        key = "inbox-undo-$accountId",
        factory = undoFactory,
    )
    MainShellContent(
        application = application,
        accountId = accountId,
        repository = application.repository,
        scheduler = application.scheduler,
        undoViewModel = undoViewModel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShellContent(
    application: BrookletApplication,
    accountId: Long,
    repository: EntryRepository,
    scheduler: SyncScheduler,
    undoViewModel: InboxUndoViewModel,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.INBOX) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var searchDestination by rememberSaveable { mutableStateOf<Destination?>(null) }
    var inboxActionsOpen by remember { mutableStateOf(false) }
    var readerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var readerOrder by remember { mutableStateOf(emptyList<Long>()) }
    val inbox by repository.inbox(accountId).collectAsStateWithLifecycle(initialValue = emptyList())
    val savedState = if (destination == Destination.SAVED || searchDestination == Destination.LIBRARY) {
        repository.saved(accountId).collectAsStateWithLifecycle(initialValue = emptyList())
    } else remember { mutableStateOf(emptyList()) }
    val allState = if (destination == Destination.LIBRARY) {
        repository.allEntries(accountId).collectAsStateWithLifecycle(initialValue = emptyList())
    } else remember { mutableStateOf(emptyList()) }
    val categoriesState = if (destination == Destination.LIBRARY) {
        repository.categories(accountId).collectAsStateWithLifecycle(initialValue = emptyList())
    } else remember { mutableStateOf(emptyList()) }
    val feedsState = if (destination == Destination.LIBRARY || searchDestination != null) {
        repository.feeds(accountId).collectAsStateWithLifecycle(initialValue = emptyList())
    } else remember { mutableStateOf(emptyList()) }
    val saved by savedState
    val all by allState
    val categories by categoriesState
    val feeds by feedsState
    val savedEntryIds = remember(saved) { saved.mapTo(mutableSetOf()) { it.id } }
    val syncActivity by scheduler.activity.collectAsStateWithLifecycle(
        initialValue = com.nedrichards.brooklet.sync.SyncActivity(),
    )
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoUiState by undoViewModel.uiState.collectAsStateWithLifecycle()
    val mainContentState = rememberSaveableStateHolder()
    val inboxListState = rememberLazyListState()
    val latestInbox by rememberUpdatedState(inbox)
    var pendingInboxReturn by remember { mutableStateOf<InboxListPosition?>(null) }
    var pendingUndoViewportAnchor by remember { mutableStateOf<UndoViewportAnchor?>(null) }
    BackHandler(enabled = readerId != null) { readerId = null }
    BackHandler(enabled = settingsOpen && readerId == null) { settingsOpen = false }
    BackHandler(enabled = searchDestination != null && readerId == null) { searchDestination = null }

    LaunchedEffect(undoUiState.pending?.generation) {
        val pending = undoUiState.pending ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(
            message = pending.message,
            actionLabel = if (pending.retry) "Retry" else "Undo",
            withDismissAction = true,
            duration = if (pending.retry) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            inboxListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key is Long && it.key !in pending.entries.keys }
                ?.let { anchor ->
                    pendingUndoViewportAnchor = UndoViewportAnchor(
                        entryId = anchor.key as Long,
                        itemOffset = anchor.offset,
                        restoredIds = pending.entries.keys.toSet(),
                    )
                }
            undoViewModel.undo()
        } else {
            undoViewModel.dismiss(pending.generation)
        }
    }
    LaunchedEffect(undoUiState.confirmation) {
        val confirmation = undoUiState.confirmation ?: return@LaunchedEffect
        snackbar.showSnackbar(
            message = confirmation,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        undoViewModel.confirmationShown(confirmation)
    }
    LaunchedEffect(undoUiState.error) {
        val error = undoUiState.error ?: return@LaunchedEffect
        snackbar.showSnackbar(
            message = error,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        undoViewModel.errorShown(error)
    }
    val undoFeedbackActive = undoUiState.pending != null ||
        undoUiState.confirmation != null || undoUiState.error != null
    val floatingUiBlocked = undoFeedbackActive || snackbar.currentSnackbarData != null
    LaunchedEffect(pendingInboxReturn, undoFeedbackActive) {
        val position = pendingInboxReturn ?: return@LaunchedEffect
        // Read-state Undo is the critical action. Wait until its feedback has
        // resolved rather than replacing it with a navigation convenience.
        if (undoFeedbackActive) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "Jumped to top",
            actionLabel = "Go back",
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            val currentEntries = latestInbox
            val targetIndex = currentEntries.indexOfFirst { it.id == position.entryId }
                .takeIf { it >= 0 }
                ?: position.fallbackIndex.coerceIn(0, (currentEntries.lastIndex).coerceAtLeast(0))
            if (currentEntries.isNotEmpty()) {
                inboxListState.animateScrollToItem(
                    articleLazyListIndex(currentEntries, targetIndex),
                    position.scrollOffset,
                )
            }
        }
        pendingInboxReturn = null
    }
    LaunchedEffect(destination) {
        if (destination != Destination.INBOX) pendingInboxReturn = null
    }
    LaunchedEffect(pendingUndoViewportAnchor, inbox, destination) {
        val anchor = pendingUndoViewportAnchor ?: return@LaunchedEffect
        if (destination != Destination.INBOX || !inbox.map { it.id }.containsAll(anchor.restoredIds)) {
            return@LaunchedEffect
        }
        val anchorIndex = inbox.indexOfFirst { it.id == anchor.entryId }
        if (anchorIndex < 0) return@LaunchedEffect
        withFrameNanos { }
        inboxListState.scrollToItem(
            index = articleLazyListIndex(inbox, anchorIndex),
            scrollOffset = -anchor.itemOffset,
        )
        pendingUndoViewportAnchor = null
    }
    if (readerId != null) {
        val activeReaderId = readerId!!
        Box(Modifier.fillMaxSize()) {
            ReaderScreen(
                accountId = accountId,
                entryId = activeReaderId,
                repository = repository,
                savePosition = { block, offset ->
                    application.saveReaderPosition(accountId, activeReaderId, block, offset)
                },
                onBack = { readerId = null },
                onKeptUnread = {
                    readerId = null
                    scope.launch {
                        snackbar.showSnackbar(
                            message = "Kept unread",
                            withDismissAction = true,
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
                onPrevious = readerOrder.before(activeReaderId)?.let { { readerId = it } },
                onNext = readerOrder.after(activeReaderId)?.let { { readerId = it } },
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    val markRead: (Entry) -> Unit = undoViewModel::markRead
    val markAllRead = { undoViewModel.markAllRead(inbox) }
    val open: (Entry, List<Entry>) -> Unit = { entry, list -> readerOrder = list.map { it.id }; readerId = entry.id }
    val jumpInboxToTop = jump@{
        if (pendingInboxReturn != null) return@jump
        val anchor = inboxListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long } ?: return@jump
        val entryId = anchor.key as Long
        val entryIndex = inbox.indexOfFirst { it.id == entryId }.takeIf { it >= 0 } ?: return@jump
        if (entryIndex == 0 && anchor.offset == 0) return@jump
        pendingInboxReturn = InboxListPosition(entryId, entryIndex, (-anchor.offset).coerceAtLeast(0))
        scope.launch { inboxListState.animateScrollToItem(0) }
    }
    val selectDestination: (Destination) -> Unit = { selected ->
        if (searchDestination != null) {
            searchDestination = null
            destination = selected
        } else if (selected == Destination.INBOX && destination == Destination.INBOX) {
            jumpInboxToTop()
        } else {
            destination = selected
        }
    }
    val mainTopBarActions: @Composable RowScope.() -> Unit = {
        if (destination == Destination.LIBRARY) {
            IconButton(onClick = { searchDestination = Destination.LIBRARY }) {
                Icon(Icons.Rounded.Search, "Search Library")
            }
        }
        when {
            syncActivity.isActive && syncActivity.userInitiated -> {
                IconButton(onClick = scheduler::cancelImmediate) {
                    Icon(Icons.Rounded.Close, "Stop sync")
                }
            }
            syncActivity.state == SyncActivityState.RUNNING -> {
                Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).semantics {
                            contentDescription = "Syncing in background"
                        },
                        strokeWidth = 2.dp,
                    )
                }
            }
            destination != Destination.LIBRARY -> {
                IconButton(onClick = scheduler::enqueueUserSync) {
                    Icon(Icons.Rounded.Refresh, "Sync now")
                }
            }
        }
        Box {
            IconButton(onClick = { inboxActionsOpen = true }) {
                Icon(Icons.Rounded.MoreVert, "More actions")
            }
            DropdownMenu(expanded = inboxActionsOpen, onDismissRequest = { inboxActionsOpen = false }) {
                if (destination != Destination.LIBRARY) {
                    DropdownMenuItem(
                        text = { Text("Search") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        onClick = {
                            inboxActionsOpen = false
                            searchDestination = destination
                        },
                    )
                }
                if (destination == Destination.INBOX && inbox.isNotEmpty()) DropdownMenuItem(
                    text = { Text("Mark all read") },
                    leadingIcon = { Icon(Icons.Rounded.DoneAll, null) },
                    onClick = { inboxActionsOpen = false; markAllRead() },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                    onClick = { inboxActionsOpen = false; settingsOpen = true },
                )
            }
        }
    }

    // The reader temporarily replaces the originating workspace. Retain that
    // workspace's list cursor, search, and browse scope until it returns.
    mainContentState.SaveableStateProvider("main-content") { BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (useRail && !settingsOpen) AppRail(destination, selectDestination)
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    if (searchDestination != null) {
                        TopAppBar(
                            modifier = Modifier.testTag("main-top-app-bar"),
                            title = { Text("Search ${searchDestination!!.label}") },
                            navigationIcon = {
                                IconButton(onClick = { searchDestination = null }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search")
                                }
                            },
                        )
                    } else if (settingsOpen) {
                        TopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                IconButton(onClick = { settingsOpen = false }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                                }
                            },
                        )
                    } else {
                        TopAppBar(
                            modifier = Modifier.testTag("main-top-app-bar"),
                            title = {
                                Text(
                                    destination.label,
                                    modifier = Modifier.testTag("main-top-app-bar-title"),
                                )
                            },
                            actions = mainTopBarActions,
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { if (!useRail && !settingsOpen) AppBar(destination, selectDestination) },
                floatingActionButton = {
                    if (!settingsOpen && searchDestination == null && destination == Destination.LIBRARY &&
                        !(syncActivity.userInitiated && syncActivity.isActive) &&
                        syncActivity.state != SyncActivityState.RUNNING
                    ) FloatingActionButton(onClick = { scheduler.enqueueManualRefresh() }) {
                        Icon(Icons.Rounded.Refresh, "Refresh feeds")
                    }
                },
            ) { padding ->
                val activeSearchDestination = searchDestination
                if (activeSearchDestination != null) {
                    val searchEntries = when (activeSearchDestination) {
                        Destination.INBOX -> inbox
                        Destination.SAVED -> saved
                        Destination.LIBRARY -> all
                    }
                    ArticleSearchScreen(
                        workspaceLabel = activeSearchDestination.label,
                        entries = searchEntries,
                        feeds = feeds,
                        savedEntryIds = if (activeSearchDestination == Destination.LIBRARY) {
                            savedEntryIds
                        } else {
                            emptySet()
                        },
                        showLibraryFilters = activeSearchDestination == Destination.LIBRARY,
                        padding = padding,
                        floatingUiBlocked = floatingUiBlocked,
                        onOpen = open,
                    )
                } else if (settingsOpen) {
                    SettingsScreen(application, accountId, padding)
                } else {
                    when (destination) {
                        Destination.INBOX -> EntryList(
                            entries = inbox,
                            emptyText = if (syncActivity.isActive) "No unread articles cached yet" else "You’re all caught up",
                            padding = padding,
                            triage = true,
                            isRefreshing = syncActivity.userInitiated && syncActivity.isActive,
                            onRefresh = scheduler::enqueueUserSync,
                            onRead = markRead,
                            listState = inboxListState,
                            onScrollToTop = jumpInboxToTop,
                            floatingUiBlocked = floatingUiBlocked,
                        ) { open(it, inbox) }
                        Destination.SAVED -> SavedList(
                            entries = saved,
                            emptyText = if (syncActivity.isActive) "No saved articles cached yet" else "Nothing saved yet",
                            padding = padding,
                            floatingUiBlocked = floatingUiBlocked,
                        ) { open(it, saved) }
                        Destination.LIBRARY -> LibraryScreen(
                            entries = all,
                            categories = categories,
                            feeds = feeds,
                            padding = padding,
                            floatingUiBlocked = floatingUiBlocked,
                            onOpen = open,
                        )
                    }
                }
            }
        }
    } }
}

@Composable private fun AppBar(selected: Destination, select: (Destination) -> Unit) = NavigationBar {
    Destination.entries.forEach {
        NavigationBarItem(
            selected == it,
            { select(it) },
            { Icon(it.icon, it.label) },
            modifier = Modifier.testTag("destination-${it.name.lowercase()}"),
            label = { Text(it.label) },
        )
    }
}

@Composable private fun AppRail(selected: Destination, select: (Destination) -> Unit) = NavigationRail(Modifier.fillMaxHeight()) {
    Destination.entries.forEach {
        NavigationRailItem(
            selected == it,
            { select(it) },
            { Icon(it.icon, it.label) },
            modifier = Modifier.testTag("destination-${it.name.lowercase()}"),
            label = { Text(it.label) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryList(
    entries: List<Entry>,
    emptyText: String,
    padding: PaddingValues,
    triage: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRead: (Entry) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onScrollToTop: (() -> Unit)? = null,
    floatingUiBlocked: Boolean = false,
    onOpen: (Entry) -> Unit,
) {
    var observedEntryIds by remember { mutableStateOf<Set<Long>?>(null) }
    var detectedNewEntries by remember { mutableLongStateOf(0) }
    var queuedNewEntries by remember { mutableLongStateOf(0) }
    var inboxLeadingHeaderVisible by remember(listState) {
        mutableStateOf(listState.firstVisibleItemIndex > 1 || listState.firstVisibleItemScrollOffset > 0)
    }
    val atTop by remember {
        derivedStateOf {
            !listState.canScrollBackward ||
                (!inboxLeadingHeaderVisible && listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset == 0)
        }
    }
    val scope = rememberCoroutineScope()
    val refreshState = rememberPullToRefreshState()
    var refreshAwaitingWorkManager by remember { mutableStateOf(false) }
    val displayRefreshing = isRefreshing || refreshAwaitingWorkManager

    androidx.compose.runtime.LaunchedEffect(listState, triage) {
        if (!triage) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { (isScrolling, index, offset) ->
            when {
                isScrolling || index > 1 || offset > 0 -> inboxLeadingHeaderVisible = true
                !listState.canScrollBackward -> inboxLeadingHeaderVisible = false
            }
        }
    }

    // WorkManager reports the newly enqueued request asynchronously. Keep the
    // pull indicator active through that brief hand-off, then use its state for
    // the rest of the sync.
    androidx.compose.runtime.LaunchedEffect(isRefreshing) {
        if (isRefreshing) refreshAwaitingWorkManager = false
    }
    val requestRefresh = {
        refreshAwaitingWorkManager = true
        onRefresh()
    }

    androidx.compose.runtime.LaunchedEffect(entries) {
        val currentIds = entries.map { it.id }
        observedEntryIds?.let { observedIds ->
            // Only count genuinely unseen rows at the leading edge. Keeping a
            // cumulative set means an Undo can reinsert an article without it
            // being mistaken for something fetched by a sync.
            val newLeadingEntries = countNewLeadingInboxEntries(observedIds, currentIds)
            detectedNewEntries += newLeadingEntries.toLong()
        }
        observedEntryIds = observedEntryIds.orEmpty() + currentIds
    }
    androidx.compose.runtime.LaunchedEffect(detectedNewEntries) {
        if (detectedNewEntries == 0L) return@LaunchedEffect
        // Let LazyColumn apply the keyed insertion before deciding whether the
        // new leading rows are actually visible in the current viewport.
        withFrameNanos { }
        if (!atTop) queuedNewEntries += detectedNewEntries
        detectedNewEntries = 0
    }
    androidx.compose.runtime.LaunchedEffect(atTop) {
        if (atTop) queuedNewEntries = 0
    }

    Box(Modifier.fillMaxSize().padding(padding)) {
        PullToRefreshBox(
            isRefreshing = displayRefreshing,
            onRefresh = requestRefresh,
            modifier = Modifier.fillMaxSize(),
            state = refreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    state = refreshState,
                    isRefreshing = displayRefreshing,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            },
        ) {
            if (entries.isEmpty()) {
                EmptyEntryState(emptyText, if (triage) Icons.Rounded.Inbox else Icons.Rounded.Bookmark, PaddingValues())
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("entry-list").semantics {
                        if (!atTop && onScrollToTop != null) {
                            customActions = listOf(CustomAccessibilityAction("Scroll to top") {
                                onScrollToTop()
                                true
                            })
                        }
                    },
                    state = listState,
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    articleItems(entries, showLeadingHeader = !triage || inboxLeadingHeaderVisible) { entry ->
                        Column(Modifier.animateItem()) {
                            if (triage) {
                                // Treat a completed swipe as a command, not as
                                // persistent UI state. Otherwise Undo can put
                                // this same keyed item back into the list with
                                // its old dismissed state and immediately mark
                                // it read again (and queue another snackbar).
                                val dismiss = rememberSwipeToDismissBoxState()
                                androidx.compose.runtime.LaunchedEffect(dismiss) {
                                    snapshotFlow { dismiss.settledValue }.collect { settledValue ->
                                        if (settledValue != SwipeToDismissBoxValue.Settled) {
                                            // Settle the keyed UI state before
                                            // Room can remove this row. Undo may
                                            // reinsert the same key immediately.
                                            dismiss.snapTo(SwipeToDismissBoxValue.Settled)
                                            onRead(entry)
                                        }
                                    }
                                }
                                SwipeToDismissBox(
                                    state = dismiss,
                                    backgroundContent = {
                                        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Icon(Icons.Rounded.Check, null); Text("Mark read", fontWeight = FontWeight.Bold); Icon(Icons.Rounded.Check, null)
                                        }
                                    },
                                    content = { HeadlineRow(entry, { onRead(entry) }, { onOpen(entry) }) },
                                )
                            } else HeadlineRow(entry, null, { onOpen(entry) })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = queuedNewEntries > 0 && !floatingUiBlocked,
            enter = fadeIn() + scaleIn(initialScale = .92f),
            exit = fadeOut() + scaleOut(targetScale = .92f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shadowElevation = 3.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (onScrollToTop != null) {
                            onScrollToTop()
                        } else {
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    }) {
                        Text("$queuedNewEntries new ${if (queuedNewEntries == 1L) "article" else "articles"}")
                    }
                    IconButton(onClick = { queuedNewEntries = 0 }) {
                        Icon(Icons.Rounded.Close, "Dismiss new articles")
                    }
                }
            }
        }
        ScrollToTopButton(
            listState = listState,
            enabled = queuedNewEntries == 0L && !floatingUiBlocked,
            onClick = onScrollToTop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun HeadlineRow(entry: Entry, onRead: (() -> Unit)?, onOpen: () -> Unit) {
    val actions = onRead?.let { listOf(CustomAccessibilityAction("Mark read") { it(); true }) }.orEmpty()
    BrookletHeadlineRow(
        title = entry.title,
        metadata = entryMetadata(entry),
        onClick = onOpen,
        // Inbox is for quick triage; read state is expressed by membership,
        // not a heavier title. Library retains unread emphasis for browsing.
        isUnread = false,
        modifier = Modifier.background(MaterialTheme.colorScheme.surface).testTag("entry-${entry.id}")
            .semantics { customActions = actions },
    ) {
        if (onRead == null) Icon(Icons.AutoMirrored.Rounded.Article, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SavedList(
    entries: List<Entry>,
    emptyText: String,
    padding: PaddingValues,
    floatingUiBlocked: Boolean,
    onOpen: (Entry) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyEntryState(emptyText, Icons.Rounded.Bookmark, padding)
    } else {
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                articleItems(entries) { entry ->
                    Column(Modifier.animateItem()) {
                        BrookletHeadlineRow(entry.title, entryMetadata(entry), { onOpen(entry) }, isUnread = !entry.read) {
                            SavedStatus(entry.deliveryState)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                    }
                }
            }
            ScrollToTopButton(
                listState,
                Modifier.align(Alignment.BottomCenter),
                enabled = !floatingUiBlocked,
            )
        }
    }
}

@Composable
private fun EmptyEntryState(text: String, icon: ImageVector, padding: PaddingValues) = Box(
    Modifier.fillMaxSize().padding(padding).padding(32.dp),
    contentAlignment = Alignment.Center,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, null, Modifier.padding(18.dp).width(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SavedStatus(state: DeliveryState?) {
    val (icon, label) = when (state) {
        DeliveryState.QUEUED, DeliveryState.SENDING -> Icons.Rounded.CloudUpload to "Queued"
        DeliveryState.SAVED -> Icons.Rounded.CloudDone to "Saved"
        DeliveryState.NEEDS_ATTENTION -> Icons.Rounded.ErrorOutline to "Attention"
        null -> Icons.Rounded.Bookmark to "Starred"
    }
    val attention = state == DeliveryState.NEEDS_ATTENTION
    Surface(
        shape = CircleShape,
        color = if (attention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

private fun entryMetadata(entry: Entry) = listOfNotNull(
    entry.feedTitle.ifBlank { null },
    age(entry.publishedAt),
    entry.author?.takeIf(String::isNotBlank),
    "${entry.readingMinutes} min",
).joinToString(" · ")

private fun age(publishedAt: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes((System.currentTimeMillis() - publishedAt).coerceAtLeast(0))
    return when { minutes < 60 -> "${minutes.coerceAtLeast(1)}m"; minutes < 1440 -> "${minutes / 60}h"; else -> "${minutes / 1440}d" }
}
private fun List<Long>.before(id: Long): Long? = indexOf(id).takeIf { it > 0 }?.let { this[it - 1] }
private fun List<Long>.after(id: Long): Long? = indexOf(id).takeIf { it >= 0 && it < lastIndex }?.let { this[it + 1] }
