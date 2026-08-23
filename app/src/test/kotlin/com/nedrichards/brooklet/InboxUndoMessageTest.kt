package com.nedrichards.brooklet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxUndoMessageTest {
    @Test
    fun singleEntryMessageKeepsAShortTitle() {
        val pending = PendingReadUndo(1, mapOf(7L to "A short headline"))

        assertEquals("Marked “A short headline” read", pending.message)
    }

    @Test
    fun singleEntryMessageCompactsAndTruncatesALongTitle() {
        val title = "A very long headline\nwith irregular    spacing that would otherwise make the snackbar far too tall"
        val compactTitle = title.snackbarTitle()

        assertEquals(48, compactTitle.length)
        assertTrue(compactTitle.endsWith("…"))
        assertTrue(!compactTitle.contains('\n'))
        assertEquals("Marked “$compactTitle” read", PendingReadUndo(1, mapOf(7L to title)).message)
    }
}
