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
        val mutation = dao.pendingMutations().single()
        assertFalse(mutation.desiredValue)
        assertEquals(200, mutation.createdAt)
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
        assertEquals(listOf(2L, 3L, 4L, 5L), dao.observeAllEntries().first().map { it.id }.sorted())
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

    @Test fun accountDeletionRemovesArticlesAndQueuedActions() = runBlocking {
        dao.upsertAccount(account())
        dao.upsertEntries(listOf(entry(accountId = 1, id = 11, read = false)))
        dao.setRead(accountId = 1, entryId = 11, read = true, now = 100)
        dao.queueKarakeep(karakeep(title = "Saved", route = "MINIFLUX"))

        dao.deleteAccountAndLocalData(1)

        assertNull(dao.account())
        assertTrue(dao.observeAllEntries().first().isEmpty())
        assertTrue(dao.pendingMutations().isEmpty())
        assertTrue(dao.pendingKarakeep().isEmpty())
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
}
