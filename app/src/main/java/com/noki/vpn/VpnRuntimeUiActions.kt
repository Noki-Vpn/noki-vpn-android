package com.noki.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.noki.vpn.data.RuntimeProfilePolicy
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.VpnRuntimeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun AppUiRuntime.showVpnConflict() {
    uiState = uiState.copy(dialog = AppDialog.VpnConflict, inlineMessage = null)
}

internal fun AppUiRuntime.updateConnectionState(
    state: VpnConnectionState,
    reason: String = "",
    connectedAtMillis: Long? = null,
    runtimeMode: VpnRuntimeMode = uiState.vpnRuntimeMode,
    latencyMs: Int? = null,
    nokiNvCarrier: String = "",
) {
    if (state == VpnConnectionState.CONNECTED && uiState.connectionState == state && reason.isBlank() &&
        connectedAtMillis == uiState.connectedAtMillis && runtimeMode == uiState.vpnRuntimeMode &&
        latencyMs == uiState.connectionLatencyMs && nokiNvCarrier != uiState.nokiNvCarrier) {
        uiState = uiState.copy(nokiNvCarrier = nokiNvCarrier)
        if (nokiNvCarrier.isBlank() || uiState.profile.proxyType != "nokinv") syncRuntimeSettingsFromRepository()
        return
    }
    when (val transition = connectionStateTransition(state, reason, connectedAtMillis, latencyMs)) {
        is ConnectionStateReducer.Transition.TrafficLimit -> {
            uiState = withFreeTrafficLimitNotice(
                transition.state.copy(vpnRuntimeMode = runtimeMode),
                forceDialog = true,
            )
            repository.clearVpnRuntimeState()
        }
        is ConnectionStateReducer.Transition.DeviceLimit -> {
            uiState = withDeviceLimitNotice(
                transition.state.copy(vpnRuntimeMode = runtimeMode),
                forceDialog = true,
            )
            repository.clearVpnRuntimeState()
        }
        is ConnectionStateReducer.Transition.EmptySelectedApps -> {
            uiState = transition.state.copy(vpnRuntimeMode = runtimeMode)
            repository.clearVpnRuntimeState()
        }
        is ConnectionStateReducer.Transition.Normal -> applyConnectionTransition(transition, runtimeMode)
    }
    uiState = uiState.copy(nokiNvCarrier = nokiNvCarrier.takeIf {
        uiState.connectionState == VpnConnectionState.CONNECTED
    }.orEmpty())
    if (reason == "client_update_required") refreshAndroidUpdateStatus()
}

internal fun AppUiRuntime.connectionStateTransition(
    state: VpnConnectionState,
    reason: String,
    connectedAtMillis: Long?,
    latencyMs: Int? = null,
): ConnectionStateReducer.Transition {
    return connectionUiCoordinator.reduce(
        current = uiState,
        state = state,
        reason = ConnectionReason.parse(reason),
        connectedAtMillis = connectedAtMillis,
        nowMillis = System.currentTimeMillis(),
        dailyStats = repository.loadDailyStats(),
        endpointRating = repository.endpointRatingSnapshot(uiState.endpointOptions.map { it.code }),
        latencyMs = latencyMs,
    )
}

internal fun AppUiRuntime.applyConnectionTransition(
    transition: ConnectionStateReducer.Transition.Normal,
    runtimeMode: VpnRuntimeMode,
) {
    val previousState = uiState.connectionState
    uiState = transition.state.copy(
        vpnRuntimeMode = if (transition.state.connectionState == VpnConnectionState.DISCONNECTED) {
            VpnRuntimeMode.ACCOUNT
        } else {
            runtimeMode
        },
    )
    if (transition.state.connectionState != VpnConnectionState.CONNECTED &&
        transition.state.connectionState != VpnConnectionState.CONNECTING
    ) {
        val staleRuntimeSettingsSync = runtimeSettingsSyncJob
        runtimeSettingsSyncJob = null
        staleRuntimeSettingsSync?.cancel()
    }
    when (transition.runtimeAction) {
        ConnectionStateReducer.RuntimeAction.Clear -> repository.clearVpnRuntimeState()
        ConnectionStateReducer.RuntimeAction.None -> Unit
    }
    transition.logAction?.let(::recordConnectionLog)
    if (previousState != VpnConnectionState.DISCONNECTED &&
        uiState.connectionState == VpnConnectionState.DISCONNECTED) prepareVpnInAdvance()
    if (transition.state.connectionState == VpnConnectionState.CONNECTED &&
        runtimeMode == VpnRuntimeMode.ACCOUNT
    ) {
        syncRuntimeSettingsFromRepository()
    }
}

internal data class RuntimeSettingsSyncKey(
    val profile: com.noki.vpn.data.VlessProfile,
    val selectedCountryCode: String,
    val serverSelectionMode: com.noki.vpn.data.ServerSelectionMode,
    val selectedNodeId: String,
    val selectedPlanCode: com.noki.vpn.data.PlanCode,
    val protocol: com.noki.vpn.data.VpnProtocol,
    val endpointSelectionMode: com.noki.vpn.data.EndpointSelectionMode,
    val manualEndpointCode: String,
    val manualEndpointGroupKey: String,
    val endpointOptions: List<com.noki.vpn.data.VpnEndpointOption>,
    val gamingModeEnabled: Boolean,
)

internal fun runtimeSettingsSyncKey(state: AppUiState): RuntimeSettingsSyncKey = RuntimeSettingsSyncKey(
    profile = state.profile,
    selectedCountryCode = state.userProfile.selectedCountryCode,
    serverSelectionMode = state.userProfile.serverSelectionMode,
    selectedNodeId = state.userProfile.selectedNodeId,
    selectedPlanCode = state.userProfile.selectedPlanCode,
    protocol = state.advancedSettings.protocol,
    endpointSelectionMode = state.advancedSettings.endpointSelectionMode,
    manualEndpointCode = state.advancedSettings.manualEndpointCode,
    manualEndpointGroupKey = state.advancedSettings.manualEndpointGroupKey,
    endpointOptions = state.endpointOptions,
    gamingModeEnabled = state.advancedSettings.gamingModeEnabled,
)

internal fun applyRuntimeOwnedSettingsSnapshot(
    current: AppUiState,
    stored: StoredSettings,
): AppUiState = current.copy(
    profile = stored.profile,
    endpointOptions = stored.endpointOptions,
    endpointOptionsCountryCode = current.userProfile.selectedCountryCode,
    userProfile = current.userProfile.copy(
        selectedPlanCode = stored.userProfile.selectedPlanCode,
        selectedPlanCodeRaw = stored.userProfile.selectedPlanCodeRaw,
        selectedServerCode = stored.userProfile.selectedServerCode,
        actualCountryCode = stored.userProfile.actualCountryCode,
    ),
    advancedSettings = current.advancedSettings.copy(
        manualEndpointCode = stored.advancedSettings.manualEndpointCode,
        manualEndpointGroupKey = stored.advancedSettings.manualEndpointGroupKey,
    ),
    currentDeviceAccessRole = stored.backendDeviceAccessRole.ifBlank { "owner" },
)

internal fun AppUiRuntime.syncRuntimeSettingsFromRepository() {
    if (runtimeSettingsSyncJob != null) return
    val attempt = authSessionCoordinator.attempt() ?: return
    val expectedUiKey = runtimeSettingsSyncKey(uiState)
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val ownerJob = currentCoroutineContext()[Job]
            ?: error("Runtime settings sync coroutine has no Job")
        try {
            val stored = withContext(Dispatchers.IO) { RuntimeProfilePolicy.normalize(repository.load()) }
            if (
                runtimeSettingsSyncJob !== ownerJob ||
                !ownerJob.isActive ||
                !authSessionCoordinator.isCurrent(attempt) ||
                runtimeSettingsSyncKey(uiState) != expectedUiKey ||
                (uiState.connectionState != VpnConnectionState.CONNECTED &&
                    uiState.connectionState != VpnConnectionState.CONNECTING)
            ) {
                return@launch
            }
            authSessionCoordinator.restore(stored)
            backendDeviceKey = stored.backendDeviceKey
            backendDeviceId = stored.backendDeviceId
            backendDeviceAccessRole = stored.backendDeviceAccessRole.ifBlank { "owner" }
            uiState = applyRuntimeOwnedSettingsSnapshot(uiState, stored)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (runtimeSettingsSyncJob === ownerJob) runtimeSettingsSyncJob = null
        }
    }
    runtimeSettingsSyncJob = job
    job.start()
}

internal fun AppUiRuntime.getVpnPermissionIntent(context: Context): Intent? {
    return VpnService.prepare(context)
}

internal fun AppUiRuntime.startVpn() {
    if (uiState.androidUpdate.requiresUpdate) {
        uiState = uiState.copy(inlineMessage = null)
        refreshAndroidUpdateStatus()
        return
    }
    val token = authSessionCoordinator.snapshot().accessToken
    if (token.isNullOrBlank()) {
        uiState = VpnStartStateReducer.notAuthenticatedState(uiState)
        return
    }
    if (VpnStartStateReducer.freeTrafficLimitReached(uiState)) {
        uiState = withFreeTrafficLimitNotice(
            VpnStartStateReducer.freeTrafficLimitState(uiState),
            forceDialog = true,
        )
        return
    }

    uiState = VpnStartStateReducer.connectingState(uiState)
    recordAppLog(
        category = "vpn",
        message = "connect_start",
        details = "country=${uiState.userProfile.selectedCountryCode}; protocol=${uiState.advancedSettings.protocol.name.lowercase(Locale.ROOT)}",
    )

    vpnCommands.start()
}

internal fun AppUiRuntime.prepareVpnInAdvance() {
    if (uiState.isAuthenticated && uiState.connectionState != VpnConnectionState.DISCONNECTED) return
    vpnPreparationJob?.cancel()
    if (uiState.androidUpdate.requiresUpdate || !uiState.isAuthenticated || uiState.connectionState != VpnConnectionState.DISCONNECTED ||
        uiState.advancedSettings.ruRelayEnabled || uiState.advancedSettings.multiHop.enabled) return
    val attempt = authSessionCoordinator.attempt() ?: return
    vpnPreparationJob = scope.launch {
        kotlinx.coroutines.delay(350)
        try {
            withContext(Dispatchers.IO) { com.noki.vpn.vpn.GeositeAsset.ensureBundledFallback(application) }
            authSessionCoordinator.run(attempt) { token ->
                vpnStartCoordinator.prepareInAdvance(token, repository.load(), syncedDevices)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Connecting retains the normal fresh-session path. */ }
    }
}

internal fun AppUiRuntime.startTemporaryVpn() {
    if (uiState.isAuthenticated) {
        startVpn()
        return
    }
    uiState = VpnStartStateReducer.connectingState(uiState).copy(
        vpnRuntimeMode = VpnRuntimeMode.AUTH_TEMP,
    )
    recordAppLog(category = "vpn", message = "temporary_vpn_connect_requested")
    vpnCommands.startTemporary()
}

internal fun AppUiRuntime.disconnect() {
    vpnCommands.stop()
    recordAppLog("vpn", message = "disconnect_requested")
    uiState = uiState.copy(
        inlineMessage = tr(uiState.personalizationSettings.language, "Отключение VPN…", "Disconnecting VPN…"),
    )
}

internal fun AppUiRuntime.withDeviceLimitNotice(
    newState: AppUiState,
    forceDialog: Boolean = false,
): AppUiState {
    var state = newState
    if (state.connectionState == VpnConnectionState.CONNECTED || state.connectionState == VpnConnectionState.CONNECTING) {
        vpnCommands.stop()
        repository.clearVpnRuntimeState()
        state = state.copy(
            connectionState = VpnConnectionState.FAILED,
            connectedAtMillis = null,
            connectionReason = deviceLimitMessage(state.personalizationSettings.language),
            inlineMessage = null,
        )
    }
    if (!forceDialog && state.dialog != null) return state
    return state.copy(
        dialog = AppDialog.DeviceLimitReached,
        inlineMessage = null,
    )
}

internal fun AppUiRuntime.withFreeTrafficLimitNotice(
    newState: AppUiState,
    forceDialog: Boolean = false,
): AppUiState {
    var state = newState
    val limitReached = VpnStartStateReducer.freeTrafficLimitReached(state)
    val legacyNotice = connectionUiCoordinator.limitNotice(limitReached, forceDialog, state.dialog != null)
    val profile = state.userProfile
    val trialExhausted = state.isAuthenticated && profile.isFreePlan && profile.freeLocationsOnly &&
        profile.trialTrafficRemainingGb?.let { it.isFinite() && it <= 0.0 } == true
    if (!limitReached && !trialExhausted) return state
    val notice = if (trialExhausted) ConnectionUiCoordinator.LimitNoticeDecision(
        showNotification = repository.claimFreeTrialNotification(profile.backendUserId),
        showDialog = state.dialog == null && repository.claimFreeTrialDialog(profile.backendUserId),
    ) else legacyNotice

    val language = state.personalizationSettings.language
    if (limitReached && (state.connectionState == VpnConnectionState.CONNECTED || state.connectionState == VpnConnectionState.CONNECTING)) {
        vpnCommands.stop()
        repository.clearVpnRuntimeState()
        state = state.copy(
            connectionState = VpnConnectionState.FAILED,
            connectedAtMillis = null,
            connectionReason = tr(language, "Бесплатный трафик закончился", "Free traffic limit reached"),
            inlineMessage = null,
        )
    }
    if (notice.showNotification) {
        FreeTrafficLimitNotifier.show(
            context = application,
            title = tr(language, "Пробный трафик закончился", "Trial traffic used up"),
            message = tr(
                language,
                "Вы использовали пробный трафик. Можно остаться на Free или выбрать подписку с полным доступом.",
                "You have used your trial traffic. You can stay on Free or choose a subscription with full access.",
            ),
        )
    }

    if (!notice.showDialog) return state

    return state.copy(
        dialog = AppDialog.FreeTrafficLimitReached,
        inlineMessage = null,
    )
}
