package com.nedrichards.brooklet.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches transient reader images. The client has no disk cache. */
class ArticleImageClient(
    private val http: OkHttpClient = untrustedImageHttpClient(),
) {
    suspend fun load(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(safeImageUrl(url)).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Image request failed (${response.code})")
            val body = response.body
            if (body.contentLength() > MAX_IMAGE_BYTES) throw IOException("Image is too large")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            body.byteStream().use { input ->
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) throw IOException("Image is too large")
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    }
}

/**
 * Article HTML is untrusted input. Images must not turn Brooklet into a probe
 * for services on the device's local network. The resolver's returned address
 * list is what OkHttp connects to, so validating it also covers DNS rebinding.
 */
internal fun untrustedImageHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .dns(PublicInternetDns)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal fun safeImageUrl(value: String): HttpUrl = MinifluxClient.requireHttps(value).also { url ->
    url.host.takeIf(::looksLikeIpLiteral)?.let { host ->
        val address = InetAddress.getByName(host)
        require(!address.isPrivateOrLocal()) { "Article images cannot use local network addresses" }
    }
}

private object PublicInternetDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
        require(addresses.isNotEmpty() && addresses.none(InetAddress::isPrivateOrLocal)) {
            "Article images cannot use local network addresses"
        }
    }
}

private fun looksLikeIpLiteral(host: String): Boolean = host.contains(':') || host.all { it.isDigit() || it == '.' }

private fun InetAddress.isPrivateOrLocal(): Boolean = when (this) {
    is Inet4Address -> {
        val bytes = address.map(Byte::toInt).map { it and 0xff }
        isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress ||
            (bytes[0] == 100 && bytes[1] in 64..127) || // Carrier-grade NAT
            (bytes[0] == 192 && bytes[1] == 0 && bytes[2] == 0) || // IETF protocol assignments
            (bytes[0] >= 224)
    }
    is Inet6Address -> {
        val first = address[0].toInt() and 0xff
        isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress ||
            (first and 0xfe) == 0xfc // Unique local addresses fc00::/7
    }
    else -> isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress
}
