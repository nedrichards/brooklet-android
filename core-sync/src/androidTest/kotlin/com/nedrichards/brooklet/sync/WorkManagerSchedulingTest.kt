package com.nedrichards.brooklet.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class WorkManagerSchedulingTest {
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerSyncScheduler

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                        if (workerClassName in setOf(SyncWorker::class.java.name, ActionSyncDebounceWorker::class.java.name)) {
                            WaitingWorker(appContext, workerParameters)
                        } else null
                }).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerSyncScheduler(context)
    }

    @After fun tearDown() {
        workManager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun sustainedActionsDoNotResetTheFirstDeliveryDeadline() = runBlocking {
        scheduler.enqueueActionWindow()
        val first = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.ACTION_DEBOUNCE).get().single()

        repeat(30) { scheduler.enqueueActionWindow() }

        val pending = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.ACTION_DEBOUNCE).get()
        assertEquals(listOf(first.id), pending.map { it.id })
        assertEquals(WorkInfo.State.ENQUEUED, pending.single().state)
    }

    @Test fun anActionDuringTheBatchTriggerGetsAnotherWindow() = runBlocking {
        scheduler.enqueueActionWindow()
        val first = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.ACTION_DEBOUNCE).get().single()
        WorkManagerTestInitHelper.getTestDriver(ApplicationProvider.getApplicationContext())!!
            .setInitialDelayMet(first.id)
        withTimeout(5_000) {
            workManager.getWorkInfoByIdFlow(first.id).first { it?.state == WorkInfo.State.RUNNING }
        }

        repeat(30) { scheduler.enqueueActionWindow() }

        val windows = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.ACTION_DEBOUNCE).get()
        assertEquals(2, windows.size)
        assertEquals(WorkInfo.State.RUNNING, windows.first { it.id == first.id }.state)
        assertEquals(1, windows.count { it.state == WorkInfo.State.BLOCKED })
    }

    @Test fun foregroundPullFollowsDeliveryWithoutCancellingOrDuplicatingIt() = runBlocking {
        scheduler.enqueueActionWorker()
        val action = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.SYNC_EXECUTION).get().single()

        scheduler.enqueueForegroundWorker()
        scheduler.enqueueForegroundWorker()

        val chain = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.SYNC_EXECUTION).get()
        assertEquals(2, chain.size)
        assertEquals(1, chain.count { WorkManagerSyncScheduler.REMOTE_PULL in it.tags })
        assertFalse(chain.first { it.id == action.id }.state.isFinished)
    }

    @Test fun repeatedDeliveryWindowsShareThePendingQueueSnapshot() = runBlocking {
        scheduler.enqueueActionWorker()
        val first = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.SYNC_EXECUTION).get().single()

        repeat(30) { scheduler.enqueueActionWorker() }

        val chain = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.SYNC_EXECUTION).get()
        assertEquals(listOf(first.id), chain.map { it.id })
        assertFalse(chain.single().state.isFinished)
    }

    @Test fun actionsDuringAnInFlightRequestCreateOnlyOneFollowUp() = runBlocking {
        scheduler.enqueueActionWorker()
        val first = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.SYNC_EXECUTION).get().single()
        WorkManagerTestInitHelper.getTestDriver(ApplicationProvider.getApplicationContext())!!
            .setAllConstraintsMet(first.id)
        withTimeout(5_000) {
            workManager.getWorkInfoByIdFlow(first.id).first { it?.state == WorkInfo.State.RUNNING }
        }

        repeat(30) { scheduler.enqueueActionWorker() }
        scheduler.enqueueForegroundWorker()
        scheduler.enqueueForegroundWorker()

        val chain = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.SYNC_EXECUTION).get()
        assertEquals(chain.joinToString { "${it.id}: ${it.state} ${it.tags}" }, 3, chain.size)
        assertEquals(WorkInfo.State.RUNNING, chain.first { it.id == first.id }.state)
        assertEquals(1, chain.count { it.state == WorkInfo.State.BLOCKED && SyncWorkIntent.ACTION_DELIVERY.name in it.tags })
        assertEquals(1, chain.count { WorkManagerSyncScheduler.REMOTE_PULL in it.tags })
    }

    @Test fun foregroundStartPreservesAnExplicitManualRefresh() = runBlocking {
        scheduler.enqueueManualRefresh()
        val refresh = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.SYNC_EXECUTION).get().single()

        scheduler.enqueueForegroundWorker()

        val chain = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.SYNC_EXECUTION).get()
        assertEquals(listOf(refresh.id), chain.map { it.id })
        assertFalse(chain.single().state.isFinished)
    }

    private class WaitingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        // A retained deferred roots the suspended continuation. An unreferenced
        // awaitCancellation coroutine can be collected by the future adapter.
        private val finish = CompletableDeferred<Unit>()
        override suspend fun doWork(): Result {
            finish.await()
            return Result.success()
        }
    }
}
