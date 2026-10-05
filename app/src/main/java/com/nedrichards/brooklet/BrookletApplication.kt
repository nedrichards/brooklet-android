package com.nedrichards.brooklet

import android.app.Application
import com.nedrichards.brooklet.database.BrookletDatabase
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.sync.EntryRepository
import com.nedrichards.brooklet.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import com.nedrichards.brooklet.database.ReaderPositionEntity

class BrookletApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { BrookletDatabase.getInstance(this) }
    val scheduler by lazy { WorkManagerSyncScheduler(this) }
    val repository by lazy { EntryRepository(database.dao(), scheduler) }
    val wearProvisioning by lazy { WearProvisioningController(this) }
    private val positionWrites = Channel<ReaderPositionEntity>(Channel.UNLIMITED)

    fun saveReaderPosition(accountId: Long, entryId: Long, block: Int, offset: Int) {
        positionWrites.trySend(ReaderPositionEntity(accountId, entryId, block, offset, System.currentTimeMillis()))
    }

    suspend fun disconnectAccountAndDeleteLocalData(accountId: Long) {
        scheduler.cancelAll()
        withTimeoutOrNull(5_000) { scheduler.activity.first { !it.isActive } }
        database.dao().deleteAccountAndLocalData(accountId)
        TokenCipher().deleteKey()
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            for (position in positionWrites) {
                database.dao().upsertPosition(position)
            }
        }
        applicationScope.launch {
            if (database.dao().account() != null) scheduler.ensurePeriodic()
        }
        // Advertise phone provisioning as soon as Brooklet starts, rather
        // than waiting until the user opens Settings.
        wearProvisioning
    }
}
