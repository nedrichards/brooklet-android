package com.nedrichards.brooklet.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BrookletDao {
    @Query("SELECT * FROM accounts WHERE id = 1") abstract fun observeAccount(): Flow<AccountEntity?>
    @Query("SELECT * FROM accounts WHERE id = 1") abstract suspend fun account(): AccountEntity?
    @Upsert abstract suspend fun upsertAccount(value: AccountEntity)
    @Upsert abstract suspend fun upsertCategories(values: List<CategoryEntity>)
    @Upsert abstract suspend fun upsertFeeds(values: List<FeedEntity>)
    @Upsert abstract suspend fun upsertEntries(values: List<EntryEntity>)
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
    @Query("SELECT * FROM reader_positions WHERE accountId = :accountId AND entryId = :entryId") abstract fun observePosition(accountId: Long, entryId: Long): Flow<ReaderPositionEntity?>
    @Query("SELECT COUNT(*) FROM pending_mutations") abstract fun observePendingMutationCount(): Flow<Int>
    @Query("SELECT id FROM entries WHERE accountId = :accountId AND read = 0 ORDER BY publishedAt DESC") abstract suspend fun unreadIds(accountId: Long): List<Long>

    @Query("UPDATE entries SET read = :read, lastOpenedAt = CASE WHEN :read THEN :now ELSE lastOpenedAt END WHERE accountId = :accountId AND id = :entryId")
    protected abstract suspend fun updateRead(accountId: Long, entryId: Long, read: Boolean, now: Long): Int
    @Query("UPDATE entries SET starred = :starred WHERE accountId = :accountId AND id = :entryId")
    protected abstract suspend fun updateStar(accountId: Long, entryId: Long, starred: Boolean)
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
        updateStar(accountId, entryId, starred)
        upsertMutation(PendingMutationEntity(accountId, entryId, "STARRED", starred, now))
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
        upsertEntries(remote.map { incoming ->
            val existing = local[incoming.id]
            val fields = pending[incoming.id].orEmpty().map { it.field }.toSet()
            incoming.copy(
                read = if ("READ" in fields) existing?.read ?: incoming.read else incoming.read,
                starred = if ("STARRED" in fields) existing?.starred ?: incoming.starred else incoming.starred,
                lastOpenedAt = existing?.lastOpenedAt,
            )
        })
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
