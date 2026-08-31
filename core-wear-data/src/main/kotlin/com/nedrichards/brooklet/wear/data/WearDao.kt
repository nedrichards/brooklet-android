package com.nedrichards.brooklet.wear.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nedrichards.brooklet.model.WatchCachePolicy
import kotlinx.coroutines.flow.Flow

@Dao
abstract class WearDao {
    @Query("SELECT * FROM wear_account WHERE id = 1") abstract fun observeAccount(): Flow<WearAccountEntity?>
    @Query("SELECT * FROM wear_account WHERE id = 1") abstract suspend fun account(): WearAccountEntity?
    @Upsert abstract suspend fun upsertAccount(value: WearAccountEntity)
    @Upsert abstract suspend fun upsertEntries(values: List<WearEntryEntity>)
    @Upsert abstract suspend fun upsertSyncState(value: WearSyncStateEntity)
    @Upsert abstract suspend fun upsertMutation(value: WearMutationEntity)
    @Upsert abstract suspend fun upsertKarakeep(value: WearKarakeepEntity)
    @Upsert abstract suspend fun upsertReaderPosition(value: WearReaderPositionEntity)

    @Query("SELECT * FROM wear_sync_state WHERE id = 1") abstract fun observeSyncState(): Flow<WearSyncStateEntity?>
    @Query("SELECT * FROM wear_sync_state WHERE id = 1") abstract suspend fun syncState(): WearSyncStateEntity?
    @Query("SELECT COUNT(*) FROM wear_entries WHERE read = 0") abstract fun observeUnreadCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM wear_entries WHERE read = 0") abstract suspend fun unreadCount(): Int
    @Query("SELECT COALESCE(SUM(bodyBytes), 0) FROM wear_entries") abstract fun observeBodyBytes(): Flow<Long>
    @Query("SELECT COALESCE(SUM(bodyBytes), 0) FROM wear_entries") abstract suspend fun bodyBytes(): Long
    @Query("SELECT id, feedTitle, title, publishedAt, read, starred, bodyBytes > 0 AS hasBody FROM wear_entries WHERE read = 0 ORDER BY publishedAt DESC LIMIT 100")
    abstract fun observeInbox(): Flow<List<WearEntrySummary>>
    @Query("SELECT id, feedTitle, title, publishedAt, read, starred, bodyBytes > 0 AS hasBody FROM wear_entries WHERE read = 0 ORDER BY publishedAt DESC LIMIT :limit")
    abstract suspend fun inboxSnapshot(limit: Int): List<WearEntrySummary>
    @Query("SELECT * FROM wear_entries WHERE id = :entryId LIMIT 1") abstract fun observeEntry(entryId: Long): Flow<WearEntryEntity?>
    @Query("SELECT * FROM wear_entries WHERE id = :entryId LIMIT 1") abstract suspend fun entry(entryId: Long): WearEntryEntity?
    @Query("SELECT * FROM wear_reader_positions WHERE entryId = :entryId LIMIT 1") abstract suspend fun readerPosition(entryId: Long): WearReaderPositionEntity?
    @Query("SELECT * FROM wear_entries WHERE id IN (:entryIds)") protected abstract suspend fun entriesById(entryIds: List<Long>): List<WearEntryEntity>
    @Query("SELECT * FROM wear_mutations ORDER BY createdAt") abstract suspend fun pendingMutations(): List<WearMutationEntity>
    @Query("SELECT * FROM wear_karakeep WHERE entryId = :entryId LIMIT 1") abstract fun observeKarakeep(entryId: Long): Flow<WearKarakeepEntity?>
    @Query("SELECT * FROM wear_karakeep WHERE state != 'SAVED' ORDER BY createdAt") abstract suspend fun pendingKarakeep(): List<WearKarakeepEntity>

    @Query("UPDATE wear_entries SET read = :read, lastOpenedAt = CASE WHEN :read THEN :now ELSE lastOpenedAt END WHERE id = :entryId")
    protected abstract suspend fun updateRead(entryId: Long, read: Boolean, now: Long): Int
    @Query("UPDATE wear_entries SET starred = :starred WHERE id = :entryId") protected abstract suspend fun updateStarred(entryId: Long, starred: Boolean): Int
    @Query("UPDATE wear_entries SET bodyLastAccessedAt = :now, lastOpenedAt = :now WHERE id = :entryId") abstract suspend fun touchBody(entryId: Long, now: Long)

    @Transaction
    open suspend fun setRead(entryId: Long, read: Boolean, now: Long) {
        if (updateRead(entryId, read, now) == 1) upsertMutation(WearMutationEntity(entryId, "READ", read, now))
    }

    @Transaction
    open suspend fun setStarred(entryId: Long, starred: Boolean, now: Long) {
        if (updateStarred(entryId, starred) == 1) upsertMutation(WearMutationEntity(entryId, "STARRED", starred, now))
    }

    @Transaction
    open suspend fun queueKarakeep(entryId: Long, now: Long) {
        if (entry(entryId) != null) upsertKarakeep(WearKarakeepEntity(entryId, "QUEUED", now))
    }

    /** Incoming server state cannot overwrite a durable, unacknowledged local intent. */
    @Transaction
    open suspend fun mergeRemoteEntries(remote: List<WearEntryEntity>) {
        if (remote.isEmpty()) return
        val local = entriesById(remote.map { it.id }).associateBy { it.id }
        val pending = pendingMutations().groupBy { it.entryId }
        upsertEntries(remote.map { incoming ->
            val existing = local[incoming.id]
            val fields = pending[incoming.id].orEmpty().map { it.field }.toSet()
            incoming.copy(
                read = if ("READ" in fields) existing?.read ?: incoming.read else incoming.read,
                starred = if ("STARRED" in fields) existing?.starred ?: incoming.starred else incoming.starred,
                blocksJson = incoming.blocksJson.ifEmpty { existing?.blocksJson.orEmpty() },
                bodyBytes = if (incoming.blocksJson.isEmpty()) existing?.bodyBytes ?: 0 else incoming.bodyBytes,
                bodyTruncated = if (incoming.blocksJson.isEmpty()) existing?.bodyTruncated ?: false else incoming.bodyTruncated,
                bodyLastAccessedAt = existing?.bodyLastAccessedAt ?: incoming.bodyLastAccessedAt,
                lastOpenedAt = existing?.lastOpenedAt,
            )
        })
    }

    @Query("""
        DELETE FROM wear_entries
        WHERE id IN (:entryIds)
          AND NOT EXISTS (SELECT 1 FROM wear_mutations m WHERE m.entryId = wear_entries.id)
          AND NOT EXISTS (SELECT 1 FROM wear_karakeep k WHERE k.entryId = wear_entries.id AND k.state != 'SAVED')
    """)
    protected abstract suspend fun deleteUnprotectedRemovedEntries(entryIds: List<Long>): Int
    @Query("DELETE FROM wear_reader_positions WHERE entryId NOT IN (SELECT id FROM wear_entries)")
    protected abstract suspend fun deleteOrphanReaderPositions(): Int

    @Transaction
    open suspend fun mergeRemotePage(remote: List<WearEntryEntity>, removedEntryIds: List<Long>) {
        if (removedEntryIds.isNotEmpty()) deleteUnprotectedRemovedEntries(removedEntryIds)
        mergeRemoteEntries(remote)
        deleteOrphanReaderPositions()
    }

    @Query("DELETE FROM wear_mutations WHERE entryId IN (:entryIds) AND field = :field AND desiredValue = :desiredValue")
    abstract suspend fun acknowledgeMutations(entryIds: List<Long>, field: String, desiredValue: Boolean)
    @Query("UPDATE wear_mutations SET attemptCount = attemptCount + 1, lastError = :message WHERE entryId = :entryId AND field = :field")
    abstract suspend fun recordMutationFailure(entryId: Long, field: String, message: String)
    @Query("UPDATE wear_karakeep SET state = 'SAVED', lastError = NULL WHERE entryId = :entryId") abstract suspend fun acknowledgeKarakeep(entryId: Long)
    @Query("UPDATE wear_karakeep SET attemptCount = attemptCount + 1, state = 'NEEDS_ATTENTION', lastError = :message WHERE entryId = :entryId")
    abstract suspend fun recordKarakeepFailure(entryId: Long, message: String)

    @Query("SELECT id FROM wear_entries WHERE bodyBytes > 0 AND id != :activeEntryId AND NOT EXISTS (SELECT 1 FROM wear_mutations m WHERE m.entryId = wear_entries.id) AND NOT EXISTS (SELECT 1 FROM wear_karakeep k WHERE k.entryId = wear_entries.id AND k.state != 'SAVED') ORDER BY COALESCE(bodyLastAccessedAt, 0), publishedAt")
    protected abstract suspend fun evictableBodyIds(activeEntryId: Long): List<Long>
    @Query("UPDATE wear_entries SET blocksJson = '', bodyBytes = 0, bodyTruncated = 0, bodyLastAccessedAt = NULL WHERE id = :entryId")
    protected abstract suspend fun evictBody(entryId: Long)
    @Query("DELETE FROM wear_entries WHERE read = 0 AND id NOT IN (SELECT id FROM wear_entries WHERE read = 0 ORDER BY publishedAt DESC LIMIT :keep) AND id != :activeEntryId AND NOT EXISTS (SELECT 1 FROM wear_mutations m WHERE m.entryId = wear_entries.id) AND NOT EXISTS (SELECT 1 FROM wear_karakeep k WHERE k.entryId = wear_entries.id AND k.state != 'SAVED')")
    protected abstract suspend fun pruneExcessUnread(keep: Int, activeEntryId: Long): Int
    @Query("DELETE FROM wear_entries WHERE read = 1 AND id != :activeEntryId AND NOT EXISTS (SELECT 1 FROM wear_mutations m WHERE m.entryId = wear_entries.id) AND NOT EXISTS (SELECT 1 FROM wear_karakeep k WHERE k.entryId = wear_entries.id AND k.state != 'SAVED') AND ((lastOpenedAt IS NULL OR lastOpenedAt < :cutoff) OR id NOT IN (SELECT id FROM wear_entries WHERE read = 1 ORDER BY COALESCE(lastOpenedAt, 0) DESC LIMIT :keep))")
    protected abstract suspend fun pruneRead(cutoff: Long, keep: Int, activeEntryId: Long): Int

    @Transaction
    open suspend fun enforceCachePolicy(policy: WatchCachePolicy, now: Long, activeEntryId: Long = -1): CacheEnforcementResult {
        var bytes = bodyBytes()
        var bodies = 0
        if (bytes > policy.maxBodyBytes) {
            for (id in evictableBodyIds(activeEntryId)) {
                val before = entry(id)?.bodyBytes ?: 0
                evictBody(id)
                bytes -= before
                bodies++
                if (bytes <= policy.maxBodyBytes) break
            }
        }
        val unreadRows = pruneExcessUnread(policy.maxUnreadEntries, activeEntryId)
        val readRows = pruneRead(now - policy.recentReadLifetimeMillis, policy.maxRecentReadEntries, activeEntryId)
        deleteOrphanReaderPositions()
        bytes = bodyBytes()
        return CacheEnforcementResult(bodies, unreadRows, readRows, bytes)
    }

    @Transaction
    open suspend fun replaceAccountAndData(account: WearAccountEntity) {
        clearAllTables()
        upsertAccount(account)
    }

    @Transaction
    open suspend fun disconnectAndDeleteAll() = clearAllTables()

    @Query("DELETE FROM wear_mutations") protected abstract suspend fun clearMutations()
    @Query("DELETE FROM wear_karakeep") protected abstract suspend fun clearKarakeep()
    @Query("DELETE FROM wear_entries") protected abstract suspend fun clearEntries()
    @Query("DELETE FROM wear_sync_state") protected abstract suspend fun clearSyncState()
    @Query("DELETE FROM wear_reader_positions") protected abstract suspend fun clearReaderPositions()
    @Query("DELETE FROM wear_account") protected abstract suspend fun clearAccount()

    protected open suspend fun clearAllTables() {
        clearMutations()
        clearKarakeep()
        clearEntries()
        clearReaderPositions()
        clearSyncState()
        clearAccount()
    }
}

data class CacheEnforcementResult(
    val evictedBodies: Int,
    val prunedUnreadRows: Int,
    val prunedReadRows: Int,
    val remainingBodyBytes: Long,
)
