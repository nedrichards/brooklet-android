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
import com.nedrichards.brooklet.sync.WorkManagerSyncScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
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
    val cursorFlow = remember(accountId, dao) { dao.observeCursor(accountId) }
    val pendingReadFlow = remember(accountId, dao) { dao.observePendingReadMutationCount(accountId) }
    val pendingStarFlow = remember(accountId, dao) { dao.observePendingStarMutationCount(accountId) }
    val pendingKarakeepFlow = remember(accountId, dao) { dao.observePendingKarakeepCount(accountId) }
    val account by accountFlow.collectAsStateWithLifecycle(initialValue = null)
    val sync by syncFlow.collectAsStateWithLifecycle(initialValue = null)
    val cursor by cursorFlow.collectAsStateWithLifecycle(initialValue = null)
    val pendingRead by pendingReadFlow.collectAsStateWithLifecycle(initialValue = 0)
    val pendingStar by pendingStarFlow.collectAsStateWithLifecycle(initialValue = 0)
    val pendingKarakeep by pendingKarakeepFlow.collectAsStateWithLifecycle(initialValue = 0)
    val syncActivity by application.scheduler.activity.collectAsStateWithLifecycle(initialValue = SyncActivity())
    var route by remember { mutableStateOf("MINIFLUX") }
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var retention by remember { mutableStateOf<Int?>(30) }
    var disconnectConfirmation by remember { mutableStateOf(false) }
    val apiKeyFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val pendingWear by application.wearProvisioning.pending.collectAsStateWithLifecycle()
    val syncStatus = syncSummary(syncActivity, sync?.phase, sync?.processed ?: 0, sync?.total ?: 0, sync?.error)
    val pendingActions = pendingRead + pendingStar + pendingKarakeep
    val accountStatus = when {
        syncActivity.state != SyncActivityState.IDLE || sync?.phase == "ERROR" -> syncStatus
        pendingActions > 0 -> "$pendingActions ${if (pendingActions == 1) "action" else "actions"} awaiting delivery"
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
                        onMessage("Karakeep and storage settings saved")
                    }
                },
            ) { Text("Save Karakeep and storage settings") }

            BrookletSection(
                title = "Set up a watch",
                supportingText = "Watch setup is separate from the settings above. Brooklet sends your Miniflux credentials only after you approve a request.",
            ) {
                if (pendingWear.isEmpty()) {
                    Text(
                        "Open Brooklet on your paired watch. It will contact this phone automatically, then appear here for approval.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    pendingWear.forEach { watch ->
                        Text(watch.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(watch.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (watch.status == "Waiting for confirmation") {
                            FilledTonalButton(onClick = {
                                scope.launch {
                                    runCatching { application.wearProvisioning.provision(watch.nodeId) }
                                        .onFailure { onMessage(it.message ?: "Watch setup failed") }
                                }
                            }) { Text("Approve and replace watch setup") }
                        }
                    }
                }
            }

            BrookletSection("Diagnostics") {
                Text("Database available", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Remote data refreshed · ${diagnosticTimestamp(cursor?.lastSuccessfulSyncAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Sync worker · $syncStatus${diagnosticTimestamp(sync?.updatedAt, prefix = " · ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Pending read/unread actions · $pendingRead", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Pending save/unsave actions · $pendingStar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Pending Karakeep deliveries · $pendingKarakeep", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Actions normally leave these queues after the ${WorkManagerSyncScheduler.ACTION_DEBOUNCE_SECONDS}-second debounce and server acknowledgement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

internal fun syncSummary(
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
        "COMPLETE" -> "Completed"
        "ERROR" -> "Last sync failed${error?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"
        null, "QUEUED" -> "Sync idle"
        else -> "Sync paused"
    }
}

internal fun diagnosticTimestamp(
    epochMillis: Long?,
    prefix: String = "",
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (epochMillis == null) return if (prefix.isEmpty()) "Never" else ""
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
    return prefix + formatter.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    RadioButton(selected, onClick)
    Text(label, style = MaterialTheme.typography.bodyMedium)
}
