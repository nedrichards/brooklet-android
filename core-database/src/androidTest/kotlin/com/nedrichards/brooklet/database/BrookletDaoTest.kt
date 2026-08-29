package com.nedrichards.brooklet.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrookletDaoTest {
    private lateinit var database: BrookletDatabase
    private lateinit var dao: BrookletDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BrookletDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.dao()
    }

    @After fun tearDown() = database.close()

    @Test fun undoRestoresInboxAndSupersedesQueuedMutation() = runBlocking {
        dao.upsertEntries(listOf(entry(accountId = 1, id = 11, read = false)))

        dao.setRead(accountId = 1, entryId = 11, read = true, now = 100)
        assertTrue(dao.observeInbox(1).first().isEmpty())

        dao.setRead(accountId = 1, entryId = 11, read = false, now = 200)
        assertEquals(listOf(11L), dao.observeInbox(1).first().map { it.id })
        assertFalse(requireNotNull(dao.observeEntry(1, 11).first()).read)
        assertFalse(dao.observeAllEntries(1).first().single().read)
        val mutation = dao.pendingMutations().single()
        assertFalse(mutation.desiredValue)
        assertEquals(200, mutation.createdAt)
    }

    @Test fun staleReadActionCannotCreateMutationForMissingEntry() = runBlocking {
        dao.setRead(accountId = 1, entryId = 404, read = false, now = 100)

        assertTrue(dao.pendingMutations().isEmpty())
    }

    @Test fun markAllAndUndoRestoreEveryOriginalUnreadEntry() = runBlocking {
        dao.upsertEntries(listOf(
            entry(accountId = 1, id = 11, read = false),
            entry(accountId = 1, id = 12, read = false),
            entry(accountId = 1, id = 13, read = true),
        ))
        val originalUnread = dao.unreadIds(1)

        dao.setReadMany(1, originalUnread, read = true, now = 100)
        assertTrue(dao.observeInbox(1).first().isEmpty())
        dao.setReadMany(1, originalUnread, read = false, now = 200)

        assertEquals(listOf(12L, 11L), dao.observeInbox(1).first().map { it.id })
        assertEquals(
            mapOf(11L to false, 12L to false, 13L to true),
            dao.observeAllEntries(1).first().associate { it.id to it.read },
        )
        assertEquals(setOf(11L, 12L), dao.pendingMutations().map { it.entryId }.toSet())
        assertTrue(dao.pendingMutations().all { !it.desiredValue })
    }

    @Test fun bulkReadMutationChunksLargeInboxesAndIgnoresMissingRows() = runBlocking {
        val entries = (1L..1_005L).map { id -> entry(accountId = 1, id = id, read = false) }
        dao.upsertEntries(entries)

        dao.setReadMany(1, entries.map { it.id } + 9_999L, read = true, now = 100)

        assertTrue(dao.observeInbox(1).first().isEmpty())
        assertEquals(1_005, dao.pendingMutations().size)
        assertTrue(dao.pendingMutations().none { it.entryId == 9_999L })
    }

    @Test fun inboxIsScopedToRequestedAccount() = runBlocking {
        dao.upsertEntries(
            listOf(
                entry(accountId = 1, id = 11, read = false),
                entry(accountId = 2, id = 22, read = false),
            ),
        )

        assertEquals(listOf(11L), dao.observeInbox(1).first().map { it.id })
        assertEquals(listOf(22L), dao.observeInbox(2).first().map { it.id })
    }

    @Test fun entryListsUseAccountAndOrderingIndexesWithoutTemporarySorts() {
        val inboxPlan = queryPlan(
            """
            SELECT e.id FROM entries e
            WHERE e.accountId = 1 AND e.read = 0
            ORDER BY e.publishedAt DESC
            """.trimIndent(),
        )
        val libraryPlan = queryPlan(
            """
            SELECT e.id FROM entries e
            WHERE e.accountId = 1
            ORDER BY e.publishedAt DESC
            """.trimIndent(),
        )
        val feedPlan = queryPlan(
            """
            SELECT e.id FROM entries e
            WHERE e.accountId = 1 AND e.feedId = 2
            ORDER BY e.publishedAt DESC
            """.trimIndent(),
        )

        assertUsesOrderedIndex(inboxPlan, "index_entries_accountId_read_publishedAt")
        assertUsesOrderedIndex(libraryPlan, "index_entries_accountId_publishedAt")
        assertUsesOrderedIndex(feedPlan, "index_entries_accountId_feedId_publishedAt")
    }

    @Test fun queuedLocalIntentWinsUntilAcknowledged() = runBlocking {
        dao.upsertEntries(listOf(entry(accountId = 1, id = 11, read = false)))
        dao.setRead(accountId = 1, entryId = 11, read = true, now = 100)

        dao.mergeRemoteEntries(
            accountId = 1,
            remote = listOf(entry(accountId = 1, id = 11, read = false, starred = true)),
        )
        val whilePending = requireNotNull(dao.observeEntry(1, 11).first())
        assertTrue(whilePending.read)
        assertTrue(whilePending.starred)

        dao.acknowledgeMutations(1, listOf(11), field = "READ", desiredValue = true)
        dao.mergeRemoteEntries(
            accountId = 1,
            remote = listOf(entry(accountId = 1, id = 11, read = false, starred = false)),
        )
        val afterAcknowledgement = requireNotNull(dao.observeEntry(1, 11).first())
        assertFalse(afterAcknowledgement.read)
        assertFalse(afterAcknowledgement.starred)
    }

    @Test fun oldAcknowledgementDoesNotEraseNewerUndo() = runBlocking {
        dao.upsertEntries(listOf(entry(accountId = 1, id = 11, read = false)))
        dao.setRead(accountId = 1, entryId = 11, read = true, now = 100)
        dao.setRead(accountId = 1, entryId = 11, read = false, now = 200)

        dao.acknowledgeMutations(1, listOf(11), field = "READ", desiredValue = true)
        assertEquals(listOf(false), dao.pendingMutations().map { it.desiredValue })

        dao.acknowledgeMutations(1, listOf(11), field = "READ", desiredValue = false)
        assertTrue(dao.pendingMutations().isEmpty())
    }

    @Test fun pruningRetainsProtectedEntries() = runBlocking {
        dao.upsertEntries(
            listOf(
                entry(accountId = 1, id = 1, read = true),
                entry(accountId = 1, id = 2, read = false),
                entry(accountId = 1, id = 3, read = true, starred = true),
                entry(accountId = 1, id = 4, read = true, lastOpenedAt = 150),
                entry(accountId = 1, id = 5, read = true),
            ),
        )
        dao.setRead(accountId = 1, entryId = 5, read = true, now = 50)

        assertEquals(1, dao.pruneReadEntries(accountId = 1, cutoff = 100, keepAtMost = 0))
        assertEquals(listOf(2L, 3L, 4L, 5L), dao.observeAllEntries(1).first().map { it.id }.sorted())
    }

    @Test fun pruningCannotDeleteSameIdInAnotherAccount() = runBlocking {
        dao.upsertEntries(
            listOf(
                entry(accountId = 1, id = 11, read = true),
                entry(accountId = 2, id = 11, read = false),
            ),
        )

        assertEquals(1, dao.pruneReadEntries(accountId = 1, cutoff = 100, keepAtMost = 0))
        assertEquals(listOf(11L), dao.observeInbox(2).first().map { it.id })
    }

    @Test fun repeatedKarakeepSavesCoalesce() = runBlocking {
        dao.queueKarakeep(karakeep(title = "First", route = "MINIFLUX"))
        dao.queueKarakeep(karakeep(title = "Latest", route = "DIRECT"))

        val pending = dao.pendingKarakeep().single()
        assertEquals("Latest", pending.title)
        assertEquals("DIRECT", pending.route)
    }

    @Test fun completedKarakeepReceiptsRetainThirtyDaysFromCompletion() = runBlocking {
        dao.queueKarakeep(karakeep(title = "Old queue", route = "MINIFLUX"))
        val id = dao.pendingKarakeep().single().id
        dao.acknowledgeKarakeep(id, completedAt = 1_000)

        assertEquals(0, dao.pruneCompletedKarakeep(accountId = 1, cutoff = 999))
        assertEquals(1, dao.pruneCompletedKarakeep(accountId = 1, cutoff = 1_001))
    }

    @Test fun accountDeletionRemovesArticlesAndQueuedActions() = runBlocking {
        dao.upsertAccount(account())
        dao.upsertEntries(listOf(entry(accountId = 1, id = 11, read = false)))
        dao.setRead(accountId = 1, entryId = 11, read = true, now = 100)
        dao.queueKarakeep(karakeep(title = "Saved", route = "MINIFLUX"))

        dao.deleteAccountAndLocalData(1)

        assertNull(dao.account())
        assertTrue(dao.observeAllEntries(1).first().isEmpty())
        assertTrue(dao.pendingMutations().isEmpty())
        assertTrue(dao.pendingKarakeep().isEmpty())
    }

    @Test fun unreadIntentAndLibraryStateSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "brooklet-read-state-reopen-test.db"
        context.deleteDatabase(name)
        var reopened: BrookletDatabase? = null
        try {
            Room.databaseBuilder(context, BrookletDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
                .also { first ->
                    first.dao().upsertEntries(listOf(entry(accountId = 1, id = 11, read = true)))
                    first.dao().setRead(accountId = 1, entryId = 11, read = false, now = 200)
                    first.close()
                }

            reopened = Room.databaseBuilder(context, BrookletDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
            val reopenedDao = reopened.dao()

            assertFalse(requireNotNull(reopenedDao.observeEntry(1, 11).first()).read)
            assertFalse(reopenedDao.observeAllEntries(1).first().single().read)
            assertFalse(reopenedDao.pendingMutations().single().desiredValue)
        } finally {
            reopened?.close()
            context.deleteDatabase(name)
        }
    }

    private fun entry(
        accountId: Long,
        id: Long,
        read: Boolean,
        starred: Boolean = false,
        lastOpenedAt: Long? = null,
    ) = EntryEntity(
        accountId = accountId,
        id = id,
        feedId = 1,
        title = "Entry $id",
        url = "https://example.com/$id",
        author = null,
        publishedAt = id,
        changedAt = id,
        html = "",
        parsedBlocksJson = "[]",
        read = read,
        starred = starred,
        readingMinutes = 1,
        lastOpenedAt = lastOpenedAt,
    )

    private fun karakeep(title: String, route: String) = PendingKarakeepEntity(
        accountId = 1,
        entryId = 11,
        canonicalUrl = "https://example.com/story",
        title = title,
        route = route,
        state = "QUEUED",
        createdAt = 10,
    )

    private fun account() = AccountEntity(
        serverUrl = "https://miniflux.example.com",
        username = "nick",
        tokenCiphertext = byteArrayOf(1),
        tokenIv = byteArrayOf(2),
        serverVersion = "2.3.2",
        createdAt = 0,
    )

    private fun queryPlan(sql: String): List<String> =
        database.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }
        }

    private fun assertUsesOrderedIndex(plan: List<String>, index: String) {
        assertTrue("Expected $index in $plan", plan.any { index in it })
        assertFalse("Unexpected temporary sort in $plan", plan.any { "USE TEMP B-TREE FOR ORDER BY" in it })
    }
}
