package com.nedrichards.brooklet.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nedrichards.brooklet.database.AccountEntity
import com.nedrichards.brooklet.database.BrookletDatabase
import com.nedrichards.brooklet.database.EntryEntity
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.network.MinifluxClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private lateinit var database: BrookletDatabase
    private lateinit var server: MockWebServer
    private lateinit var cipher: TokenCipher

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BrookletDatabase::class.java,
        ).allowMainThreadQueries().build()
        server = MockWebServer().apply { start() }
        cipher = TokenCipher()
    }

    @After fun tearDown() {
        server.shutdown()
        database.close()
        cipher.deleteKey()
    }

    @Test fun syncPushesQueuedReadAndPersistsPulledEntryAndCursor() = runBlocking {
        val dao = database.dao()
        val encrypted = cipher.encrypt("test-token")
        dao.upsertAccount(
            AccountEntity(
                serverUrl = server.url("/").toString(),
                username = "nick",
                tokenCiphertext = encrypted.ciphertext,
                tokenIv = encrypted.iv,
                serverVersion = "2.3.2",
                createdAt = 0,
            ),
        )
        dao.upsertEntries(listOf(entry(read = false)))
        dao.setRead(accountId = 1, entryId = 7, read = true, now = 50)
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("[]"))
        server.enqueue(jsonResponse("[]"))
        server.enqueue(jsonResponse("""{"total":1,"entries":[${remoteEntryJson()}]}"""))

        SyncEngine(
            dao = dao,
            cipher = cipher,
            clock = { 999 },
            minifluxClient = { url, token -> MinifluxClient(url, token, allowInsecureForTests = true) },
        ).run()

        val mutation = server.takeRequest()
        assertEquals("PUT", mutation.method)
        assertEquals("/v1/entries", mutation.requestUrl?.encodedPath)
        assertEquals("test-token", mutation.getHeader("X-Auth-Token"))
        assertTrue(mutation.body.readUtf8().contains("\"status\":\"read\""))
        assertEquals("/v1/categories", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/v1/feeds", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/v1/entries", server.takeRequest().requestUrl?.encodedPath)

        assertTrue(dao.pendingMutations().isEmpty())
        assertEquals(1_735_689_600L, dao.cursor(1)?.changedAfterEpochSeconds)
        assertEquals("COMPLETE", dao.observeSyncState(1).first()?.phase)
        val stored = requireNotNull(dao.observeEntry(1, 7).first())
        assertTrue(stored.read)
        assertEquals("Updated title", stored.title)
        assertEquals("<p>Cached text</p>", stored.html)
    }

    private fun entry(read: Boolean) = EntryEntity(
        accountId = 1,
        id = 7,
        feedId = 4,
        title = "Local title",
        url = "https://example.com/local",
        author = null,
        publishedAt = 1,
        changedAt = 1,
        html = "",
        parsedBlocksJson = "[]",
        read = read,
        starred = false,
        readingMinutes = 1,
        lastOpenedAt = null,
    )

    private fun remoteEntryJson() = """
        {"id":7,"feed_id":4,"title":"Updated title","url":"https://example.com/remote",
        "published_at":"2025-01-01T00:00:00Z","changed_at":"2025-01-01T00:00:00Z",
        "content":"<p>Cached text</p>","status":"read","starred":false,"reading_time":3}
    """.trimIndent()

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
