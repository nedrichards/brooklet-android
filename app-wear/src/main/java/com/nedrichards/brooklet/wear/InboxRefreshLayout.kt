package com.nedrichards.brooklet.wear

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.LocalReduceMotion
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Inbox-only preview handling leaves native rotary scrolling and focus ownership with the list. */
@Composable
internal fun InboxRefreshLayout(
    state: TransformingLazyColumnState,
    refreshing: Boolean,
    running: Boolean,
    newCount: Int,
    message: String?,
    canRefresh: Boolean = true,
    onRefresh: () -> Unit,
    onShowNew: () -> Unit,
    content: @Composable (Modifier, String?) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    var pull by remember { mutableStateOf(CrownPull()) }
    var committed by remember { mutableStateOf(false) }
    var heldOffset by remember { mutableFloatStateOf(38f) }
    var heldProgress by remember { mutableFloatStateOf(0f) }
    val beginRefresh = {
        heldOffset = crownPullOffset(pull.distance).takeIf { it > 0f } ?: 38f
        heldProgress = pull.distance / CrownPull.THRESHOLD
        // Bridge the frame between releasing the crown and the parent's work acknowledgement.
        committed = true
        onRefresh()
    }
    LaunchedEffect(refreshing, committed) {
        if (refreshing) committed = false
    }
    val holding = committed || refreshing
    val atTop = !state.canScrollBackward
    val enabled = canRefresh && !holding && lifecycle == Lifecycle.State.RESUMED
    LaunchedEffect(atTop, enabled) {
        if (!atTop || !enabled) pull = CrownPull()
    }
    LaunchedEffect(pull.lastEventMillis) {
        if (pull.lastEventMillis != null) {
            delay(CrownPull.COMMIT_PAUSE_MILLIS)
            if (pull.armed && enabled && !state.canScrollBackward) beginRefresh()
            pull = CrownPull()
        }
    }
    // Queueing, running and retries share the same held geometry. Only completion releases it.
    val distance = if (holding && atTop) heldOffset else crownPullOffset(pull.distance)
    val displacement by animateFloatAsState(
        targetValue = if (reduceMotion) 0f else distance,
        // Crown input owns the position directly. Never carry spring velocity into the hold.
        animationSpec = if (reduceMotion || holding || pull.distance > 0f) snap()
            else tween(220, easing = LinearOutSlowInEasing),
        label = "Inbox refresh displacement",
    )
    val status = when {
        holding && !running -> "Refresh queued"
        holding && running -> "Refreshing…"
        pull.armed -> "Ready to refresh"
        else -> message
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent().changes.any { it.pressed }) pull = CrownPull()
            }
        }
    }.onPreRotaryScrollEvent { event ->
        // Further upward crown movement belongs to the held refresh, not native edge overscroll.
        if (holding && !state.canScrollBackward && event.verticalScrollPixels < 0f) return@onPreRotaryScrollEvent true
        if (!enabled) return@onPreRotaryScrollEvent false
        val before = pull
        val after = before.rotate(event.verticalScrollPixels / density.density, !state.canScrollBackward, event.uptimeMillis)
        if (before.distance == 0f && after.distance > 0f) {
            // The rotary scroll/fling coroutine may still be active at the upper boundary.
            // Cancel it before this gesture takes ownership of the headline's position.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.stopScroll(MutatePriority.UserInput)
            }
        }
        pull = after
        if (after.distance > 0f) heldProgress = after.distance / CrownPull.THRESHOLD
        if (after.armed && !before.armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        before.distance > 0f || after.distance > 0f
    }.semantics {
        customActions = listOf(CustomAccessibilityAction("Refresh Inbox") {
            if (enabled) beginRefresh()
            enabled
        })
    }) {
        content(Modifier.graphicsLayer { translationY = with(density) { displacement.dp.toPx() } }, status)
        // Keep one indicator node for the entire Inbox lifetime. Visibility and motion change;
        // the winding pose survives ready -> requested -> queued -> running -> settling.
        BrookRipple(
            progress = if (pull.distance > 0f) pull.distance / CrownPull.THRESHOLD else heldProgress,
            armed = pull.armed && !holding && atTop && lifecycle == Lifecycle.State.RESUMED,
            spinning = holding && running && atTop && lifecycle == Lifecycle.State.RESUMED,
            reduceMotion = reduceMotion,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 30.dp).graphicsLayer {
                alpha = when {
                    reduceMotion || !atTop -> 0f
                    holding || pull.distance > 0f -> 1f
                    else -> (displacement / heldOffset).coerceIn(0f, 1f)
                }
            },
        )
        if (newCount > 0 && !holding) {
            CompactButton(
                onClick = onShowNew,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 26.dp).semantics { liveRegion = LiveRegionMode.Polite },
            ) { Text(if (newCount == 1) "1 new article ↑" else "$newCount new articles ↑") }
        }

    }
}

@Composable
private fun BrookRipple(progress: Float, armed: Boolean, spinning: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val pose = remember { RipplePose() }
    val transition = rememberInfiniteTransition(label = "Brook ripple clock")
    val tick = if ((armed || spinning) && !reduceMotion) {
        transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
            label = "Brook ripple phase",
        )
    } else rememberUpdatedState(0f)
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(28.dp).testTag("inbox-refresh-indicator")) {
        val angle = pose.rotation(progress, armed, spinning, tick.value)
        drawArc(color, angle, 170f + progress * 45f, false, style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        val inset = 7.dp.toPx()
        drawArc(color, 150f - angle, 130f + progress * 25f, false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** Draw-time pose memory, independent of which animation is currently driving the indicator. */
internal class RipplePose {
    private var angle = 0f
    private var previousProgress = Float.NaN
    private var wasSpinning = false
    private var spinOrigin = 0f
    private var tickOrigin = 0f

    fun rotation(progress: Float, armed: Boolean, spinning: Boolean, tick: Float): Float {
        when {
            spinning -> {
                if (!wasSpinning) {
                    spinOrigin = angle
                    tickOrigin = tick
                }
                angle = spinOrigin + (tick - tickOrigin + 360f) % 360f
            }
            armed -> angle = progress * 240f + sin(tick * PI / 180).toFloat() * 5f
            progress != previousProgress -> angle = progress * 240f
            // Queued or settling: retain the last drawn pose rather than restarting the artwork.
        }
        previousProgress = progress
        wasSpinning = spinning
        return angle
    }
}
