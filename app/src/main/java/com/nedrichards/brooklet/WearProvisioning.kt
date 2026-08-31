package com.nedrichards.brooklet

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.nedrichards.brooklet.database.TokenCipher
import com.nedrichards.brooklet.model.WEAR_PROVISIONING_ACK_PATH
import com.nedrichards.brooklet.model.WEAR_PROVISIONING_PATH
import com.nedrichards.brooklet.model.WEAR_PROVISIONING_REQUEST_PATH
import com.nedrichards.brooklet.model.WearProvisioningAcknowledgementV1
import com.nedrichards.brooklet.model.WearProvisioningRequestV1
import com.nedrichards.brooklet.model.WearProvisioningV1
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PendingWearSetup(
    val nodeId: String,
    val displayName: String,
    val nonce: String,
    val requestedAt: Long,
    val status: String = "Waiting for confirmation",
)

class WearProvisioningController(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentHashMap<String, WearProvisioningRequestV1>()
    private val _pending = MutableStateFlow<List<PendingWearSetup>>(emptyList())
    val pending: StateFlow<List<PendingWearSetup>> = _pending.asStateFlow()

    init {
        // Static capability metadata enables discovery before Brooklet opens;
        // the runtime copy makes setup robust after an app update as well.
        scope.launch {
            runCatching {
                Wearable.getCapabilityClient(context)
                    .addLocalCapability(PHONE_CAPABILITY)
                    .awaitResult()
            }
        }
    }

    suspend fun receiveRequest(sourceNodeId: String, bytes: ByteArray) {
        val request = runCatching { json.decodeFromString<WearProvisioningRequestV1>(bytes.decodeToString()) }.getOrNull() ?: return
        if (request.watchNodeId != sourceNodeId || request.nonce.length < 16) return
        if (System.currentTimeMillis() - request.issuedAtEpochMillis !in 0..REQUEST_LIFETIME_MS) return
        val node = runCatching { Wearable.getNodeClient(context).connectedNodes.awaitResult().firstOrNull { it.id == sourceNodeId } }.getOrNull()
        requests[sourceNodeId] = request
        update(sourceNodeId, node?.displayName ?: "Brooklet watch", "Waiting for confirmation")
    }

    suspend fun provision(nodeId: String) {
        val request = requireNotNull(requests[nodeId]) { "The watch setup request has expired" }
        require(System.currentTimeMillis() - request.issuedAtEpochMillis <= REQUEST_LIFETIME_MS) { "The watch setup request has expired" }
        val account = requireNotNull((context.applicationContext as BrookletApplication).database.dao().account())
        val token = TokenCipher().decrypt(TokenCipher.Encrypted(account.tokenCiphertext, account.tokenIv))
        val payload = WearProvisioningV1(request.nonce, account.serverUrl, token, account.createdAt.coerceAtLeast(1))
        update(nodeId, pending.value.firstOrNull { it.nodeId == nodeId }?.displayName ?: "Brooklet watch", "Sending securely")
        Wearable.getMessageClient(context).sendMessage(nodeId, WEAR_PROVISIONING_PATH, json.encodeToString(WearProvisioningV1.serializer(), payload).encodeToByteArray()).awaitResult()
        update(nodeId, pending.value.firstOrNull { it.nodeId == nodeId }?.displayName ?: "Brooklet watch", "Validating on watch")
    }

    fun receiveAcknowledgement(sourceNodeId: String, bytes: ByteArray) {
        val ack = runCatching { json.decodeFromString<WearProvisioningAcknowledgementV1>(bytes.decodeToString()) }.getOrNull() ?: return
        val request = requests[sourceNodeId] ?: return
        if (ack.nonce != request.nonce) return
        val old = pending.value.firstOrNull { it.nodeId == sourceNodeId }
        update(
            sourceNodeId,
            old?.displayName ?: "Brooklet watch",
            if (ack.success) "Connected" else "Setup failed: ${ack.failure?.name?.lowercase()?.replace('_', ' ') ?: "unknown error"}",
        )
        if (ack.success) requests.remove(sourceNodeId)
    }

    private fun update(nodeId: String, displayName: String, status: String) {
        val current = _pending.value.filterNot { it.nodeId == nodeId }
        _pending.value = current + PendingWearSetup(nodeId, displayName, requests[nodeId]?.nonce.orEmpty(), System.currentTimeMillis(), status)
    }

    companion object {
        private const val REQUEST_LIFETIME_MS = 2 * 60 * 1000L
        const val PHONE_CAPABILITY = "brooklet_phone_provisioning"
    }
}

class PhoneWearMessageService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onMessageReceived(event: MessageEvent) {
        val controller = (applicationContext as BrookletApplication).wearProvisioning
        when (event.path) {
            WEAR_PROVISIONING_REQUEST_PATH ->
                scope.launch { controller.receiveRequest(event.sourceNodeId, event.data) }
            WEAR_PROVISIONING_ACK_PATH -> controller.receiveAcknowledgement(event.sourceNodeId, event.data)
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
