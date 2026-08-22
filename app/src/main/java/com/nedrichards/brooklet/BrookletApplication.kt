package com.nedrichards.brooklet

import android.app.Application
import com.nedrichards.brooklet.database.BrookletDatabase
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.sync.EntryRepository
import com.nedrichards.brooklet.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class BrookletApplication : Application() {
    val database by lazy { BrookletDatabase.create(this) }
    val scheduler by lazy { WorkManagerSyncScheduler(this) }
    val repository by lazy { EntryRepository(database.dao(), scheduler) }

    suspend fun disconnectAccountAndDeleteLocalData(accountId: Long) {
        scheduler.cancelAll()
        withTimeoutOrNull(5_000) { scheduler.activity.first { !it.isActive } }
        database.dao().deleteAccountAndLocalData(accountId)
        TokenCipher().deleteKey()
    }

    override fun onCreate() {
        super.onCreate()
        scheduler.ensurePeriodic()
    }
}
