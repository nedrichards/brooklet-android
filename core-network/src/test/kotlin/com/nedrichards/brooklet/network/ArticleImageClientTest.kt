package com.nedrichards.brooklet.network

import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleImageClientTest {
    @Test fun `article images require HTTPS`() {
        val error = runCatching { safeImageUrl("http://example.com/image.jpg") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("HTTPS"))
    }

    @Test fun `article images reject local IPv4 literals`() {
        val error = runCatching { safeImageUrl("https://127.0.0.1/image.jpg") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("local network"))
    }

    @Test fun `article images reject unique local IPv6 literals`() {
        val error = runCatching { safeImageUrl("https://[fd00::1]/image.jpg") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("local network"))
    }
}
