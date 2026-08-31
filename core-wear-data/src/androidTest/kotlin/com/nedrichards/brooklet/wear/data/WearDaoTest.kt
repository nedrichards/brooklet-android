package com.nedrichards.brooklet.wear.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nedrichards.brooklet.model.WatchCachePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearDaoTest {
    private lateinit var database: WearDatabase
    private lateinit var dao: WearDao

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WearDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dao()
    }

    @After fun closeDatabase() = database.close()

    @Test fun localReadIntentWinsStaleRemoteAndSurvivesRestartBoundary() = runBlocking {
        dao.upsertEntries(listOf(entry(1, read = false)))
        dao.setRead(1, true, 200)
        dao.mergeRemoteEntries(listOf(entry(1, read = false, changedAt = 300)))

        assertTrue(requireNotNull(dao.entry(1)).read)
        assertEquals(true, dao.pendingMutations().single().desiredValue)
    }

    @Test fun keepUnreadReplacesAutomaticReadWithDurableUnreadIntent() = runBlocking {
        dao.upsertEntries(listOf(entry(1, read = false)))
        dao.setRead(1, true, 200)
        dao.setRead(1, false, 300)
        dao.mergeRemoteEntries(listOf(entry(1, read = true, changedAt = 400)))

        assertFalse(requireNotNull(dao.entry(1)).read)
        val mutation = dao.pendingMutations().single()
        assertEquals("READ", mutation.field)
        assertEquals(false, mutation.desiredValue)
        assertEquals(300, mutation.createdAt)
    }

    @Test fun pendingStarIntentWinsStaleRemoteState() = runBlocking {
        dao.upsertEntries(listOf(entry(1)))
        dao.setStarred(1, true, 200)
        dao.mergeRemoteEntries(listOf(entry(1, changedAt = 300)))

        assertTrue(requireNotNull(dao.entry(1)).starred)
        val mutation = dao.pendingMutations().single()
        assertEquals("STARRED", mutation.field)
        assertEquals(true, mutation.desiredValue)
    }

    @Test fun staleAcknowledgementCannotDeleteNewerUnreadIntent() = runBlocking {
        dao.upsertEntries(listOf(entry(1)))
        dao.setRead(1, true, 200)
        dao.setRead(1, false, 300)

        dao.acknowledgeMutations(listOf(1), "READ", true)

        val mutation = dao.pendingMutations().single()
        assertEquals(false, mutation.desiredValue)
        assertEquals(300, mutation.createdAt)
    }

    @Test fun actionsForMissingRowsDoNotCreateOrphanedMutations() = runBlocking {
        dao.setRead(99, true, 200)
        dao.setStarred(99, true, 200)
        dao.queueKarakeep(99, 200)

        assertTrue(dao.pendingMutations().isEmpty())
        assertTrue(dao.pendingKarakeep().isEmpty())
    }

    @Test fun karakeepStateIsObservableAfterQueueingAndAcknowledgement() = runBlocking {
        dao.upsertEntries(listOf(entry(1)))
        dao.queueKarakeep(1, 200)
        assertEquals("QUEUED", requireNotNull(dao.observeKarakeep(1).first()).state)

        dao.acknowledgeKarakeep(1)
        assertEquals("SAVED", requireNotNull(dao.observeKarakeep(1).first()).state)
    }

    @Test fun removedRemoteEntriesAreDeletedUnlessLocalIntentProtectsThem() = runBlocking {
        dao.upsertEntries(listOf(entry(1), entry(2), entry(3)))
        dao.setRead(2, true, 100)
        dao.queueKarakeep(3, 100)

        dao.mergeRemotePage(emptyList(), listOf(1, 2, 3))

        assertNull(dao.entry(1))
        assertTrue(dao.entry(2) != null)
        assertTrue(dao.entry(3) != null)
    }

    @Test fun readerPositionPersistsAndIsDeletedWithWatchData() = runBlocking {
        dao.upsertReaderPosition(WearReaderPositionEntity(7, 12, 34, 100))
        assertEquals(12, dao.readerPosition(7)?.anchorItemIndex)
        assertEquals(34, dao.readerPosition(7)?.anchorItemScrollOffset)

        dao.disconnectAndDeleteAll()

        assertNull(dao.readerPosition(7))
    }

    @Test fun cacheEvictsOldBodiesBeforeRowsAndProtectsActiveAndPendingEntries() = runBlocking {
        dao.upsertEntries(
            listOf(
                entry(1, publishedAt = 1, bodyBytes = 80, bodyLastAccessedAt = 1),
                entry(2, publishedAt = 2, bodyBytes = 80, bodyLastAccessedAt = 2),
                entry(3, publishedAt = 3, bodyBytes = 80, bodyLastAccessedAt = 3),
            ),
        )
        dao.setStarred(2, true, 10)
        val result = dao.enforceCachePolicy(
            WatchCachePolicy(maxUnreadEntries = 2, maxBodyBytes = 100),
            now = 20,
            activeEntryId = 3,
        )

        assertEquals(1, result.evictedBodies)
        assertEquals(null, dao.entry(1))
        assertEquals(80, dao.entry(2)?.bodyBytes)
        assertEquals(80, dao.entry(3)?.bodyBytes)
        assertTrue(dao.entry(2) != null)
        assertTrue(dao.entry(3) != null)
        assertTrue(result.remainingBodyBytes > 100) // Protected bodies may exceed the soft budget.
    }

    @Test fun retentionBoundsOrdinaryUnreadAndRecentlyOpenedReadRows() = runBlocking {
        dao.upsertEntries((1L..8L).map { entry(it, publishedAt = it, read = it > 5, lastOpenedAt = it) })
        dao.enforceCachePolicy(
            WatchCachePolicy(maxUnreadEntries = 3, maxRecentReadEntries = 1, recentReadLifetimeMillis = 5),
            now = 10,
        )

        assertEquals(3, dao.observeUnreadCount().first())
        assertFalse(dao.entry(6) != null)
        assertFalse(dao.entry(7) != null)
        assertTrue(dao.entry(8) != null)
    }

    private fun entry(
        id: Long,
        publishedAt: Long = id,
        changedAt: Long = id,
        bodyBytes: Int = 4,
        bodyLastAccessedAt: Long? = id,
        read: Boolean = false,
        lastOpenedAt: Long? = null,
    ) = WearEntryEntity(
        id = id,
        feedId = 1,
        feedTitle = "Feed",
        title = "Article $id",
        url = "https://example.com/$id",
        author = null,
        publishedAt = publishedAt,
        changedAt = changedAt,
        blocksJson = if (bodyBytes == 0) "" else "body",
        bodyBytes = bodyBytes,
        bodyTruncated = false,
        bodyLastAccessedAt = bodyLastAccessedAt,
        read = read,
        starred = false,
        readingMinutes = 1,
        lastOpenedAt = lastOpenedAt,
    )
}
