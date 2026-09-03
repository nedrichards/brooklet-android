package com.nedrichards.brooklet.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

object BrookletSpacing {
    val screenCompact = 20.dp
    val screenComfortable = 28.dp
    val section = 16.dp
    val compact = 8.dp
    val rowVertical = 13.dp
    val capsuleHorizontal = 9.dp
    val capsuleVertical = 6.dp
    val capsuleIcon = 16.dp
}

object BrookletShapes {
    val section = RoundedCornerShape(20.dp)
    val media = RoundedCornerShape(14.dp)
    val floating = RoundedCornerShape(24.dp)
    val capsule = CircleShape
}

object BrookletWidths {
    val form = 560.dp
    val settings = 720.dp
    val reading = 760.dp
}

@Composable
fun BrookletContextIcon(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier.size(64.dp),
        shape = BrookletShapes.capsule,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
fun BrookletSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .testTag("brooklet-snackbar"),
            shape = BrookletShapes.floating,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = data.visuals.message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                data.visuals.actionLabel?.let { label ->
                    TextButton(
                        onClick = data::performAction,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.inversePrimary,
                        ),
                    ) { Text(label) }
                }
                if (data.visuals.withDismissAction) {
                    IconButton(onClick = data::dismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
                    }
                }
            }
        }
    }
}

@Composable
fun BrookletEmptyState(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrookletContextIcon(
                icon = icon,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun BrookletInlineError(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = BrookletShapes.media,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(BrookletSpacing.compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun BrookletSection(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = BrookletShapes.section,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(BrookletSpacing.section), verticalArrangement = Arrangement.spacedBy(BrookletSpacing.compact)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            supportingText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun BrookletActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) = Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(BrookletSpacing.compact),
    content = content,
)

@Composable
fun BrookletHeadlineRow(
    title: String,
    metadata: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isUnread: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(
            start = 18.dp,
            top = BrookletSpacing.rowVertical,
            end = 8.dp,
            bottom = BrookletSpacing.rowVertical,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(metadata, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
