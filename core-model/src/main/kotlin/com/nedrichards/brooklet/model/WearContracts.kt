package com.nedrichards.brooklet.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val WEAR_PROVISIONING_PATH = "/brooklet/provision/v1"
const val WEAR_PROVISIONING_REQUEST_PATH = "/brooklet/provision/request/v1"
const val WEAR_PROVISIONING_ACK_PATH = "/brooklet/provision/ack/v1"
const val WEAR_OPEN_ENTRY_PATH = "/brooklet/open-entry/v1"

@Serializable
data class WearProvisioningRequestV1(
    val nonce: String,
    val watchNodeId: String,
    val issuedAtEpochMillis: Long,
)

@Serializable
data class WearProvisioningV1(
    val nonce: String,
    val minifluxUrl: String,
    val token: String,
    val accountGeneration: Long,
)

@Serializable
data class WearProvisioningAcknowledgementV1(
    val nonce: String,
    val accountGeneration: Long,
    val success: Boolean,
    val failure: WearProvisioningFailure? = null,
)

@Serializable
enum class WearProvisioningFailure {
    INVALID_MESSAGE,
    NONCE_MISMATCH,
    EXPIRED,
    INVALID_TOKEN,
    AUTHENTICATION,
    UNSUPPORTED_SERVER,
    CERTIFICATE,
    PHONE_UNAVAILABLE,
    WATCH_STORAGE,
    STALE_ACCOUNT_GENERATION,
    UNKNOWN,
}

enum class WearAccountGenerationDecision { REPLACE, REJECT_STALE }

fun decideWearAccountGeneration(
    currentGeneration: Long?,
    currentServerUrl: String?,
    incomingGeneration: Long,
    incomingServerUrl: String,
): WearAccountGenerationDecision {
    if (currentGeneration == null) return WearAccountGenerationDecision.REPLACE
    if (incomingGeneration < currentGeneration) return WearAccountGenerationDecision.REJECT_STALE
    if (incomingGeneration > currentGeneration) return WearAccountGenerationDecision.REPLACE
    return if (currentServerUrl?.trim()?.trimEnd('/') != incomingServerUrl.trim().trimEnd('/')) {
        WearAccountGenerationDecision.REJECT_STALE
    } else {
        // A fresh nonce with the same account generation is an intentional
        // reconnect. Duplicate delivery of one nonce is handled separately by
        // consuming the provisioning request exactly once.
        WearAccountGenerationDecision.REPLACE
    }
}

fun WearProvisioningV1.validateFor(
    request: WearProvisioningRequestV1,
    nowEpochMillis: Long,
    lifetimeMillis: Long = 2 * 60 * 1000L,
) {
    require(nonce == request.nonce) { "Provisioning nonce does not match the request" }
    require(request.nonce.length >= 16) { "Provisioning nonce is too short" }
    require(nowEpochMillis - request.issuedAtEpochMillis in 0..lifetimeMillis) {
        "Provisioning request has expired"
    }
    require(accountGeneration > 0) { "Account generation must be positive" }
    require(token.isNotBlank()) { "Miniflux token must not be blank" }
}

@Serializable
enum class MinifluxEntryStatus(val wireValue: String) {
    UNREAD("unread"),
    READ("read"),
    REMOVED("removed");

    companion object {
        fun fromWire(value: String): MinifluxEntryStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
data class MinifluxEntryQuery(
    val status: MinifluxEntryStatus? = null,
    val changedAfterEpochSeconds: Long? = null,
    val order: String = "changed_at",
    val direction: String = "desc",
    val limit: Int = 100,
    val offset: Int = 0,
) {
    init {
        require(limit in 1..1000) { "Entry query limit must be between 1 and 1000" }
        require(offset >= 0) { "Entry query offset must not be negative" }
        require(changedAfterEpochSeconds == null || changedAfterEpochSeconds >= 0) {
            "changed_after must not be negative"
        }
        require(order in setOf("id", "status", "published_at", "category_title", "category_id", "title", "author", "share_code", "changed_at", "feed_title", "feed_id")) {
            "Unsupported Miniflux entry ordering"
        }
        require(direction == "asc" || direction == "desc") { "Entry direction must be asc or desc" }
    }
}

@Serializable
data class WatchDocument(
    val blocks: List<DocumentBlock>,
    val byteSize: Int,
    val truncated: Boolean,
)

object WatchDocumentNormalizer {
    const val MAX_BODY_BYTES = 256 * 1024
    private val json = Json { encodeDefaults = true }

    fun normalize(html: String, maxBytes: Int = MAX_BODY_BYTES): WatchDocument {
        require(maxBytes > 0)
        val normalized = HtmlDocumentParser.parse(html).mapNotNull { block ->
            when (block) {
                is DocumentBlock.Image -> block.description
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { DocumentBlock.Caption(it) }
                is DocumentBlock.Heading -> block.copy(html = null)
                is DocumentBlock.Paragraph -> block.copy(html = null)
                is DocumentBlock.Quote -> block.copy(html = null)
                is DocumentBlock.ListItem -> block.copy(html = null)
                is DocumentBlock.Caption -> block.copy(html = null)
                else -> block
            }
        }
        val retained = ArrayList<DocumentBlock>(normalized.size)
        var bytes = 0
        for (block in normalized) {
            val blockBytes = json.encodeToString(block).encodeToByteArray().size
            // blocksJson stores a JSON array, so account for its brackets and
            // separators rather than only the individually encoded blocks.
            val candidateBytes = if (retained.isEmpty()) blockBytes + 2 else bytes + blockBytes + 1
            if (candidateBytes > maxBytes) break
            retained += block
            bytes = candidateBytes
        }
        return WatchDocument(retained, bytes, retained.size < normalized.size)
    }

    fun encodedSize(blocks: List<DocumentBlock>): Int =
        if (blocks.isEmpty()) 0 else json.encodeToString(blocks).encodeToByteArray().size
}

data class WatchCachePolicy(
    val maxUnreadEntries: Int = 100,
    val maxRecentReadEntries: Int = 10,
    val recentReadLifetimeMillis: Long = 24 * 60 * 60 * 1000L,
    val maxBodyBytes: Long = 25L * 1024 * 1024,
    val maxArticleBodyBytes: Int = WatchDocumentNormalizer.MAX_BODY_BYTES,
)

enum class WearSyncIntent { ACTION_DELIVERY, FOREGROUND, USER_SYNC, PERIODIC, BOOTSTRAP }
enum class WearSyncActivityState { IDLE, QUEUED, RUNNING, RETRYING, RECONNECT_REQUIRED }
