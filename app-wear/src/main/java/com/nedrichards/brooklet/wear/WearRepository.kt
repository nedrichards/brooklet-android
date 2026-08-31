package com.nedrichards.brooklet.wear

import com.nedrichards.brooklet.model.DocumentBlock
import com.nedrichards.brooklet.wear.data.WearDao
import com.nedrichards.brooklet.wear.data.WearReaderPositionEntity
import kotlinx.serialization.json.Json

class WearRepository(
    private val dao: WearDao,
    private val scheduler: WearSyncScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    val account = dao.observeAccount()
    val inbox = dao.observeInbox()
    val unreadCount = dao.observeUnreadCount()
    val syncState = dao.observeSyncState()
    val bodyBytes = dao.observeBodyBytes()
    fun entry(id: Long) = dao.observeEntry(id)
    fun karakeep(entryId: Long) = dao.observeKarakeep(entryId)
    suspend fun readerPosition(entryId: Long) = dao.readerPosition(entryId)

    fun blocks(value: String): List<DocumentBlock> = if (value.isBlank()) emptyList() else
        runCatching { json.decodeFromString<List<DocumentBlock>>(value) }.getOrDefault(emptyList())

    suspend fun setRead(id: Long, read: Boolean) {
        dao.setRead(id, read, clock())
        scheduler.requestTileUpdate()
        scheduler.enqueueActionDelivery()
    }

    suspend fun setStarred(id: Long, starred: Boolean) {
        dao.setStarred(id, starred, clock())
        scheduler.requestTileUpdate()
        scheduler.enqueueActionDelivery()
    }

    suspend fun sendToKarakeep(id: Long) {
        dao.queueKarakeep(id, clock())
        scheduler.requestTileUpdate()
        scheduler.enqueueActionDelivery()
    }

    suspend fun touchBody(id: Long) = dao.touchBody(id, clock())

    suspend fun saveReaderPosition(entryId: Long, anchorItemIndex: Int, anchorItemScrollOffset: Int) {
        dao.upsertReaderPosition(
            WearReaderPositionEntity(entryId, anchorItemIndex, anchorItemScrollOffset, clock()),
        )
    }
}
