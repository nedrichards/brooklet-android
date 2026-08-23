package com.nedrichards.brooklet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nedrichards.brooklet.model.Entry
import com.nedrichards.brooklet.sync.EntryRepository
import java.util.LinkedHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PendingReadUndo(
    val generation: Long,
    val entries: Map<Long, String>,
    val retry: Boolean = false,
) {
    val message: String
        get() = when {
            retry && entries.size == 1 && entries.values.first().isNotBlank() -> "Couldn’t restore “${entries.values.first()}”"
            retry && entries.size == 1 -> "Couldn’t restore article"
            retry -> "Couldn’t restore ${entries.size} articles"
            entries.size == 1 && entries.values.first().isNotBlank() -> "Marked “${entries.values.first()}” read"
            entries.size == 1 -> "Marked article read"
            else -> "Marked ${entries.size} articles read"
        }
}

internal data class InboxUndoUiState(
    val pending: PendingReadUndo? = null,
    val confirmation: String? = null,
    val error: String? = null,
)

/**
 * Serialises Inbox read commands and represents Undo as restorable UI state.
 *
 * The previous implementation coupled each mutation to the lifetime and order
 * of an individual `showSnackbar` coroutine. Keeping the action here makes a
 * configuration change harmless, lets rapid actions form one unambiguous
 * batch, and gives the UI one source of truth for the action it is displaying.
 */
internal class InboxUndoViewModel(
    private val accountId: Long,
    private val repository: EntryRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private sealed interface Command {
        data class MarkRead(val entry: Entry) : Command
        data class MarkAllRead(val entries: List<Entry>) : Command
        data object Undo : Command
        data class Dismiss(val generation: Long) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val _uiState = MutableStateFlow(InboxUndoUiState(pending = restorePending()))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            for (command in commands) {
                when (command) {
                    is Command.MarkRead -> handleMarkRead(command.entry)
                    is Command.MarkAllRead -> handleMarkAllRead(command.entries)
                    Command.Undo -> handleUndo()
                    is Command.Dismiss -> handleDismiss(command.generation)
                }
            }
        }
    }

    fun markRead(entry: Entry) {
        check(commands.trySend(Command.MarkRead(entry)).isSuccess)
    }

    fun markAllRead(entries: List<Entry>) {
        if (entries.isNotEmpty()) check(commands.trySend(Command.MarkAllRead(entries)).isSuccess)
    }

    fun undo() {
        check(commands.trySend(Command.Undo).isSuccess)
    }

    fun dismiss(generation: Long) {
        check(commands.trySend(Command.Dismiss(generation)).isSuccess)
    }

    fun confirmationShown(message: String) {
        if (_uiState.value.confirmation == message) {
            _uiState.value = _uiState.value.copy(confirmation = null)
        }
    }

    fun errorShown(message: String) {
        if (_uiState.value.error == message) {
            _uiState.value = _uiState.value.copy(error = null)
        }
    }

    private suspend fun handleMarkRead(entry: Entry) {
        if (entry.accountId != accountId) return
        runCatching { repository.markRead(accountId, entry.id, true) }
            .onSuccess { addPending(mapOf(entry.id to entry.title)) }
            .onFailure { showError("Couldn’t mark “${entry.title}” read") }
    }

    private suspend fun handleMarkAllRead(entries: List<Entry>) {
        val titles = entries.filter { it.accountId == accountId }.associate { it.id to it.title }
        runCatching { repository.markAllRead(accountId) }
            .onSuccess { ids ->
                if (ids.isNotEmpty()) addPending(ids.associateWith { titles[it].orEmpty() })
            }
            .onFailure { showError("Couldn’t mark all articles read") }
    }

    private suspend fun handleUndo() {
        val pending = _uiState.value.pending ?: return
        runCatching { repository.restoreUnread(accountId, pending.entries.keys.toList()) }
            .onSuccess {
                clearPending()
                _uiState.value = _uiState.value.copy(
                    confirmation = if (pending.entries.size == 1 && pending.entries.values.first().isNotBlank()) {
                        "Restored “${pending.entries.values.first()}” as unread"
                    } else if (pending.entries.size == 1) {
                        "Restored article as unread"
                    } else {
                        "Restored ${pending.entries.size} articles as unread"
                    },
                    error = null,
                )
            }
            .onFailure {
                // Keep the exact IDs available and advance the generation so
                // the UI presents a Retry action rather than silently losing Undo.
                setPending(pending.copy(generation = nextGeneration(), retry = true))
            }
    }

    private fun handleDismiss(generation: Long) {
        if (_uiState.value.pending?.generation == generation) clearPending()
    }

    private fun addPending(entries: Map<Long, String>) {
        val merged = LinkedHashMap<Long, String>()
        _uiState.value.pending?.entries?.let(merged::putAll)
        merged.putAll(entries)
        setPending(PendingReadUndo(nextGeneration(), merged))
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun showError(message: String) {
        _uiState.value = _uiState.value.copy(error = message, confirmation = null)
    }

    private fun nextGeneration(): Long = (savedStateHandle[GENERATION] ?: 0L) + 1L

    private fun setPending(pending: PendingReadUndo) {
        savedStateHandle[GENERATION] = pending.generation
        savedStateHandle[ENTRY_IDS] = pending.entries.keys.toLongArray()
        if (pending.entries.size == 1) {
            savedStateHandle[SINGLE_ENTRY_TITLE] = pending.entries.values.first()
        } else {
            savedStateHandle.remove<String>(SINGLE_ENTRY_TITLE)
        }
        savedStateHandle[RETRY] = pending.retry
        _uiState.value = _uiState.value.copy(pending = pending, confirmation = null)
    }

    private fun clearPending() {
        savedStateHandle.remove<LongArray>(ENTRY_IDS)
        savedStateHandle.remove<String>(SINGLE_ENTRY_TITLE)
        savedStateHandle.remove<Boolean>(RETRY)
        _uiState.value = _uiState.value.copy(pending = null)
    }

    private fun restorePending(): PendingReadUndo? {
        val ids = savedStateHandle.get<LongArray>(ENTRY_IDS) ?: return null
        if (ids.isEmpty()) return null
        val singleTitle = savedStateHandle.get<String>(SINGLE_ENTRY_TITLE).orEmpty()
        return PendingReadUndo(
            generation = savedStateHandle[GENERATION] ?: 0L,
            entries = LinkedHashMap<Long, String>().apply {
                ids.forEach { id -> put(id, if (ids.size == 1) singleTitle else "") }
            },
            retry = savedStateHandle[RETRY] ?: false,
        )
    }

    companion object {
        private const val GENERATION = "inbox-undo-generation"
        private const val ENTRY_IDS = "inbox-undo-entry-ids"
        private const val SINGLE_ENTRY_TITLE = "inbox-undo-entry-title"
        private const val RETRY = "inbox-undo-retry"

    }
}
