package com.nedrichards.brooklet.testing

import com.nedrichards.brooklet.model.DocumentBlock
import com.nedrichards.brooklet.model.Entry

class FakeClock(var now: Long = 1_700_000_000_000) { fun advance(milliseconds: Long) { now += milliseconds }; fun time() = now }

fun entryFixture(id: Long = 1, read: Boolean = false, starred: Boolean = false) = Entry(
    id, 1, 10, "Brooklet Gazette", "News", "A compact headline for testing", "https://example.com/$id",
    "N. Richards", 1_700_000_000_000, "<p>Cached article text.</p>", listOf(DocumentBlock.Paragraph("Cached article text.")),
    read, starred, 3,
)
