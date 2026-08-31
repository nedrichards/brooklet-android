package com.nedrichards.brooklet.wear

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RevealValue
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwipeToReveal
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import androidx.wear.compose.material3.rememberRevealState
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.nedrichards.brooklet.model.DocumentBlock
import com.nedrichards.brooklet.wear.data.WearEntrySummary
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable private data object InboxDestination : NavKey
@Serializable private data class ReaderDestination(val entryId: Long) : NavKey
@Serializable private data object StatusDestination : NavKey
@Serializable private data object DisconnectDestination : NavKey
@Serializable private data object ReconnectDestination : NavKey

class MainActivity : ComponentActivity() {
    private var deepLinkEntryId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkEntryId = intent.entryId()
        setContent { BrookletWearApp(deepLinkEntryId) { deepLinkEntryId = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkEntryId = intent.entryId()
    }

    private fun Intent.entryId(): Long? = data?.takeIf { it.scheme == "brooklet" && it.host == "entry" }
        ?.pathSegments?.firstOrNull()?.toLongOrNull()
}

@Composable
private fun BrookletWearApp(deepLinkEntryId: Long?, consumeDeepLink: () -> Unit) {
    val application = LocalContext.current.applicationContext as BrookletWearApplication
    val account by application.repository.account.collectAsStateWithLifecycle(initialValue = null)
    MaterialTheme {
        AppScaffold {
            if (account == null) {
                SetupScreen(application.provisioning)
            } else {
                val backStack = rememberNavBackStack(InboxDestination)
                val sync by application.repository.syncState.collectAsStateWithLifecycle(initialValue = null)
                LaunchedEffect(account, sync?.lastSuccessfulSyncAt) {
                    val age = System.currentTimeMillis() - (sync?.lastSuccessfulSyncAt ?: 0L)
                    if (age > TimeUnit.MINUTES.toMillis(30)) application.scheduler.enqueueForeground()
                }
                LaunchedEffect(deepLinkEntryId) {
                    deepLinkEntryId?.let { id ->
                        backStack.add(ReaderDestination(id))
                        consumeDeepLink()
                    }
                }
                NavDisplay(
                    backStack = backStack,
                    sceneStrategies = listOf(rememberSwipeDismissableSceneStrategy()),
                    entryProvider = entryProvider {
                        entry<InboxDestination> {
                            InboxScreen(application, onOpen = { backStack.add(ReaderDestination(it)) }, onStatus = { backStack.add(StatusDestination) })
                        }
                        entry<ReaderDestination> { key -> ReaderScreen(application, key.entryId) }
                        entry<StatusDestination> {
                            StatusScreen(
                                application,
                                onDisconnect = { backStack.add(DisconnectDestination) },
                                onReconnect = { backStack.add(ReconnectDestination) },
                            )
                        }
                        entry<ReconnectDestination> {
                            SetupScreen(application.provisioning, reconnect = true) {
                                if (backStack.lastOrNull() == ReconnectDestination) backStack.removeAt(backStack.lastIndex)
                            }
                        }
                        entry<DisconnectDestination> {
                            DisconnectScreen(application) {
                                backStack.clear()
                                backStack.add(InboxDestination)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    provisioning: WatchProvisioningManager,
    reconnect: Boolean = false,
    onConnected: () -> Unit = {},
) {
    val state by provisioning.state.collectAsState()
    val scope = rememberCoroutineScope()
    val directDevelopmentSetup = shouldUseEmbeddedDevelopmentCredentials(
        BuildConfig.DEBUG,
        BuildConfig.DEV_MINIFLUX_URL,
        BuildConfig.DEV_MINIFLUX_TOKEN,
    )
    var started by remember(provisioning, reconnect) { mutableStateOf(false) }
    LaunchedEffect(provisioning, reconnect) {
        started = true
        provisioning.beginSetup()
    }
    LaunchedEffect(started, state.status) {
        if (reconnect && started && state.status == "Connected") onConnected()
    }
    WearList { _ ->
        item { ListHeader { Text("Brooklet") } }
        item {
            Text(
                when {
                    reconnect && directDevelopmentSetup -> "Reconnect Brooklet"
                    reconnect -> "Reconnect using your phone"
                    directDevelopmentSetup -> "Setting up Brooklet"
                    else -> "Set up using your phone"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (reconnect) item {
            Text(
                "Approval replaces this watch's cached articles and queued watch actions. Phone and Miniflux data are unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item { Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (state.retryAvailable) {
            item {
                Button(
                    onClick = { scope.launch { provisioning.beginSetup() } },
                    enabled = !state.requesting,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Try again") },
                )
            }
        }
    }
}

internal sealed interface InboxItem {
    data class Entry(val value: WearEntrySummary) : InboxItem
    data class Undo(val value: WearEntrySummary) : InboxItem
}

internal data class InboxUndoState(
    val index: Int,
    val entry: WearEntrySummary,
    val restoring: Boolean = false,
)

private val InboxUndoStateSaver = listSaver<InboxUndoState?, Any>(
    save = { state ->
        state?.let {
            listOf(
                it.index,
                it.entry.id,
                it.entry.feedTitle,
                it.entry.title,
                it.entry.publishedAt,
                it.entry.read,
                it.entry.starred,
                it.entry.hasBody,
                it.restoring,
            )
        }.orEmpty()
    },
    restore = { values ->
        if (values.isEmpty()) null else InboxUndoState(
            index = (values[0] as Number).toInt(),
            entry = WearEntrySummary(
                id = (values[1] as Number).toLong(),
                feedTitle = values[2] as String,
                title = values[3] as String,
                publishedAt = (values[4] as Number).toLong(),
                read = values[5] as Boolean,
                starred = values[6] as Boolean,
                hasBody = values[7] as Boolean,
            ),
            restoring = values[8] as Boolean,
        )
    },
)

internal fun inboxRows(inbox: List<WearEntrySummary>, undo: InboxUndoState?): List<InboxItem> {
    val rows = inbox
        .filterNot { it.id == undo?.entry?.id }
        .map<WearEntrySummary, InboxItem>(InboxItem::Entry)
        .toMutableList()
    undo?.let { rows.add(it.index.coerceIn(0, rows.size), InboxItem.Undo(it.entry)) }
    return rows
}

@Composable
private fun InboxScreen(application: BrookletWearApplication, onOpen: (Long) -> Unit, onStatus: () -> Unit) {
    val inbox by application.repository.inbox.collectAsStateWithLifecycle(initialValue = emptyList())
    val sync by application.repository.syncState.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val transformation = rememberTransformationSpec()
    var undo by rememberSaveable(stateSaver = InboxUndoStateSaver) { mutableStateOf<InboxUndoState?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(8_000)
            undo = null
        }
    }
    LaunchedEffect(inbox, undo?.restoring) {
        val restoring = undo?.takeIf { it.restoring } ?: return@LaunchedEffect
        if (inbox.any { it.id == restoring.entry.id }) undo = null
    }
    val rows = remember(inbox, undo) { inboxRows(inbox, undo) }
    WearList { listState ->
        item {
            ListHeader {
                val unread = if (inbox.size == 1) "1 unread" else "${inbox.size} unread"
                Text("Brooklet · $unread")
            }
        }
        if (rows.isEmpty()) item { Text("No unread articles", modifier = Modifier.fillMaxWidth().padding(16.dp)) }
        items(rows, key = { row -> when (row) { is InboxItem.Entry -> "entry-${row.value.id}"; is InboxItem.Undo -> "undo-${row.value.id}" } }) { row ->
            when (row) {
                is InboxItem.Undo -> FilledTonalButton(
                    onClick = {
                        undo = undo?.copy(restoring = true)
                        scope.launch {
                            runCatching { application.repository.setRead(row.value.id, false) }
                                .onFailure { undo = undo?.copy(restoring = false) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformation).animateItem(),
                    enabled = undo?.restoring != true,
                    label = { Text(if (undo?.restoring == true) "Restoring…" else "Undo mark read") },
                    secondaryLabel = { Text(row.value.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
                is InboxItem.Entry -> InboxArticleRow(
                    entry = row.value,
                    onOpen = { onOpen(row.value.id) },
                    onRead = {
                        val index = inbox.indexOfFirst { it.id == row.value.id }
                        undo = InboxUndoState(index, row.value)
                        scope.launch { application.repository.setRead(row.value.id, true) }
                    },
                    onStar = { scope.launch { application.repository.setStarred(row.value.id, !row.value.starred) } },
                    scrolling = listState.isScrollInProgress,
                    modifier = Modifier.transformedHeight(this, transformation).animateItem(),
                )
            }
        }
        item {
            val status = when (sync?.phase) {
                "ERROR" -> "Offline · sync failed"
                "RECONNECT_REQUIRED" -> "Reconnect using phone"
                "CONNECTING", "PUSHING_ACTIONS", "PULLING_ENTRIES", "BOOTSTRAPPING", "PRUNING" -> "Syncing…"
                else -> lastSyncLabel(sync?.lastSuccessfulSyncAt)
            }
            Button(
                onClick = onStatus,
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Sync & settings") },
                secondaryLabel = { Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun InboxArticleRow(entry: WearEntrySummary, onOpen: () -> Unit, onRead: () -> Unit, onStar: () -> Unit, scrolling: Boolean, modifier: Modifier = Modifier) {
    val revealState = rememberRevealState()
    val scope = rememberCoroutineScope()
    val markRead = {
        scope.launch {
            // Reset the reveal state before Room removes the row. If Undo
            // restores the same keyed item, it must return covered rather than
            // replaying the completed swipe.
            revealState.snapTo(RevealValue.Covered)
            onRead()
        }
        Unit
    }
    LaunchedEffect(scrolling) {
        if (scrolling && revealState.currentValue != RevealValue.Covered) revealState.animateTo(RevealValue.Covered)
    }
    SwipeToReveal(
        revealState = revealState,
        onSwipePrimaryAction = markRead,
        modifier = modifier,
        primaryAction = {
            PrimaryActionButton(
                onClick = markRead,
                icon = { Icon(Icons.Default.Check, contentDescription = "Mark read") },
                text = { Text("Read") },
            )
        },
        secondaryAction = {
            SecondaryActionButton(
                onClick = onStar,
                icon = { Icon(if (entry.starred) Icons.Outlined.Star else Icons.Default.Star, contentDescription = if (entry.starred) "Unsave" else "Save") },
            )
        },
    ) {
        TitleCard(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Mark read") { markRead(); true },
                    CustomAccessibilityAction(if (entry.starred) "Unsave" else "Save") { onStar(); true },
                )
            },
            title = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            subtitle = { Text(entry.feedTitle.ifBlank { "Feed" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            time = { Text(relativeTime(entry.publishedAt)) },
        )
    }
}

@Composable
private fun ReaderScreen(application: BrookletWearApplication, entryId: Long) {
    val entry by application.repository.entry(entryId).collectAsStateWithLifecycle(initialValue = null)
    val karakeep by application.repository.karakeep(entryId).collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var keepUnreadRequested by rememberSaveable(entryId) { mutableStateOf(false) }
    val context = LocalContext.current
    val openPhone = rememberOpenOnPhone {
        message = it
        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
    }
    val blocks = remember(entry?.blocksJson) { application.repository.blocks(entry?.blocksJson.orEmpty()) }
    var positionLoaded by remember(entryId) { mutableStateOf(false) }
    var savedPosition by remember(entryId) { mutableStateOf<com.nedrichards.brooklet.wear.data.WearReaderPositionEntity?>(null) }
    LaunchedEffect(entryId) {
        savedPosition = application.repository.readerPosition(entryId)
        positionLoaded = true
    }
    DisposableEffect(entryId) {
        WearSyncCoordinator.readerOpened(entryId)
        onDispose { WearSyncCoordinator.readerClosed(entryId) }
    }
    val keepUnread: () -> Unit = {
        keepUnreadRequested = true
        scope.launch {
            runCatching { application.repository.setRead(entryId, false) }
                .onSuccess { message = null }
                .onFailure { message = "Could not keep this article unread" }
        }
    }
    LaunchedEffect(entryId, entry?.bodyBytes) {
        val value = entry ?: return@LaunchedEffect
        if (value.bodyBytes == 0) {
            runCatching {
                WearSyncCoordinator.exclusive {
                    WearSyncEngine(application.database.dao(), com.nedrichards.brooklet.wear.data.WearTokenCipher()).fetchBody(entryId)
                }
            }.onSuccess { available ->
                application.scheduler.requestTileUpdate()
                if (!available) message = "Article body is unavailable"
            }.onFailure { message = "Article body unavailable offline" }
        } else {
            application.repository.touchBody(entryId)
        }
    }
    LaunchedEffect(entryId, blocks.isNotEmpty(), keepUnreadRequested) {
        if (shouldAutomaticallyMarkRead(blocks.isNotEmpty(), entry?.read == false, keepUnreadRequested)) {
            application.repository.setRead(entryId, true)
        }
    }
    if (!positionLoaded) {
        WearList { item { Text("Opening article…", modifier = Modifier.fillMaxWidth().padding(16.dp)) } }
        return
    }
    val savePosition = remember(application, entryId) {
        { index: Int, offset: Int -> application.saveReaderPosition(entryId, index, offset) }
    }
    WearList(
        initialAnchorItemIndex = savedPosition?.anchorItemIndex ?: 0,
        initialAnchorItemScrollOffset = savedPosition?.anchorItemScrollOffset ?: 0,
        onPositionChanged = savePosition,
    ) { _ ->
        item { Spacer(Modifier.height(18.dp)) }
        item { Text(entry?.title ?: "Article", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
        item {
            Button(
                onClick = keepUnread,
                enabled = entry != null,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (keepUnreadRequested && entry?.read == false) "Kept unread" else "Keep unread") },
            )
        }
        message?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) } }
        blocks.forEach { block ->
            item { ReaderBlock(block) }
            block.links().forEach { link ->
                item {
                    FilledTonalButton(
                        onClick = { openPhone(resolveLink(entry?.url.orEmpty(), link.url)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(link.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text("Open link on phone") },
                    )
                }
            }
        }
        if (entry?.bodyTruncated == true) item {
            Button(onClick = { openPhone(entry?.url.orEmpty()) }, modifier = Modifier.fillMaxWidth(), label = { Text("Continue on phone") })
        }
        item {
            Button(
                onClick = { scope.launch { application.repository.setStarred(entryId, entry?.starred != true) } },
                enabled = entry != null,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (entry?.starred == true) "Unsave" else "Save") },
            )
        }
        item {
            Button(
                onClick = { scope.launch { application.repository.sendToKarakeep(entryId) } },
                enabled = entry != null,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        when (karakeep?.state) {
                            "QUEUED" -> "Queued for Karakeep"
                            "NEEDS_ATTENTION" -> "Retry Karakeep"
                            "SAVED" -> "Sent to Karakeep"
                            else -> "Send to Karakeep"
                        },
                    )
                },
            )
        }
        item {
            Button(
                onClick = { openPhone(entry?.url.orEmpty()) },
                enabled = entry != null,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Open on phone") },
            )
        }
        item {
            Button(
                onClick = keepUnread,
                enabled = entry != null,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (keepUnreadRequested && entry?.read == false) "Kept unread" else "Keep unread") },
            )
        }
        if (keepUnreadRequested && entry?.read == false) {
            item {
                Text(
                    "This article will stay in your Inbox",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

internal fun shouldAutomaticallyMarkRead(
    hasRenderableContent: Boolean,
    isUnread: Boolean,
    keepUnreadRequested: Boolean,
): Boolean = hasRenderableContent && isUnread && !keepUnreadRequested

@Composable
private fun ReaderBlock(block: DocumentBlock) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    when (block) {
        is DocumentBlock.Heading -> Text(block.text, style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, modifier = modifier)
        is DocumentBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
        is DocumentBlock.Quote -> Text("“${block.text}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
        is DocumentBlock.Code -> Text(block.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp))
        is DocumentBlock.ListItem -> Text((if (block.ordered) "1. " else "• ") + block.text, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        is DocumentBlock.Caption -> Text(block.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
        is DocumentBlock.Table -> Text(block.rows.joinToString("\n") { it.joinToString(" · ") }, style = MaterialTheme.typography.bodySmall, modifier = modifier)
        is DocumentBlock.Image -> Unit
    }
}

private fun DocumentBlock.links() = when (this) {
    is DocumentBlock.Heading -> links
    is DocumentBlock.Paragraph -> links
    is DocumentBlock.Quote -> links
    is DocumentBlock.ListItem -> links
    is DocumentBlock.Caption -> links
    else -> emptyList()
}

@Composable
private fun rememberOpenOnPhone(onMessage: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    return remember(context, onMessage) {{ url: String ->
        val resolved = runCatching { URI(url).takeIf { it.scheme == "https" || it.scheme == "http" } }.getOrNull()
        if (resolved == null) {
            onMessage("This link cannot be opened")
        } else {
            runCatching {
                val future = RemoteActivityHelper(context).startRemoteActivity(Intent(Intent.ACTION_VIEW, resolved.toString().toUri()))
                future.addListener({
                    runCatching { future.get() }.onFailure { onMessage("Phone unavailable. Try again when it is connected.") }
                }, context.mainExecutor)
            }.onFailure { onMessage("Phone unavailable. Try again when it is connected.") }
        }
    }}
}

private fun resolveLink(articleUrl: String, link: String): String = runCatching {
    URI(articleUrl).resolve(link).toString()
}.getOrDefault(link)

@Composable
private fun StatusScreen(
    application: BrookletWearApplication,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    val sync by application.repository.syncState.collectAsStateWithLifecycle(initialValue = null)
    val bytes by application.repository.bodyBytes.collectAsStateWithLifecycle(initialValue = 0L)
    var syncRequested by remember { mutableStateOf(false) }
    val syncing = sync?.phase in setOf("CONNECTING", "PUSHING_ACTIONS", "PULLING_ENTRIES", "BOOTSTRAPPING", "PRUNING")
    LaunchedEffect(sync?.phase) {
        if (syncing || sync?.phase == "COMPLETE" || sync?.phase == "ERROR" || sync?.phase == "RECONNECT_REQUIRED") {
            syncRequested = false
        }
    }
    WearList { _ ->
        item { ListHeader { Text("Sync & settings") } }
        item {
            Button(
                onClick = {
                    syncRequested = true
                    application.scheduler.enqueueUserSync()
                },
                enabled = !syncRequested && !syncing,
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                label = { Text(if (syncing) "Syncing…" else if (syncRequested) "Sync queued" else "Sync now") },
                secondaryLabel = { Text(lastSyncLabel(sync?.lastSuccessfulSyncAt)) },
            )
        }
        if (sync?.phase == "RECONNECT_REQUIRED") item {
            Button(
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Reconnect using phone") },
            )
        }
        sync?.error?.let {
            item {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
        item { ListHeader { Text("Watch") } }
        item {
            Text(
                "Offline text · ${"%.1f".format(bytes / 1024f / 1024f)} MiB of 25 MiB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            FilledTonalButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Disconnect watch") },
                secondaryLabel = { Text("Removes Brooklet data from this watch") },
            )
        }
    }
}

@Composable
private fun DisconnectScreen(application: BrookletWearApplication, onDisconnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    WearList { _ ->
        item { ListHeader { Text("Disconnect watch?") } }
        item {
            Text(
                "This removes Brooklet's account, offline articles and pending actions from this watch. Your phone and Miniflux account are not changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Button(
                onClick = { scope.launch { application.disconnectAndDeleteWatchData(); onDisconnected() } },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledVariantButtonColors(),
                label = { Text("Disconnect and delete data") },
            )
        }
    }
}

@Composable
private fun WearList(
    initialAnchorItemIndex: Int = 0,
    initialAnchorItemScrollOffset: Int = 0,
    onPositionChanged: ((Int, Int) -> Unit)? = null,
    content: androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.(androidx.wear.compose.foundation.lazy.TransformingLazyColumnState) -> Unit,
) {
    val state = rememberTransformingLazyColumnState(initialAnchorItemIndex, initialAnchorItemScrollOffset)
    LaunchedEffect(state, onPositionChanged) {
        if (onPositionChanged != null) {
            snapshotFlow { state.anchorItemIndex to state.anchorItemScrollOffset }
                .distinctUntilChanged()
                .collectLatest { (index, offset) ->
                    delay(500)
                    onPositionChanged(index, offset)
                }
        }
    }
    DisposableEffect(state, onPositionChanged) {
        onDispose {
            onPositionChanged?.invoke(state.anchorItemIndex, state.anchorItemScrollOffset)
        }
    }
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(
            state = state,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        ) {
            content(state)
        }
    }
}

private fun relativeTime(epochMillis: Long): String {
    val minutes = ((System.currentTimeMillis() - epochMillis).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> "Now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}

private fun lastSyncLabel(epochMillis: Long?): String {
    val relative = epochMillis?.let(::relativeTime) ?: return "Not synced yet"
    return if (relative == "Now") "Last synced just now" else "Last synced $relative ago"
}
