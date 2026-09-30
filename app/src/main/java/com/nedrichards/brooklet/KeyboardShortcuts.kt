package com.nedrichards.brooklet

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.nedrichards.brooklet.model.Entry

internal enum class InputCommand {
    DOWN, UP, FIRST, LAST, PAGE_DOWN, PAGE_UP, OPEN, SPACE, READ, UNDO, SAVE, BROWSER, SHARE, KARAKEEP,
    SEARCH, SYNC, REFRESH, INBOX, SAVED, LIBRARY, SETTINGS, HELP, BACK, PREVIOUS, NEXT,
}

internal data class KeyBinding(val key: Key, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) {
    fun matches(event: KeyEvent) = event.key == key && event.isCtrlPressed == ctrl &&
        event.isAltPressed == alt && event.isShiftPressed == shift && !event.isMetaPressed
}

internal data class Shortcut(
    val command: InputCommand,
    val keys: String,
    val description: String,
    val context: String,
    val bindings: List<KeyBinding>,
)
internal val shortcuts = listOf(
    Shortcut(InputCommand.DOWN, "↓ / J", "Next headline; scroll in reader", "Content", listOf(KeyBinding(Key.DirectionDown), KeyBinding(Key.J))),
    Shortcut(InputCommand.UP, "↑ / K", "Previous headline; scroll in reader", "Content", listOf(KeyBinding(Key.DirectionUp), KeyBinding(Key.K))),
    Shortcut(InputCommand.FIRST, "Home", "First headline; beginning of article", "Content", listOf(KeyBinding(Key.MoveHome))),
    Shortcut(InputCommand.LAST, "End", "Last headline; end of article", "Content", listOf(KeyBinding(Key.MoveEnd))),
    Shortcut(InputCommand.PAGE_DOWN, "Page Down", "Move down one page", "Content", listOf(KeyBinding(Key.PageDown))),
    Shortcut(InputCommand.PAGE_UP, "Page Up / Shift+Space", "Move up one page", "Content", listOf(KeyBinding(Key.PageUp), KeyBinding(Key.Spacebar, shift = true))),
    Shortcut(InputCommand.OPEN, "Enter", "Open selected headline", "Content", listOf(KeyBinding(Key.Enter), KeyBinding(Key.NumPadEnter), KeyBinding(Key.DirectionCenter))),
    Shortcut(InputCommand.SPACE, "Space", "Open headline; page down in reader", "Content", listOf(KeyBinding(Key.Spacebar))),
    Shortcut(InputCommand.READ, "R", "Toggle read/unread; unread returns from reader", "Article", listOf(KeyBinding(Key.R))),
    Shortcut(InputCommand.UNDO, "U / Ctrl+Z", "Undo Mark Read", "Article", listOf(KeyBinding(Key.U), KeyBinding(Key.Z, ctrl = true))),
    Shortcut(InputCommand.SAVE, "S", "Save / remove from Saved", "Article", listOf(KeyBinding(Key.S))),
    Shortcut(InputCommand.BROWSER, "O", "Open in browser", "Article", listOf(KeyBinding(Key.O))),
    Shortcut(InputCommand.SHARE, "Ctrl+Shift+S", "Share URL", "Article", listOf(KeyBinding(Key.S, ctrl = true, shift = true))),
    Shortcut(InputCommand.KARAKEEP, "Ctrl+Shift+K", "Send to Karakeep", "Article", listOf(KeyBinding(Key.K, ctrl = true, shift = true))),
    Shortcut(InputCommand.PREVIOUS, "Alt+Page Up", "Previous article", "Reader", listOf(KeyBinding(Key.PageUp, alt = true))),
    Shortcut(InputCommand.NEXT, "Alt+Page Down", "Next article", "Reader", listOf(KeyBinding(Key.PageDown, alt = true))),
    Shortcut(InputCommand.SEARCH, "Ctrl+F / /", "Search current workspace", "Workspace", listOf(KeyBinding(Key.F, ctrl = true), KeyBinding(Key.Slash))),
    Shortcut(InputCommand.SYNC, "Ctrl+R", "Sync articles", "Workspace", listOf(KeyBinding(Key.R, ctrl = true))),
    Shortcut(InputCommand.REFRESH, "Ctrl+Shift+R", "Refresh feeds", "Workspace", listOf(KeyBinding(Key.R, ctrl = true, shift = true))),
    Shortcut(InputCommand.INBOX, "Ctrl+1", "Inbox", "Workspace", listOf(KeyBinding(Key.One, ctrl = true))),
    Shortcut(InputCommand.SAVED, "Ctrl+2", "Saved", "Workspace", listOf(KeyBinding(Key.Two, ctrl = true))),
    Shortcut(InputCommand.LIBRARY, "Ctrl+3", "Library", "Workspace", listOf(KeyBinding(Key.Three, ctrl = true))),
    Shortcut(InputCommand.SETTINGS, "Ctrl+,", "Settings", "Workspace", listOf(KeyBinding(Key.Comma, ctrl = true))),
    Shortcut(InputCommand.BACK, "Escape / Alt+←", "Dismiss or go back", "General", listOf(KeyBinding(Key.Escape), KeyBinding(Key.DirectionLeft, alt = true))),
    Shortcut(InputCommand.HELP, "F1 / ? / Ctrl+?", "Keyboard shortcuts", "General", listOf(KeyBinding(Key.F1), KeyBinding(Key.Slash, shift = true), KeyBinding(Key.Slash, ctrl = true, shift = true))),
)

// Routing, help and menu hints all use this registry. Lock modifiers do not change bindings.
internal fun shortcutCommand(event: KeyEvent): InputCommand? {
    if (event.type != KeyEventType.KeyDown) return null
    return shortcuts.firstOrNull { shortcut -> shortcut.bindings.any { it.matches(event) } }?.command
}

internal val repeatingCommands = setOf(InputCommand.DOWN, InputCommand.UP, InputCommand.PAGE_DOWN, InputCommand.PAGE_UP, InputCommand.SPACE)
internal val articleCommands = setOf(InputCommand.READ, InputCommand.SAVE, InputCommand.BROWSER, InputCommand.SHARE, InputCommand.KARAKEEP)
internal val LocalKeyboardWorkspace = staticCompositionLocalOf<KeyboardWorkspace?> { null }

internal class KeyboardWorkspace {
    var content: ((InputCommand) -> Boolean)? = null
    var back: () -> Unit = {}
    var global: (InputCommand) -> Boolean = { false }
    var article: (InputCommand, Entry) -> Boolean = { _, _ -> false }
    var restoredIds by mutableStateOf<Set<Long>>(emptySet())
    val editors = mutableSetOf<Any>()
    val controlGroups = mutableSetOf<Any>()
    val editing get() = editors.isNotEmpty()
    val controls get() = controlGroups.isNotEmpty()
    var showHelp by mutableStateOf(false)

    fun handle(event: KeyEvent): Boolean {
        val command = shortcutCommand(event) ?: return false
        if (editing && command !in setOf(InputCommand.HELP, InputCommand.BACK)) return false
        if (event.nativeKeyEvent.repeatCount > 0 && command !in repeatingCommands) return true
        if (command == InputCommand.BACK) { back(); return true }
        if (command == InputCommand.HELP) { showHelp = true; return true }
        if (controls && command in setOf(InputCommand.DOWN, InputCommand.UP, InputCommand.FIRST, InputCommand.LAST, InputCommand.OPEN, InputCommand.SPACE)) return false
        return global(command) || content?.invoke(command) == true
    }
}

@Composable
internal fun KeyboardWorkspaceHost(content: @Composable () -> Unit) {
    if (LocalKeyboardWorkspace.current != null) { content(); return }
    val workspace = remember { KeyboardWorkspace() }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (workspace.content == null && !workspace.editing) rootFocus.requestFocus() }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    SideEffect { workspace.back = { dispatcher?.onBackPressed() } }
    CompositionLocalProvider(LocalKeyboardWorkspace provides workspace) {
        Box(Modifier.fillMaxSize().focusRequester(rootFocus).onPreviewKeyEvent { event ->
            val command = shortcutCommand(event)
            if (command in setOf(InputCommand.DOWN, InputCommand.UP, InputCommand.FIRST, InputCommand.LAST, InputCommand.PAGE_DOWN, InputCommand.PAGE_UP)) workspace.handle(event)
            else false
        }.onKeyEvent { workspace.handle(it) }.focusable()) { content() }
        if (workspace.showHelp) {
            AlertDialog(
                onDismissRequest = { workspace.showHelp = false },
                title = { Text("Keyboard shortcuts") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Tab / Shift+Tab move between controls. Text fields keep their normal editing shortcuts. Mouse: click selects, double-click opens, right-click shows actions.")
                        shortcuts.groupBy { it.context }.forEach { (context, rows) ->
                            Text(context, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                            rows.forEach { Text("${it.keys} — ${it.description}", modifier = Modifier.padding(vertical = 4.dp)) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { workspace.showHelp = false }) { Text("Close") } },
            )
        }
        BackHandler(workspace.showHelp) { workspace.showHelp = false }
    }
}

@Composable
internal fun Modifier.keyboardEditing(): Modifier {
    val workspace = LocalKeyboardWorkspace.current
    val token = remember { Any() }
    DisposableEffect(workspace) { onDispose { workspace?.editors?.remove(token) } }
    return onFocusChanged { if (it.hasFocus) workspace?.editors?.add(token) else workspace?.editors?.remove(token) }
}

@Composable
internal fun Modifier.keyboardControls(): Modifier {
    val workspace = LocalKeyboardWorkspace.current
    val token = remember { Any() }
    DisposableEffect(workspace) { onDispose { workspace?.controlGroups?.remove(token) } }
    return onFocusChanged { if (it.hasFocus) workspace?.controlGroups?.add(token) else workspace?.controlGroups?.remove(token) }
}
