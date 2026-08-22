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
import kotlinx.coroutines.flow.combine
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
    fun enqueueImmediate()
    fun enqueueUserSync()
    fun enqueueManualRefresh()
    fun cancelImmediate()
    fun cancelAll()
    fun ensurePeriodic()
}

class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    override val activity: Flow<SyncActivity> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(IMMEDIATE).map { values ->
            values.map { info ->
                SyncWorkSnapshot(
                    state = info.state.name,
                    runAttemptCount = info.runAttemptCount,
                    userInitiated = USER_INITIATED in info.tags,
                    backgroundScheduled = false,
                    cancellable = true,
                )
            }
        },
        workManager.getWorkInfosByTagFlow(SYNC_WORK).map { values ->
            values.map { info ->
                SyncWorkSnapshot(
                    state = info.state.name,
                    runAttemptCount = info.runAttemptCount,
                    userInitiated = USER_INITIATED in info.tags,
                    backgroundScheduled = BACKGROUND_SCHEDULED in info.tags,
                    cancellable = CANCELLABLE in info.tags,
                )
            }
        },
    ) { immediate, tagged -> resolveSyncActivity(immediate + tagged) }

    override fun enqueueImmediate() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(SYNC_WORK)
            .addTag(CANCELLABLE)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, request)
    }

    override fun enqueueUserSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(SYNC_WORK)
            .addTag(CANCELLABLE)
            .addTag(USER_INITIATED)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
    }

    override fun enqueueManualRefresh() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(SYNC_WORK)
            .addTag(CANCELLABLE)
            .addTag(USER_INITIATED)
            .setInputData(androidx.work.workDataOf(SyncWorker.MANUAL_REFRESH to true))
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelImmediate() {
        workManager.cancelUniqueWork(IMMEDIATE)
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(SYNC_WORK)
    }

    override fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .addTag(SYNC_WORK)
            .addTag(BACKGROUND_SCHEDULED)
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun enqueueRefreshFollowUp() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(SYNC_WORK)
            .addTag(BACKGROUND_SCHEDULED)
            .setInitialDelay(20, TimeUnit.SECONDS)
            .setConstraints(network)
            .build()
        workManager.enqueueUniqueWork(FOLLOW_UP, ExistingWorkPolicy.REPLACE, request)
    }
    companion object {
        const val IMMEDIATE = "brooklet-immediate-sync"
        const val PERIODIC = "brooklet-periodic-sync"
        const val FOLLOW_UP = "brooklet-refresh-follow-up"
        const val SYNC_WORK = "brooklet-sync-work"
        const val USER_INITIATED = "brooklet-user-initiated-sync"
        const val BACKGROUND_SCHEDULED = "brooklet-background-scheduled-sync"
        const val CANCELLABLE = "brooklet-cancellable-sync"
    }
}
