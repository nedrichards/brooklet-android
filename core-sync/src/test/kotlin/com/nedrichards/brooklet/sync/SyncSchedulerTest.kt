package com.nedrichards.brooklet.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerTest {
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
