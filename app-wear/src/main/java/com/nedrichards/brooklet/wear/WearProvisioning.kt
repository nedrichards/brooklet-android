package com.nedrichards.brooklet.wear

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.nedrichards.brooklet.model.FailureKind
import com.nedrichards.brooklet.model.WEAR_PROVISIONING_ACK_PATH
import com.nedrichards.brooklet.model.WEAR_PROVISIONING_PATH
import com.nedrichards.brooklet.model.WEAR_PROVISIONING_REQUEST_PATH
import com.nedrichards.brooklet.model.WearProvisioningAcknowledgementV1
import com.nedrichards.brooklet.model.WearProvisioningFailure
import com.nedrichards.brooklet.model.WearProvisioningRequestV1
import com.nedrichards.brooklet.model.WearProvisioningV1
import com.nedrichards.brooklet.model.WearAccountGenerationDecision
import com.nedrichards.brooklet.model.decideWearAccountGeneration
import com.nedrichards.brooklet.model.validateFor
import com.nedrichards.brooklet.network.ApiException
import com.nedrichards.brooklet.network.MinifluxClient
import com.nedrichards.brooklet.wear.data.WearAccountEntity
import com.nedrichards.brooklet.wear.data.WearTokenCipher
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WatchSetupState(
    val phoneAvailable: Boolean = false,
    val requesting: Boolean = false,
    val retryAvailable: Boolean = false,
    val status: String = "Connecting to your phone",
)

internal fun shouldUseEmbeddedDevelopmentCredentials(
    isDebug: Boolean,
    serverUrl: String,
    token: String,
): Boolean = isDebug && serverUrl.isNotBlank() && token.isNotBlank()

class WatchProvisioningManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences = context.getSharedPreferences("wear_setup", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val provisioningMutex = Mutex()
    private val _state = MutableStateFlow(WatchSetupState(requesting = true))
    val state: StateFlow<WatchSetupState> = _state.asStateFlow()

    init {
        // Keep the runtime advertisement as a defence in depth measure. The
        // static resource is still retained for discovery before first launch.
        scope.launch {
            runCatching {
                Wearable.getCapabilityClient(context)
                    .addLocalCapability(WATCH_CAPABILITY)
                    .awaitResult()
            }
        }
    }

    suspend fun beginSetup() {
        if (shouldUseEmbeddedDevelopmentCredentials(BuildConfig.DEBUG, BuildConfig.DEV_MINIFLUX_URL, BuildConfig.DEV_MINIFLUX_TOKEN)) {
            provisionDevelopmentAccount()
        } else {
            requestSetup()
        }
    }

    private suspend fun provisionDevelopmentAccount() {
        _state.value = WatchSetupState(requesting = true, status = "Connecting to development Miniflux")
        runCatching {
            storeValidatedAccount(
                serverUrl = BuildConfig.DEV_MINIFLUX_URL,
                token = BuildConfig.DEV_MINIFLUX_TOKEN,
                accountGeneration = BuildConfig.VERSION_CODE.toLong(),
            )
        }.onSuccess {
            _state.value = WatchSetupState(phoneAvailable = false, status = "Connected")
        }.onFailure {
            _state.value = WatchSetupState(
                phoneAvailable = false,
                retryAvailable = true,
                status = "Could not connect to development Miniflux. Check the watch network and try again.",
            )
        }
    }

    suspend fun requestSetup() {
        _state.value = WatchSetupState(requesting = true, status = "Connecting to your phone")
        val capableNodes = runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .awaitResult()
                .nodes
        }.getOrDefault(emptySet())
        // A connected-node fallback also covers newly installed builds while
        // Play services is still propagating their static capabilities.
        val connectedNodes = runCatching {
            Wearable.getNodeClient(context).connectedNodes.awaitResult()
        }.getOrDefault(emptyList())
        val nodes = (capableNodes + connectedNodes).distinctBy { it.id }
        if (nodes.isEmpty()) {
            _state.value = WatchSetupState(
                phoneAvailable = false,
                retryAvailable = true,
                status = "Your phone is not reachable yet. Keep both devices awake and connected.",
            )
            return
        }
        val localNode = runCatching {
            Wearable.getNodeClient(context).localNode.awaitResult()
        }.getOrElse {
            _state.value = WatchSetupState(
                phoneAvailable = false,
                retryAvailable = true,
                status = "Brooklet could not identify this watch. Keep both devices awake and try again.",
            )
            return
        }
        val request = WearProvisioningRequestV1(newNonce(), localNode.id, System.currentTimeMillis())
        preferences.edit { putString("request", json.encodeToString(request)) }
        val bytes = json.encodeToString(request).encodeToByteArray()
        val delivered = nodes.count { node ->
            runCatching {
                Wearable.getMessageClient(context).sendMessage(node.id, WEAR_PROVISIONING_REQUEST_PATH, bytes).awaitResult()
            }.isSuccess
        }
        _state.value = if (delivered > 0) {
            WatchSetupState(phoneAvailable = true, status = "Open Brooklet settings on your phone to approve this watch")
        } else {
            WatchSetupState(
                phoneAvailable = false,
                retryAvailable = true,
                status = "Brooklet could not reach your phone. Keep both devices awake and try again.",
            )
        }
    }

    internal suspend fun accept(sourceNodeId: String, bytes: ByteArray) = provisioningMutex.withLock {
        val payload = runCatching { json.decodeFromString<WearProvisioningV1>(bytes.decodeToString()) }.getOrElse {
            acknowledge(sourceNodeId, WearProvisioningAcknowledgementV1("invalid", 0, false, WearProvisioningFailure.INVALID_MESSAGE))
            return@withLock
        }
        val request = preferences.getString("request", null)
            ?.let { runCatching { json.decodeFromString<WearProvisioningRequestV1>(it) }.getOrNull() }
        if (request == null) {
            acknowledge(sourceNodeId, WearProvisioningAcknowledgementV1(payload.nonce, payload.accountGeneration, false, WearProvisioningFailure.NONCE_MISMATCH))
            return@withLock
        }
        try {
            payload.validateFor(request, System.currentTimeMillis())
            // Consume the nonce before any destructive replacement. A retry
            // always creates a fresh request, so interrupted or duplicated
            // message delivery cannot clear a newly bootstrapped cache twice.
            preferences.edit { clear() }
            _state.value = WatchSetupState(phoneAvailable = true, requesting = true, status = "Validating Miniflux")
            storeValidatedAccount(payload.minifluxUrl, payload.token, payload.accountGeneration)
            acknowledge(sourceNodeId, WearProvisioningAcknowledgementV1(payload.nonce, payload.accountGeneration, true))
            _state.value = WatchSetupState(phoneAvailable = true, status = "Connected")
        } catch (error: Throwable) {
            val failure = when {
                error is IllegalArgumentException && error.message.orEmpty().contains("nonce", true) -> WearProvisioningFailure.NONCE_MISMATCH
                error is IllegalArgumentException && error.message.orEmpty().contains("expired", true) -> WearProvisioningFailure.EXPIRED
                error is ApiException && error.kind == FailureKind.AUTHENTICATION -> WearProvisioningFailure.AUTHENTICATION
                error is ApiException && error.kind == FailureKind.UNSUPPORTED_SERVER -> WearProvisioningFailure.UNSUPPORTED_SERVER
                error is ApiException && error.kind == FailureKind.CERTIFICATE -> WearProvisioningFailure.CERTIFICATE
                error is java.io.IOException -> WearProvisioningFailure.PHONE_UNAVAILABLE
                error is StaleWearAccountGenerationException -> WearProvisioningFailure.STALE_ACCOUNT_GENERATION
                else -> WearProvisioningFailure.INVALID_TOKEN
            }
            acknowledge(sourceNodeId, WearProvisioningAcknowledgementV1(payload.nonce, payload.accountGeneration, false, failure))
            _state.value = WatchSetupState(
                phoneAvailable = true,
                retryAvailable = true,
                status = "Setup failed: ${failure.name.lowercase().replace('_', ' ')}",
            )
        }
    }

    private suspend fun acknowledge(nodeId: String, value: WearProvisioningAcknowledgementV1) {
        runCatching {
            Wearable.getMessageClient(context).sendMessage(
                nodeId,
                WEAR_PROVISIONING_ACK_PATH,
                json.encodeToString(value).encodeToByteArray(),
            ).awaitResult()
        }
    }

    private suspend fun storeValidatedAccount(serverUrl: String, token: String, accountGeneration: Long) {
        val app = context.applicationContext as BrookletWearApplication
        val current = app.database.dao().account()
        when (decideWearAccountGeneration(current?.accountGeneration, current?.serverUrl, accountGeneration, serverUrl)) {
            WearAccountGenerationDecision.REJECT_STALE -> throw StaleWearAccountGenerationException()
            WearAccountGenerationDecision.REPLACE -> Unit
        }
        val identity = MinifluxClient(serverUrl, token).validate()
        app.scheduler.cancelAllAndAwait()
        WearSyncCoordinator.exclusive {
            val encrypted = WearTokenCipher().encrypt(token)
            app.database.dao().replaceAccountAndData(
                WearAccountEntity(
                    serverUrl = serverUrl.trim().trimEnd('/'),
                    username = identity.user.username,
                    tokenCiphertext = encrypted.ciphertext,
                    tokenIv = encrypted.iv,
                    serverVersion = identity.version.version,
                    accountGeneration = accountGeneration,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        app.scheduler.ensurePeriodic()
        app.scheduler.requestTileUpdate()
        app.scheduler.enqueueBootstrap()
    }

    private fun newNonce(): String {
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val PHONE_CAPABILITY = "brooklet_phone_provisioning"
        const val WATCH_CAPABILITY = "brooklet_watch_provisioning"
    }
}

private class StaleWearAccountGenerationException : IllegalArgumentException("Stale account generation")

class WatchWearMessageService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WEAR_PROVISIONING_PATH) {
            scope.launch { (applicationContext as BrookletWearApplication).provisioning.accept(event.sourceNodeId, event.data) }
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
