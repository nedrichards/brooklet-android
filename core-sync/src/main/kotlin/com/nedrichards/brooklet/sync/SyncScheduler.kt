package com.nedrichards.brooklet.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class SyncActivityState { IDLE, QUEUED, RUNNING, RETRYING }

data class SyncActivity(
    val state: SyncActivityState = SyncActivityState.IDLE,
    val userInitiated: Boolean = false,
    val cancellable: Boolean = false,
    val runAttemptCount: Int = 0,
) {
    val isActive: Boolean get() = state != SyncActivityState.IDLE
}

internal val ACTION_DEBOUNCE_POLICY = ExistingWorkPolicy.REPLACE
internal val ACTION_DELIVERY_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE
internal val FOREGROUND_SYNC_POLICY = ExistingWorkPolicy.REPLACE
internal val PERIODIC_DELIVERY_POLICY = ExistingWorkPolicy.KEEP
internal val REFRESH_FOLLOW_UP_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

internal data class SyncWorkSnapshot(
    val state: String,
    val runAttemptCount: Int,
    val userInitiated: Boolean,
    val backgroundScheduled: Boolean,
    val cancellable: Boolean,
)

internal fun resolveSyncActivity(values: List<SyncWorkSnapshot>): SyncActivity {
    val active = values.firstOrNull { it.state == "RUNNING" && it.userInitiated }
        ?: values.firstOrNull { it.state == "RUNNING" && it.cancellable }
        ?: values.firstOrNull { it.state == "RUNNING" }
        ?: values.firstOrNull {
            (it.state == "ENQUEUED" || it.state == "BLOCKED") && !it.backgroundScheduled
        }
        ?: values.firstOrNull {
            (it.state == "ENQUEUED" || it.state == "BLOCKED") && it.runAttemptCount > 0
        }
        ?: return SyncActivity()
    val state = when {
        active.state == "RUNNING" -> SyncActivityState.RUNNING
        active.runAttemptCount > 0 -> SyncActivityState.RETRYING
        else -> SyncActivityState.QUEUED
    }
    return SyncActivity(state, active.userInitiated, active.cancellable, active.runAttemptCount)
}

interface SyncScheduler {
    val activity: Flow<SyncActivity>
    fun enqueueActionDelivery()
    fun enqueueForegroundSync()
    fun enqueueUserSync()
    fun enqueueManualRefresh()
    fun cancelImmediate()
    fun cancelAll()
    fun ensurePeriodic()
}

class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val periodicNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()
    override val activity: Flow<SyncActivity> = workManager.getWorkInfosByTagFlow(SYNC_WORK).map { values ->
        resolveSyncActivity(values.map { info ->
            SyncWorkSnapshot(
                state = info.state.name,
                runAttemptCount = info.runAttemptCount,
                userInitiated = USER_INITIATED in info.tags,
                backgroundScheduled = BACKGROUND_SCHEDULED in info.tags,
                cancellable = CANCELLABLE in info.tags,
            )
        })
    }

    override fun enqueueActionDelivery() {
        val request = OneTimeWorkRequestBuilder<ActionSyncDebounceWorker>()
            .addTag(SYNC_CONTROL_WORK)
            .setInitialDelay(ACTION_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ACTION_DEBOUNCE, ACTION_DEBOUNCE_POLICY, request)
    }

    internal fun enqueueActionWorker() {
        val request = syncRequest(SyncWorkIntent.ACTION_DELIVERY)
            .addTag(BACKGROUND_SCHEDULED)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        // Debounce collapses a rapid gesture burst. If delivery is already
        // running, append one follow-up so a later queue snapshot is not lost.
        workManager.enqueueUniqueWork(SYNC_EXECUTION, ACTION_DELIVERY_POLICY, request)
    }

    override fun enqueueForegroundSync() {
        val request = syncRequest(SyncWorkIntent.FOREGROUND)
            .addTag(CANCELLABLE)
            .setConstraints(network)
            .build()
        // A full foreground sync also drains durable actions, so replacing an
        // action-only run coalesces both intents into one network session.
        workManager.enqueueUniqueWork(SYNC_EXECUTION, FOREGROUND_SYNC_POLICY, request)
    }

    override fun enqueueUserSync() {
        val request = syncRequest(SyncWorkIntent.USER_SYNC)
            .addTag(CANCELLABLE)
            .addTag(USER_INITIATED)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(SYNC_EXECUTION, ExistingWorkPolicy.REPLACE, request)
    }

    override fun enqueueManualRefresh() {
        val request = syncRequest(SyncWorkIntent.MANUAL_REFRESH)
            .addTag(CANCELLABLE)
            .addTag(USER_INITIATED)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(SYNC_EXECUTION, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelImmediate() {
        workManager.cancelUniqueWork(SYNC_EXECUTION)
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(SYNC_WORK)
        workManager.cancelAllWorkByTag(SYNC_CONTROL_WORK)
    }

    override fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<PeriodicSyncTriggerWorker>(
            PERIODIC_REPEAT_MINUTES, TimeUnit.MINUTES,
            PERIODIC_FLEX_MINUTES, TimeUnit.MINUTES,
        )
            .addTag(SYNC_WORK)
            .addTag(BACKGROUND_SCHEDULED)
            .setInitialDelay(PERIODIC_REPEAT_MINUTES, TimeUnit.MINUTES)
            .setConstraints(periodicNetwork)
            .build()
        // Versioning applies the new constraints once; KEEP then avoids a
        // periodic-work update on every process start.
        workManager.cancelUniqueWork(LEGACY_PERIODIC)
        workManager.cancelUniqueWork(LEGACY_PERIODIC_V2)
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun enqueuePeriodicSync() {
        val request = syncRequest(SyncWorkIntent.PERIODIC)
            .addTag(BACKGROUND_SCHEDULED)
            .setConstraints(periodicNetwork)
            .build()
        // Any immediate work is newer and already drains actions. Skipping this
        // tick avoids a second sync; the next lifecycle start or periodic tick
        // will perform the next freshness pull.
        workManager.enqueueUniqueWork(SYNC_EXECUTION, PERIODIC_DELIVERY_POLICY, request)
    }

    fun enqueueRefreshFollowUp() {
        val request = syncRequest(SyncWorkIntent.REFRESH_FOLLOW_UP)
            .addTag(BACKGROUND_SCHEDULED)
            .setInitialDelay(20, TimeUnit.SECONDS)
            .setConstraints(network)
            .build()
        workManager.enqueueUniqueWork(SYNC_EXECUTION, REFRESH_FOLLOW_UP_POLICY, request)
    }

    private fun syncRequest(intent: SyncWorkIntent) = OneTimeWorkRequestBuilder<SyncWorker>()
        .addTag(SYNC_WORK)
        .setInputData(intent.asInputData())

    companion object {
        const val SYNC_EXECUTION = "brooklet-sync-execution-v2"
        const val ACTION_DEBOUNCE = "brooklet-action-sync-debounce"
        const val LEGACY_PERIODIC = "brooklet-periodic-sync"
        const val LEGACY_PERIODIC_V2 = "brooklet-periodic-sync-v2"
        const val PERIODIC = "brooklet-periodic-sync-v3"
        const val SYNC_WORK = "brooklet-sync-work"
        const val SYNC_CONTROL_WORK = "brooklet-sync-control-work"
        const val USER_INITIATED = "brooklet-user-initiated-sync"
        const val BACKGROUND_SCHEDULED = "brooklet-background-scheduled-sync"
        const val CANCELLABLE = "brooklet-cancellable-sync"
        const val ACTION_DEBOUNCE_SECONDS = 2L
        const val PERIODIC_REPEAT_MINUTES = 120L
        const val PERIODIC_FLEX_MINUTES = 30L
    }
}
