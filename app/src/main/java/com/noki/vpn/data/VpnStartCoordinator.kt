package com.noki.vpn.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class VpnStartCoordinator internal constructor(
    private val sessionPreparer: VpnSessionPreparer,
    private val networkSignature: () -> String? = { null },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onDiagnostic: (String) -> Unit = {},
) {
    constructor(
        context: Context,
        repository: VpnSessionStore,
        backendApi: VpnSessionApi = BackendApiClient(),
        startupTcpPrecheckTimeoutMs: Int,
        onDiagnostic: (String) -> Unit = {},
    ) : this(
        sessionPreparer = VpnSessionPreparer { token, settings, knownDevices, sessionSelection ->
            VpnSessionCoordinator(
                context = context.applicationContext,
                repository = repository,
                backendApi = backendApi,
                startupTcpPrecheckTimeoutMs = startupTcpPrecheckTimeoutMs,
                onDiagnostic = onDiagnostic,
            ).prepare(
                token = token,
                settings = settings,
                knownDevices = knownDevices,
                sessionSelection = sessionSelection,
            )
        },
        networkSignature = { com.noki.vpn.vpn.AndroidUnderlyingNetworkSource(context.applicationContext).currentSnapshot()?.signature },
        onDiagnostic = onDiagnostic,
    )

    data class Result(
        val settings: StoredSettings,
        val session: BackendVpnSession,
        val currentDevice: BackendDevice,
        val selection: EndpointSelector.EndpointSelectionResult,
        val mergedDevices: List<BackendDevice>,
    )

    sealed interface StartDecision {
        data class FreshSession(val result: Result) : StartDecision
        data class CachedFallback(val error: Throwable) : StartDecision
        data class Failure(val error: Throwable) : StartDecision
    }

    suspend fun resolveStart(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice>,
        allowCachedFallback: Boolean = true,
        sessionSelection: VpnSessionSelection = VpnSessionSelection(
            countryCode = settings.userProfile.selectedCountryCode,
        ),
    ): StartDecision {
        return runCatching {
            consumePrepared(token, settings, sessionSelection) ?: prepare(
                token = token,
                settings = settings,
                knownDevices = knownDevices,
                sessionSelection = sessionSelection,
            ).also { result ->
                if (!VpnProfileValidator.isUsable(result.settings)) {
                    throw UnusableFreshProfileException()
                }
            }
        }.fold(
            onSuccess = { StartDecision.FreshSession(it) },
            onFailure = { error ->
                if (error is CancellationException) throw error
                if (allowCachedFallback &&
                    RuntimeProfilePolicy.isCachedProfileUsable(settings) &&
                    BackendRetryPolicy.isTransient(error)
                ) {
                    StartDecision.CachedFallback(error)
                } else {
                    StartDecision.Failure(error)
                }
            },
        )
    }

    suspend fun prepareInAdvance(token: String, settings: StoredSettings, knownDevices: List<BackendDevice>) {
        if (settings.advancedSettings.ruRelayEnabled || settings.advancedSettings.multiHop.enabled) return
        val selection = VpnSessionSelection(countryCode = settings.userProfile.selectedCountryCode)
        val key = preparationKey(token, settings, selection) ?: return
        val pending = synchronized(preparedLock) {
            if (prepared?.let { it.key == key && nowMillis() - it.createdAt in 0 until 60_000L } == true) return
            if (preparing?.key == key) return
            PendingPreparation(key, CompletableDeferred<Result>()).also { preparing = it }
        }
        try {
            val result = prepare(token, settings, knownDevices, selection)
            if (networkSignature() != key.network || !VpnProfileValidator.isUsable(result.settings)) {
                throw UnusableFreshProfileException()
            }
            synchronized(preparedLock) {
                if (preparing === pending) prepared = Prepared(key, nowMillis(), result)
            }
            pending.result.complete(result)
        } catch (error: Throwable) {
            pending.result.completeExceptionally(error)
            throw error
        } finally {
            synchronized(preparedLock) { if (preparing === pending) preparing = null }
        }
    }

    private suspend fun consumePrepared(token: String, settings: StoredSettings, selection: VpnSessionSelection): Result? {
        val key = preparationKey(token, settings, selection) ?: return null
        val pending = synchronized(preparedLock) { preparing?.takeIf { it.key == key } }
        if (pending != null) {
            onDiagnostic("stage=prepared_cache; wait=in_flight")
            try { pending.result.await() } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            if (networkSignature() != key.network) return null
        }
        val hit = synchronized(preparedLock) {
            prepared?.takeIf { it.key == key && nowMillis() - it.createdAt in 0 until 60_000L }
                ?.also { prepared = null }
        } ?: return null
        onDiagnostic("stage=prepared_cache; hit=true; age_ms=${nowMillis() - hit.createdAt}")
        return hit.result.copy(settings = settings.copy(
            profile = hit.result.settings.profile,
            endpointOptions = hit.result.settings.endpointOptions,
            advancedSettings = hit.result.settings.advancedSettings,
            backendDeviceId = hit.result.settings.backendDeviceId,
            backendDeviceKey = hit.result.settings.backendDeviceKey,
            backendDeviceAccessRole = hit.result.settings.backendDeviceAccessRole,
            userProfile = settings.userProfile.copy(
                selectedServerCode = hit.result.settings.userProfile.selectedServerCode,
                actualCountryCode = hit.result.settings.userProfile.actualCountryCode,
            ),
        ))
    }

    private fun preparationKey(token: String, settings: StoredSettings, selection: VpnSessionSelection): PreparationKey? {
        if (settings.advancedSettings.ruRelayEnabled || settings.advancedSettings.multiHop.enabled) return null
        val network = networkSignature()?.takeIf(String::isNotBlank) ?: return null
        return PreparationKey(network, token, settings.copy(
            profile = VlessProfile(),
            personalizationSettings = PersonalizationSettings(),
            userProfile = settings.userProfile.copy(
                username = "", email = "", avatarUri = null, trafficUsedGb = null, sharedTrafficUsedGb = null,
                trialTrafficRemainingGb = settings.userProfile.trialTrafficRemainingGb?.takeIf { it <= 0.0 },
            ),
        ), selection)
    }

    suspend fun prepare(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice>,
        sessionSelection: VpnSessionSelection = VpnSessionSelection(
            countryCode = settings.userProfile.selectedCountryCode,
        ),
    ): Result {
        val prepared = sessionPreparer.prepare(
            token = token,
            settings = settings,
            knownDevices = knownDevices,
            sessionSelection = sessionSelection,
        )
        return Result(
            settings = prepared.settings,
            session = prepared.session,
            currentDevice = prepared.currentDevice,
            selection = prepared.selection,
            mergedDevices = mergeCurrentDevice(knownDevices, prepared.currentDevice),
        )
    }

    fun interface VpnSessionPreparer {
        suspend fun prepare(
            token: String,
            settings: StoredSettings,
            knownDevices: List<BackendDevice>,
            sessionSelection: VpnSessionSelection,
        ): VpnSessionCoordinator.Result
    }

    private companion object {
        data class PreparationKey(val network: String, val token: String, val settings: StoredSettings, val selection: VpnSessionSelection)
        data class Prepared(val key: PreparationKey, val createdAt: Long, val result: Result)
        data class PendingPreparation(val key: PreparationKey, val result: CompletableDeferred<Result>)
        val preparedLock = Any()
        var prepared: Prepared? = null
        var preparing: PendingPreparation? = null
        fun mergeCurrentDevice(
            knownDevices: List<BackendDevice>,
            currentDevice: BackendDevice,
        ): List<BackendDevice> {
            return knownDevices.filterNot { it.deviceKey == currentDevice.deviceKey } + currentDevice
        }
    }
}

internal class UnusableFreshProfileException : RuntimeException("unusable_profile")
