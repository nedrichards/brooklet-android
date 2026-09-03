package com.nedrichards.brooklet

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxNewArticleDetectionTest {
    @Test fun initialInboxLoadIsNotReportedAsNew() {
        assertEquals(0, countNewLeadingInboxEntries(null, listOf(3, 2, 1)))
    }

    @Test fun unseenEntriesAtTheTopAreReportedWithoutDependingOnSyncState() {
        assertEquals(
            2,
            countNewLeadingInboxEntries(setOf(3, 2, 1), listOf(5, 4, 3, 2, 1)),
        )
    }

    @Test fun emptyComposePlaceholderDoesNotTurnInitialDatabaseHydrationIntoNewArticles() {
        val tracker = InboxObservationTracker()

        assertEquals(0, tracker.observe(emptyList()))
        assertEquals(0, tracker.observe(listOf(3L, 2L, 1L)))
        assertEquals(1, tracker.observe(listOf(4L, 3L, 2L, 1L)))
    }

    @Test fun anObservedEntryRestoredByUndoIsNotReportedAsNew() {
        assertEquals(
            0,
            countNewLeadingInboxEntries(setOf(3, 2, 1), listOf(3, 2, 1)),
        )
    }

    @Test fun anUnseenOlderEntryAwayFromTheLeadingEdgeIsNotReported() {
        assertEquals(
            0,
            countNewLeadingInboxEntries(setOf(3, 2, 1), listOf(3, 2, 4, 1)),
        )
    }

    @Test fun trackerBoundsRemovedHistoryWithoutForgettingTheLiveInbox() {
        val tracker = InboxObservationTracker(recentRemovalLimit = 8)
        tracker.observe((1L..100L).toList())

        repeat(20) { removed ->
            tracker.observe(((removed + 2L)..100L).toList())
        }

        assertEquals(8, tracker.retainedRemovalCount)
        assertEquals(0, tracker.observe((21L..100L).toList()))
    }

    @Test fun largeUndoBatchIsNotReportedAsNewWithoutRetainingItForever() {
        val tracker = InboxObservationTracker(recentRemovalLimit = 8)
        val original = (1L..1_000L).toList()
        tracker.observe(original)
        tracker.observe(emptyList())

        assertEquals(0, tracker.observe(original, restoredIds = original.toSet()))
        assertEquals(0, tracker.retainedRemovalCount)
    }

    @Test fun trackerStillReportsGenuineLeadingInsertions() {
        val tracker = InboxObservationTracker()
        tracker.observe(listOf(3L, 2L, 1L))

        assertEquals(2, tracker.observe(listOf(5L, 4L, 3L, 2L, 1L)))
    }

    @Test fun startupCatchUpUpdatesBaselineWithoutReportingTheCacheAsNew() {
        val tracker = InboxObservationTracker()

        assertEquals(0, tracker.observe(emptyList(), reportNewEntries = false))
        assertEquals(0, tracker.observe(listOf(3L, 2L, 1L), reportNewEntries = false))
        assertEquals(
            0,
            tracker.observe((144L downTo 1L).toList(), reportNewEntries = false),
        )
        assertEquals(1, tracker.observe((145L downTo 1L).toList()))
    }
}
