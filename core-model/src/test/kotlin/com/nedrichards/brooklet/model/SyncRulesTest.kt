package com.nedrichards.brooklet.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRulesTest {
    @Test fun `temporary response codes retry`() { listOf(408, 429, 500, 503).forEach { assertEquals(FailureKind.RETRYABLE, RetryClassifier.classify(it)) } }
    @Test fun `authentication does not retry`() { assertEquals(FailureKind.AUTHENTICATION, RetryClassifier.classify(401)) }
    @Test fun `canonical URLs discard fragments and normalize origin`() { assertEquals("https://example.com/story?a=1", canonicalUrl("HTTPS://Example.COM:443/story/?a=1#comments")) }
    @Test fun `incremental cursor overlaps without becoming negative`() {
        assertEquals(940, incrementalStart(1000))
        assertEquals(0, incrementalStart(30))
    }
    @Test fun `pending and valuable entries are protected from pruning`() {
        assertEquals(false, RetentionCandidate(true, true, false, false, 1).canPrune(10))
        assertEquals(false, RetentionCandidate(true, false, true, false, 1).canPrune(10))
        assertEquals(true, RetentionCandidate(true, false, false, false, 1).canPrune(10))
    }
}
