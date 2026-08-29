package com.nedrichards.brooklet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nedrichards.brooklet.database.KarakeepConfigEntity
import com.nedrichards.brooklet.database.StoragePolicyEntity
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.designsystem.BrookletActionRow
import com.nedrichards.brooklet.designsystem.BrookletSection
import com.nedrichards.brooklet.designsystem.BrookletSpacing
import com.nedrichards.brooklet.designsystem.BrookletWidths
import com.nedrichards.brooklet.sync.SyncActivity
import com.nedrichards.brooklet.sync.SyncActivityState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    application: BrookletApplication,
    accountId: Long,
    padding: PaddingValues,
    onMessage: (String) -> Unit,
) {
    val dao = application.database.dao()
    val accountFlow = remember(dao) { dao.observeAccount() }
    val syncFlow = remember(accountId, dao) { dao.observeSyncState(accountId) }
    val account by accountFlow.collectAsStateWithLifecycle(initialValue = null)
    val pending by application.repository.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
    val sync by syncFlow.collectAsStateWithLifecycle(initialValue = null)
    val syncActivity by application.scheduler.activity.collectAsStateWithLifecycle(initialValue = SyncActivity())
    var route by remember { mutableStateOf("MINIFLUX") }
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var retention by remember { mutableStateOf<Int?>(30) }
    var disconnectConfirmation by remember { mutableStateOf(false) }
    val apiKeyFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val syncStatus = syncSummary(syncActivity, sync?.phase, sync?.processed ?: 0, sync?.total ?: 0, sync?.error)
    val accountStatus = when {
        syncActivity.state != SyncActivityState.IDLE || sync?.phase == "ERROR" -> syncStatus
        pending > 0 -> "$pending reading ${if (pending == 1) "action" else "actions"} queued"
        else -> null
    }

    LaunchedEffect(accountId) {
        dao.karakeepConfig(accountId)?.let {
            route = it.preferredRoute
            endpoint = it.directEndpoint.orEmpty()
        }
        dao.storagePolicy(accountId)?.let { policy ->
            // A stored null means "indefinitely"; only a missing policy uses
            // the 30-day default.
            retention = policy.retainReadDays
        }
    }

    Box(Modifier.fillMaxSize().padding(padding)) {
        Column(
            Modifier.widthIn(max = BrookletWidths.settings).fillMaxWidth().align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState()).padding(BrookletSpacing.screenCompact),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrookletSection("Account") {
                Text(account?.username.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${account?.serverUrl.orEmpty()} · Miniflux ${account?.serverVersion.orEmpty()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                accountStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (syncActivity.state == SyncActivityState.RETRYING || syncActivity.state == SyncActivityState.IDLE && sync?.phase == "ERROR") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                BrookletActionRow {
                    FilledTonalButton(
                        onClick = if (syncActivity.cancellable && syncActivity.isActive) {
                            application.scheduler::cancelImmediate
                        } else {
                            application.scheduler::enqueueUserSync
                        },
                        enabled = syncActivity.state != SyncActivityState.RUNNING || syncActivity.cancellable,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text(if (syncActivity.cancellable && syncActivity.isActive) "Stop sync" else "Sync now") }
                    OutlinedButton(
                        onClick = { disconnectConfirmation = true },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text("Disconnect account") }
                }
            }

            BrookletSection(
                title = "Send to Karakeep",
                supportingText = "Choose how Brooklet delivers articles. This never changes their read or starred state.",
            ) {
                Choice("Through Miniflux", route == "MINIFLUX") { route = "MINIFLUX" }
                Choice("Direct from this device", route == "DIRECT") { route = "DIRECT" }
                if (route == "MINIFLUX") {
                    Text(
                        "Uses the save integration configured in Miniflux. Private-network delivery may require INTEGRATION_ALLOW_PRIVATE_NETWORKS=1 on the Miniflux server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        endpoint,
                        { endpoint = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Bookmarks endpoint") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { apiKeyFocus.requestFocus() }),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        apiKey,
                        { apiKey = it },
                        Modifier.fillMaxWidth().focusRequester(apiKeyFocus),
                        label = { Text("API key (blank keeps current key)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        singleLine = true,
                    )
                }
            }

            BrookletSection(
                title = "Offline storage",
                supportingText = "Unread, starred, recently opened, and pending articles are always protected.",
            ) {
                listOf(7, 30, 90).forEach { days -> Choice("Keep read articles for $days days", retention == days) { retention = days } }
                Choice("Keep read articles indefinitely", retention == null) { retention = null }
                Text("Article images are loaded from the network and never stored on disk.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    focusManager.clearFocus()
                    scope.launch {
                        val old = dao.karakeepConfig(accountId)
                        val encrypted = apiKey.takeIf(String::isNotBlank)?.let { TokenCipher().encrypt(it) }
                        dao.upsertKarakeepConfig(KarakeepConfigEntity(
                            accountId, route, true, endpoint.takeIf(String::isNotBlank),
                            encrypted?.ciphertext ?: old?.directKeyCiphertext,
                            encrypted?.iv ?: old?.directKeyIv,
                        ))
                        dao.upsertStoragePolicy(StoragePolicyEntity(accountId, retention))
                        apiKey = ""
                        onMessage("Settings saved")
                    }
                },
            ) { Text("Save settings") }

            BrookletSection("Diagnostics") {
                Text("Database available", style = MaterialTheme.typography.bodyMedium)
                Text("Sync · $syncStatus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Reading action queue · $pending", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Token and article-body logging disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (disconnectConfirmation) {
        AlertDialog(
            onDismissRequest = { disconnectConfirmation = false },
            title = { Text("Disconnect account and delete local data?") },
            text = {
                Text(
                    "Brooklet will delete this account and all of its offline data from this device, including cached articles, reading state, queued actions, Karakeep settings, and locally stored credentials.\n\nYour Miniflux and Karakeep servers will not be changed. No articles, feeds, reading state, or bookmarks will be deleted from either server.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    disconnectConfirmation = false
                    scope.launch { application.disconnectAccountAndDeleteLocalData(accountId) }
                }) { Text("Delete local data") }
            },
            dismissButton = { TextButton(onClick = { disconnectConfirmation = false }) { Text("Cancel") } },
        )
    }
}

private fun syncSummary(
    activity: SyncActivity,
    phase: String?,
    processed: Int,
    total: Int,
    error: String?,
): String = when (activity.state) {
    SyncActivityState.RUNNING -> if (phase == "PULLING_ENTRIES" && total > 0) {
        "Caching $processed of $total articles"
    } else {
        when (phase) {
            "CONNECTING" -> "Connecting to Miniflux"
            "REFRESHING_FEEDS" -> "Refreshing feeds"
            "PUSHING_ACTIONS" -> "Sending queued actions"
            "PULLING_FEEDS" -> "Syncing feeds"
            "PRUNING" -> "Tidying offline storage"
            else -> "Syncing"
        }
    }
    SyncActivityState.QUEUED -> "Waiting for a network connection"
    SyncActivityState.RETRYING -> "Waiting to retry${error?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"
    SyncActivityState.IDLE -> when (phase) {
        "COMPLETE" -> "Up to date"
        "ERROR" -> "Last sync failed${error?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"
        null, "QUEUED" -> "Sync idle"
        else -> "Sync paused"
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    RadioButton(selected, onClick)
    Text(label, style = MaterialTheme.typography.bodyMedium)
}
