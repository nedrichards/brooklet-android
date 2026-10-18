package com.nedrichards.brooklet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val ScrollToTopVisibleMillis = 4_000L

/** Draw-only arrival feedback: leaves row geometry and pull-to-refresh alone. */
@Composable
internal fun TopEdgePulse(generation: Long, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    val color = MaterialTheme.colorScheme.primary
    LaunchedEffect(generation) {
        alpha.snapTo(0f)
        if (generation == 0L) return@LaunchedEffect
        // Animatable follows Compose's system motion duration scale, including
        // skipping the pulse when animations are disabled.
        alpha.animateTo(.24f, tween(100))
        alpha.animateTo(0f, tween(500))
    }
    Canvas(modifier.fillMaxWidth().height(32.dp)) {
        drawRect(Brush.verticalGradient(listOf(color.copy(alpha = alpha.value), color.copy(alpha = 0f))))
    }
}

@Composable
internal fun ScrollToTopButton(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val farFromTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }
    var recentlyScrolledUp by remember(listState) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState, enabled, farFromTop) {
        if (!enabled || !farFromTop) {
            recentlyScrolledUp = false
            return@LaunchedEffect
        }
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collectLatest { (isScrolling, index, offset) ->
            if (index <= 2) {
                recentlyScrolledUp = false
            } else {
                val movedUp = index < previousIndex || index == previousIndex && offset < previousOffset
                val movedDown = index > previousIndex || index == previousIndex && offset > previousOffset
                when {
                    movedUp -> recentlyScrolledUp = true
                    movedDown -> recentlyScrolledUp = false
                }
                previousIndex = index
                previousOffset = offset
                if (!isScrolling && recentlyScrolledUp) {
                    delay(ScrollToTopVisibleMillis)
                    recentlyScrolledUp = false
                }
            }
        }
    }

    AnimatedVisibility(
        visible = enabled && farFromTop && recentlyScrolledUp,
        enter = fadeIn() + scaleIn(initialScale = .85f),
        exit = fadeOut() + scaleOut(targetScale = .85f),
        modifier = modifier.padding(top = 8.dp),
    ) {
        SmallFloatingActionButton(
            onClick = {
                if (onClick != null) {
                    onClick()
                } else {
                    scope.launch { listState.animateScrollToItem(0) }
                }
            },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Scroll to top")
        }
    }
}
