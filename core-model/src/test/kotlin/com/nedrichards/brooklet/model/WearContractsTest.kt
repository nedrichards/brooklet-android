package com.nedrichards.brooklet.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WearContractsTest {
    private val wearJson = Json { encodeDefaults = true }
    @Test fun `provisioning is nonce bound and expires`() {
        val request = WearProvisioningRequestV1("0123456789abcdef", "watch", 1_000)
        WearProvisioningV1(request.nonce, "https://miniflux.example", "token", 4)
            .validateFor(request, 60_000)

        assertThrows(IllegalArgumentException::class.java) {
            WearProvisioningV1("fedcba9876543210", "https://miniflux.example", "token", 4)
                .validateFor(request, 60_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WearProvisioningV1(request.nonce, "https://miniflux.example", "token", 4)
                .validateFor(request, 200_000)
        }
    }

    @Test fun `provisioning payload round trips with versioned fields`() {
        val payload = WearProvisioningV1("0123456789abcdef", "https://miniflux.example", "secret", 9)
        assertEquals(payload, Json.decodeFromString<WearProvisioningV1>(Json.encodeToString(payload)))
    }

    @Test fun `account generation rejects stale payloads and permits an intentional reconnect`() {
        assertEquals(
            WearAccountGenerationDecision.REPLACE,
            decideWearAccountGeneration(null, null, 10, "https://miniflux.example"),
        )
        assertEquals(
            WearAccountGenerationDecision.REPLACE,
            decideWearAccountGeneration(10, "https://miniflux.example/", 10, "https://miniflux.example"),
        )
        assertEquals(
            WearAccountGenerationDecision.REJECT_STALE,
            decideWearAccountGeneration(10, "https://miniflux.example", 9, "https://miniflux.example"),
        )
        assertEquals(
            WearAccountGenerationDecision.REPLACE,
            decideWearAccountGeneration(10, "https://miniflux.example", 11, "https://other.example"),
        )
        assertEquals(
            WearAccountGenerationDecision.REJECT_STALE,
            decideWearAccountGeneration(10, "https://miniflux.example", 10, "https://other.example"),
        )
    }

    @Test fun `watch normalizer omits images but retains useful alt text`() {
        val result = WatchDocumentNormalizer.normalize(
            "<h2>Heading</h2><img src='https://example.com/image.jpg' alt='Chart trends upwards'><p>Body</p>",
        )

        assertFalse(result.blocks.any { it is DocumentBlock.Image })
        assertTrue(result.blocks.any { it is DocumentBlock.Caption && it.text == "Chart trends upwards" })
        assertEquals(result.byteSize, WatchDocumentNormalizer.encodedSize(result.blocks))
    }

    @Test fun `watch normalizer truncates only between blocks`() {
        val first = DocumentBlock.Paragraph("first")
        val firstSize = WatchDocumentNormalizer.encodedSize(listOf(first))
        val result = WatchDocumentNormalizer.normalize("<p>first</p><p>${"x".repeat(500)}</p>", firstSize)

        assertEquals(listOf(first), result.blocks)
        assertTrue(result.truncated)
        assertEquals(firstSize, result.byteSize)
    }

    @Test fun `watch byte accounting matches the exact stored json array`() {
        val blocks: List<DocumentBlock> = listOf(DocumentBlock.Paragraph("one"), DocumentBlock.Paragraph("two"))
        val exactBytes = wearJson.encodeToString(blocks).encodeToByteArray().size

        assertEquals(exactBytes, WatchDocumentNormalizer.encodedSize(blocks))
        assertEquals(0, WatchDocumentNormalizer.encodedSize(emptyList()))
        assertEquals(exactBytes, WatchDocumentNormalizer.normalize("<p>one</p><p>two</p>").byteSize)
    }

    @Test fun `miniflux entry statuses are parsed without treating removed as unread`() {
        assertEquals(MinifluxEntryStatus.UNREAD, MinifluxEntryStatus.fromWire("unread"))
        assertEquals(MinifluxEntryStatus.READ, MinifluxEntryStatus.fromWire("read"))
        assertEquals(MinifluxEntryStatus.REMOVED, MinifluxEntryStatus.fromWire("removed"))
        assertEquals(null, MinifluxEntryStatus.fromWire("unexpected"))
    }

    @Test fun `watch normalizer retains link targets without original html`() {
        val result = WatchDocumentNormalizer.normalize("<p>Read <a href='https://example.com/more'>more</a>.</p>")
        val paragraph = result.blocks.single() as DocumentBlock.Paragraph
        assertEquals(null, paragraph.html)
        assertEquals(listOf(DocumentLink("more", "https://example.com/more")), paragraph.links)
    }
}
