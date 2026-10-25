package com.nedrichards.brooklet.wear

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.wear.compose.foundation.LocalReduceMotion
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InboxRefreshLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var refreshes = 0
    private var acknowledgeRefreshImmediately = true
    private var arrivals = 0
    private lateinit var listState: TransformingLazyColumnState
    private lateinit var scope: CoroutineScope
    private val refreshing = mutableStateOf(false)
    private val running = mutableStateOf(false)

    @Composable
    private fun Inbox(newCount: Int = 0, reduceMotion: Boolean = false) {
        listState = rememberTransformingLazyColumnState()
        scope = rememberCoroutineScope()
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
            MaterialTheme {
                AppScaffold {
                    InboxRefreshLayout(
                        state = listState,
                        refreshing = refreshing.value,
                        running = running.value,
                        newCount = newCount,
                        message = null,
                        onRefresh = {
                            refreshes++
                            if (acknowledgeRefreshImmediately) refreshing.value = true
                        },
                        onShowNew = { arrivals++ },
                    ) { modifier, status ->
                        ScreenScaffold(scrollState = listState) { padding ->
                            TransformingLazyColumn(
                                state = listState,
                                contentPadding = padding,
                                overscrollEffect = null,
                                modifier = modifier.fillMaxSize().testTag("inbox"),
                            ) {
                                item(key = "header") {
                                    InboxHeader(12, status)
                                }
                                items(12, key = { "entry-$it" }) { index ->
                                    TitleCard(onClick = {}, title = { Text("Article $index") }) { Text("A walk beside the river") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun crownPullCommitsOnceAfterPausingAndQueuedWorkDoesNotSpin() {
        compose.setContent { Inbox() }
        compose.onNodeWithTag("inbox").performRotaryScrollInput { rotateToScrollVertically(-200f) }
        compose.waitUntil(3_000) { refreshes == 1 }
        compose.onNodeWithText("Refresh queued").assertIsDisplayed()
        compose.onNodeWithTag("inbox").performRotaryScrollInput { rotateToScrollVertically(-200f) }
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test fun queuedRunningAndRetryingRefreshKeepTheSameHeldPosition() {
        refreshing.value = true
        compose.setContent { Inbox() }
        compose.onNodeWithText("Refresh queued").assertIsDisplayed()
        val held = compose.onNodeWithTag("inbox").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { running.value = true }
        compose.onNodeWithText("Refreshing…").assertIsDisplayed()
        assertEquals(held, compose.onNodeWithTag("inbox").fetchSemanticsNode().boundsInRoot.top, .5f)
        compose.runOnIdle { running.value = false }
        compose.onNodeWithText("Refresh queued").assertIsDisplayed()
        assertEquals(held, compose.onNodeWithTag("inbox").fetchSemanticsNode().boundsInRoot.top, .5f)
    }

    @Test fun thresholdShowsReadyBeforeCommittingTheRefresh() {
        compose.setContent { Inbox() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("inbox").performRotaryScrollInput { rotateToScrollVertically(-200f) }
        compose.mainClock.advanceTimeBy(180)
        compose.onNodeWithText("Ready to refresh").assertIsDisplayed()
        assertEquals(0, refreshes)
        capture("wear-refresh-ready.png")
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("Refresh queued").assertIsDisplayed()
        assertEquals(1, refreshes)
    }

    @Test fun releaseKeepsTheSameIndicatorAndPositionBeforeTheParentAcknowledgesWork() {
        acknowledgeRefreshImmediately = false
        compose.setContent { Inbox() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("inbox").performRotaryScrollInput { rotateToScrollVertically(-200f) }
        compose.mainClock.advanceTimeBy(400)
        val indicator = compose.onNodeWithTag("inbox-refresh-indicator").fetchSemanticsNode().id
        val held = compose.onNodeWithTag("inbox").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.advanceTimeBy(350)
        assertEquals(1, refreshes)
        assertEquals(indicator, compose.onNodeWithTag("inbox-refresh-indicator").fetchSemanticsNode().id)
        assertEquals(held, compose.onNodeWithTag("inbox").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("inbox").performRotaryScrollInput { rotateToScrollVertically(-200f) }
        compose.mainClock.advanceTimeBy(200)
        assertEquals(indicator, compose.onNodeWithTag("inbox-refresh-indicator").fetchSemanticsNode().id)
        assertEquals(held, compose.onNodeWithTag("inbox").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { refreshing.value = true; running.value = true }
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithText("Refreshing…").assertIsDisplayed()
        assertEquals(indicator, compose.onNodeWithTag("inbox-refresh-indicator").fetchSemanticsNode().id)
        assertEquals(held, compose.onNodeWithTag("inbox").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun crownTakeoverCancelsNativeScrollAndKeepsBothHeadlinesStillThroughHandoff() {
        compose.setContent { Inbox() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        var nativeScroll: Job? = null
        compose.runOnIdle {
            nativeScroll = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Model the scroll mutation still owned by native rotary/fling handling at the edge.
                listState.scroll { awaitCancellation() }
            }
        }
        assertTrue(nativeScroll?.isActive == true)
        compose.onNodeWithTag("inbox").performRotaryScrollInput { rotateToScrollVertically(-200f) }
        compose.mainClock.advanceTimeBy(400)
        assertTrue("Native scroll ownership survived custom crown takeover", nativeScroll?.isCancelled == true)
        val titleTop = compose.onNodeWithText("Brooklet", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val articleTop = compose.onNodeWithText("Article 0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals(titleTop, compose.onNodeWithText("Brooklet", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, .5f)
            assertEquals(articleTop, compose.onNodeWithText("Article 0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, .5f)
        }
        compose.runOnIdle { running.value = true }
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals(titleTop, compose.onNodeWithText("Brooklet", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, .5f)
            assertEquals(articleTop, compose.onNodeWithText("Article 0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, .5f)
        }
        assertEquals(1, refreshes)
    }

    @Test fun completingRefreshSettlesOnceWithoutReversingDirection() {
        refreshing.value = true
        running.value = true
        compose.setContent { Inbox() }
        compose.waitForIdle()
        var previous = compose.onNodeWithTag("inbox").fetchSemanticsNode().boundsInRoot.top
        assertTrue(previous > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { refreshing.value = false; running.value = false }
        repeat(50) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val top = compose.onNodeWithTag("inbox").fetchSemanticsNode().boundsInRoot.top
            assertTrue("List reversed direction while settling: $previous to $top", top <= previous + .5f)
            assertTrue("List overshot its resting position: $top", top >= -.5f)
            previous = top
        }
        assertEquals(0f, previous, .5f)
    }

    @Test fun reversingBelowThresholdCancelsWithoutRefreshing() {
        compose.setContent { Inbox() }
        compose.onNodeWithTag("inbox").performRotaryScrollInput {
            rotateToScrollVertically(-200f)
            advanceEventTime(50)
            rotateToScrollVertically(200f)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(0, refreshes)
        compose.onNodeWithText("Refresh queued").assertDoesNotExist()
    }

    @Test fun crownStillScrollsBelowTheHeader() {
        compose.setContent { Inbox() }
        compose.runOnIdle { scope.launch { listState.scrollToItem(5) } }
        compose.waitForIdle()
        var before = 0
        compose.runOnIdle { before = listState.anchorItemIndex }
        compose.onNodeWithTag("inbox").performRotaryScrollInput { rotateToScrollVertically(200f) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(listState.anchorItemIndex > before) }
        assertEquals(0, refreshes)
    }

    @Test fun backgroundHasNoBusyMessageAndArrivalActionWorksWithReducedMotion() {
        compose.setContent { Inbox(newCount = 2, reduceMotion = true) }
        compose.runOnIdle { scope.launch { listState.scrollToItem(4) } }
        compose.waitForIdle()
        compose.onNodeWithText("Refreshing…").assertDoesNotExist()
        compose.onNodeWithText("Refresh queued").assertDoesNotExist()
        compose.onNodeWithText("2 new articles ↑").performClick()
        capture("wear-refresh-arrival.png")
        assertEquals(1, arrivals)
        assertEquals(0, refreshes)
    }

    @Test fun explicitRunningRefreshShowsProgressAndSettlesOnCompletion() {
        refreshing.value = true
        running.value = true
        compose.setContent { Inbox() }
        compose.onNodeWithText("Refreshing…").assertIsDisplayed()
        capture("wear-refresh-running.png")
        compose.runOnIdle { refreshing.value = false; running.value = false }
        compose.onNodeWithText("Refreshing…").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
