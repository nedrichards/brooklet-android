package com.nedrichards.brooklet.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nedrichards.brooklet.database.BrookletDatabase
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.model.FailureKind
import com.nedrichards.brooklet.network.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SyncWorkIntent(
    val pullsRemoteState: Boolean,
    val refreshesFeeds: Boolean = false,
    val entriesOnly: Boolean = false,
    val retriesTransientFailure: Boolean,
) {
    ACTION_DELIVERY(pullsRemoteState = false, retriesTransientFailure = true),
    FOREGROUND(pullsRemoteState = true, retriesTransientFailure = false),
    USER_SYNC(pullsRemoteState = true, retriesTransientFailure = true),
    MANUAL_REFRESH(pullsRemoteState = true, refreshesFeeds = true, retriesTransientFailure = true),
    PERIODIC(pullsRemoteState = true, retriesTransientFailure = false),
    REFRESH_FOLLOW_UP(pullsRemoteState = true, entriesOnly = true, retriesTransientFailure = false),
    ;

    fun asInputData(): Data = workDataOf(INPUT_KEY to name)

    companion object {
        private const val INPUT_KEY = "sync_intent"
        fun from(data: Data): SyncWorkIntent = data.getString(INPUT_KEY)
            ?.let { value -> entries.firstOrNull { it.name == value } }
            ?: FOREGROUND
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = syncMutex.withLock {
        val intent = SyncWorkIntent.from(inputData)
        try {
            val database = BrookletDatabase.getInstance(applicationContext)
            SyncEngine(database.dao(), TokenCipher()).run(
                refreshFeeds = intent.refreshesFeeds,
                pullRemoteState = intent.pullsRemoteState,
                entriesOnly = intent.entriesOnly,
            )
            if (intent.refreshesFeeds) WorkManagerSyncScheduler(applicationContext).enqueueRefreshFollowUp()
            Result.success()
        } catch (error: ApiException) {
            when {
                error.kind != FailureKind.RETRYABLE -> Result.failure()
                intent.retriesTransientFailure -> Result.retry()
                else -> Result.success()
            }
        } catch (_: java.io.IOException) {
            if (intent.retriesTransientFailure) Result.retry() else Result.success()
        }
    }

    companion object {
        private val syncMutex = Mutex()
    }
}

/** A durable debounce whose replacement never cancels an in-flight network request. */
class ActionSyncDebounceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WorkManagerSyncScheduler(applicationContext).enqueueActionWorker()
        return Result.success()
    }
}

/** Keeps periodic constraints separate while routing real sync work through one unique chain. */
class PeriodicSyncTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WorkManagerSyncScheduler(applicationContext).enqueuePeriodicSync()
        return Result.success()
    }
}
