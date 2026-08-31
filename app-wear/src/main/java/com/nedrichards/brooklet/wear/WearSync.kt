package com.nedrichards.brooklet.wear

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import androidx.wear.tiles.TileService
import com.nedrichards.brooklet.model.FailureKind
import com.nedrichards.brooklet.model.MinifluxEntryQuery
import com.nedrichards.brooklet.model.MinifluxEntryStatus
import com.nedrichards.brooklet.model.WatchCachePolicy
import com.nedrichards.brooklet.model.WatchDocumentNormalizer
import com.nedrichards.brooklet.model.incrementalStart
import com.nedrichards.brooklet.network.ApiException
import com.nedrichards.brooklet.network.EntryDto
import com.nedrichards.brooklet.network.MinifluxClient
import com.nedrichards.brooklet.wear.data.WearDao
import com.nedrichards.brooklet.wear.data.WearDatabase
import com.nedrichards.brooklet.wear.data.WearEntryEntity
import com.nedrichards.brooklet.wear.data.WearSyncStateEntity
import com.nedrichards.brooklet.wear.data.WearTokenCipher
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class WatchWorkIntent(val pulls: Boolean, val retries: Boolean) {
    ACTION_DELIVERY(false, true),
    FOREGROUND(true, false),
    USER_SYNC(true, true),
    PERIODIC(true, false),
    BOOTSTRAP(true, true),
}

internal fun existingWorkPolicy(intent: WatchWorkIntent): ExistingWorkPolicy = when (intent) {
    WatchWorkIntent.ACTION_DELIVERY, WatchWorkIntent.FOREGROUND -> ExistingWorkPolicy.APPEND_OR_REPLACE
    WatchWorkIntent.USER_SYNC, WatchWorkIntent.BOOTSTRAP -> ExistingWorkPolicy.REPLACE
    WatchWorkIntent.PERIODIC -> error("Periodic work uses ExistingPeriodicWorkPolicy")
}

class WearSyncEngine(
    private val dao: WearDao,
    private val cipher: WearTokenCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val clients: (String, String) -> MinifluxClient = { url, token -> MinifluxClient(url, token) },
) {
    private val json = Json { encodeDefaults = true }

    suspend fun run(pull: Boolean = true, activeEntryId: () -> Long = { -1 }) {
        val account = dao.account() ?: return
        try {
            state("CONNECTING")
            val token = cipher.decrypt(WearTokenCipher.Encrypted(account.tokenCiphertext, account.tokenIv))
            val client = clients(account.serverUrl, token)
            state("PUSHING_ACTIONS")
            push(client)
            if (pull) {
                if (dao.syncState()?.bootstrapped != true) bootstrap(client) else incrementalPull(client)
                state("PRUNING")
                dao.enforceCachePolicy(WatchCachePolicy(), clock(), activeEntryId())
            }
            state("COMPLETE", lastSuccess = clock())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val reconnect = error is ApiException && error.kind == FailureKind.AUTHENTICATION
            state(if (reconnect) "RECONNECT_REQUIRED" else "ERROR", error = safeError(error))
            throw error
        }
    }

    suspend fun fetchBody(entryId: Long): Boolean {
        val account = dao.account() ?: return false
        try {
            val token = cipher.decrypt(WearTokenCipher.Encrypted(account.tokenCiphertext, account.tokenIv))
            val dto = clients(account.serverUrl, token).entry(entryId)
            if (MinifluxEntryStatus.fromWire(dto.status) == MinifluxEntryStatus.REMOVED) {
                dao.mergeRemotePage(emptyList(), listOf(entryId))
                return false
            }
            if (MinifluxEntryStatus.fromWire(dto.status) == null) return false
            dao.mergeRemoteEntries(listOf(map(dto)))
            dao.touchBody(entryId, clock())
            dao.enforceCachePolicy(WatchCachePolicy(), clock(), entryId)
            return dao.entry(entryId)?.bodyBytes?.let { it > 0 } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val reconnect = error is ApiException && error.kind == FailureKind.AUTHENTICATION
            state(if (reconnect) "RECONNECT_REQUIRED" else "ERROR", error = safeError(error))
            throw error
        }
    }

    private suspend fun bootstrap(client: MinifluxClient) {
        state("BOOTSTRAPPING")
        val highWater = client.entries(MinifluxEntryQuery(order = "changed_at", limit = 1))
            .entries.firstOrNull()?.changedAt?.let { Instant.parse(it).epochSecond } ?: 0
        val unread = client.entries(
            MinifluxEntryQuery(status = MinifluxEntryStatus.UNREAD, order = "published_at", limit = 100),
        )
        dao.mergeRemoteEntries(unread.entries.map(::map))
        dao.upsertSyncState(
            WearSyncStateEntity(
                changedAfterEpochSeconds = highWater,
                bootstrapHighWaterEpochSeconds = highWater,
                bootstrapped = true,
                phase = "PULLING_ENTRIES",
                lastSuccessfulSyncAt = null,
                error = null,
                updatedAt = clock(),
            ),
        )
        // The next sync overlaps this pre-bootstrap high-water mark, so changes
        // racing the unread snapshot are observed rather than skipped.
        incrementalPull(client)
    }

    private suspend fun incrementalPull(client: MinifluxClient) {
        val previous = dao.syncState()
        val cursor = previous?.changedAfterEpochSeconds ?: 0
        val overlap = incrementalStart(cursor)
        var offset = 0
        var newest = cursor
        state("PULLING_ENTRIES")
        do {
            val page = client.entries(
                MinifluxEntryQuery(changedAfterEpochSeconds = overlap, limit = 100, offset = offset),
            )
            page.entries.forEach { newest = maxOf(newest, Instant.parse(it.changedAt).epochSecond) }
            val removed = page.entries.filter {
                MinifluxEntryStatus.fromWire(it.status) == MinifluxEntryStatus.REMOVED
            }.map { it.id }
            dao.mergeRemotePage(
                page.entries.filter {
                    MinifluxEntryStatus.fromWire(it.status) in setOf(MinifluxEntryStatus.UNREAD, MinifluxEntryStatus.READ)
                }.map(::map),
                removed,
            )
            offset += page.entries.size
        } while (page.entries.isNotEmpty() && offset < page.total)
        dao.upsertSyncState(
            WearSyncStateEntity(
                changedAfterEpochSeconds = newest,
                bootstrapHighWaterEpochSeconds = previous?.bootstrapHighWaterEpochSeconds,
                bootstrapped = true,
                phase = "PULLING_ENTRIES",
                lastSuccessfulSyncAt = previous?.lastSuccessfulSyncAt,
                error = null,
                updatedAt = clock(),
            ),
        )
    }

    private suspend fun push(client: MinifluxClient) {
        dao.pendingMutations().groupBy { it.field to it.desiredValue }.forEach { (key, values) ->
            val ids = values.map { it.entryId }
            if (key.first == "READ") client.setRead(ids, key.second) else client.setStarred(ids, key.second)
            dao.acknowledgeMutations(ids, key.first, key.second)
        }
        dao.pendingKarakeep().forEach { item ->
            runCatching { client.saveToIntegration(item.entryId) }
                .onSuccess { dao.acknowledgeKarakeep(item.entryId) }
                .onFailure { error ->
                    dao.recordKarakeepFailure(item.entryId, safeError(error))
                    if (error is java.io.IOException || error is ApiException && error.kind == FailureKind.RETRYABLE) throw error
                }
        }
    }

    private fun map(dto: EntryDto): WearEntryEntity {
        val document = WatchDocumentNormalizer.normalize(dto.content)
        return WearEntryEntity(
            id = dto.id,
            feedId = dto.feedId,
            feedTitle = dto.feed?.title.orEmpty(),
            title = dto.title,
            url = dto.url,
            author = dto.author,
            publishedAt = Instant.parse(dto.publishedAt).toEpochMilli(),
            changedAt = Instant.parse(dto.changedAt).toEpochMilli(),
            blocksJson = if (document.blocks.isEmpty()) "" else json.encodeToString(document.blocks),
            bodyBytes = document.byteSize,
            bodyTruncated = document.truncated,
            bodyLastAccessedAt = null,
            read = dto.status == "read",
            starred = dto.starred,
            readingMinutes = dto.readingTime.coerceAtLeast(1),
            lastOpenedAt = null,
        )
    }

    private suspend fun state(phase: String, error: String? = null, lastSuccess: Long? = null) {
        val old = dao.syncState()
        dao.upsertSyncState(
            WearSyncStateEntity(
                changedAfterEpochSeconds = old?.changedAfterEpochSeconds ?: 0,
                bootstrapHighWaterEpochSeconds = old?.bootstrapHighWaterEpochSeconds,
                bootstrapped = old?.bootstrapped ?: false,
                phase = phase,
                lastSuccessfulSyncAt = lastSuccess ?: old?.lastSuccessfulSyncAt,
                error = error,
                updatedAt = clock(),
            ),
        )
    }

    private fun safeError(error: Throwable) = error.message?.take(240) ?: error::class.simpleName ?: "Sync failed"
}

class WearSyncScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val work = WorkManager.getInstance(appContext)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val periodicNetwork = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun enqueueActionDelivery() {
        val request = OneTimeWorkRequestBuilder<WearActionDebounceWorker>()
            .setInitialDelay(2, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(ACTION_DEBOUNCE, ExistingWorkPolicy.REPLACE, request)
    }

    internal fun enqueueActionWorker() = enqueue(WatchWorkIntent.ACTION_DELIVERY)
    fun enqueueForeground() = enqueue(WatchWorkIntent.FOREGROUND)
    fun enqueueUserSync() = enqueue(WatchWorkIntent.USER_SYNC)
    fun enqueueBootstrap() = enqueue(WatchWorkIntent.BOOTSTRAP)

    private fun enqueue(intent: WatchWorkIntent) {
        val request = OneTimeWorkRequestBuilder<WearSyncWorker>()
            .setInputData(workDataOf("intent" to intent.name))
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(SYNC, existingWorkPolicy(intent), request)
    }

    fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<WearSyncWorker>(6, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            .setInputData(workDataOf("intent" to WatchWorkIntent.PERIODIC.name))
            .setConstraints(periodicNetwork)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        work.cancelUniqueWork(SYNC)
        work.cancelUniqueWork(ACTION_DEBOUNCE)
        work.cancelUniqueWork(PERIODIC)
    }

    suspend fun cancelAllAndAwait() {
        work.cancelUniqueWork(SYNC).await()
        work.cancelUniqueWork(ACTION_DEBOUNCE).await()
        work.cancelUniqueWork(PERIODIC).await()
    }

    fun requestTileUpdate() {
        TileService.getUpdater(appContext).requestUpdate(BrookletInboxTileService::class.java)
    }

    companion object {
        const val SYNC = "brooklet-watch-sync"
        const val ACTION_DEBOUNCE = "brooklet-watch-action-debounce"
        const val PERIODIC = "brooklet-watch-periodic-6h"
    }
}

class WearSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = WearSyncCoordinator.exclusive {
        val intent = inputData.getString("intent")?.let { runCatching { WatchWorkIntent.valueOf(it) }.getOrNull() }
            ?: WatchWorkIntent.FOREGROUND
        try {
            try {
                WearSyncEngine(WearDatabase.getInstance(applicationContext).dao(), WearTokenCipher()).run(
                    pull = intent.pulls,
                activeEntryId = { WearSyncCoordinator.activeReaderEntryId },
                )
                Result.success()
            } catch (error: ApiException) {
                if (intent.retries && error.kind == FailureKind.RETRYABLE) Result.retry() else Result.failure()
            } catch (_: java.io.IOException) {
                if (intent.retries) Result.retry() else Result.success()
            }
        } finally {
            WearSyncScheduler(applicationContext).requestTileUpdate()
        }
    }

}

/** Serializes account replacement/deletion, worker sync, and direct body fetch. */
object WearSyncCoordinator {
    private val mutex = Mutex()
    private val openReaderEntries = linkedSetOf<Long>()
    @Volatile var activeReaderEntryId: Long = -1
        private set

    @Synchronized
    fun readerOpened(entryId: Long) {
        openReaderEntries.remove(entryId)
        openReaderEntries.add(entryId)
        activeReaderEntryId = entryId
    }

    @Synchronized
    fun readerClosed(entryId: Long) {
        openReaderEntries.remove(entryId)
        activeReaderEntryId = openReaderEntries.lastOrNull() ?: -1
    }

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

class WearActionDebounceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WearSyncScheduler(applicationContext).enqueueActionWorker()
        return Result.success()
    }
}
