package com.nedrichards.brooklet.sync

import com.nedrichards.brooklet.database.BrookletDao
import com.nedrichards.brooklet.database.EntryRow
import com.nedrichards.brooklet.database.PendingKarakeepEntity
import com.nedrichards.brooklet.database.ReaderPositionEntity
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.model.Entry
import com.nedrichards.brooklet.model.DeliveryState
import com.nedrichards.brooklet.model.Category
import com.nedrichards.brooklet.model.Feed
import com.nedrichards.brooklet.model.HtmlDocumentParser
import com.nedrichards.brooklet.model.KarakeepRoute
import com.nedrichards.brooklet.model.canonicalUrl
import com.nedrichards.brooklet.network.MinifluxClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class EntryRepository(
    private val dao: BrookletDao,
    private val scheduler: SyncScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun inbox(accountId: Long): Flow<List<Entry>> = dao.observeInbox(accountId).map { rows -> rows.map(::map) }
    val saved: Flow<List<Entry>> = dao.observeSaved().map { rows -> rows.map(::map) }
    val allEntries: Flow<List<Entry>> = dao.observeAllEntries().map { rows -> rows.map(::map) }
    val pendingCount: Flow<Int> = dao.observePendingMutationCount()
    fun categories(accountId: Long): Flow<List<Category>> = dao.observeCategories(accountId).map { values -> values.map { Category(it.id, it.title) } }
    fun feeds(accountId: Long): Flow<List<Feed>> = dao.observeFeeds(accountId).map { values -> values.map { Feed(it.id, it.categoryId, it.title, it.siteUrl, it.feedUrl) } }
    fun entry(accountId: Long, entryId: Long): Flow<Entry?> = dao.observeEntry(accountId, entryId).map { it?.let(::map) }
    fun position(accountId: Long, entryId: Long) = dao.observePosition(accountId, entryId)

    suspend fun markRead(accountId: Long, entryId: Long, read: Boolean) {
        dao.setRead(accountId, entryId, read, clock())
        scheduler.enqueueImmediate()
    }
    suspend fun setStarred(accountId: Long, entryId: Long, starred: Boolean) {
        dao.setStarred(accountId, entryId, starred, clock())
        scheduler.enqueueImmediate()
    }
    suspend fun markAllRead(accountId: Long): List<Long> {
        val ids = dao.unreadIds(accountId)
        dao.setReadMany(accountId, ids, true, clock())
        scheduler.enqueueImmediate()
        return ids
    }
    suspend fun restoreUnread(accountId: Long, entryIds: List<Long>) {
        dao.setReadMany(accountId, entryIds, false, clock())
        scheduler.enqueueImmediate()
    }
    suspend fun savePosition(accountId: Long, entryId: Long, block: Int, offset: Int) =
        dao.upsertPosition(ReaderPositionEntity(accountId, entryId, block, offset, clock()))

    suspend fun sendToKarakeep(entry: Entry, route: KarakeepRoute) {
        dao.queueKarakeep(PendingKarakeepEntity(
            accountId = entry.accountId,
            entryId = entry.id,
            canonicalUrl = canonicalUrl(entry.url),
            title = entry.title,
            route = route.name,
            state = "QUEUED",
            createdAt = clock(),
        ))
        scheduler.enqueueImmediate()
    }

    /** Subscribing needs a feed refresh so Miniflux exposes its initial entries to the next pull. */
    suspend fun subscribe(accountId: Long, feedUrl: String) {
        val account = requireNotNull(dao.account()) { "No Miniflux account is configured" }
        require(account.id == accountId) { "The configured account has changed" }
        val token = TokenCipher().decrypt(TokenCipher.Encrypted(account.tokenCiphertext, account.tokenIv))
        MinifluxClient(account.serverUrl, token).subscribe(feedUrl, categoryId = null)
        scheduler.enqueueManualRefresh()
    }

    private fun map(row: EntryRow) = Entry(
        id = row.id,
        accountId = row.accountId,
        feedId = row.feedId,
        feedTitle = row.feedTitle,
        categoryTitle = row.categoryTitle,
        title = row.title,
        url = row.url,
        author = row.author,
        publishedAt = row.publishedAt,
        html = row.html,
        // Reparse retained HTML when opening the reader so renderer/parser
        // improvements apply to already-cached articles without another download.
        blocks = if (row.html.isEmpty()) emptyList() else HtmlDocumentParser.parse(row.html),
        read = row.read,
        starred = row.starred,
        readingMinutes = row.readingMinutes,
        deliveryState = row.deliveryState?.let { state -> runCatching { DeliveryState.valueOf(state) }.getOrNull() },
        deliveryError = row.deliveryError,
    )
}
