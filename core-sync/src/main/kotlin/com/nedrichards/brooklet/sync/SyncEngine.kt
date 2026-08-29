package com.nedrichards.brooklet.sync

import com.nedrichards.brooklet.database.BrookletDao
import com.nedrichards.brooklet.database.CategoryEntity
import com.nedrichards.brooklet.database.EntryEntity
import com.nedrichards.brooklet.database.FeedEntity
import com.nedrichards.brooklet.database.SyncCursorEntity
import com.nedrichards.brooklet.database.SyncStateEntity
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.model.incrementalStart
import com.nedrichards.brooklet.network.KarakeepClient
import com.nedrichards.brooklet.network.MinifluxClient
import java.time.Instant
import kotlinx.coroutines.CancellationException

class SyncEngine(
    private val dao: BrookletDao,
    private val cipher: TokenCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minifluxClient: (String, String) -> MinifluxClient = { serverUrl, token ->
        MinifluxClient(serverUrl, token)
    },
) {
    suspend fun run(
        refreshFeeds: Boolean = false,
        pullRemoteState: Boolean = true,
        entriesOnly: Boolean = false,
    ) {
        val account = dao.account() ?: return
        try {
            state(account.id, "CONNECTING")
            val token = cipher.decrypt(TokenCipher.Encrypted(account.tokenCiphertext, account.tokenIv))
            val miniflux = minifluxClient(account.serverUrl, token)
            if (refreshFeeds) {
                state(account.id, "REFRESHING_FEEDS")
                miniflux.refreshFeeds()
            }
            state(account.id, "PUSHING_ACTIONS")
            pushMutations(miniflux, account.id)
            pushKarakeep(miniflux, account.id)
            if (pullRemoteState) {
                pull(miniflux, account.id, includeFeedMetadata = !entriesOnly)
                if (!entriesOnly) {
                    state(account.id, "PRUNING")
                    dao.pruneCompletedKarakeep(account.id, clock() - COMPLETED_KARAKEEP_RECEIPT_MS)
                    val policy = dao.storagePolicy(account.id)
                    if (policy == null) {
                        dao.pruneReadEntries(account.id, clock() - 30L * 24 * 60 * 60 * 1000)
                    } else {
                        policy.retainReadDays?.let { days ->
                            dao.pruneReadEntries(account.id, clock() - days.toLong() * 24 * 60 * 60 * 1000, policy.keepAtMost)
                        }
                    }
                }
            }
            state(account.id, "COMPLETE")
        } catch (cancelled: CancellationException) {
            state(account.id, "QUEUED")
            throw cancelled
        } catch (error: Throwable) {
            state(account.id, "ERROR", error = error.message?.take(240) ?: error::class.simpleName ?: "Sync failed")
            throw error
        }
    }

    private suspend fun pushMutations(client: MinifluxClient, accountId: Long) {
        dao.pendingMutationsForAccount(accountId).groupBy { it.field to it.desiredValue }.forEach { (key, values) ->
            val ids = values.map { it.entryId }
            if (key.first == "READ") client.setRead(ids, key.second) else client.setStarred(ids, key.second)
            dao.acknowledgeMutations(accountId, ids, key.first, key.second)
        }
    }

    private suspend fun pushKarakeep(miniflux: MinifluxClient, accountId: Long) {
        val config = dao.karakeepConfig(accountId)
        val pendingItems = dao.pendingKarakeepForAccount(accountId)
        var directClient: KarakeepClient? = null
        pendingItems.forEach { pending ->
            runCatching {
                if (pending.route == "DIRECT") {
                    val client = directClient ?: run {
                        val endpoint = requireNotNull(config?.directEndpoint) { "Direct Karakeep is not configured" }
                        val encrypted = TokenCipher.Encrypted(requireNotNull(config.directKeyCiphertext), requireNotNull(config.directKeyIv))
                        KarakeepClient(endpoint, cipher.decrypt(encrypted)).also { directClient = it }
                    }
                    client.save(pending.canonicalUrl, pending.title)
                } else {
                    miniflux.saveToIntegration(pending.entryId)
                }
            }.onSuccess { dao.acknowledgeKarakeep(pending.id, clock()) }
                .onFailure { error ->
                    dao.recordKarakeepFailure(pending.id, if (error is IllegalStateException) "NEEDS_ATTENTION" else "QUEUED", error.message ?: "Delivery failed")
                    if (error is java.io.IOException || error is com.nedrichards.brooklet.network.ApiException && error.kind == com.nedrichards.brooklet.model.FailureKind.RETRYABLE) throw error
                }
        }
    }

    private suspend fun pull(client: MinifluxClient, accountId: Long, includeFeedMetadata: Boolean) {
        val cursor = dao.cursor(accountId)?.changedAfterEpochSeconds ?: 0
        val overlap = incrementalStart(cursor)
        if (includeFeedMetadata) {
            state(accountId, "PULLING_FEEDS")
            val categories = client.categories()
            val feeds = client.feeds()
            dao.upsertCategories(categories.map { CategoryEntity(accountId, it.id, it.title) })
            dao.upsertFeeds(feeds.map { feed -> FeedEntity(accountId, feed.id, feed.category?.id ?: 0, feed.title, feed.siteUrl, feed.feedUrl) })
        }
        var offset = 0
        var newest = cursor
        state(accountId, "PULLING_ENTRIES")
        do {
            val page = client.entriesChangedAfter(overlap, offset = offset)
            val mapped = page.entries.map { dto ->
                val changed = Instant.parse(dto.changedAt).epochSecond
                newest = maxOf(newest, changed)
                EntryEntity(accountId, dto.id, dto.feedId, dto.title, dto.url, dto.author,
                    Instant.parse(dto.publishedAt).toEpochMilli(), changed * 1000, dto.content,
                    "[]", dto.status == "read", dto.starred,
                    dto.readingTime.coerceAtLeast(1), null)
            }
            dao.mergeRemoteEntries(accountId, mapped)
            offset += page.entries.size
            state(accountId, "PULLING_ENTRIES", offset, page.total)
        } while (page.entries.isNotEmpty() && offset < page.total)
        dao.upsertCursor(SyncCursorEntity(accountId, newest, clock()))
    }

    private suspend fun state(accountId: Long, phase: String, processed: Int = 0, total: Int = 0, error: String? = null) {
        dao.upsertSyncState(SyncStateEntity(accountId, phase, processed, total, error, clock()))
    }

    private companion object {
        const val COMPLETED_KARAKEEP_RECEIPT_MS = 30L * 24 * 60 * 60 * 1000
    }
}
