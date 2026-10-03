package com.nedrichards.brooklet

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nedrichards.brooklet.designsystem.BrookletHeadlineRow
import com.nedrichards.brooklet.model.Entry
import kotlinx.coroutines.launch

internal class KeyboardListSelection {
    var selectedId by mutableStateOf<Long?>(null)
    var select: (Long) -> Unit = {}
    var record: (Long) -> Unit = {}
    var clearForTouch: () -> Unit = {}
    var lastMouseId: Long? = null
}
private val LocalListSelection = staticCompositionLocalOf<KeyboardListSelection?> { null }

@Composable
internal fun KeyboardArticleList(
    entries: List<Entry>,
    onOpen: (Entry) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit,
) {
    val workspace = LocalKeyboardWorkspace.current
    val ids = remember(entries) { entries.map { it.id } }
    val positions = remember(entries) {
        buildMap {
            var index = 0
            articleDateSections(entries).forEach { section ->
                index++ // Date heading, including the hidden leading Inbox heading.
                section.entries.forEach { entry -> put(entry.id, index++) }
            }
        }
    }
    KeyboardList(
        ids = ids,
        onOpen = { id -> entries.firstOrNull { it.id == id }?.let(onOpen) },
        indexOf = { id -> positions[id] ?: 0 },
        onCommand = { command, id -> entries.firstOrNull { it.id == id }?.let { workspace?.article?.invoke(command, it) } == true },
        modifier = modifier, state = state, contentPadding = contentPadding, content = content,
    )
}

@Composable
internal fun KeyboardList(
    ids: List<Long>,
    onOpen: (Long) -> Unit,
    indexOf: (Long) -> Int,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    onCommand: (InputCommand, Long) -> Boolean = { _, _ -> false },
    content: LazyListScope.() -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var removedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selection = remember { KeyboardListSelection() }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val workspace = LocalKeyboardWorkspace.current
    val currentIds by rememberUpdatedState(ids)
    val focusManager = LocalFocusManager.current
    val open by rememberUpdatedState(onOpen)
    val lazyIndex by rememberUpdatedState(indexOf)
    val action by rememberUpdatedState(onCommand)
    var focused by remember { mutableStateOf(false) }

    fun select(id: Long, reveal: Boolean = true) {
        // Undo may restore the automatic neighbor, but must not pull the cursor
        // back after the user has deliberately moved somewhere else.
        if (id != selectedId) removedId = null
        selectedId = id
        selectedIndex = currentIds.indexOf(id).coerceAtLeast(0)
        focus.requestFocus()
        if (reveal) scope.launch {
            val index = lazyIndex(id)
            val visible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (visible == null || visible.offset < state.layoutInfo.viewportStartOffset ||
                visible.offset + visible.size > state.layoutInfo.viewportEndOffset) state.scrollToItem(index)
        }
    }
    val handle: (InputCommand) -> Boolean = { command ->
        val values = currentIds
        if (values.isEmpty()) false else {
            val current = values.indexOf(selectedId)
            val firstVisible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key in values }?.key as? Long
            val start = values.indexOf(firstVisible).coerceAtLeast(0)
            val page = state.layoutInfo.visibleItemsInfo.count { it.key in values }.coerceAtLeast(1)
            val target = when (command) {
                InputCommand.DOWN -> if (current < 0) start else (current + 1).coerceAtMost(values.lastIndex)
                InputCommand.UP -> if (current < 0) start else (current - 1).coerceAtLeast(0)
                InputCommand.FIRST -> 0
                InputCommand.LAST -> values.lastIndex
                InputCommand.PAGE_DOWN -> ((if (current < 0) start else current) + page).coerceAtMost(values.lastIndex)
                InputCommand.PAGE_UP -> ((if (current < 0) start else current) - page).coerceAtLeast(0)
                else -> null
            }
            when {
                target != null -> { select(values[target]); true }
                command in setOf(InputCommand.OPEN, InputCommand.SPACE) && current >= 0 -> { open(values[current]); true }
                command in articleCommands && current >= 0 -> action(command, values[current])
                else -> false
            }
        }
    }
    val currentHandle by rememberUpdatedState(handle)
    DisposableEffect(workspace) {
        val callback: (InputCommand) -> Boolean = { currentHandle(it) }
        workspace?.content = callback
        onDispose { if (workspace?.content === callback) workspace.content = null }
    }
    LaunchedEffect(ids, workspace?.restoredIds) {
        val previousSelection = selectedId
        val restore = removedId?.takeIf { it in (workspace?.restoredIds ?: emptySet()) && it in ids }
        if (restore != null) { selectedId = restore; removedId = null }
        if (selectedId != null && selectedId !in ids) {
            removedId = selectedId
            selectedId = ids.getOrNull(selectedIndex.coerceAtMost(ids.lastIndex))
        }
        selectedId?.let { selectedIndex = ids.indexOf(it).coerceAtLeast(0) }
        if (focused && selectedId != null && previousSelection != selectedId) select(selectedId!!)
    }
    LaunchedEffect(Unit) { if (workspace?.editing != true) focus.requestFocus() }
    selection.selectedId = selectedId
    selection.select = { id -> select(id, reveal = false) }
    selection.record = { id ->
        if (id != selectedId) removedId = null
        selectedId = id
        selectedIndex = currentIds.indexOf(id).coerceAtLeast(0)
    }
    selection.clearForTouch = {
        // Touch changes the input mode, including when a keyboard is attached.
        // Do not save a cursor that would select a replacement row on return.
        selectedId = null
        removedId = null
        selection.lastMouseId = null
        focusManager.clearFocus()
    }
    CompositionLocalProvider(LocalListSelection provides selection) {
        Box(
            Modifier.fillMaxSize().focusRequester(focus).onFocusChanged { focused = it.isFocused }.onKeyEvent { event ->
                val command = shortcutCommand(event)
                if (command == null || workspace?.editing == true) false
                else if (event.nativeKeyEvent.repeatCount > 0 && command !in repeatingCommands) true
                else currentHandle(command)
            }.focusable(),
        ) {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                state = state, contentPadding = contentPadding, content = content,
            )
        }
    }
}

@Composable
internal fun Modifier.keyboardListItem(id: Long): Modifier {
    val selection = LocalListSelection.current
    val selected = selection?.selectedId == id
    return background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
        .semantics { this.selected = selected }
        .onFocusChanged { if (it.isFocused) selection?.record?.invoke(id) }
}

/** Pointer gestures are intercepted only for mouse input; touch retains click/swipe semantics. */
@Composable
internal fun InputHeadlineRow(
    entry: Entry,
    title: String,
    metadata: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isUnread: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val selection = LocalListSelection.current
    val workspace = LocalKeyboardWorkspace.current
    var menu by remember { mutableStateOf(false) }
    val latestOpen by rememberUpdatedState(onClick)
    val latestEntry by rememberUpdatedState(entry)
    val timeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box {
        BrookletHeadlineRow(
            title, metadata,
            onClick = onClick,
            isUnread = isUnread,
            modifier = modifier.testTag("entry-${entry.id}").keyboardListItem(entry.id)
                .background(if (hovered && selection?.selectedId != entry.id) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
                .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(entry.id, selection) {
                    var lastClick = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (down.type == PointerType.Touch) selection?.clearForTouch?.invoke()
                        if (down.type == PointerType.Mouse && (currentEvent.buttons.isPrimaryPressed || currentEvent.buttons.isSecondaryPressed)) {
                            val secondary = currentEvent.buttons.isSecondaryPressed
                            down.consume()
                            var released: Boolean
                            var moved = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { change ->
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                    change.consume()
                                }
                                released = event.changes.none { it.pressed }
                            } while (!released)
                            if (!moved) {
                                val consecutive = selection == null || selection.lastMouseId == latestEntry.id
                                selection?.select?.invoke(latestEntry.id)
                                if (secondary) { menu = true; lastClick = 0L; selection?.lastMouseId = null }
                                else {
                                    selection?.lastMouseId = latestEntry.id
                                    val now = down.uptimeMillis
                                    if (consecutive && lastClick != 0L && now - lastClick <= timeout) { latestOpen(); lastClick = 0L }
                                    else lastClick = now
                                }
                            }
                        }
                    }
                },
            trailing = trailing,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.testTag("article-menu-${entry.id}")) {
            DropdownMenuItem(text = { Text("Open") }, trailingIcon = { Text(shortcuts.first { it.command == InputCommand.OPEN }.keys) }, onClick = { menu = false; latestOpen() })
            if (workspace != null) articleCommands.forEach { command ->
                val item = shortcuts.first { it.command == command }
                val label = when (command) {
                    InputCommand.READ -> if (entry.read) "Mark unread" else "Mark read"
                    InputCommand.SAVE -> if (entry.starred) "Remove from Saved" else "Save"
                    else -> item.description
                }
                DropdownMenuItem(
                    modifier = Modifier.testTag("article-action-${entry.id}-${command.name}"),
                    text = { Text(label) }, trailingIcon = { Text(item.keys) },
                    onClick = { menu = false; workspace.article(command, latestEntry) },
                )
            }
        }
    }
}
