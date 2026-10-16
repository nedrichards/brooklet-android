package com.nedrichards.brooklet.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nedrichards.brooklet.model.ArticleSearchText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArticleSearchDaoTest {
    private lateinit var database: BrookletDatabase
    private lateinit var dao: BrookletDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            BrookletDatabase::class.java).build()
        dao = database.dao()
    }

    @After fun tearDown() = database.close()

    @Test fun searchesVisibleBodiesAndUnicodeMetadataWithoutLoadingBodies() = runBlocking {
        dao.upsertFeeds(listOf(FeedEntity(1, 10, 1, "ÉNERGIE", "", "")))
        dao.upsertEntries(listOf(
            entry(1, "<p>A <b>heat</b> pump costs 100% for a_b at c\\d.</p>"),
            entry(2, "<script>heat pump</script><img alt='heat pump'><p>Elsewhere</p>"),
            entry(3, "<p>Other</p>").copy(author = "ΑΘΉΝΑ", title = "CAFÉ"),
            entry(4, "<p>Heat pump</p>").copy(accountId = 2),
        ))
        assertEquals(listOf(1L), ids("heat pump"))
        assertEquals(listOf(1L), ids("HEAT\nPUMP"))
        listOf("100%", "a_b", "c\\d").forEach { assertEquals(listOf(1L), ids(it)) }
        assertEquals(listOf(3L), ids("café"))
        assertEquals(listOf(3L), ids("αθήνα"))
        assertEquals(listOf(3L, 2L, 1L), ids("énergie"))
        val result = dao.observeSearchEntries(1, ArticleSearchText.pattern("heat pump"), "library").first().single()
        assertEquals("", result.html)
        assertEquals("[]", result.parsedBlocksJson)
    }

    @Test fun scopesFiltersAndSavedReceiptsAreAppliedBeforeLimit() = runBlocking {
        dao.upsertEntries((1L..520L).map { entry(it, "<p>Needle</p>").copy(read = true) } + listOf(
            entry(521, "<p>Needle</p>"),
            entry(522, "<p>Needle</p>").copy(starred = true, read = true, feedId = 20),
        ))
        dao.queueKarakeep(PendingKarakeepEntity(accountId = 1, entryId = 1,
            canonicalUrl = "https://example.com/1", title = "Saved elsewhere", route = "DIRECT",
            state = "SAVED", createdAt = 1, completedAt = 1))
        assertEquals(501, ids("needle").size)
        assertEquals(listOf(521L), ids("needle", scope = "inbox"))
        assertEquals(listOf(522L, 1L), ids("needle", scope = "saved"))
        assertEquals(listOf(1L), ids("needle", read = true, savedOnly = true, feedId = 10))
        assertEquals(listOf(522L), ids("needle", feedId = 20))
        assertEquals(listOf(521L), ids("needle", read = false))
    }

    @Test fun changedContentFeedRenamesPruningAndDisconnectUpdateSearch() = runBlocking {
        dao.upsertFeeds(listOf(FeedEntity(1, 10, 1, "Old feed", "", "")))
        dao.upsertEntries(listOf(entry(1, "<p>Old body</p>")))
        val changed = dao.entriesById(1, listOf(1)).single().copy(html = "<p>New body</p>")
        dao.mergeRemoteEntries(1, listOf(changed))
        dao.mergeRemoteFeeds(1, listOf(FeedEntity(1, 10, 1, "New feed", "", "")))
        assertTrue(ids("old body").isEmpty())
        assertTrue(ids("old feed").isEmpty())
        assertEquals(listOf(1L), ids("new body"))
        assertEquals(listOf(1L), ids("new feed"))
        dao.mergeRemotePage(1, emptyList(), listOf(1))
        assertTrue(ids("new body").isEmpty())
        dao.upsertEntries(listOf(entry(2, "<p>New body</p>").copy(read = true)))
        dao.pruneReadEntries(1, cutoff = 10, keepAtMost = 0)
        assertTrue(ids("new body").isEmpty())
        dao.upsertEntries(listOf(entry(3, "<p>New body</p>")))
        dao.deleteAccountAndLocalData(1)
        assertTrue(ids("new body").isEmpty())
    }

    @Test fun backfillResumesAndCannotOverwriteNewContentOrResurrectDeletedRows() = runBlocking {
        dao.upsertFeeds(listOf(FeedEntity(1, 10, 1, "CAFÉ", "", "")))
        dao.upsertEntries(listOf(entry(1, "<p>Old text</p>"), entry(2, "<p>Still here</p>")))
        database.openHelper.writableDatabase.execSQL("UPDATE entries SET searchVersion = 0, searchBody = ''")
        database.openHelper.writableDatabase.execSQL("UPDATE feeds SET searchVersion = 0, searchTitle = ''")
        val old = dao.entriesNeedingSearchText(1).first { it.id == 1L }
        dao.mergeRemoteEntries(1, listOf(old.copy(html = "<p>New text</p>")))
        assertEquals(0, dao.updateSearchTextIfUnchanged(1, old.id, old.html, old.title, old.author,
            "stale", "", "stale"))
        dao.prepareSearchText(1)
        assertTrue(dao.entriesNeedingSearchText(1).isEmpty())
        assertTrue(dao.feedsNeedingSearchText(1).isEmpty())
        assertEquals(listOf(2L), ids("still here"))
        assertEquals(listOf(1L), ids("new text"))
        assertEquals(listOf(2L, 1L), ids("café"))
        dao.deleteAccountAndLocalData(1)
        assertEquals(0, dao.updateSearchTextIfUnchanged(1, old.id, old.html, old.title, old.author,
            "stale", "", "stale"))
        dao.prepareSearchText(1)
        assertTrue(ids("stale").isEmpty())
    }

    @Test fun twentyThousandArticleFixtureFindsBodyOnlyMatchesAndReportsScanTiming() = runBlocking {
        val html = "<p>" + "Ordinary cached article text. ".repeat(100) + "</p>"
        dao.upsertEntries((1L..20_000L).map { entry(it, html + if (it == 1L) "<p>Distinctive phrase</p>" else "") })
        val timings = mutableListOf<Long>()
        repeat(6) {
            val start = System.nanoTime()
            assertEquals(listOf(1L), ids("distinctive phrase"))
            timings += (System.nanoTime() - start) / 1_000_000
        }
        android.util.Log.i("ArticleSearch", "20k body scan coldMs=${timings.first()} warmMs=${timings.drop(1)}")
        assertEquals(501, ids("ordinary").size)
        assertTrue(ids("missing phrase").isEmpty())
    }

    private suspend fun ids(query: String, scope: String = "library", read: Boolean? = null,
        savedOnly: Boolean = false, feedId: Long? = null) = dao.observeSearchEntries(
            1, ArticleSearchText.pattern(query), scope, read, savedOnly, feedId).first().map { it.id }

    private fun entry(id: Long, html: String) = EntryEntity(1, id, 10, "Headline $id",
        "https://example.com/$id", null, id, id, html, "[]", false, false, 1, null)
}
