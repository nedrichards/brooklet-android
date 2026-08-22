package com.nedrichards.brooklet.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

object BrookletSpacing {
    val screen = 20.dp
    val section = 16.dp
    val compact = 8.dp
    val rowVertical = 13.dp
}

object BrookletShapes {
    val section = RoundedCornerShape(20.dp)
    val media = RoundedCornerShape(14.dp)
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
