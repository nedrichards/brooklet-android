package com.nedrichards.brooklet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val ScrollToTopVisibleMillis = 4_000L

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
    var recentlyScrolled by remember(listState) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState, enabled, farFromTop) {
        if (!enabled || !farFromTop) {
            recentlyScrolled = false
            return@LaunchedEffect
        }
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collectLatest { (isScrolling, index) ->
                if (index <= 2) {
                    recentlyScrolled = false
                } else {
                    recentlyScrolled = true
                    if (!isScrolling) {
                        delay(ScrollToTopVisibleMillis)
                        recentlyScrolled = false
                    }
                }
            }
    }

    AnimatedVisibility(
        visible = enabled && farFromTop && recentlyScrolled,
        enter = fadeIn() + scaleIn(initialScale = .85f),
        exit = fadeOut() + scaleOut(targetScale = .85f),
        modifier = modifier.padding(bottom = 16.dp),
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
