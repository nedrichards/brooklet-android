package com.nedrichards.brooklet.sync

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerTest {
    @Test
    fun rapidActionsReplaceThePendingDebounce() {
        assertEquals(ExistingWorkPolicy.REPLACE, ACTION_DEBOUNCE_POLICY)
    }

    @Test
    fun actionArrivingDuringDeliverySchedulesOneFollowUp() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, ACTION_DELIVERY_POLICY)
    }

    @Test
    fun allRealSyncIntentsShareOneSerialPolicySet() {
        assertEquals(ExistingWorkPolicy.REPLACE, FOREGROUND_SYNC_POLICY)
        assertEquals(ExistingWorkPolicy.KEEP, PERIODIC_DELIVERY_POLICY)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, REFRESH_FOLLOW_UP_POLICY)
        assertEquals(120L, WorkManagerSyncScheduler.PERIODIC_REPEAT_MINUTES)
        assertEquals(30L, WorkManagerSyncScheduler.PERIODIC_FLEX_MINUTES)
    }

    @Test
    fun retryPolicyDependsOnUserIntent() {
        assertTrue(SyncWorkIntent.ACTION_DELIVERY.retriesTransientFailure)
        assertTrue(SyncWorkIntent.USER_SYNC.retriesTransientFailure)
        assertTrue(SyncWorkIntent.MANUAL_REFRESH.retriesTransientFailure)
        assertFalse(SyncWorkIntent.FOREGROUND.retriesTransientFailure)
        assertFalse(SyncWorkIntent.PERIODIC.retriesTransientFailure)
        assertFalse(SyncWorkIntent.REFRESH_FOLLOW_UP.retriesTransientFailure)
        assertTrue(SyncWorkIntent.REFRESH_FOLLOW_UP.entriesOnly)
        assertFalse(SyncWorkIntent.MANUAL_REFRESH.entriesOnly)
    }

    @Test
    fun finishedOrMissingWorkIsIdle() {
        val activity = resolveSyncActivity(listOf(snapshot("SUCCEEDED")))

        assertEquals(SyncActivityState.IDLE, activity.state)
        assertFalse(activity.isActive)
    }

    @Test
    fun firstAttemptWaitingForConstraintsIsQueued() {
        val activity = resolveSyncActivity(listOf(snapshot("ENQUEUED")))

        assertEquals(SyncActivityState.QUEUED, activity.state)
        assertTrue(activity.isActive)
    }

    @Test
    fun enqueuedWorkAfterAnAttemptIsWaitingToRetry() {
        val activity = resolveSyncActivity(listOf(snapshot("ENQUEUED", attempts = 2)))

        assertEquals(SyncActivityState.RETRYING, activity.state)
        assertEquals(2, activity.runAttemptCount)
    }

    @Test
    fun runningWorkWinsOverOldQueuedWork() {
        val activity = resolveSyncActivity(listOf(
            snapshot("ENQUEUED", attempts = 1),
            snapshot("RUNNING", userInitiated = true),
        ))

        assertEquals(SyncActivityState.RUNNING, activity.state)
        assertTrue(activity.userInitiated)
    }

    @Test
    fun scheduledPeriodicWorkIsIdleBetweenRuns() {
        val activity = resolveSyncActivity(listOf(snapshot("ENQUEUED", backgroundScheduled = true)))

        assertEquals(SyncActivityState.IDLE, activity.state)
    }

    @Test
    fun runningPeriodicWorkIsVisibleButNotCancellable() {
        val activity = resolveSyncActivity(listOf(snapshot("RUNNING", backgroundScheduled = true)))

        assertEquals(SyncActivityState.RUNNING, activity.state)
        assertFalse(activity.cancellable)
    }

    private fun snapshot(
        state: String,
        attempts: Int = 0,
        userInitiated: Boolean = false,
        backgroundScheduled: Boolean = false,
    ) = SyncWorkSnapshot(
        state = state,
        runAttemptCount = attempts,
        userInitiated = userInitiated,
        backgroundScheduled = backgroundScheduled,
        cancellable = !backgroundScheduled,
    )
}
