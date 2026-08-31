package com.nedrichards.brooklet.wear

import android.app.Application
import com.nedrichards.brooklet.wear.data.WearDatabase
import com.nedrichards.brooklet.wear.data.WearTokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BrookletWearApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { WearDatabase.getInstance(this) }
    val scheduler by lazy { WearSyncScheduler(this) }
    val repository by lazy { WearRepository(database.dao(), scheduler) }
    val provisioning by lazy { WatchProvisioningManager(this) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            if (database.dao().account() != null) scheduler.ensurePeriodic()
        }
    }

    suspend fun disconnectAndDeleteWatchData() {
        scheduler.cancelAllAndAwait()
        WearSyncCoordinator.exclusive {
            database.dao().disconnectAndDeleteAll()
            WearTokenCipher().deleteKey()
        }
        scheduler.requestTileUpdate()
    }

    fun saveReaderPosition(entryId: Long, anchorItemIndex: Int, anchorItemScrollOffset: Int) {
        applicationScope.launch {
            repository.saveReaderPosition(entryId, anchorItemIndex, anchorItemScrollOffset)
        }
    }
}
