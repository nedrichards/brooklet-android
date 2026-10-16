package com.nedrichards.brooklet.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import com.nedrichards.brooklet.model.ArticleSearchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val ENTRY_SUMMARY_QUERY = """
    SELECT e.accountId, e.id, e.feedId, COALESCE(f.title, '') AS feedTitle,
        COALESCE(c.title, '') AS categoryTitle, e.title, e.url, e.author,
        e.publishedAt, '' AS html, '[]' AS parsedBlocksJson, e.read, e.starred, e.readingMinutes,
        NULL AS deliveryState, NULL AS deliveryError
    FROM entries e LEFT JOIN feeds f ON f.accountId=e.accountId AND f.id=e.feedId
    LEFT JOIN categories c ON c.accountId=f.accountId AND c.id=f.categoryId
"""

@Dao
abstract class BrookletDao {
    @Query("SELECT * FROM accounts WHERE id = 1") abstract fun observeAccount(): Flow<AccountEntity?>
    @Query("SELECT * FROM accounts WHERE id = 1") abstract suspend fun account(): AccountEntity?
    @Upsert abstract suspend fun upsertAccount(value: AccountEntity)
    @Upsert abstract suspend fun upsertCategories(values: List<CategoryEntity>)
    @Upsert protected abstract suspend fun writeFeeds(values: List<FeedEntity>)
    open suspend fun upsertFeeds(values: List<FeedEntity>) = writeFeeds(values.map {
        it.copy(searchTitle = ArticleSearchText.normalize(it.title), searchVersion = ArticleSearchText.VERSION)
    })
    @Query("SELECT * FROM categories WHERE accountId = :accountId")
    protected abstract suspend fun categoriesForAccount(accountId: Long): List<CategoryEntity>
    @Query("SELECT * FROM feeds WHERE accountId = :accountId")
    protected abstract suspend fun feedsForAccount(accountId: Long): List<FeedEntity>
    @Upsert protected abstract suspend fun writeEntries(values: List<EntryEntity>)

    /** All entry write paths derive text here; status-only changes reuse the cached body. */
    open suspend fun upsertEntries(values: List<EntryEntity>) = withContext(Dispatchers.Default) {
        values.chunked(100).forEach { batch ->
            val previous = batch.groupBy { it.accountId }.flatMap { (accountId, rows) ->
                entriesById(accountId, rows.map { it.id })
            }.associateBy { it.accountId to it.id }
            writeEntries(batch.map { incoming ->
                val old = previous[incoming.accountId to incoming.id]
                incoming.copy(
                    searchTitle = ArticleSearchText.normalize(incoming.title),
                    searchAuthor = ArticleSearchText.normalize(incoming.author.orEmpty()),
                    searchBody = if (old?.html == incoming.html && old.searchVersion == ArticleSearchText.VERSION)
                        old.searchBody else ArticleSearchText.body(incoming.html),
                    searchVersion = ArticleSearchText.VERSION,
                )
            })
        }
    }

    @Query("SELECT * FROM entries WHERE accountId = :accountId AND searchVersion < :version LIMIT 100")
    abstract suspend fun entriesNeedingSearchText(accountId: Long, version: Int = ArticleSearchText.VERSION): List<EntryEntity>

    @Query("SELECT * FROM feeds WHERE accountId = :accountId AND searchVersion < :version LIMIT 100")
    abstract suspend fun feedsNeedingSearchText(accountId: Long, version: Int = ArticleSearchText.VERSION): List<FeedEntity>

    @Query("""
        UPDATE entries SET searchTitle = :searchTitle, searchAuthor = :searchAuthor,
            searchBody = :searchBody, searchVersion = :version
        WHERE accountId = :accountId AND id = :entryId AND html = :html AND title = :title
            AND author IS :author AND searchVersion < :version
    """)
    abstract suspend fun updateSearchTextIfUnchanged(accountId: Long, entryId: Long, html: String,
        title: String, author: String?, searchTitle: String, searchAuthor: String, searchBody: String,
        version: Int = ArticleSearchText.VERSION): Int

    @Query("UPDATE feeds SET searchTitle = :searchTitle, searchVersion = :version WHERE accountId = :accountId AND id = :feedId AND title = :title AND searchVersion < :version")
    abstract suspend fun updateFeedSearchTextIfUnchanged(accountId: Long, feedId: Long, title: String,
        searchTitle: String, version: Int = ArticleSearchText.VERSION): Int

    /** Bounded, restartable backfill. Conditional updates cannot resurrect deleted rows or replace newer content. */
    open suspend fun prepareSearchText(accountId: Long) = withContext(Dispatchers.Default) {
        while (true) {
            val rows = entriesNeedingSearchText(accountId)
            if (rows.isEmpty()) break
            applySearchTextBatch(rows.map { row -> row.copy(
                searchTitle = ArticleSearchText.normalize(row.title),
                searchAuthor = ArticleSearchText.normalize(row.author.orEmpty()),
                searchBody = ArticleSearchText.body(row.html),
            ) })
            yield()
        }
        while (true) {
            val rows = feedsNeedingSearchText(accountId)
            if (rows.isEmpty()) break
            applyFeedSearchTextBatch(rows.map { row -> row.copy(searchTitle = ArticleSearchText.normalize(row.title)) })
            yield()
        }
    }

    @Transaction
    open suspend fun applySearchTextBatch(rows: List<EntryEntity>) {
        rows.forEach { row -> updateSearchTextIfUnchanged(row.accountId, row.id, row.html, row.title,
            row.author, row.searchTitle, row.searchAuthor, row.searchBody) }
    }

    @Transaction
    open suspend fun applyFeedSearchTextBatch(rows: List<FeedEntity>) {
        rows.forEach { row -> updateFeedSearchTextIfUnchanged(row.accountId, row.id, row.title, row.searchTitle) }
    }

    @Query(ENTRY_SUMMARY_QUERY + """
        WHERE e.accountId = :accountId
            AND (e.searchTitle LIKE :pattern ESCAPE '\' OR e.searchAuthor LIKE :pattern ESCAPE '\'
                OR e.searchBody LIKE :pattern ESCAPE '\' OR f.searchTitle LIKE :pattern ESCAPE '\')
            AND (:scope != 'inbox' OR e.read = 0)
            AND ((:scope != 'saved' AND :savedOnly = 0) OR e.starred = 1 OR EXISTS (
                SELECT 1 FROM pending_karakeep k WHERE k.accountId = e.accountId AND k.entryId = e.id))
            AND (:read IS NULL OR e.read = :read)
            AND (:feedId IS NULL OR e.feedId = :feedId)
        ORDER BY e.publishedAt DESC, e.id DESC LIMIT 501
    """)
    abstract fun observeSearchEntries(accountId: Long, pattern: String, scope: String,
        read: Boolean? = null, savedOnly: Boolean = false, feedId: Long? = null): Flow<List<EntryRow>>

    @Query("""
        SELECT DISTINCT f.* FROM feeds f JOIN entries e ON e.accountId = f.accountId AND e.feedId = f.id
        WHERE f.accountId = :accountId AND (:scope != 'inbox' OR e.read = 0)
            AND (:scope != 'saved' OR e.starred = 1 OR EXISTS (
                SELECT 1 FROM pending_karakeep k WHERE k.accountId = e.accountId AND k.entryId = e.id))
        ORDER BY f.title COLLATE NOCASE
    """)
    abstract fun observeSearchFeeds(accountId: Long, scope: String): Flow<List<FeedEntity>>
    @Upsert abstract suspend fun upsertPosition(value: ReaderPositionEntity)
    @Upsert abstract suspend fun upsertCursor(value: SyncCursorEntity)
    @Upsert abstract suspend fun upsertSyncState(value: SyncStateEntity)
    @Upsert abstract suspend fun upsertMutation(value: PendingMutationEntity)
    @Upsert protected abstract suspend fun upsertMutations(values: List<PendingMutationEntity>)
    @Upsert protected abstract suspend fun upsertKarakeep(value: PendingKarakeepEntity)
    @Upsert abstract suspend fun upsertKarakeepConfig(value: KarakeepConfigEntity)
    @Query("SELECT * FROM karakeep_config WHERE accountId = :accountId") abstract suspend fun karakeepConfig(accountId: Long): KarakeepConfigEntity?
    @Upsert abstract suspend fun upsertStoragePolicy(value: StoragePolicyEntity)
    @Query("SELECT * FROM storage_policy WHERE accountId = :accountId") abstract suspend fun storagePolicy(accountId: Long): StoragePolicyEntity?

    /** Removes every local record belonging to the account in one transaction. */
    @Transaction
    open suspend fun deleteAccountAndLocalData(accountId: Long) {
        deletePendingMutations(accountId)
        deletePendingKarakeep(accountId)
        deletePositions(accountId)
        deleteEntries(accountId)
        deleteFeeds(accountId)
        deleteCategories(accountId)
        deleteCursor(accountId)
        deleteSyncState(accountId)
        deleteKarakeepConfig(accountId)
        deleteStoragePolicy(accountId)
        deleteAccount(accountId)
    }

    @Query("DELETE FROM pending_mutations WHERE accountId = :accountId")
    protected abstract suspend fun deletePendingMutations(accountId: Long)
    @Query("DELETE FROM pending_karakeep WHERE accountId = :accountId")
    protected abstract suspend fun deletePendingKarakeep(accountId: Long)
    @Query("DELETE FROM reader_positions WHERE accountId = :accountId")
    protected abstract suspend fun deletePositions(accountId: Long)
    @Query("DELETE FROM entries WHERE accountId = :accountId")
    protected abstract suspend fun deleteEntries(accountId: Long)
    @Query("DELETE FROM feeds WHERE accountId = :accountId")
    protected abstract suspend fun deleteFeeds(accountId: Long)
    @Query("DELETE FROM categories WHERE accountId = :accountId")
    protected abstract suspend fun deleteCategories(accountId: Long)
    @Query("DELETE FROM sync_cursors WHERE accountId = :accountId")
    protected abstract suspend fun deleteCursor(accountId: Long)
    @Query("DELETE FROM sync_state WHERE accountId = :accountId")
    protected abstract suspend fun deleteSyncState(accountId: Long)
    @Query("DELETE FROM karakeep_config WHERE accountId = :accountId")
    protected abstract suspend fun deleteKarakeepConfig(accountId: Long)
    @Query("DELETE FROM storage_policy WHERE accountId = :accountId")
    protected abstract suspend fun deleteStoragePolicy(accountId: Long)
    @Query("DELETE FROM accounts WHERE id = :accountId")
    protected abstract suspend fun deleteAccount(accountId: Long)
    @Query("SELECT * FROM categories WHERE accountId = :accountId ORDER BY title COLLATE NOCASE") abstract fun observeCategories(accountId: Long): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM feeds WHERE accountId = :accountId ORDER BY title COLLATE NOCASE") abstract fun observeFeeds(accountId: Long): Flow<List<FeedEntity>>

    @Query("""
        SELECT e.accountId, e.id, e.feedId, COALESCE(f.title, '') AS feedTitle,
            COALESCE(c.title, '') AS categoryTitle, e.title, e.url, e.author,
            e.publishedAt, '' AS html, '[]' AS parsedBlocksJson, e.read, e.starred, e.readingMinutes,
            NULL AS deliveryState, NULL AS deliveryError
        FROM entries e
        LEFT JOIN feeds f ON f.accountId = e.accountId AND f.id = e.feedId
        LEFT JOIN categories c ON c.accountId = f.accountId AND c.id = f.categoryId
        WHERE e.accountId = :accountId AND e.read = 0
        ORDER BY e.publishedAt DESC
    """)
    abstract fun observeInbox(accountId: Long): Flow<List<EntryRow>>

    @Query("""
        SELECT e.accountId, e.id, e.feedId, COALESCE(f.title, '') AS feedTitle,
            COALESCE(c.title, '') AS categoryTitle, e.title, e.url, e.author,
            e.publishedAt, '' AS html, '[]' AS parsedBlocksJson, e.read, e.starred, e.readingMinutes,
            k.state AS deliveryState, k.lastError AS deliveryError
        FROM entries e LEFT JOIN feeds f ON f.accountId=e.accountId AND f.id=e.feedId
        LEFT JOIN categories c ON c.accountId=f.accountId AND c.id=f.categoryId
        LEFT JOIN pending_karakeep k ON k.accountId=e.accountId AND k.entryId=e.id
        WHERE e.accountId = :accountId AND (e.starred = 1 OR k.id IS NOT NULL)
        ORDER BY e.publishedAt DESC
    """) abstract fun observeSaved(accountId: Long): Flow<List<EntryRow>>

    @Query("""
        SELECT e.accountId, e.id, e.feedId, COALESCE(f.title, '') AS feedTitle,
            COALESCE(c.title, '') AS categoryTitle, e.title, e.url, e.author,
            e.publishedAt, '' AS html, '[]' AS parsedBlocksJson, e.read, e.starred, e.readingMinutes,
            NULL AS deliveryState, NULL AS deliveryError
        FROM entries e LEFT JOIN feeds f ON f.accountId=e.accountId AND f.id=e.feedId
        LEFT JOIN categories c ON c.accountId=f.accountId AND c.id=f.categoryId
        WHERE e.accountId = :accountId
        ORDER BY e.publishedAt DESC
    """) abstract fun observeAllEntries(accountId: Long): Flow<List<EntryRow>>

    @Query("""
        SELECT e.accountId, e.id, e.feedId, COALESCE(f.title, '') AS feedTitle,
            COALESCE(c.title, '') AS categoryTitle, e.title, e.url, e.author,
            e.publishedAt, e.html, e.parsedBlocksJson, e.read, e.starred, e.readingMinutes,
            k.state AS deliveryState, k.lastError AS deliveryError
        FROM entries e
        LEFT JOIN feeds f ON f.accountId = e.accountId AND f.id = e.feedId
        LEFT JOIN categories c ON c.accountId = f.accountId AND c.id = f.categoryId
        LEFT JOIN pending_karakeep k ON k.accountId=e.accountId AND k.entryId=e.id
        WHERE e.id = :entryId AND e.accountId = :accountId LIMIT 1
    """)
    abstract fun observeEntry(accountId: Long, entryId: Long): Flow<EntryRow?>

    @Query("SELECT * FROM pending_mutations ORDER BY createdAt") abstract suspend fun pendingMutations(): List<PendingMutationEntity>
    @Query("SELECT * FROM pending_mutations WHERE accountId = :accountId ORDER BY createdAt")
    abstract suspend fun pendingMutationsForAccount(accountId: Long): List<PendingMutationEntity>
    @Query("SELECT * FROM entries WHERE accountId = :accountId AND id IN (:entryIds)") abstract suspend fun entriesById(accountId: Long, entryIds: List<Long>): List<EntryEntity>
    @Query("SELECT * FROM pending_karakeep WHERE state != 'SAVED' ORDER BY createdAt") abstract suspend fun pendingKarakeep(): List<PendingKarakeepEntity>
    @Query("SELECT * FROM pending_karakeep WHERE accountId = :accountId AND state != 'SAVED' ORDER BY createdAt")
    abstract suspend fun pendingKarakeepForAccount(accountId: Long): List<PendingKarakeepEntity>
    @Query("SELECT id FROM pending_karakeep WHERE accountId = :accountId AND canonicalUrl = :canonicalUrl LIMIT 1")
    protected abstract suspend fun karakeepId(accountId: Long, canonicalUrl: String): Long?
    @Query("SELECT * FROM sync_cursors WHERE accountId = :accountId") abstract suspend fun cursor(accountId: Long): SyncCursorEntity?
    @Query("SELECT * FROM sync_cursors WHERE accountId = :accountId") abstract fun observeCursor(accountId: Long): Flow<SyncCursorEntity?>
    @Query("SELECT * FROM sync_state WHERE accountId = :accountId") abstract fun observeSyncState(accountId: Long): Flow<SyncStateEntity?>
    @Query("SELECT COUNT(*) FROM entries WHERE accountId = :accountId") abstract fun observeEntryCount(accountId: Long): Flow<Int>
    @Query("SELECT feedId, COUNT(*) AS total, SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END) AS unread FROM entries WHERE accountId = :accountId GROUP BY feedId")
    abstract fun observeLibraryCounts(accountId: Long): Flow<List<FeedEntryCount>>
    @Query(ENTRY_SUMMARY_QUERY + " WHERE e.accountId = :accountId AND e.feedId = :feedId ORDER BY e.publishedAt DESC")
    abstract fun observeFeedEntries(accountId: Long, feedId: Long): Flow<List<EntryRow>>
    @Query(ENTRY_SUMMARY_QUERY + " WHERE e.accountId = :accountId AND e.read = :read ORDER BY e.publishedAt DESC")
    abstract fun observeReadEntries(accountId: Long, read: Boolean): Flow<List<EntryRow>>
    @Query("SELECT * FROM reader_positions WHERE accountId = :accountId AND entryId = :entryId") abstract fun observePosition(accountId: Long, entryId: Long): Flow<ReaderPositionEntity?>
    @Query("SELECT COUNT(*) FROM pending_mutations WHERE accountId = :accountId AND field = 'READ'")
    abstract fun observePendingReadMutationCount(accountId: Long): Flow<Int>
    @Query("SELECT COUNT(*) FROM pending_mutations WHERE accountId = :accountId AND field = 'STARRED'")
    abstract fun observePendingStarMutationCount(accountId: Long): Flow<Int>
    @Query("SELECT COUNT(*) FROM pending_karakeep WHERE accountId = :accountId AND state != 'SAVED'")
    abstract fun observePendingKarakeepCount(accountId: Long): Flow<Int>
    @Query("SELECT id FROM entries WHERE accountId = :accountId AND read = 0 ORDER BY publishedAt DESC") abstract suspend fun unreadIds(accountId: Long): List<Long>

    @Query("UPDATE entries SET read = :read, lastOpenedAt = CASE WHEN :read THEN :now ELSE lastOpenedAt END WHERE accountId = :accountId AND id = :entryId")
    protected abstract suspend fun updateRead(accountId: Long, entryId: Long, read: Boolean, now: Long): Int
    @Query("UPDATE entries SET starred = :starred WHERE accountId = :accountId AND id = :entryId")
    protected abstract suspend fun updateStar(accountId: Long, entryId: Long, starred: Boolean): Int
    @Query("SELECT id FROM entries WHERE accountId = :accountId AND id IN (:entryIds)")
    protected abstract suspend fun existingEntryIds(accountId: Long, entryIds: List<Long>): List<Long>
    @Query("UPDATE entries SET read = :read, lastOpenedAt = CASE WHEN :read THEN :now ELSE lastOpenedAt END WHERE accountId = :accountId AND id IN (:entryIds)")
    protected abstract suspend fun updateReadMany(accountId: Long, entryIds: List<Long>, read: Boolean, now: Long): Int

    @Transaction
    open suspend fun setRead(accountId: Long, entryId: Long, read: Boolean, now: Long) {
        if (updateRead(accountId, entryId, read, now) == 1) {
            upsertMutation(PendingMutationEntity(accountId, entryId, "READ", read, now))
        }
    }

    @Transaction
    open suspend fun setStarred(accountId: Long, entryId: Long, starred: Boolean, now: Long) {
        if (updateStar(accountId, entryId, starred) == 1) {
            upsertMutation(PendingMutationEntity(accountId, entryId, "STARRED", starred, now))
        }
    }

    @Transaction
    open suspend fun setReadMany(accountId: Long, entryIds: List<Long>, read: Boolean, now: Long) {
        entryIds.chunked(SQLITE_BIND_CHUNK).forEach { candidates ->
            val existing = existingEntryIds(accountId, candidates)
            if (existing.isEmpty()) return@forEach
            updateReadMany(accountId, existing, read, now)
            upsertMutations(existing.map { entryId ->
                PendingMutationEntity(accountId, entryId, "READ", read, now)
            })
        }
    }

    /** Coalesce repeat saves of the same canonical URL into one durable delivery. */
    @Transaction
    open suspend fun queueKarakeep(value: PendingKarakeepEntity) {
        val id = karakeepId(value.accountId, value.canonicalUrl)
        upsertKarakeep(value.copy(id = id ?: value.id))
    }

    /** Remote state must not overwrite a newer, unacknowledged local intent. */
    @Transaction
    open suspend fun mergeRemoteEntries(accountId: Long, remote: List<EntryEntity>) {
        if (remote.isEmpty()) return
        val local = entriesById(accountId, remote.map { it.id }).associateBy { it.id }
        val pending = pendingMutationsForAccount(accountId).groupBy { it.entryId }
        val changed = remote.map { incoming ->
            val existing = local[incoming.id]
            val fields = pending[incoming.id].orEmpty().map { it.field }.toSet()
            incoming.copy(
                read = if ("READ" in fields) existing?.read ?: incoming.read else incoming.read,
                starred = if ("STARRED" in fields) existing?.starred ?: incoming.starred else incoming.starred,
                lastOpenedAt = existing?.lastOpenedAt,
                searchTitle = existing?.searchTitle.orEmpty(),
                searchAuthor = existing?.searchAuthor.orEmpty(),
                searchBody = existing?.searchBody.orEmpty(),
                searchVersion = existing?.searchVersion ?: 0,
            )
        }.filter { incoming -> incoming != local[incoming.id] }
        if (changed.isNotEmpty()) upsertEntries(changed)
    }

    @Transaction
    open suspend fun mergeRemoteCategories(accountId: Long, remote: List<CategoryEntity>) {
        val local = categoriesForAccount(accountId).associateBy { it.id }
        val changed = remote.filter { it != local[it.id] }
        if (changed.isNotEmpty()) upsertCategories(changed)
    }

    @Transaction
    open suspend fun mergeRemoteFeeds(accountId: Long, remote: List<FeedEntity>) {
        val local = feedsForAccount(accountId).associateBy { it.id }
        val changed = remote.filter { incoming ->
            val old = local[incoming.id]
            incoming.copy(searchTitle = old?.searchTitle.orEmpty(), searchVersion = old?.searchVersion ?: 0) != old
        }
        if (changed.isNotEmpty()) upsertFeeds(changed)
    }

    @Query("""
        DELETE FROM entries
        WHERE accountId = :accountId AND id IN (:entryIds)
          AND NOT EXISTS (
              SELECT 1 FROM pending_mutations m
              WHERE m.accountId = entries.accountId AND m.entryId = entries.id
          )
          AND NOT EXISTS (
              SELECT 1 FROM pending_karakeep k
              WHERE k.accountId = entries.accountId AND k.entryId = entries.id AND k.state != 'SAVED'
          )
    """)
    protected abstract suspend fun deleteUnprotectedRemovedEntries(accountId: Long, entryIds: List<Long>): Int

    @Transaction
    open suspend fun mergeRemotePage(accountId: Long, remote: List<EntryEntity>, removedEntryIds: List<Long>) {
        if (removedEntryIds.isNotEmpty()) deleteUnprotectedRemovedEntries(accountId, removedEntryIds)
        mergeRemoteEntries(accountId, remote)
    }

    @Query("DELETE FROM pending_mutations WHERE accountId = :accountId AND entryId IN (:entryIds) AND field = :field AND desiredValue = :desiredValue")
    abstract suspend fun acknowledgeMutations(accountId: Long, entryIds: List<Long>, field: String, desiredValue: Boolean)
    @Query("UPDATE pending_mutations SET attemptCount = attemptCount + 1, lastError = :message WHERE accountId = :accountId AND entryId = :entryId AND field = :field")
    abstract suspend fun recordMutationFailure(accountId: Long, entryId: Long, field: String, message: String)
    @Query("UPDATE pending_karakeep SET state = 'SAVED', completedAt = :completedAt, lastError = NULL WHERE id = :id")
    abstract suspend fun acknowledgeKarakeep(id: Long, completedAt: Long)
    @Query("UPDATE pending_karakeep SET attemptCount = attemptCount + 1, state = :state, lastError = :message WHERE id = :id")
    abstract suspend fun recordKarakeepFailure(id: Long, state: String, message: String)
    @Query("DELETE FROM pending_karakeep WHERE accountId = :accountId AND state = 'SAVED' AND completedAt < :cutoff")
    abstract suspend fun pruneCompletedKarakeep(accountId: Long, cutoff: Long): Int

    @Query("""
        DELETE FROM entries WHERE accountId = :accountId AND id IN (
            SELECT e.id FROM entries e
            WHERE e.accountId = :accountId AND e.read = 1 AND e.starred = 0 AND e.publishedAt < :cutoff
              AND (e.lastOpenedAt IS NULL OR e.lastOpenedAt < :cutoff)
              AND NOT EXISTS (SELECT 1 FROM pending_mutations m WHERE m.accountId = e.accountId AND m.entryId = e.id)
              AND NOT EXISTS (SELECT 1 FROM pending_karakeep k WHERE k.accountId = e.accountId AND k.entryId = e.id)
            ORDER BY e.publishedAt DESC LIMIT -1 OFFSET :keepAtMost
        )
    """)
    abstract suspend fun pruneReadEntries(accountId: Long, cutoff: Long, keepAtMost: Int = 5000): Int

    private companion object {
        /** Safely below the legacy SQLite 999-variable limit used by minSdk devices. */
        const val SQLITE_BIND_CHUNK = 900
    }
}
