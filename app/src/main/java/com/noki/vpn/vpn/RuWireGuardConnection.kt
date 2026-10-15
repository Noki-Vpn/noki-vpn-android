package com.noki.vpn.vpn

import android.content.Context
import android.net.Network
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noki.vpn.data.AndroidDeviceInfo
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.BackendRuWireGuardSession
import com.noki.vpn.data.DeviceIdentity
import com.noki.vpn.data.DeviceLatency
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.PendingRuWireGuardRevoke
import com.noki.vpn.data.RuRelayCatalog
import com.noki.vpn.data.RuRelayCatalogEntry
import com.noki.vpn.data.RuRelayRttSample
import com.noki.vpn.data.RuRelaySelection
import com.noki.vpn.data.RuWireGuardSessionRequest
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.WireGuardKeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal data class PreparedRuWireGuardConnection(
    val session: BackendRuWireGuardSession.Ready,
    val privateKey: ByteArray,
    val exitNodeId: String,
    val exitNodeName: String,
)

/** The control plane admits the exact RU/entry/exit route before Android opens a TUN. */
internal class RuWireGuardConnectionCoordinator(
    private val context: Context,
    private val repository: SettingsRepository,
    private val api: BackendApiClient,
    private val physicalNetwork: Network,
) {
    suspend fun prepare(settings: StoredSettings): PreparedRuWireGuardConnection {
        require(settings.advancedSettings.ruRelayEnabled) {
            "ru_wireguard_selection_invalid"
        }
        val multiHop = settings.advancedSettings.multiHop
        val hops = multiHop.explicitNodeIds()
        val entryId = hops?.first
        val exitId = hops?.second
        val token = settings.backendAccessToken?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("auth_required")
        val deviceKey = repository.ensureBackendDeviceKey(settings.backendDeviceKey)
        val device = if (settings.backendDeviceId.isNotBlank()) {
            settings.backendDeviceId
        } else {
            val registered = api.registerDevice(
                token = token,
                deviceKey = deviceKey,
                deviceId = null,
                deviceName = AndroidDeviceInfo.deviceName(),
                publicKey = DeviceIdentity.publicKeyBase64(),
                deviceClaims = DeviceIdentity.deviceClaims(context),
                platform = "android",
            )
            val saved = repository.updateAndReturn { current ->
                if (current.backendAccessToken != token || !current.advancedSettings.ruRelayEnabled) {
                    current to false
                } else {
                    current.copy(backendDeviceId = registered.id, backendDeviceKey = registered.deviceKey) to true
                }
            }
            check(saved) { "selection_changed" }
            registered.id
        }
        val pair = WireGuardKeyStore.android(context).getOrCreate()
        val foreignNodes = api.ruRelayCatalog(token, device, deviceKey).foreignNodes
        val country = settings.userProfile.selectedCountryCode.uppercase()
        if (entryId != null) check(foreignNodes.any { it.id == entryId }) {
            "multihop_entry_unavailable"
        }
        val candidates = foreignNodes.asSequence().filter { candidate ->
            if (multiHop.enabled) candidate.id == exitId
            else (settings.userProfile.serverSelectionMode == ServerSelectionMode.AUTO || candidate.countryCode.uppercase() == country) &&
                (settings.userProfile.selectedNodeId.isBlank() ||
                    settings.userProfile.serverSelectionMode != ServerSelectionMode.SERVER ||
                    candidate.id == settings.userProfile.selectedNodeId)
        }
            .sortedBy { it.id }
            .toList()
        var selectedExit: RuForeignNode? = null
        var selectedCatalog: RuRelayCatalog? = null
        for (candidate in candidates) {
            val catalog = api.ruRelayCatalog(token, device, deviceKey, candidate.id, entryId)
            val available = catalog.relays.any { relay -> relay.available }
            if (available) {
                selectedExit = candidate
                selectedCatalog = catalog
                break
            }
        }
        val exit = selectedExit ?: throw IllegalStateException("ru_relay_pair_unavailable")
        val catalog = checkNotNull(selectedCatalog)
        val relayRttSamples = measureRuRelayRtt(catalog) { relay ->
            runInterruptible(Dispatchers.IO) {
                DeviceLatency.measureTcpConnectMs(
                    relay.probeHost!!, relay.probePort!!, timeoutMs = 1_800,
                    onDiagnostic = {},
                    socketFactory = { physicalNetwork.socketFactory.createSocket() },
                )
            }
        }
        val keyProof = api.createDeviceChallenge(token, device)
        val key = api.registerRuWireGuardKey(
            token, device, deviceKey, keyProof.nonce,
            DeviceIdentity.signChallenge(keyProof.nonce), pair.publicKeyBase64(),
        )
        val sessionProof = api.createDeviceChallenge(token, device)
        val request = RuWireGuardSessionRequest(
            deviceId = device,
            deviceKey = deviceKey,
            deviceNonce = sessionProof.nonce,
            deviceSignature = DeviceIdentity.signChallenge(sessionProof.nonce),
            requestId = UUID.randomUUID().toString(),
            publicKeyId = key.id,
            exitNodeId = exit.id,
            entryNodeId = entryId,
            routeVersion = catalog.routeVersion,
            relaySelection = RuRelaySelection.Auto,
            relayRttSamples = relayRttSamples,
        )
        var response: BackendRuWireGuardSession? = null
        try {
            val initialResponse = api.createRuWireGuardSession(token, request)
            response = initialResponse
            val initialIdentity = initialResponse.identity
            repository.stageRuWireGuardRevoke(PendingRuWireGuardRevoke(
                initialIdentity.sessionId, checkNotNull(initialIdentity.revokeToken), provisional = true,
            ))
            response = api.activateRuWireGuardSession(token, device, deviceKey, initialIdentity)
            val ready = awaitRuWireGuardReady(checkNotNull(response),
                poll = { api.pollRuWireGuardSession(token, device, deviceKey, initialIdentity) })
            return PreparedRuWireGuardConnection(ready, pair.privateKey, exit.id, exit.name)
        } catch (error: Throwable) {
            response?.identity?.let { identity ->
                identity.revokeToken?.let { revokeToken ->
                    runCatching {
                        repository.stageRuWireGuardRevoke(PendingRuWireGuardRevoke(identity.sessionId, revokeToken))
                        PendingRuWireGuardRevokeWorker.enqueue(context)
                    }
                }
            }
            throw error
        }
    }

}

/** Fresh samples from real pair probes; absent targets leave weighted selection to the server. */
internal suspend fun measureRuRelayRtt(
    catalog: RuRelayCatalog,
    probe: suspend (RuRelayCatalogEntry) -> Int?,
): List<RuRelayRttSample> = coroutineScope {
    val slots = Semaphore(6)
    val targets = catalog.relays.filter { it.available && it.pairReady && it.probeHost != null && it.probePort != null }.take(32)
    val samples = targets.map { relay ->
            async {
                slots.withPermit {
                    val rtt = withTimeoutOrNull(2_500L) { probe(relay) }
                    rtt?.takeIf { it in 1..3000 }?.let { RuRelayRttSample(relay.id, it) }
                }
            }
        }.awaitAll().filterNotNull()
    samples
}

internal suspend fun awaitRuWireGuardReady(
    initial: BackendRuWireGuardSession,
    poll: suspend () -> BackendRuWireGuardSession,
    pause: suspend () -> Unit = { delay(2_000) },
): BackendRuWireGuardSession.Ready {
    var response = initial
    repeat(15) {
        if (response is BackendRuWireGuardSession.Ready) return response
        pause()
        response = poll()
        check(response.identity.hasSameAuthorization(initial.identity)) { "ru_wireguard_session_changed" }
    }
    return response as? BackendRuWireGuardSession.Ready
        ?: throw IllegalStateException("ru_wireguard_session_timeout")
}

class PendingRuWireGuardRevokeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val repository = SettingsRepository(applicationContext)
        val pending = repository.loadPendingRuWireGuardRevokes().filterNot { it.provisional }
        if (pending.isEmpty()) return Result.success()
        val physical = AndroidUnderlyingNetworkSource(applicationContext).currentObservation().candidate?.network
            ?: return Result.retry()
        val api = BackendApiClient().onNetwork(physical)
        var failed = false
        for (entry in pending) {
            if (revokePendingRuWireGuardSession(api, entry)) repository.clearPendingRuWireGuardRevoke(entry.sessionId)
            else failed = true
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingRuWireGuardRevokeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "pending_ru_wireguard_revoke", ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}

internal suspend fun revokePendingRuWireGuardSession(
    api: BackendApiClient,
    pending: PendingRuWireGuardRevoke,
): Boolean = try {
    api.revokeRuWireGuardSession(pending.sessionId, pending.revokeToken)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: BackendException) {
    error.statusCode == 404
} catch (_: Exception) {
    false
}
