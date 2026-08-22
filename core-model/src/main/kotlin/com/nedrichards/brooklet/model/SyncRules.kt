package com.nedrichards.brooklet.model

import java.net.URI

enum class FailureKind { RETRYABLE, AUTHENTICATION, UNSUPPORTED_SERVER, CERTIFICATE, MALFORMED_REQUEST }
object RetryClassifier {
    fun classify(httpCode: Int? = null, cause: Throwable? = null): FailureKind = when {
        cause?.javaClass?.simpleName?.contains("SSL", ignoreCase = true) == true -> FailureKind.CERTIFICATE
        httpCode == 401 || httpCode == 403 -> FailureKind.AUTHENTICATION
        httpCode == 400 || httpCode == 404 || httpCode == 422 -> FailureKind.MALFORMED_REQUEST
        httpCode == 408 || httpCode == 429 || httpCode != null && httpCode >= 500 -> FailureKind.RETRYABLE
        cause != null -> FailureKind.RETRYABLE
        else -> FailureKind.MALFORMED_REQUEST
    }
}
fun canonicalUrl(value: String): String {
    val uri = URI(value.trim())
    val scheme = uri.scheme?.lowercase() ?: "https"
    val host = uri.host?.lowercase() ?: return value.trim()
    val port = if (uri.port == -1 || scheme == "https" && uri.port == 443 || scheme == "http" && uri.port == 80) -1 else uri.port
    val path = uri.path?.ifBlank { "/" } ?: "/"
    return URI(scheme, uri.userInfo, host, port, path.trimEnd('/').ifBlank { "/" }, uri.query, null).toString()
}

fun incrementalStart(lastSuccessfulChangedAt: Long?, overlapSeconds: Long = 60): Long =
    ((lastSuccessfulChangedAt ?: 0) - overlapSeconds).coerceAtLeast(0)

data class RetentionCandidate(
    val read: Boolean,
    val starred: Boolean,
    val pendingMutation: Boolean,
    val pendingKarakeep: Boolean,
    val publishedAt: Long,
)

fun RetentionCandidate.canPrune(cutoff: Long): Boolean =
    read && !starred && !pendingMutation && !pendingKarakeep && publishedAt < cutoff
