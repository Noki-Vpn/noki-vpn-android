package com.noki.vpn

import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.RuntimeProfilePolicy
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnSessionCoordinator

object EndpointOptionsStateReducer {
    data class Result(
        val state: AppUiState,
        val syncedDevices: List<BackendDevice>,
    )

    fun applyEndpointOptions(
        current: AppUiState,
        result: VpnSessionCoordinator.EndpointOptionsResult,
        syncedDevices: List<BackendDevice>,
    ): Result? {
        if (current.advancedSettings.ruRelayEnabled ||
            current.advancedSettings.gamingModeEnabled) return null
        if (result.session.multiHop?.selection != current.advancedSettings.multiHop.takeIf { it.enabled }) return null
        val options = result.endpointOptions
        if (options.isEmpty()) return null

        val manualMode = current.advancedSettings.endpointSelectionMode == EndpointSelectionMode.MANUAL
        val selectedOption = selectedOption(
            current = current,
            options = options,
        )
        val nextAdvanced = if (manualMode && selectedOption != null) {
            current.advancedSettings.copy(
                manualEndpointCode = selectedOption.code,
                manualEndpointGroupKey = EndpointGroupPolicy.groupKey(selectedOption),
            )
        } else {
            current.advancedSettings
        }
        val nextProfile = if (manualMode && selectedOption != null) {
            RuntimeProfilePolicy.profileForManualEndpoint(current.profile, selectedOption)
        } else if (manualMode) {
            RuntimeProfilePolicy.profileAfterServerSelection(current.profile)
        } else {
            current.profile
        }
        return Result(
            state = current.copy(
                advancedSettings = nextAdvanced,
                endpointOptions = options,
                profile = nextProfile,
                userProfile = current.userProfile.copy(
                    selectedServerCode = result.session.locationCode,
                ),
                currentDeviceAccessRole = result.backendDeviceAccessRole,
            ),
            syncedDevices = syncedDevices.filterNot { it.deviceKey == result.currentDevice.deviceKey } + result.currentDevice,
        )
    }

    private fun selectedOption(
        current: AppUiState,
        options: List<VpnEndpointOption>,
    ): VpnEndpointOption? {
        val currentGroupKey = EndpointGroupPolicy.resolveManualGroupKey(
            settings = current.advancedSettings,
            endpointOptions = options,
            profile = current.profile,
        )
        val manualOptions = EndpointGroupPolicy.manualOptions(options)
        if (currentGroupKey.isNotBlank()) return manualOptions.firstOrNull {
            EndpointGroupPolicy.groupKey(it) == currentGroupKey
        }
        return manualOptions.firstOrNull {
            VpnProtocol.fromBackendCode(it.security, it.proxyType) == current.advancedSettings.protocol
        }
    }
}
