package com.nedrichards.brooklet

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.activity.compose.ReportDrawnWhen
import com.nedrichards.brooklet.database.AccountEntity
import com.nedrichards.brooklet.sync.SyncActivity
import com.nedrichards.brooklet.sync.SyncActivityState
import com.nedrichards.brooklet.designsystem.BrookletInlineError
import com.nedrichards.brooklet.designsystem.BrookletContextIcon
import kotlinx.coroutines.launch

@Composable
fun BrookletApp(sharedUrl: String? = null, onSharedUrlHandled: () -> Unit = {}) {
    val application = LocalContext.current.applicationContext as BrookletApplication
    val accountState by produceState<AccountLoadState>(AccountLoadState.Loading, application) {
        application.database.dao().observeAccount().collect { account ->
            value = AccountLoadState.Loaded(account)
        }
    }
    ReportDrawnWhen { accountState is AccountLoadState.Loaded }
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        when (val state = accountState) {
            AccountLoadState.Loading -> FullScreenProgress()
            is AccountLoadState.Loaded -> {
                val account = state.account
                if (account == null) SetupScreen(application)
                else if (sharedUrl != null) SubscribeScreen(application, account.id, sharedUrl, onDismiss = onSharedUrlHandled)
                else InitialSyncGate(application, account.id)
            }
        }
    }
}

private sealed interface AccountLoadState {
    data object Loading : AccountLoadState
    data class Loaded(val account: AccountEntity?) : AccountLoadState
}

@Composable
private fun InitialSyncGate(application: BrookletApplication, accountId: Long) {
    val dao = application.database.dao()
    val cursorFlow = remember(accountId, dao) { dao.observeCursor(accountId) }
    val syncFlow = remember(accountId, dao) { dao.observeSyncState(accountId) }
    val entryCountFlow = remember(accountId, dao) { dao.observeEntryCount(accountId) }
    val cursor by cursorFlow.collectAsStateWithLifecycle(initialValue = null)
    val sync by syncFlow.collectAsStateWithLifecycle(initialValue = null)
    val entryCount by entryCountFlow.collectAsStateWithLifecycle(initialValue = 0)
    val syncActivity by application.scheduler.activity.collectAsStateWithLifecycle(initialValue = SyncActivity())
    val sessionStartedAt = remember(accountId) { System.currentTimeMillis() }
    var syncStopped by rememberSaveable(accountId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LifecycleStartEffect(accountId) {
        scope.launch {
            val latest = dao.cursor(accountId)?.lastSuccessfulSyncAt
            if (latest == null || latest < System.currentTimeMillis() - FOREGROUND_SYNC_MAX_AGE_MS) {
                application.scheduler.enqueueForegroundSync()
            }
        }
        onStopOrDispose { }
    }
    // Each fetched page is committed independently. Let the user start reading as
    // soon as the first page lands while the rest of the cache fills in behind it.
    if (cursor != null || entryCount > 0) {
        MainShell(
            application = application,
            accountId = accountId,
            suppressNewEntryNotifications = cursor?.lastSuccessfulSyncAt
                ?.let { it < sessionStartedAt - FOREGROUND_SYNC_MAX_AGE_MS }
                ?: true,
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BrookletContextIcon(
                    icon = Icons.Rounded.WaterDrop,
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(16.dp))
                Text("Brooklet", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(20.dp))
                if (syncActivity.isActive) {
                    if (syncActivity.state == SyncActivityState.RUNNING) CircularProgressIndicator()
                    Text(
                        when (syncActivity.state) {
                            SyncActivityState.RUNNING -> syncLabel(sync?.phase)
                            SyncActivityState.RETRYING -> "Waiting to retry…"
                            SyncActivityState.QUEUED -> "Waiting for connection…"
                            SyncActivityState.IDLE -> "Sync paused"
                        },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    if (syncActivity.state == SyncActivityState.RETRYING && !sync?.error.isNullOrBlank()) {
                        BrookletInlineError(sync?.error.orEmpty(), Modifier.padding(top = 8.dp))
                    }
                    if (syncActivity.state == SyncActivityState.RUNNING && (sync?.total ?: 0) > 0) {
                        LinearProgressIndicator(
                            progress = { (sync!!.processed.toFloat() / sync!!.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                        Text("${sync?.processed} of ${sync?.total} entries", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    }
                    if (syncActivity.state == SyncActivityState.RETRYING) {
                        Button(
                            onClick = {
                                syncStopped = false
                                application.scheduler.enqueueUserSync()
                            },
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text("Try now") }
                    }
                    if (syncActivity.cancellable) {
                        OutlinedButton(
                            onClick = {
                                syncStopped = true
                                application.scheduler.cancelImmediate()
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Stop") }
                    }
                } else if (sync?.phase == "ERROR" && !syncStopped) {
                    Text("Initial sync needs attention", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    BrookletInlineError(sync?.error.orEmpty(), Modifier.padding(vertical = 12.dp))
                    Button(onClick = {
                        syncStopped = false
                        application.scheduler.enqueueUserSync()
                    }) { Text("Try again") }
                } else {
                    Text("Initial sync paused", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    Text("You can resume without losing articles already cached.", modifier = Modifier.padding(vertical = 12.dp))
                    Button(onClick = {
                        syncStopped = false
                        application.scheduler.enqueueUserSync()
                    }) { Text("Resume sync") }
                }
            }
        }
    }
}

private const val FOREGROUND_SYNC_MAX_AGE_MS = 5L * 60 * 1000

private fun syncLabel(phase: String?): String = when (phase) {
    "CONNECTING" -> "Connecting to Miniflux…"
    "REFRESHING_FEEDS" -> "Refreshing feeds…"
    "PUSHING_ACTIONS" -> "Sending queued actions…"
    "PULLING_FEEDS" -> "Syncing feeds…"
    "PULLING_ENTRIES" -> "Caching articles…"
    "PRUNING" -> "Tidying offline storage…"
    "QUEUED" -> "Waiting to sync…"
    else -> "Starting initial sync…"
}

@Composable
fun FullScreenProgress() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
