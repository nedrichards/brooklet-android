package com.nedrichards.brooklet

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.nedrichards.brooklet.designsystem.BrookletTheme
import com.nedrichards.brooklet.model.DocumentBlock
import com.nedrichards.brooklet.model.Entry
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InboxReaderJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inboxSwipeThenRestoreDoesNotReplayRead() {
        val entry = entry("Inbox headline")
        var entries by mutableStateOf(listOf(entry))
        var readRequests = 0

        compose.setContent {
            BrookletTheme(dynamicColor = false) {
                EntryList(
                    entries = entries,
                    emptyText = "All caught up",
                    padding = PaddingValues(),
                    triage = true,
                    isRefreshing = false,
                    onRefresh = {},
                    onRead = {
                        readRequests += 1
                        entries = emptyList()
                    },
                    onOpen = {},
                )
            }
        }

        compose.onNodeWithText("Inbox headline").performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 5_000) { readRequests == 1 }
        compose.onNodeWithText("All caught up").fetchSemanticsNode()

        compose.runOnIdle { entries = listOf(entry) }
        compose.onNodeWithText("Inbox headline").fetchSemanticsNode()
        compose.runOnIdle { assertEquals(1, readRequests) }
    }

    @Test fun readerHeaderPrioritisesPrimaryActionsAndRunsOverflowActions() {
        var keepUnread = 0
        var openBrowser = 0
        var save = 0
        var karakeep = 0
        var share = 0

        compose.setContent {
            BrookletTheme(dynamicColor = false) {
                ReaderTopAppBar(
                    entry = entry("Reader headline"),
                    onBack = {},
                    onKeepUnread = { keepUnread += 1 },
                    onOpenInBrowser = { openBrowser += 1 },
                    onSave = { save += 1 },
                    onSendToKarakeep = { karakeep += 1 },
                    onShareUrl = { share += 1 },
                )
            }
        }

        compose.onNodeWithContentDescription("Keep unread").performClick()
        compose.onNodeWithContentDescription("Open in browser").performClick()
        compose.onNodeWithContentDescription("More article actions").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onAllNodesWithText("Save").assertCountEquals(0)
        compose.onNodeWithContentDescription("More article actions").performClick()
        compose.onNodeWithText("Send to Karakeep").performClick()
        compose.onNodeWithContentDescription("More article actions").performClick()
        compose.onNodeWithText("Share URL").performClick()

        compose.runOnIdle {
            assertEquals(1, keepUnread)
            assertEquals(1, openBrowser)
            assertEquals(1, save)
            assertEquals(1, karakeep)
            assertEquals(1, share)
        }
    }

    @Test fun keepingReaderEntryUnreadDoesNotTriggerMarkReadAgain() {
        var read by mutableStateOf<Boolean?>(null)
        var markReadRequests = 0

        compose.setContent {
            MarkEntryReadOnOpen(entryId = 1, loadedEntryId = if (read == null) null else 1, read = read) {
                markReadRequests += 1
                read = true
            }
        }

        compose.runOnIdle { read = false }
        compose.waitUntil(timeoutMillis = 5_000) { markReadRequests == 1 }
        compose.runOnIdle { read = false }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(1, markReadRequests) }
    }

    @Test fun readerNavigationDoesNotApplyStaleEntryStateToNewEntry() {
        var loadedEntryId by mutableStateOf<Long?>(1)
        var read by mutableStateOf<Boolean?>(true)
        var markReadRequests = 0

        compose.setContent {
            MarkEntryReadOnOpen(entryId = 2, loadedEntryId = loadedEntryId, read = read) {
                markReadRequests += 1
            }
        }

        compose.runOnIdle { read = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, markReadRequests) }

        compose.runOnIdle { loadedEntryId = 2 }
        compose.waitUntil(timeoutMillis = 5_000) { markReadRequests == 1 }
    }

    @Test fun sharedUrlShowsSubscriptionConfirmationAndCanBeDismissed() {
        var dismissed = false
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BrookletApplication

        compose.setContent {
            BrookletTheme(dynamicColor = false) {
                SubscribeScreen(
                    application = application,
                    accountId = 1,
                    url = "https://example.com/feed.xml",
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Subscribe to this feed?").fetchSemanticsNode()
        compose.onNodeWithText("https://example.com/feed.xml").fetchSemanticsNode()
        compose.onNodeWithText("Not now").performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }

    private fun entry(title: String) = Entry(
        id = 1,
        accountId = 1,
        feedId = 1,
        feedTitle = "Test feed",
        categoryTitle = "Test category",
        title = title,
        url = "https://example.com/article",
        author = null,
        publishedAt = 0,
        html = "<p>Article text</p>",
        blocks = listOf(DocumentBlock.Paragraph("Article text")),
        read = false,
        starred = false,
        readingMinutes = 1,
    )
}
