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
}
