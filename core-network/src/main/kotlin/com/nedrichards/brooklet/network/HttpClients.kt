package com.nedrichards.brooklet.network

import com.nedrichards.brooklet.model.FailureKind
import com.nedrichards.brooklet.model.MinifluxEntryQuery
import com.nedrichards.brooklet.model.RetryClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val code: Int, message: String, val kind: FailureKind) : Exception(message)
data class ServerIdentity(val user: UserDto, val version: VersionDto)

class MinifluxClient(
    serverUrl: String,
    private val token: String,
    private val http: OkHttpClient = authenticatedHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    allowInsecureForTests: Boolean = false,
) {
    private val base = if (allowInsecureForTests) serverUrl.trim().trimEnd('/').toHttpUrl() else requireHttps(serverUrl)

    suspend fun validate(): ServerIdentity {
        val user = get<UserDto>("v1/me")
        val version = get<VersionDto>("v1/version")
        if (semanticParts(version.version) < semanticParts("2.3.2")) {
            throw ApiException(0, "Miniflux ${version.version} is unsupported; Brooklet requires 2.3.2 or newer", FailureKind.UNSUPPORTED_SERVER)
        }
        return ServerIdentity(user, version)
    }

    suspend fun categories(): List<CategoryDto> = get("v1/categories")
    suspend fun feeds(): List<FeedDto> = get("v1/feeds")
    suspend fun entriesChangedAfter(epochSeconds: Long, limit: Int = 100, offset: Int = 0): EntriesDto =
        entries(MinifluxEntryQuery(changedAfterEpochSeconds = epochSeconds, limit = limit, offset = offset))
    suspend fun entries(query: MinifluxEntryQuery): EntriesDto {
        val requestUrl = base.newBuilder().addPathSegments("v1/entries").apply {
            query.status?.let { addQueryParameter("status", it.wireValue) }
            query.changedAfterEpochSeconds?.let { addQueryParameter("changed_after", it.toString()) }
            addQueryParameter("direction", query.direction)
            addQueryParameter("order", query.order)
            addQueryParameter("limit", query.limit.toString())
            addQueryParameter("offset", query.offset.toString())
        }.build()
        return get(requestUrl)
    }
    suspend fun entry(entryId: Long): EntryDto {
        require(entryId > 0) { "Entry id must be positive" }
        return get("v1/entries/$entryId")
    }
    suspend fun setRead(entryIds: List<Long>, read: Boolean) = put("v1/entries", StatusMutationDto(entryIds, if (read) "read" else "unread"))
    suspend fun setStarred(entryIds: List<Long>, starred: Boolean) = put("v1/entries", StarMutationDto(entryIds, starred))
    suspend fun saveToIntegration(entryId: Long) = postEmpty("v1/entries/$entryId/save")
    suspend fun refreshFeeds() = put<Unit>("v1/feeds/refresh", Unit)
    suspend fun subscribe(feedUrl: String, categoryId: Long?) = post<SubscriptionDto, FeedDto>("v1/feeds", SubscriptionDto(feedUrl, categoryId))
    suspend fun deleteFeed(feedId: Long) = request<Unit>(Request.Builder().url(url("v1/feeds/$feedId")).delete().authenticated().build())

    private suspend inline fun <reified T> get(path: String): T = request(Request.Builder().url(url(path)).get().authenticated().build())
    private suspend inline fun <reified T> get(requestUrl: okhttp3.HttpUrl): T =
        request(Request.Builder().url(requestUrl).get().authenticated().build())
    private suspend inline fun <reified T> put(path: String, value: T) {
        val body = if (value is Unit) ByteArray(0).toRequestBody(JSON) else json.encodeToString(value).toRequestBody(JSON)
        request<Unit>(Request.Builder().url(url(path)).put(body).authenticated().build())
    }
    private suspend inline fun <reified I, reified O> post(path: String, value: I): O =
        request(Request.Builder().url(url(path)).post(json.encodeToString(value).toRequestBody(JSON)).authenticated().build())
    private suspend fun postEmpty(path: String) = request<Unit>(Request.Builder().url(url(path)).post(ByteArray(0).toRequestBody()).authenticated().build())

    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> request(request: Request): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "Miniflux request failed (${response.code})", RetryClassifier.classify(response.code))
            if (T::class == Unit::class) Unit as T else response.body.byteStream().use { json.decodeFromStream<T>(it) }
        }
    }
    private fun Request.Builder.authenticated() = header("X-Auth-Token", token).header("Accept", "application/json")
    private fun url(path: String) = base.newBuilder().addPathSegments(path.substringBefore('?')).apply {
        path.substringAfter('?', "").split('&').filter { it.contains('=') }.forEach { pair ->
            addQueryParameter(pair.substringBefore('='), pair.substringAfter('='))
        }
    }.build()
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun requireHttps(value: String) = value.trim().trimEnd('/').toHttpUrl().also {
            require(it.isHttps) { "Service URL must use HTTPS" }
            require(it.username.isEmpty() && it.password.isEmpty()) { "Service URL must not include credentials" }
        }
        private fun semanticParts(value: String) = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }.let { parts -> parts[0] * 1_000_000 + parts.getOrElse(1) { 0 } * 1_000 + parts.getOrElse(2) { 0 } }
    }
}

class KarakeepClient(
    endpoint: String,
    private val apiKey: String,
    private val http: OkHttpClient = authenticatedHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val url = MinifluxClient.requireHttps(endpoint)
    suspend fun save(urlToSave: String, title: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(json.encodeToString(KarakeepBookmarkRequest(url = urlToSave, title = title)).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 409) throw ApiException(response.code, "Karakeep request failed (${response.code})", RetryClassifier.classify(response.code))
        }
    }
}

/**
 * API credentials are sent as request headers. Redirects are deliberately not
 * followed: a configured service can be reached at its final HTTPS URL, but a
 * response must never be able to move a credential-bearing request elsewhere.
 */
private val sharedAuthenticatedHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

internal fun authenticatedHttpClient(): OkHttpClient = sharedAuthenticatedHttpClient
