package com.nedrichards.brooklet.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MinifluxClientTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun `validation authenticates and accepts 2_3_2`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":1,"username":"ned"}""").setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody("""{"version":"2.3.2"}""").setHeader("Content-Type", "application/json"))
        val identity = client().validate()
        assertEquals("ned", identity.user.username)
        assertEquals("secret", server.takeRequest().getHeader("X-Auth-Token"))
        assertEquals("/v1/version", server.takeRequest().path)
    }

    @Test fun `batched status and stars use update entries contract`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        client().setRead(listOf(4, 9), true)
        client().setRead(listOf(4), false)
        client().setStarred(listOf(9), true)
        assertEquals("{\"entry_ids\":[4,9],\"status\":\"read\"}", server.takeRequest().body.readUtf8())
        assertEquals("{\"entry_ids\":[4],\"status\":\"unread\"}", server.takeRequest().body.readUtf8())
        assertEquals("{\"entry_ids\":[9],\"starred\":true}", server.takeRequest().body.readUtf8())
    }

    @Test fun `third party save is POST`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        client().saveToIntegration(42)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/entries/42/save", request.path)
    }

    @Test fun `subscription creates a feed from the shared URL`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":7,"title":"Example","site_url":"https://example.com","feed_url":"https://example.com/feed.xml"}""").setHeader("Content-Type", "application/json"))
        client().subscribe("https://example.com", categoryId = null)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/feeds", request.path)
        assertEquals("{\"feed_url\":\"https://example.com\"}", request.body.readUtf8())
    }

    @Test fun `rate limit is classified retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        val error = runCatching { client().categories() }.exceptionOrNull()
        assertTrue(error is ApiException && error.kind == com.nedrichards.brooklet.model.FailureKind.RETRYABLE)
    }

    @Test fun `credential requests do not follow redirects`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))

        val error = runCatching { client().categories() }.exceptionOrNull()

        assertTrue(error is ApiException && error.code == 302)
        assertEquals(1, server.requestCount)
        assertEquals("secret", server.takeRequest().getHeader("X-Auth-Token"))
    }

    @Test fun `configured service URLs reject embedded credentials`() {
        val error = runCatching { MinifluxClient.requireHttps("https://name:secret@example.com") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("must not include credentials"))
    }

    private fun client() = MinifluxClient(server.url("/").toString(), "secret", allowInsecureForTests = true)
}
