package com.nedrichards.brooklet.wear

import com.nedrichards.brooklet.wear.data.WearEntrySummary

/** Only a new rotary burst that starts at the upper boundary can pull to refresh. */
internal data class CrownPull(
    val distance: Float = 0f,
    val lastEventMillis: Long? = null,
    val acceptsBurst: Boolean = false,
) {
    val armed: Boolean get() = distance >= THRESHOLD

    fun rotate(delta: Float, atTop: Boolean, timeMillis: Long): CrownPull {
        if (!delta.isFinite() || delta == 0f) return this
        val newBurst = lastEventMillis == null || timeMillis - lastEventMillis > BURST_GAP_MILLIS
        val accepts = atTop && if (newBurst) true else acceptsBurst
        return CrownPull(
            distance = if (accepts) (distance - delta).coerceIn(0f, THRESHOLD * 1.25f) else 0f,
            lastEventMillis = timeMillis,
            acceptsBurst = accepts,
        )
    }

    companion object {
        const val THRESHOLD = 72f
        const val BURST_GAP_MILLIS = 250L
        const val COMMIT_PAUSE_MILLIS = 450L
    }
}

/** Resistance increases near the threshold, but excess crown travel still tensions the pull. */
internal fun crownPullOffset(distance: Float): Float {
    val progress = (distance / CrownPull.THRESHOLD).coerceIn(0f, 1.25f)
    return if (progress <= .75f) progress * 44f else 33f + (progress - .75f) * 20f
}

/** Room remains authoritative. Stage remote membership changes; local actions remain immediate. */
internal data class InboxArrival(
    val latest: List<WearEntrySummary> = emptyList(),
    val displayedIds: List<Long>? = null,
    val availableIds: Set<Long> = emptySet(),
) {
    val entries: List<WearEntrySummary> get() {
        val byId = latest.associateBy { it.id }
        return displayedIds.orEmpty().mapNotNull(byId::get)
    }
    val newIds: Set<Long> get() = availableIds.intersect(latest.map { it.id }.toSet()) - displayedIds.orEmpty().toSet()

    fun receive(snapshot: List<WearEntrySummary>, holdRemovals: Boolean = false): InboxArrival = copy(
        latest = if (holdRemovals) {
            val incoming = snapshot.map { it.id }.toSet()
            snapshot + latest.filter { it.id !in incoming && it.id in displayedIds.orEmpty() }
        } else snapshot,
        displayedIds = displayedIds ?: snapshot.map { it.id },
    )

    fun hide(id: Long): InboxArrival = copy(
        displayedIds = displayedIds.orEmpty().filterNot { it == id },
        availableIds = availableIds - id,
    )

    fun completed(snapshot: List<WearEntrySummary>): InboxArrival = receive(snapshot).copy(
        availableIds = snapshot.map { it.id }.toSet(),
    )

    fun reveal(): InboxArrival {
        val allowed = displayedIds.orEmpty().toSet() + availableIds
        return copy(displayedIds = latest.filter { it.id in allowed }.map { it.id }, availableIds = emptySet())
    }

    // Undo is a local action, so it must not wait for a subsequent network pull.
    fun restore(id: Long, index: Int): InboxArrival {
        val ids = displayedIds.orEmpty().filterNot { it == id }.toMutableList()
        ids.add(index.coerceIn(0, ids.size), id)
        return copy(displayedIds = ids)
    }
}
