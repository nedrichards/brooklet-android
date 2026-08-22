package com.nedrichards.brooklet.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerTest {
    @Test fun workerSucceedsWhenTheAccountHasBeenRemovedBeforeQueuedWorkRuns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

        // Cancellation/disconnect can race an already-enqueued request. The
        // worker must treat the absent account as recovered work, rather than
        // retrying indefinitely or crashing.
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}
