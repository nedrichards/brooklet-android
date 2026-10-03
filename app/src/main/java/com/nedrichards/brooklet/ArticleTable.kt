package com.nedrichards.brooklet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.nedrichards.brooklet.model.DocumentBlock
import com.nedrichards.brooklet.model.TableCell

/** A shared column grid, including merged cells; wide tables scroll within the reader. */
@Composable
internal fun ArticleTable(table: DocumentBlock.Table, articleUrl: String) {
    val cells = remember(table) {
        table.cells.ifEmpty {
            table.rows.flatMapIndexed { row, values -> values.mapIndexed { column, text -> TableCell(row, column, text) } }
        }
    }
    if (cells.isEmpty()) return
    val columnCount = cells.maxOf { it.column + it.columnSpan }
    val rowCount = cells.maxOf { it.row + it.rowSpan }
    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).horizontalScroll(rememberScrollState())) {
        Layout(modifier = Modifier.semantics { collectionInfo = CollectionInfo(rowCount, columnCount) }, content = {
            cells.forEach { cell ->
                Box(
                    Modifier
                        .background(if (cell.header) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        .semantics { collectionItemInfo = CollectionItemInfo(cell.row, cell.rowSpan, cell.column, cell.columnSpan) }
                        .then(if (cell.header) Modifier.semantics { heading() } else Modifier)
                        .padding(10.dp),
                ) {
                    RichArticleText(
                        cell.html, cell.text, articleUrl, Modifier,
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = if (cell.header) FontWeight.Bold else FontWeight.Normal),
                    )
                }
            }
        }) { measurables, constraints ->
            val columnWidth = 160.dp.roundToPx()
            val widths = cells.map { it.columnSpan * columnWidth }
            val naturalHeights = measurables.mapIndexed { index, measurable ->
                measurable.maxIntrinsicHeight(widths[index])
            }
            val heights = IntArray(rowCount) { 40.dp.roundToPx() }
            // Satisfy ordinary cells first, then distribute additional height for spans.
            cells.indices.sortedBy { cells[it].rowSpan }.forEach { index ->
                val cell = cells[index]
                val current = (cell.row until cell.row + cell.rowSpan).sumOf { heights[it] }
                val extra = (naturalHeights[index] - current).coerceAtLeast(0)
                for (r in cell.row until cell.row + cell.rowSpan) {
                    heights[r] += extra / cell.rowSpan + if (r - cell.row < extra % cell.rowSpan) 1 else 0
                }
            }
            val offsets = IntArray(rowCount + 1)
            for (r in heights.indices) offsets[r + 1] = offsets[r] + heights[r]
            val placeables = measurables.mapIndexed { index, measurable ->
                val cell = cells[index]
                measurable.measure(Constraints.fixed(widths[index], offsets[cell.row + cell.rowSpan] - offsets[cell.row]))
            }
            layout(constraints.constrainWidth(columnCount * columnWidth), constraints.constrainHeight(offsets.last())) {
                placeables.forEachIndexed { index, placeable ->
                    val cell = cells[index]
                    placeable.placeRelative(cell.column * columnWidth, offsets[cell.row])
                }
            }
        }
    }
}
