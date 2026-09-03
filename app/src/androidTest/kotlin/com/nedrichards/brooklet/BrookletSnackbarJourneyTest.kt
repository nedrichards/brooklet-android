package com.nedrichards.brooklet

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.nedrichards.brooklet.designsystem.BrookletSnackbarHost
import com.nedrichards.brooklet.designsystem.BrookletTheme
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrookletSnackbarJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun multilineMessageHasBalancedVerticalInsets() {
        val hostState = SnackbarHostState()
        val message = "Marked “A two-line article title that needs the available width” read"

        compose.setContent {
            BrookletTheme(dynamicColor = false) {
                BrookletSnackbarHost(hostState)
                LaunchedEffect(hostState) {
                    hostState.showSnackbar(
                        message = message,
                        actionLabel = "Undo",
                        withDismissAction = true,
                        duration = SnackbarDuration.Indefinite,
                    )
                }
            }
        }

        compose.onNodeWithText(message).fetchSemanticsNode()
        val snackbar = compose.onNodeWithTag("brooklet-snackbar").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot
        val topInset = text.top - snackbar.top
        val bottomInset = snackbar.bottom - text.bottom

        assertTrue("Expected the snackbar message to wrap", text.height > 30f)
        assertTrue(
            "Snackbar insets were top=$topInset and bottom=$bottomInset",
            abs(topInset - bottomInset) <= 1f,
        )
    }
}
