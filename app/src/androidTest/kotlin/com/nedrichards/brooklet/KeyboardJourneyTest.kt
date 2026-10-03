package com.nedrichards.brooklet

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nedrichards.brooklet.database.BrookletDatabase
import com.nedrichards.brooklet.database.EntryEntity
import com.nedrichards.brooklet.designsystem.BrookletTheme
import com.nedrichards.brooklet.model.Entry
import com.nedrichards.brooklet.sync.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource

@OptIn(ExperimentalTestApi::class)
class KeyboardJourneyTest {
    private lateinit var database: BrookletDatabase
    private var newestSeededEntryId: Long? = null
    @get:Rule(order = 0) val cleanup = object : ExternalResource() {
        override fun after() { if (::database.isInitialized) database.close() }
    }
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var repository: EntryRepository
    private lateinit var undo: InboxUndoViewModel
    private val scheduler = object : SyncScheduler {
        override val activity = MutableStateFlow(SyncActivity())
        override fun enqueueActionDelivery() = Unit
        override fun enqueueForegroundSync() = Unit
        override fun enqueueUserSync() = Unit
        override fun enqueueManualRefresh() = Unit
        override fun cancelImmediate() = Unit
        override fun cancelAll() = Unit
        override fun ensurePeriodic() = Unit
    }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrookletDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = EntryRepository(database.dao(), scheduler) { 1234L }
        undo = InboxUndoViewModel(1, repository, SavedStateHandle())
    }

    @Test fun arrowsFromHeaderSkipDatesRevealOffscreenAndNeverMarkRead() {
        seed(40)
        shell()
        key(Key.DirectionDown)
        selected(40)
        key(Key.DirectionDown)
        selected(39)
        key(Key.DirectionUp)
        selected(40)
        key(Key.MoveEnd)
        selected(1)
        compose.onNodeWithTag("entry-1").assertIsDisplayed()
        key(Key.MoveHome)
        selected(40)
        compose.onNodeWithContentDescription("More actions").performSemanticsAction(SemanticsActions.RequestFocus)
        key(Key.DirectionDown)
        selected(39)
        assertEquals(0, runBlocking { database.dao().pendingMutations().size })
    }

    @Test fun readUndoAndSyncInsertionPreserveArticleIdentity() {
        seed(6)
        shell()
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        selected(5)
        runBlocking { database.dao().upsertEntries(listOf(row(10))) }
        compose.waitForIdle()
        selected(5)
        key(Key.R)
        compose.waitUntil(5_000) { read(5) }
        selected(4)
        key(Key.U)
        compose.waitUntil(5_000) { !read(5) }
        selected(5)
        assertEquals(false, runBlocking { database.dao().pendingMutations().first { it.entryId == 5L && it.field == "READ" }.desiredValue })
    }

    @Test fun undoAfterMovingDoesNotPullTheCursorBackToTheRestoredArticle() {
        seed(6)
        shell()
        key(Key.DirectionDown)
        key(Key.R)
        compose.waitUntil(5_000) { read(6) }
        selected(5)
        key(Key.DirectionDown)
        selected(4)
        key(Key.U)
        compose.waitUntil(5_000) { !read(6) }
        selected(4)
    }

    @Test fun enterOpensAndEscapeReturnsToNeighborWithoutResettingWorkspace() {
        seed(6)
        shell()
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        key(Key.Enter)
        readerDisplayed()
        compose.waitUntil(5_000) { read(5) }
        key(Key.Escape)
        compose.onNodeWithTag("entry-list").assertIsDisplayed()
        selected(4)
    }

    @Test fun touchOpenClearsKeyboardCursorBeforeReturningToInbox() {
        seed(6)
        shell()
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        selected(5)
        compose.onNodeWithTag("entry-5").performTouchInput { click() }
        readerDisplayed()
        compose.waitUntil(5_000) { read(5) }
        compose.onNodeWithContentDescription("Back").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("entry-4").assertIsNotSelected()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .filter(hasTestTag("entry-6") or hasTestTag("entry-4") or hasTestTag("entry-3") or hasTestTag("entry-2") or hasTestTag("entry-1"))
            .assertCountEquals(0)
        key(Key.DirectionDown)
        selected(6)
    }

    @Test fun searchEditingKeepsLettersArrowsAndUndoAwayFromArticleCommands() {
        seed(6)
        shell()
        chord(Key.CtrlLeft, Key.F)
        compose.onNodeWithTag("article-search-field").performTextInput("Headline")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-6").fetchSemanticsNodes().isNotEmpty() }
        key(Key.R)
        key(Key.U)
        key(Key.DirectionDown)
        assertEquals(0, runBlocking { database.dao().pendingMutations().size })
        key(Key.F1)
        compose.onNodeWithText("Keyboard shortcuts").assertIsDisplayed()
        key(Key.Escape)
        compose.onNodeWithTag("article-search-field").assertIsDisplayed()
    }

    @Test fun tabReachesControlsAndHelpDoesNotMoveSelection() {
        seed(6)
        shell()
        key(Key.DirectionDown)
        key(Key.F1)
        compose.onNodeWithText("Keyboard shortcuts").assertIsDisplayed()
        key(Key.Escape)
        selected(6)
        key(Key.Tab)
        assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun mouseSingleClickSelectsDoubleClickOpensAndRightClickOffersActions() {
        seed(6)
        shell()
        compose.onNodeWithTag("entry-5").performMouseInput { click() }
        selected(5)
        assertFalse(read(5))
        compose.onNodeWithTag("entry-4").performMouseInput { click(button = MouseButton.Secondary) }
        compose.onAllNodes(isPopup()).assertCountEquals(1)
        compose.onNodeWithTag("article-action-4-READ").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { read(4) }
        compose.onNodeWithTag("entry-5").performMouseInput { doubleClick() }
        readerDisplayed()
    }

    @Test fun mouseClicksOnDifferentHeadlinesCannotAccidentallyOpenAnArticle() {
        seed(6)
        shell()
        compose.onNodeWithTag("entry-6").performMouseInput { click() }
        compose.onNodeWithTag("entry-5").performMouseInput { click() }
        compose.onNodeWithTag("entry-6").performMouseInput { click() }
        selected(6)
        compose.onNodeWithTag("reader-content").assertDoesNotExist()
        assertFalse(read(6))
        assertFalse(read(5))
    }

    @Test fun libraryBrowseAndDestinationsAreKeyboardAccessible() {
        seed(6)
        shell()
        chord(Key.CtrlLeft, Key.Three)
        key(Key.DirectionDown)
        key(Key.Enter)
        compose.onNodeWithTag("entry-list").assertIsDisplayed()
        key(Key.DirectionDown)
        selected(6)
        key(Key.Escape)
        compose.onNodeWithText("Browse").assertIsDisplayed()
        chord(Key.CtrlLeft, Key.One)
        compose.onNodeWithTag("entry-list").assertIsDisplayed()
    }

    @Test fun readerPagesAndPreviousNextRespectBackConvention() {
        seedEntries((1L..3L).map { row(it).copy(html = (1..80).joinToString("") { n -> "<p>Paragraph $n with enough text to scroll the reader.</p>" }) })
        shell()
        key(Key.DirectionDown)
        key(Key.Enter)
        readerDisplayed()
        key(Key.MoveEnd)
        compose.onNodeWithText("Next").assertIsDisplayed()
        key(Key.MoveHome)
        compose.onNodeWithText("Headline 3").assertIsDisplayed()
        key(Key.Spacebar)
        compose.onNodeWithText("Headline 3").assertIsNotDisplayed()
        chord(Key.AltLeft, Key.PageDown)
        readerDisplayed("Headline 2")
        chord(Key.AltLeft, Key.DirectionLeft)
        compose.onNodeWithTag("entry-list").assertIsDisplayed()
    }

    @Test fun heldReadKeyDoesNotDismissMoreThanOneArticle() {
        seed(6)
        shell()
        key(Key.DirectionDown)
        selected(6)
        compose.onNodeWithTag("keyboard-list-focus").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("keyboard-list-focus").assertIsFocused()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_R, 0))
        compose.waitUntil(5_000) { read(6) }
        instrumentation.sendKeySync(android.view.KeyEvent(now, now + 100, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_R, 1))
        instrumentation.sendKeySync(android.view.KeyEvent(now, now + 200, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_R, 0))
        compose.waitForIdle()
        assertFalse(read(5))
    }

    @Test fun readerRImmediatelyKeepsUnreadAndRestoresTheSourceSelection() {
        seed(1)
        shell()
        key(Key.DirectionDown)
        key(Key.Enter)
        readerDisplayed()
        key(Key.R)
        compose.waitUntil(5_000) { !read(1) && compose.onAllNodesWithTag("entry-1").fetchSemanticsNodes().isNotEmpty() }
        selected(1)
        assertEquals(false, runBlocking { database.dao().pendingMutations().first { it.entryId == 1L && it.field == "READ" }.desiredValue })
    }

    @Test fun savedReadToggleAndCtrlUndoUseTheSameRoomActions() {
        seed(6)
        shell()
        key(Key.DirectionDown)
        key(Key.S)
        compose.waitUntil(5_000) { runBlocking { database.dao().entriesById(1, listOf(6)).single().starred } }
        chord(Key.CtrlLeft, Key.Two)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-6").fetchSemanticsNodes().isNotEmpty() }
        key(Key.DirectionDown)
        selected(6)
        key(Key.R)
        compose.waitUntil(5_000) { read(6) }
        chord(Key.CtrlLeft, Key.Z)
        compose.waitUntil(5_000) { !read(6) }
        selected(6)
        chord(Key.CtrlLeft, Key.One)
        selected(6)
    }

    @Test fun emptyInboxCanOpenHelpAndNavigationHasNoArticleSideEffects() {
        shell()
        key(Key.DirectionDown)
        key(Key.R)
        key(Key.F1)
        compose.onNodeWithText("Keyboard shortcuts").assertIsDisplayed()
        key(Key.Escape)
        assertEquals(0, runBlocking { database.dao().pendingMutations().size })
    }

    @Test fun readerArticleOrderSurvivesSavedStateRestoration() {
        seed(3)
        val restoration = StateRestorationTester(compose)
        shell(restoration)
        key(Key.DirectionDown)
        key(Key.Enter)
        readerDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        readerDisplayed()
        chord(Key.AltLeft, Key.PageDown)
        readerDisplayed()
        compose.onNodeWithText("Headline 2").assertIsDisplayed()
    }

    private fun readerDisplayed(title: String? = null) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("reader-content").fetchSemanticsNodes().isNotEmpty() &&
                (title == null || compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
        }
        compose.onNodeWithTag("reader-content").assertIsDisplayed()
        title?.let { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    private fun shell(restoration: StateRestorationTester? = null) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BrookletApplication
        val content: @Composable () -> Unit = { BrookletTheme(dynamicColor = false) { MainShellContent(app, 1, repository, scheduler, undo) } }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitForIdle()
        newestSeededEntryId?.let { id ->
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("entry-$id").fetchSemanticsNodes().isNotEmpty() }
        }
    }
    private fun key(key: Key) { compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }; compose.waitForIdle() }
    private fun chord(modifier: Key, key: Key) { compose.onAllNodes(isRoot()).onLast().performKeyInput { keyDown(modifier); pressKey(key); keyUp(modifier) }; compose.waitForIdle() }
    private fun selected(id: Long) { compose.onNodeWithTag("entry-$id").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)) }
    private fun read(id: Long) = runBlocking { database.dao().entriesById(1, listOf(id)).single().read }
    private fun seed(count: Int) {
        seedEntries((1L..count.toLong()).map(::row))
    }
    private fun seedEntries(entries: List<EntryEntity>) {
        newestSeededEntryId = entries.maxOfOrNull { it.id }
        runBlocking { database.dao().upsertEntries(entries) }
    }
    private fun row(id: Long) = EntryEntity(
        accountId = 1, id = id, feedId = 1, title = "Headline $id", url = "https://example.com/$id", author = null,
        publishedAt = id * 86_400_000, changedAt = id, html = "<p>Article $id</p>", parsedBlocksJson = "[]",
        read = false, starred = false, readingMinutes = 1, lastOpenedAt = null,
    )
}
