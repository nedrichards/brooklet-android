package com.nedrichards.brooklet.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "accounts")
data class AccountEntity(
    @androidx.room.PrimaryKey val id: Long = 1,
    val serverUrl: String,
    val username: String,
    val tokenCiphertext: ByteArray,
    val tokenIv: ByteArray,
    val serverVersion: String,
    val createdAt: Long,
)

@Entity(tableName = "categories", primaryKeys = ["accountId", "id"], indices = [Index("accountId")])
data class CategoryEntity(val accountId: Long, val id: Long, val title: String)

@Entity(tableName = "feeds", primaryKeys = ["accountId", "id"], indices = [Index("accountId"), Index("categoryId")])
data class FeedEntity(
    val accountId: Long,
    val id: Long,
    val categoryId: Long,
    val title: String,
    val siteUrl: String,
    val feedUrl: String,
)

@Entity(
    tableName = "entries",
    primaryKeys = ["accountId", "id"],
    indices = [Index("accountId"), Index("feedId"), Index("read"), Index("starred"), Index("changedAt")],
)
data class EntryEntity(
    val accountId: Long,
    val id: Long,
    val feedId: Long,
    val title: String,
    val url: String,
    val author: String?,
    val publishedAt: Long,
    val changedAt: Long,
    val html: String,
    val parsedBlocksJson: String,
    val read: Boolean,
    val starred: Boolean,
    val readingMinutes: Int,
    val lastOpenedAt: Long?,
)

@Entity(tableName = "enclosures", primaryKeys = ["accountId", "entryId", "url"], indices = [Index("entryId")])
data class EnclosureEntity(val accountId: Long, val entryId: Long, val url: String, val mimeType: String?, val size: Long?)

@Entity(tableName = "reader_positions", primaryKeys = ["accountId", "entryId"])
data class ReaderPositionEntity(val accountId: Long, val entryId: Long, val firstVisibleBlock: Int, val offsetPx: Int, val updatedAt: Long)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(@androidx.room.PrimaryKey val accountId: Long, val changedAfterEpochSeconds: Long, val lastSuccessfulSyncAt: Long)

@Entity(tableName = "pending_mutations", primaryKeys = ["accountId", "entryId", "field"], indices = [Index("createdAt")])
data class PendingMutationEntity(
    val accountId: Long,
    val entryId: Long,
    val field: String,
    val desiredValue: Boolean,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "pending_karakeep", indices = [Index(value = ["accountId", "canonicalUrl"], unique = true)])
data class PendingKarakeepEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val entryId: Long,
    val canonicalUrl: String,
    val title: String,
    val route: String,
    val state: String,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "karakeep_config")
data class KarakeepConfigEntity(
    @androidx.room.PrimaryKey val accountId: Long,
    val preferredRoute: String,
    val minifluxIntegrationConfirmed: Boolean,
    val directEndpoint: String?,
    val directKeyCiphertext: ByteArray?,
    val directKeyIv: ByteArray?,
)

@Entity(tableName = "storage_policy")
data class StoragePolicyEntity(
    @androidx.room.PrimaryKey val accountId: Long,
    /** Null means retain ordinary read entries indefinitely. */
    val retainReadDays: Int?,
    val keepAtMost: Int = 5000,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @androidx.room.PrimaryKey val accountId: Long,
    val phase: String,
    val processed: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val updatedAt: Long,
)

data class EntryRow(
    val accountId: Long,
    val id: Long,
    val feedId: Long,
    val feedTitle: String,
    val categoryTitle: String,
    val title: String,
    val url: String,
    val author: String?,
    val publishedAt: Long,
    val html: String,
    val parsedBlocksJson: String,
    val read: Boolean,
    val starred: Boolean,
    val readingMinutes: Int,
    val deliveryState: String?,
    val deliveryError: String?,
)
