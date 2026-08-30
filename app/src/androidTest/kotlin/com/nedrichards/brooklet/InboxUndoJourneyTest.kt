package com.nedrichards.brooklet

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nedrichards.brooklet.database.BrookletDatabase
import com.nedrichards.brooklet.database.EntryEntity
import com.nedrichards.brooklet.designsystem.BrookletTheme
import com.nedrichards.brooklet.model.DocumentBlock
import com.nedrichards.brooklet.model.HtmlDocumentParser
import com.nedrichards.brooklet.sync.EntryRepository
import com.nedrichards.brooklet.sync.SyncActivity
import com.nedrichards.brooklet.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class InboxUndoJourneyTest {
    private lateinit var database: BrookletDatabase

    // Compose must dispose its Room collectors before the in-memory database
    // closes. A lower-order rule wraps the Compose rule and cleans up last.
    @get:Rule(order = 0)
    val databaseCleanup = object : ExternalResource() {
        override fun after() {
            if (this@InboxUndoJourneyTest::database.isInitialized) database.close()
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private lateinit var scheduler: RecordingScheduler
    private lateinit var repository: EntryRepository
    private lateinit var undoViewModel: InboxUndoViewModel
    private lateinit var savedState: SavedStateHandle
    private val application: BrookletApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BrookletApplication

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BrookletDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = RecordingScheduler()
        repository = EntryRepository(database.dao(), scheduler) { 1234L }
        savedState = SavedStateHandle()
        undoViewModel = InboxUndoViewModel(ACCOUNT_ID, repository, savedState)
    }

    @Test fun swipeUndoRestoresRoomMutationInboxRowAndShowsConfirmation() {
        seed(entry(1, "Undo me", 2))
        showInbox()

        compose.onNodeWithText("Undo me").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { isRead(1) }
        compose.onNodeWithText("Undo").assertIsDisplayed().performClick()

        compose.waitUntil(5_000) { !isRead(1) }
        compose.onNodeWithText("Undo me").assertIsDisplayed()
        compose.onNodeWithText("Restored “Undo me” as unread").assertIsDisplayed()
        assertEquals(false, pendingReadValues()[1])
        assertEquals(2, scheduler.immediateRequests)
    }

    @Test fun rapidSwipesBecomeOneUndoableBatchWithNoStaleSnackbarAssociation() {
        seed(entry(1, "First", 2), entry(2, "Second", 1))
        showInbox()

        compose.onNodeWithText("First").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { isRead(1) }
        compose.onNodeWithText("Second").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { isRead(2) }

        compose.onNodeWithText("Marked 2 articles read").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { !isRead(1) && !isRead(2) }

        compose.onNodeWithText("First").assertIsDisplayed()
        compose.onNodeWithText("Second").assertIsDisplayed()
        assertEquals(mapOf(1L to false, 2L to false), pendingReadValues())
    }

    @Test fun repeatedSwipeUndoAtDateBoundaryKeepsViewportAndDoesNotReplayDismissal() {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1_000L
        seed(
            *(1L..20L).map { entry(it, "Today $it", now + it) }.toTypedArray(),
            entry(50, "Date boundary", now - day),
            *(21L..30L).map { entry(it, "Older $it", now - (2 * day) + it) }.toTypedArray(),
        )
        showInbox()
        compose.onNodeWithTag("entry-list").performScrollToIndex(19)
        compose.onNodeWithText("Date boundary").assertIsDisplayed()

        compose.onNodeWithText("Date boundary").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { isRead(50) }
        val anchorBeforeUndo = compose.onNodeWithText("Today 2")
            .assertIsDisplayed().getUnclippedBoundsInRoot().top.value
        compose.onNodeWithText("Undo").performClick()

        compose.waitUntil(5_000) { !isRead(50) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val anchorAfterUndo = compose.onNodeWithText("Today 2")
            .assertIsDisplayed().getUnclippedBoundsInRoot().top.value
        assertTrue(
            "Viewport anchor moved from $anchorBeforeUndo to $anchorAfterUndo",
            kotlin.math.abs(anchorAfterUndo - anchorBeforeUndo) < 0.5f,
        )
        assertEquals(false, isRead(50))
        assertEquals(false, pendingReadValues()[50])
        assertEquals(2, scheduler.immediateRequests)

        compose.onNodeWithTag("entry-list").performScrollToIndex(19)
        compose.onNodeWithText("Date boundary").assertIsDisplayed()
        compose.onNodeWithText("Date boundary").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { isRead(50) }
        val secondAnchorBeforeUndo = compose.onNodeWithText("Today 2")
            .assertIsDisplayed().getUnclippedBoundsInRoot().top.value
        compose.onNodeWithText("Undo").performClick()

        compose.waitUntil(5_000) { !isRead(50) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val secondAnchorAfterUndo = compose.onNodeWithText("Today 2")
            .assertIsDisplayed().getUnclippedBoundsInRoot().top.value
        assertTrue(
            "Viewport anchor moved from $secondAnchorBeforeUndo to $secondAnchorAfterUndo",
            kotlin.math.abs(secondAnchorAfterUndo - secondAnchorBeforeUndo) < 0.5f,
        )
        assertEquals(false, isRead(50))
        assertEquals(false, pendingReadValues()[50])
        assertEquals(4, scheduler.immediateRequests)
    }

    @Test fun pendingUndoSurvivesLossAndRecreationOfItsSnackbarHost() {
        seed(entry(1, "Lifecycle", 1))
        var hostVisible by mutableStateOf(true)
        compose.setContent {
            BrookletTheme(dynamicColor = false) {
                if (hostVisible) {
                    MainShellContent(application, ACCOUNT_ID, repository, scheduler, undoViewModel)
                } else {
                    // Retain a root while deliberately removing the snackbar
                    // host, so the Compose test harness can drive recreation.
                    Box(Modifier.fillMaxSize().testTag("host-absent"))
                }
            }
        }

        compose.onNodeWithText("Lifecycle").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { isRead(1) }
        compose.runOnIdle { hostVisible = false }
        compose.onNodeWithTag("host-absent").assertIsDisplayed()
        compose.runOnIdle { hostVisible = true }

        compose.onNodeWithText("Undo").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !isRead(1) }
        compose.onNodeWithText("Lifecycle").assertIsDisplayed()
    }

    @Test fun pendingBatchCanBeRecreatedFromCompactSavedState() {
        seed(entry(1, "One", 2), entry(2, "Two", 1))
        showInbox()

        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Mark all read").performClick()
        compose.waitUntil(5_000) {
            isRead(1) && isRead(2) &&
                savedState.get<LongArray>("inbox-undo-entry-ids")?.toSet() == setOf(1L, 2L)
        }

        val recreated = InboxUndoViewModel(ACCOUNT_ID, repository, savedState)
        assertEquals(setOf(1L, 2L), recreated.uiState.value.pending?.entries?.keys)
        assertEquals(
            setOf("inbox-undo-generation", "inbox-undo-entry-ids", "inbox-undo-retry"),
            savedState.keys(),
        )
    }

    @Test fun markAllUndoRestoresExactlyTheEntriesChangedByTheAction() {
        seed(entry(1, "Unread one", 2), entry(2, "Unread two", 1), entry(3, "Already read", 0, read = true))
        showInbox()

        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Mark all read").performClick()
        compose.waitUntil(5_000) { isRead(1) && isRead(2) }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { !isRead(1) && !isRead(2) }

        assertTrue(isRead(3))
        assertEquals(mapOf(1L to false, 2L to false), pendingReadValues().filterKeys { it != 3L })
    }

    @Test fun accessibilityMarkReadUsesTheSameDurableUndoPath() {
        seed(entry(1, "Accessible action", 1))
        showInbox()

        val actions: List<CustomAccessibilityAction> = compose.onNodeWithText("Accessible action")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.single { it.label == "Mark read" }.action())
        }
        compose.waitUntil(5_000) { isRead(1) }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { !isRead(1) }

        compose.onNodeWithText("Accessible action").assertIsDisplayed()
        assertEquals(false, pendingReadValues()[1])
    }

    @Test fun richArticleSpansCannotOverrideThemeColours() {
        val rendered = themeSafeArticleText(
            """<span style="color:black;background:black"><font color="#000000">Dark text</font></span>""",
        )

        assertEquals("Dark text", rendered.text)
        assertTrue(rendered.spanStyles.none { it.item.color != Color.Unspecified })
        assertTrue(rendered.spanStyles.none { it.item.background != Color.Unspecified })
    }

    @Test fun richArticleLinksBecomeComposeLinkAnnotations() {
        val rendered = themeSafeArticleText(
            html = """Read <a href="/next">the next article</a>.""",
            articleUrl = "https://example.com/articles/current",
            linkColor = Color.Cyan,
        )

        val link = rendered.getLinkAnnotations(0, rendered.length).single().item as LinkAnnotation.Url
        assertEquals("https://example.com/next", link.url)
        assertEquals(Color.Cyan, link.styles?.style?.color)
    }

    @Test fun phoronixStyleUnwrappedLinksBecomeComposeLinkAnnotations() {
        val paragraph = HtmlDocumentParser.parse(
            """<div class="content">Intro.<br>Among the changes, the <a href="/news/driver">LED driver</a> was merged.</div>""",
        )[1] as DocumentBlock.Paragraph

        val rendered = themeSafeArticleText(
            html = paragraph.html!!,
            articleUrl = "https://example.com/news/current",
            linkColor = Color.Cyan,
        )

        assertEquals("Among the changes, the LED driver was merged.", rendered.text)
        val link = rendered.getLinkAnnotations(0, rendered.length).single().item as LinkAnnotation.Url
        assertEquals("https://example.com/news/driver", link.url)
        assertEquals(Color.Cyan, link.styles?.style?.color)
    }

    @Test fun productionUndoViewModelFactoryReceivesSavedStateCreationExtras() {
        compose.setContent {
            BrookletTheme(dynamicColor = false) {
                MainShell(application, 999L)
            }
        }

        compose.onNodeWithContentDescription("More actions").assertIsDisplayed()
    }

    @Test fun overflowSearchStaysScopedToInbox() {
        seed(
            entry(1, "Needle unread", 2),
            entry(2, "Needle read", 1, read = true),
        )
        showInbox()

        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("article-search-field").performTextInput("Needle")

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Needle unread").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Needle unread").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Needle read").fetchSemanticsNodes().isEmpty())
    }

    @Test fun librarySearchExposesReadAndSavedFiltersOnlyWhileSearching() {
        seed(
            entry(1, "Needle unread", 2),
            entry(2, "Needle saved", 1, read = true, starred = true),
        )
        showInbox()

        compose.onNodeWithTag("destination-library").performClick()
        compose.onNodeWithContentDescription("Search Library").performClick()
        compose.onNodeWithTag("article-search-field").performTextInput("Needle")

        compose.onNodeWithTag("search-filter-unread").assertIsDisplayed()
        compose.onNodeWithTag("search-filter-read").performClick()
        compose.onNodeWithTag("search-filter-saved").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Needle saved").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Needle saved").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Needle unread").fetchSemanticsNodes().isEmpty())
    }

    @Test fun backgroundInboxInsertionShowsNewArticleNoticeAwayFromTop() {
        seed(*(1L..30L).map { entry(it, "Entry $it", it) }.toTypedArray())
        showInbox()
        compose.onNodeWithTag("entry-list").performScrollToIndex(15)

        seed(entry(31, "New background article", 31))

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("1 new article").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("1 new article").assertIsDisplayed()
    }

    @Test fun reselectingInboxJumpsToTopAndGoBackRestoresTheArticlePosition() {
        seed(*(1L..30L).map { entry(it, "Entry $it", it) }.toTypedArray())
        showInbox()
        compose.onNodeWithTag("entry-list").performScrollToIndex(15)
        compose.onNodeWithText("Entry 15").assertIsDisplayed()

        compose.onNodeWithTag("destination-inbox").performClick()

        compose.onNodeWithText("Jumped to top").assertIsDisplayed()
        compose.onNodeWithText("Entry 30").assertIsDisplayed()
        compose.onNodeWithText("Go back").performClick()
        compose.onNodeWithText("Entry 15").assertIsDisplayed()
    }

    @Test fun visibleScrollToTopButtonJumpsToTop() {
        seed(*(1L..30L).map { entry(it, "Entry $it", it) }.toTypedArray())
        showInbox()
        compose.onNodeWithTag("entry-list").performScrollToIndex(15)

        compose.onNodeWithContentDescription("Scroll to top").assertIsDisplayed().performClick()

        compose.onNodeWithText("Jumped to top").assertIsDisplayed()
        compose.onNodeWithText("Entry 30").assertIsDisplayed()
    }

    @Test fun scrollToTopButtonFadesAfterInactivity() {
        seed(*(1L..30L).map { entry(it, "Entry $it", it) }.toTypedArray())
        showInbox()
        compose.onNodeWithTag("entry-list").performScrollToIndex(15)
        compose.onNodeWithContentDescription("Scroll to top").assertIsDisplayed()

        compose.waitUntil(6_000) {
            compose.onAllNodesWithContentDescription("Scroll to top").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun undoSnackbarTakesPriorityOverScrollToTopButton() {
        seed(*(1L..30L).map { entry(it, "Entry $it", it) }.toTypedArray())
        showInbox()
        compose.onNodeWithTag("entry-list").performScrollToIndex(15)
        compose.onNodeWithContentDescription("Scroll to top").assertIsDisplayed()

        compose.onNodeWithText("Entry 15").performTouchInput { swipeLeft() }

        compose.onNodeWithText("Undo").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Scroll to top").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun browsingTopAppBarKeepsTitleAndActionsInOneStableRow() {
        seed(*(1L..30L).map { entry(it, "Entry $it", it) }.toTypedArray())
        showInbox()
        val initialBounds = compose.onNodeWithTag("main-top-app-bar").getUnclippedBoundsInRoot()
        val titleBounds = compose.onNodeWithTag("main-top-app-bar-title").getUnclippedBoundsInRoot()
        val actionsBounds = compose.onNodeWithContentDescription("More actions").getUnclippedBoundsInRoot()

        assertTrue(titleBounds.top < actionsBounds.bottom && actionsBounds.top < titleBounds.bottom)

        compose.onNodeWithTag("entry-list").performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals(initialBounds, compose.onNodeWithTag("main-top-app-bar").getUnclippedBoundsInRoot())
    }

    @Test fun inboxDateHeadingAppearsOnlyAfterLeavingTheInitialPosition() {
        val now = System.currentTimeMillis()
        seed(*(1L..30L).map { entry(it, "Entry $it", now + it) }.toTypedArray())
        showInbox()

        assertTrue(compose.onAllNodesWithText("Today").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("entry-list").performTouchInput { swipeUp() }
        compose.onNodeWithText("Today").assertIsDisplayed()
    }

    private fun showInbox() {
        compose.setContent {
            BrookletTheme(dynamicColor = false) {
                MainShellContent(application, ACCOUNT_ID, repository, scheduler, undoViewModel)
            }
        }
        compose.waitForIdle()
    }

    private fun seed(vararg entries: EntryEntity) = runBlocking {
        database.dao().upsertEntries(entries.toList())
    }

    private fun isRead(id: Long): Boolean = runBlocking {
        database.dao().entriesById(ACCOUNT_ID, listOf(id)).single().read
    }

    private fun pendingReadValues(): Map<Long, Boolean> = runBlocking {
        database.dao().pendingMutations()
            .filter { it.accountId == ACCOUNT_ID && it.field == "READ" }
            .associate { it.entryId to it.desiredValue }
    }

    private fun entry(
        id: Long,
        title: String,
        order: Long,
        read: Boolean = false,
        starred: Boolean = false,
    ) = EntryEntity(
        accountId = ACCOUNT_ID,
        id = id,
        feedId = 1,
        title = title,
        url = "https://example.com/$id",
        author = null,
        publishedAt = order,
        changedAt = order,
        html = "<p>Article $id</p>",
        parsedBlocksJson = "[]",
        read = read,
        starred = starred,
        readingMinutes = 1,
        lastOpenedAt = null,
    )

    private class RecordingScheduler : SyncScheduler {
        override val activity = MutableStateFlow(SyncActivity())
        var immediateRequests = 0
        override fun enqueueActionDelivery() { immediateRequests += 1 }
        override fun enqueueForegroundSync() = Unit
        override fun enqueueUserSync() = Unit
        override fun enqueueManualRefresh() = Unit
        override fun cancelImmediate() = Unit
        override fun cancelAll() = Unit
        override fun ensurePeriodic() = Unit
    }

    private companion object {
        const val ACCOUNT_ID = 1L
    }
}
