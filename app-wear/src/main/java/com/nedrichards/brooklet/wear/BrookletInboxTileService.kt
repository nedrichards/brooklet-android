package com.nedrichards.brooklet.wear

import android.content.Intent
import androidx.core.app.TaskStackBuilder
import androidx.core.net.toUri
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A Room-only Tile provider: rendering never starts sync or any network request. */
class BrookletInboxTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        requestParams.currentState.lastClickableId
            .removePrefix(ENTRY_CLICK_PREFIX)
            .toLongOrNull()
            ?.let(::openEntry)
        return CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                val dao = (applicationContext as BrookletWearApplication).database.dao()
                val entries = dao.inboxSnapshot(3)
                completer.set(tile(dao.unreadCount(), entries.map { it.id to it.title }))
            }
            "Brooklet cached inbox Tile"
        }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
            "Brooklet Tile resources"
        }

    private fun tile(visibleCount: Int, headlines: List<Pair<Long, String>>): TileBuilders.Tile {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("Brooklet · $visibleCount unread", 18f, null))
        if (headlines.isEmpty()) {
            column.addContent(text("No cached unread articles", 14f, null))
        } else {
            headlines.forEach { (id, title) -> column.addContent(text(title, 14f, "$ENTRY_CLICK_PREFIX$id")) }
        }
        val layout = LayoutElementBuilders.Layout.Builder().setRoot(column.build()).build()
        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build())
            .build()
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(30 * 60 * 1000L)
            .setTileTimeline(timeline)
            .build()
    }

    private fun text(value: String, size: Float, clickId: String?): LayoutElementBuilders.Text {
        val modifiers = ModifiersBuilders.Modifiers.Builder()
        clickId?.let {
            modifiers.setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId(it)
                    .setOnClick(ActionBuilders.LoadAction.Builder().build())
                    .build(),
            )
        }
        return LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(2)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(size)).build())
            .setModifiers(modifiers.build())
            .build()
    }

    private fun openEntry(entryId: Long) {
        TaskStackBuilder.create(this).addNextIntentWithParentStack(
            Intent(Intent.ACTION_VIEW, "brooklet://entry/$entryId".toUri(), this, MainActivity::class.java)
        ).startActivities()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ENTRY_CLICK_PREFIX = "entry-"
    }
}
