package com.nedrichards.brooklet.wear.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "wear_account")
data class WearAccountEntity(
    @PrimaryKey val id: Long = 1,
    val serverUrl: String,
    val username: String,
    val tokenCiphertext: ByteArray,
    val tokenIv: ByteArray,
    val serverVersion: String,
    val accountGeneration: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "wear_entries",
    indices = [
        Index(value = ["read", "publishedAt"]),
        Index(value = ["bodyLastAccessedAt"]),
    ],
)
data class WearEntryEntity(
    @PrimaryKey val id: Long,
    val feedId: Long,
    val feedTitle: String,
    val title: String,
    val url: String,
    val author: String?,
    val publishedAt: Long,
    val changedAt: Long,
    val blocksJson: String,
    val bodyBytes: Int,
    val bodyTruncated: Boolean,
    val bodyLastAccessedAt: Long?,
    val read: Boolean,
    val starred: Boolean,
    val readingMinutes: Int,
    val lastOpenedAt: Long?,
)

@Entity(tableName = "wear_mutations", primaryKeys = ["entryId", "field"], indices = [Index("createdAt")])
data class WearMutationEntity(
    val entryId: Long,
    val field: String,
    val desiredValue: Boolean,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "wear_karakeep")
data class WearKarakeepEntity(
    @PrimaryKey val entryId: Long,
    val state: String,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "wear_sync_state")
data class WearSyncStateEntity(
    @PrimaryKey val id: Long = 1,
    val changedAfterEpochSeconds: Long,
    val bootstrapHighWaterEpochSeconds: Long?,
    val bootstrapped: Boolean,
    val phase: String,
    val lastSuccessfulSyncAt: Long?,
    val error: String?,
    val updatedAt: Long,
)

@Entity(tableName = "wear_reader_positions")
data class WearReaderPositionEntity(
    @PrimaryKey val entryId: Long,
    val anchorItemIndex: Int,
    val anchorItemScrollOffset: Int,
    val updatedAt: Long,
)

data class WearEntrySummary(
    val id: Long,
    val feedTitle: String,
    val title: String,
    val publishedAt: Long,
    val read: Boolean,
    val starred: Boolean,
    val hasBody: Boolean,
)
