package com.nedrichards.brooklet.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class UserDto(val id: Long, val username: String, @SerialName("is_admin") val isAdmin: Boolean = false)
@Serializable data class VersionDto(val version: String, val commit: String? = null)
@Serializable data class CategoryDto(val id: Long, val title: String)
@Serializable data class FeedDto(
    val id: Long,
    @SerialName("category") val category: CategoryDto? = null,
    val title: String,
    @SerialName("site_url") val siteUrl: String = "",
    @SerialName("feed_url") val feedUrl: String = "",
)
@Serializable data class EntryDto(
    val id: Long,
    @SerialName("feed_id") val feedId: Long,
    val title: String,
    val url: String,
    val author: String? = null,
    @SerialName("published_at") val publishedAt: String,
    @SerialName("changed_at") val changedAt: String,
    val content: String = "",
    val status: String,
    val starred: Boolean,
    @SerialName("reading_time") val readingTime: Int = 0,
    val feed: FeedDto? = null,
)
@Serializable data class EntriesDto(val total: Int, val entries: List<EntryDto>)
@Serializable data class StatusMutationDto(@SerialName("entry_ids") val entryIds: List<Long>, val status: String)
@Serializable data class StarMutationDto(@SerialName("entry_ids") val entryIds: List<Long>, val starred: Boolean)
@Serializable data class SubscriptionDto(@SerialName("feed_url") val feedUrl: String, @SerialName("category_id") val categoryId: Long? = null)
@Serializable data class KarakeepBookmarkRequest(val type: String = "link", val url: String, val title: String, val source: String = "mobile")
