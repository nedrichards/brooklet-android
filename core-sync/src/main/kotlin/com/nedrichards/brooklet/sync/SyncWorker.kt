package com.nedrichards.brooklet.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nedrichards.brooklet.database.BrookletDatabase
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.model.FailureKind
import com.nedrichards.brooklet.network.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = syncMutex.withLock {
        try {
            val database = BrookletDatabase.create(applicationContext)
            try {
                val manualRefresh = inputData.getBoolean(MANUAL_REFRESH, false)
                SyncEngine(database.dao(), TokenCipher()).run(manualRefresh)
                if (manualRefresh) WorkManagerSyncScheduler(applicationContext).enqueueRefreshFollowUp()
            } finally {
                database.close()
            }
            Result.success()
        } catch (error: ApiException) {
            if (error.kind == FailureKind.RETRYABLE) Result.retry() else Result.failure()
        } catch (_: java.io.IOException) {
            Result.retry()
        }
    }

    companion object {
        const val MANUAL_REFRESH = "manual_refresh"
        private val syncMutex = Mutex()
    }
}
