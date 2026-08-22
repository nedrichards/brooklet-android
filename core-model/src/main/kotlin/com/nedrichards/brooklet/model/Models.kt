package com.nedrichards.brooklet.model

import kotlinx.serialization.Serializable

typealias AccountId = Long
typealias EntryId = Long

@Serializable data class Category(val id: Long, val title: String)
@Serializable data class Feed(val id: Long, val categoryId: Long, val title: String, val siteUrl: String, val feedUrl: String)

data class Entry(
    val id: EntryId,
    val accountId: AccountId,
    val feedId: Long,
    val feedTitle: String,
    val categoryTitle: String,
    val title: String,
    val url: String,
    val author: String?,
    val publishedAt: Long,
    val html: String,
    val blocks: List<DocumentBlock>,
    val read: Boolean,
    val starred: Boolean,
    val readingMinutes: Int,
    val deliveryState: DeliveryState? = null,
    val deliveryError: String? = null,
)

@Serializable sealed interface DocumentBlock {
    @Serializable data class Heading(val level: Int, val text: String) : DocumentBlock
    @Serializable data class Paragraph(val text: String) : DocumentBlock
    @Serializable data class Quote(val text: String) : DocumentBlock
    @Serializable data class Code(val text: String) : DocumentBlock
    @Serializable data class ListItem(val text: String, val ordered: Boolean) : DocumentBlock
    @Serializable data class Image(val url: String, val description: String?) : DocumentBlock
}

enum class MutationField { READ, STARRED }
enum class DeliveryState { QUEUED, SENDING, SAVED, NEEDS_ATTENTION }
enum class KarakeepRoute { MINIFLUX, DIRECT }
data class ReaderPosition(val entryId: EntryId, val firstVisibleBlock: Int, val offsetPx: Int)
data class SyncStatus(val running: Boolean = false, val queuedMutations: Int = 0, val lastSuccessfulSyncAt: Long? = null, val error: String? = null)
