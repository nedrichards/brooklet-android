package com.nedrichards.brooklet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareSubscriptionTest {
    @Test fun `accepts the first shared HTTP URL`() {
        assertEquals(
            "https://example.com/feed",
            sharedHttpUrl("android.intent.action.SEND", "text/plain", "Try https://example.com/feed."),
        )
    }

    @Test fun `requires a text share intent`() {
        assertNull(sharedHttpUrl("android.intent.action.VIEW", "text/plain", "https://example.com/feed"))
        assertNull(sharedHttpUrl("android.intent.action.SEND", "image/png", "https://example.com/feed"))
    }

    @Test fun `rejects URLs with embedded credentials`() {
        assertNull(sharedHttpUrl("android.intent.action.SEND", "text/plain", "https://name:secret@example.com/feed"))
    }
}
