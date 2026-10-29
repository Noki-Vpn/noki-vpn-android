package com.noki.vpn.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class TemporaryVpnLease(
    val sessionId: String,
    val controlToken: String,
    val expiresAtEpochMillis: Long,
    val trafficLimitBytes: Long,
    val locationCode: String,
    val locationName: String,
    val profile: VlessProfile,
)

data class TemporaryVpnPendingRevoke(
    val sessionId: String,
    val controlToken: String,
    val expiresAtEpochMillis: Long,
)

interface TemporaryVpnLeaseStore {
    fun loadTemporaryVpnLease(): TemporaryVpnLease?
    fun saveTemporaryVpnLease(lease: TemporaryVpnLease)
    fun clearTemporaryVpnLease()
    fun loadTemporaryVpnPendingRevoke(): TemporaryVpnPendingRevoke?
    fun markTemporaryVpnLeasePendingRevoke(lease: TemporaryVpnLease)
    fun clearTemporaryVpnPendingRevoke()
}

class TemporaryVpnSessionCoordinator(
    private val store: TemporaryVpnLeaseStore,
    private val api: TemporaryVpnApi,
    private val publicKeyProvider: () -> String,
    private val deviceKeyProvider: () -> String,
    private val deviceNameProvider: () -> String,
    private val platformProvider: () -> String,
    private val challengeSigner: (String) -> String,
    private val profileSelector: (BackendVpnSession) -> VlessProfile,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val revokeMutex = Mutex()

    suspend fun prepare(): TemporaryVpnLease {
        check(retryPendingRevoke()) { "temporary_vpn_revoke_pending" }
        val now = nowMillis()
        val storedLease = withContext(storageDispatcher) { store.loadTemporaryVpnLease() }
        if (TemporaryVpnLeasePolicy.isUsable(storedLease, now)) return requireNotNull(storedLease)
        if (storedLease != null && storedLease.expiresAtEpochMillis > now && storedLease.hasRevokeControl()) {
            withContext(storageDispatcher) { store.markTemporaryVpnLeasePendingRevoke(storedLease) }
            check(retryPendingRevoke()) { "temporary_vpn_revoke_pending" }
        } else {
            withContext(storageDispatcher) { store.clearTemporaryVpnLease() }
        }

        return withContext(NonCancellable) {
            val publicKey = publicKeyProvider().trim()
            require(publicKey.isNotBlank()) { "temporary_vpn_public_key_missing" }
            val deviceKey = deviceKeyProvider().trim()
            require(deviceKey.isNotBlank()) { "temporary_vpn_device_key_missing" }
            val challenge = api.createTemporaryVpnChallenge(
                publicKey = publicKey,
                deviceKey = deviceKey,
                deviceName = deviceNameProvider().trim(),
                platform = platformProvider().trim(),
            )
            val response = api.createTemporaryVpnSession(
                publicKey = publicKey,
                nonce = challenge.nonce,
                signature = challengeSigner(challenge.nonce),
                deviceKey = deviceKey,
            )
            require(response.mode == "auth_temp") { "temporary_vpn_mode_invalid" }
            require(response.vpnSession.canConnect) { "temporary_vpn_access_denied" }
            val lease = TemporaryVpnLease(
                sessionId = response.sessionId,
                controlToken = response.controlToken,
                expiresAtEpochMillis = response.expiresAtEpochMillis,
                trafficLimitBytes = response.trafficLimitBytes,
                locationCode = response.vpnSession.locationCode,
                locationName = response.vpnSession.locationName,
                profile = profileSelector(response.vpnSession),
            )
            require(TemporaryVpnLeasePolicy.isUsable(lease, nowMillis())) {
                "temporary_vpn_lease_unusable"
            }
            withContext(storageDispatcher) { store.saveTemporaryVpnLease(lease) }
            lease
        }
    }

    suspend fun stageStoredLeaseForRevoke(): TemporaryVpnPendingRevoke? = revokeMutex.withLock {
        stageStoredLeaseForRevokeLocked()
    }

    suspend fun revokeStoredLease(): Boolean = revokeMutex.withLock {
        val pending = stageStoredLeaseForRevokeLocked() ?: return@withLock true
        retryPendingRevokeLocked(pending)
    }

    suspend fun retryPendingRevoke(): Boolean = revokeMutex.withLock {
        val pending = withContext(storageDispatcher) {
            store.loadTemporaryVpnPendingRevoke()
        } ?: return@withLock true
        retryPendingRevokeLocked(pending)
    }

    private suspend fun retryPendingRevokeLocked(pending: TemporaryVpnPendingRevoke): Boolean {
        if (pending.expiresAtEpochMillis <= nowMillis()) {
            withContext(storageDispatcher) { store.clearTemporaryVpnPendingRevoke() }
            return true
        }
        val result = runCatching {
            api.revokeTemporaryVpnSession(
                sessionId = pending.sessionId,
                controlToken = pending.controlToken,
            )
        }
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        val terminal = result.isSuccess || (error as? BackendException)?.statusCode in setOf(404, 410)
        if (terminal) {
            withContext(storageDispatcher) { store.clearTemporaryVpnPendingRevoke() }
        }
        return terminal
    }

    private suspend fun stageStoredLeaseForRevokeLocked(): TemporaryVpnPendingRevoke? {
        withContext(storageDispatcher) { store.loadTemporaryVpnPendingRevoke() }?.let { return it }
        val lease = withContext(storageDispatcher) { store.loadTemporaryVpnLease() } ?: return null
        withContext(storageDispatcher) { store.markTemporaryVpnLeasePendingRevoke(lease) }
        return lease.toPendingRevoke()
    }
}

private fun TemporaryVpnLease.hasRevokeControl(): Boolean =
    sessionId.isNotBlank() && controlToken.isNotBlank()

private fun TemporaryVpnLease.toPendingRevoke(): TemporaryVpnPendingRevoke = TemporaryVpnPendingRevoke(
    sessionId = sessionId,
    controlToken = controlToken,
    expiresAtEpochMillis = expiresAtEpochMillis,
)

object TemporaryVpnLeasePolicy {
    private const val DEFAULT_MINIMUM_REMAINING_MILLIS = 5_000L
    private const val MAX_TRAFFIC_LIMIT_BYTES = 100L * 1024L * 1024L
    private const val MAX_LEASE_DURATION_MILLIS = 10L * 60L * 1_000L
    private const val MAX_CLOCK_SKEW_MILLIS = 30_000L

    fun isUsable(
        lease: TemporaryVpnLease?,
        nowMillis: Long = System.currentTimeMillis(),
        minimumRemainingMillis: Long = DEFAULT_MINIMUM_REMAINING_MILLIS,
    ): Boolean {
        if (lease == null) return false
        if (lease.sessionId.isBlank() || lease.controlToken.isBlank()) return false
        if (lease.trafficLimitBytes !in 1L..MAX_TRAFFIC_LIMIT_BYTES) return false
        val remainingMillis = lease.expiresAtEpochMillis - nowMillis
        if (remainingMillis <= minimumRemainingMillis.coerceAtLeast(0L)) return false
        if (remainingMillis > MAX_LEASE_DURATION_MILLIS + MAX_CLOCK_SKEW_MILLIS) return false
        return VpnProfileValidator.isUsable(
            profile = lease.profile,
            advancedSettings = AdvancedSettings(),
            selectedLocationCode = lease.locationCode,
            directOnly = false,
        )
    }
}

object TemporaryVpnPendingRevokeCodec {
    fun encode(pending: TemporaryVpnPendingRevoke): String = JSONObject()
        .put("session_id", pending.sessionId)
        .put("control_token", pending.controlToken)
        .put("expires_at_epoch_millis", pending.expiresAtEpochMillis)
        .toString()

    fun decode(raw: String?): TemporaryVpnPendingRevoke? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            TemporaryVpnPendingRevoke(
                sessionId = json.getString("session_id"),
                controlToken = json.getString("control_token"),
                expiresAtEpochMillis = json.getLong("expires_at_epoch_millis"),
            )
        }.getOrNull()
    }
}

object TemporaryVpnLeaseCodec {
    fun encode(lease: TemporaryVpnLease): String = JSONObject()
        .put("session_id", lease.sessionId)
        .put("control_token", lease.controlToken)
        .put("expires_at_epoch_millis", lease.expiresAtEpochMillis)
        .put("traffic_limit_bytes", lease.trafficLimitBytes)
        .put("location_code", lease.locationCode)
        .put("location_name", lease.locationName)
        .put("profile", encodeProfile(lease.profile))
        .toString()

    fun decode(raw: String?): TemporaryVpnLease? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            TemporaryVpnLease(
                sessionId = json.getString("session_id"),
                controlToken = json.getString("control_token"),
                expiresAtEpochMillis = json.getLong("expires_at_epoch_millis"),
                trafficLimitBytes = json.getLong("traffic_limit_bytes"),
                locationCode = json.getString("location_code"),
                locationName = json.optString("location_name"),
                profile = decodeProfile(json.getJSONObject("profile")),
            )
        }.getOrNull()
    }

    private fun encodeProfile(profile: VlessProfile): JSONObject = JSONObject()
        .put("remark", profile.remark)
        .put("endpoint_code", profile.endpointCode)
        .put("proxy_type", profile.proxyType)
        .put("transport", profile.transport)
        .put("transport_mode", profile.transportMode)
        .put("host", profile.host)
        .put("port", profile.port)
        .put("uuid", profile.uuid)
        .put("vpnUsername", profile.vpnUsername)
        .put("flow", profile.flow)
        .put("security", profile.security)
        .put("fingerprint", profile.fingerprint)
        .put("server_name", profile.serverName)
        .put("request_host", profile.requestHost)
        .put("path", profile.path)
        .put("alpn", profile.alpn)
        .put("allow_insecure", profile.allowInsecure)
        .put("enable_mux", profile.enableMux)
        .put("random_user_agent", profile.randomUserAgent)
        .put("public_key", profile.publicKey)
        .put("short_id", profile.shortId)
        .put("spider_x", profile.spiderX)

    private fun decodeProfile(json: JSONObject): VlessProfile = VlessProfile(
        remark = json.optString("remark", "Noki VPN"),
        endpointCode = json.optString("endpoint_code"),
        proxyType = json.optString("proxy_type", "vless"),
        transport = json.optString("transport", "tcp"),
        transportMode = json.optString("transport_mode"),
        host = json.optString("host"),
        port = json.optString("port", "443"),
        uuid = json.optString("uuid"),
        vpnUsername = json.optString("vpnUsername"),
        flow = json.optString("flow"),
        security = json.optString("security", "reality"),
        fingerprint = json.optString("fingerprint", "chrome"),
        serverName = json.optString("server_name"),
        requestHost = json.optString("request_host"),
        path = json.optString("path"),
        alpn = json.optString("alpn"),
        allowInsecure = json.optBoolean("allow_insecure", false),
        enableMux = json.optBoolean("enable_mux", false),
        randomUserAgent = json.optBoolean("random_user_agent", false),
        publicKey = json.optString("public_key"),
        shortId = json.optString("short_id"),
        spiderX = json.optString("spider_x", "/"),
    )
}
