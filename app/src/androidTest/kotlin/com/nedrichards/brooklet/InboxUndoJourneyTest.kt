package com.nedrichards.brooklet

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nedrichards.brooklet.database.BrookletDatabase
import com.nedrichards.brooklet.database.EntryEntity
import com.nedrichards.brooklet.designsystem.BrookletTheme
import com.nedrichards.brooklet.sync.EntryRepository
import com.nedrichards.brooklet.sync.SyncActivity
import com.nedrichards.brooklet.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class InboxUndoJourneyTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var database: BrookletDatabase
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

    @After fun tearDown() {
        database.close()
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

    @Test fun pendingUndoSurvivesLossAndRecreationOfItsSnackbarHost() {
        seed(entry(1, "Lifecycle", 1))
        var hostVisible by mutableStateOf(true)
        compose.setContent {
            BrookletTheme(dynamicColor = false) {
                if (hostVisible) {
                    MainShellContent(application, ACCOUNT_ID, repository, scheduler, undoViewModel)
                }
            }
        }

        compose.onNodeWithText("Lifecycle").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { isRead(1) }
        compose.runOnIdle { hostVisible = false }
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

    @Test fun richArticleSpansCannotOverrideThemeForegroundOrBackground() {
        val rendered = themeSafeArticleText(
            """<span style="color:black;background:black"><font color="#000000">Dark text</font></span>""",
        )

        assertEquals("Dark text", rendered.toString())
        assertEquals(0, rendered.getSpans(0, rendered.length, ForegroundColorSpan::class.java).size)
        assertEquals(0, rendered.getSpans(0, rendered.length, BackgroundColorSpan::class.java).size)
    }

    @Test fun richArticleTextViewUsesTheSuppliedDarkThemeColoursForTextLinksAndBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var view: TextView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = TextView(context)
            configureRichArticleText(
                view = view,
                html = """<font color="black"><a href="https://example.com">Dark link</a></font>""",
                textSizeSp = 18f,
                textColor = Color.WHITE,
                linkColor = Color.CYAN,
            )
        }

        assertEquals(Color.WHITE, view.currentTextColor)
        assertEquals(Color.CYAN, view.linkTextColors.defaultColor)
        assertEquals(Color.TRANSPARENT, (view.background as ColorDrawable).color)
        assertEquals(0, (view.text as android.text.Spanned).getSpans(
            0,
            view.text.length,
            ForegroundColorSpan::class.java,
        ).size)
    }

    @Test fun productionUndoViewModelFactoryReceivesSavedStateCreationExtras() {
        compose.setContent {
            BrookletTheme(dynamicColor = false) {
                MainShell(application, 999L)
            }
        }

        compose.onNodeWithContentDescription("More actions").assertIsDisplayed()
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

    private fun entry(id: Long, title: String, order: Long, read: Boolean = false) = EntryEntity(
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
        starred = false,
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
