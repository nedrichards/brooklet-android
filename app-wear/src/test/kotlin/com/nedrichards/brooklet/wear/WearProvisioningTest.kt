package com.nedrichards.brooklet.wear

import androidx.work.ExistingWorkPolicy
import com.nedrichards.brooklet.wear.data.WearEntrySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearProvisioningTest {
    @Test
    fun `debug build with both local credentials provisions directly`() {
        assertTrue(
            shouldUseEmbeddedDevelopmentCredentials(
                isDebug = true,
                serverUrl = "https://miniflux.example",
                token = "development-token",
            ),
        )
    }

    @Test
    fun `release build never uses embedded development credentials`() {
        assertFalse(
            shouldUseEmbeddedDevelopmentCredentials(
                isDebug = false,
                serverUrl = "https://miniflux.example",
                token = "development-token",
            ),
        )
    }

    @Test
    fun `incomplete local credentials fall back to phone setup`() {
        assertFalse(shouldUseEmbeddedDevelopmentCredentials(true, "", "development-token"))
        assertFalse(shouldUseEmbeddedDevelopmentCredentials(true, "https://miniflux.example", ""))
    }

    @Test
    fun `reader marks unread article read only before keep unread is requested`() {
        assertTrue(shouldAutomaticallyMarkRead(hasRenderableContent = true, isUnread = true, keepUnreadRequested = false))
        assertFalse(shouldAutomaticallyMarkRead(hasRenderableContent = true, isUnread = true, keepUnreadRequested = true))
        assertFalse(shouldAutomaticallyMarkRead(hasRenderableContent = false, isUnread = true, keepUnreadRequested = false))
    }

    @Test fun `foreground pull appends behind action delivery instead of being discarded`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, existingWorkPolicy(WatchWorkIntent.ACTION_DELIVERY))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, existingWorkPolicy(WatchWorkIntent.FOREGROUND))
        assertEquals(ExistingWorkPolicy.REPLACE, existingWorkPolicy(WatchWorkIntent.USER_SYNC))
        assertEquals(ExistingWorkPolicy.REPLACE, existingWorkPolicy(WatchWorkIntent.BOOTSTRAP))
    }

    @Test fun `active reader coordinator tracks the entry protected from pruning`() {
        WearSyncCoordinator.readerOpened(42)
        assertEquals(42L, WearSyncCoordinator.activeReaderEntryId)
        WearSyncCoordinator.readerOpened(7)
        assertEquals(7L, WearSyncCoordinator.activeReaderEntryId)
        WearSyncCoordinator.readerClosed(7)
        assertEquals(42L, WearSyncCoordinator.activeReaderEntryId)
        WearSyncCoordinator.readerClosed(42)
        assertEquals(-1L, WearSyncCoordinator.activeReaderEntryId)
    }

    @Test
    fun `undo placeholder replaces its article without duplicating the row`() {
        val first = summary(1)
        val second = summary(2)

        val rows = inboxRows(listOf(first, second), InboxUndoState(index = 0, entry = first))

        assertTrue(rows[0] is InboxItem.Undo)
        assertTrue(rows[1] is InboxItem.Entry)
        assertEquals(2, rows.size)
    }

    private fun summary(id: Long) = WearEntrySummary(
        id = id,
        feedTitle = "Feed",
        title = "Article $id",
        publishedAt = id,
        read = false,
        starred = false,
        hasBody = true,
    )
}
