package com.noki.vpn.data

object RuntimeProfilePolicy {
    fun normalizeAdvancedSettings(settings: AdvancedSettings, profile: UserProfile): AdvancedSettings {
        return settings.copy(
            endpointSelectionMode = if (settings.protocol != VpnProtocol.AUTO) EndpointSelectionMode.MANUAL
                else settings.endpointSelectionMode,
            ruRelayEnabled = settings.ruRelayEnabled && !settings.gamingModeEnabled,
            youtubeNoAdsEnabled = settings.youtubeNoAdsEnabled && !profile.isFreePlan,
        )
    }

    fun profileAfterServerSelection(profile: VlessProfile): VlessProfile = profile.withoutRuntimeCredentials()

    fun normalize(settings: StoredSettings): StoredSettings {
        if (settings.advancedSettings.ruRelayEnabled) return settings
        return settings.copy(
            profile = profileAfterProtocolChange(
                profile = settings.profile,
                protocol = settings.advancedSettings.effectiveProtocol,
            ),
        )
    }

    fun profileAfterProtocolChange(
        profile: VlessProfile,
        protocol: VpnProtocol,
    ): VlessProfile {
        if (protocol == VpnProtocol.AUTO) return profile
        return if (if (protocol == VpnProtocol.NAIVE) profile.proxyType == "naive"
            else if (protocol == VpnProtocol.NOKINV) profile.proxyType == "nokinv"
            else profile.proxyType != "nokinv" && profile.security.equals(protocol.name, ignoreCase = true)) {
            profile
        } else {
            profile.withoutRuntimeCredentials()
        }
    }

    fun profileAfterLocationSelection(
        profile: VlessProfile,
        selectedLocationCode: String,
        endpointOptions: List<VpnEndpointOption>,
    ): VlessProfile {
        val selectedCode = selectedLocationCode.trim()
        if (selectedCode.isBlank() || profile.endpointCode.isBlank()) return profile
        val endpoint = endpointOptions.firstOrNull { it.code == profile.endpointCode }
            ?: return profile.withoutRuntimeCredentials()
        return if (endpoint.locationCode.equals(selectedCode, ignoreCase = true)) {
            profile
        } else {
            profile.withoutRuntimeCredentials()
        }
    }

    fun profileForManualEndpoint(
        profile: VlessProfile,
        option: VpnEndpointOption,
    ): VlessProfile {
        return profile.copy(
            endpointCode = option.code,
            proxyType = option.proxyType,
            transport = option.transport,
            transportMode = option.transportMode,
            host = option.host,
            port = option.port.toString(),
            security = option.security,
            uuid = "",
            vpnUsername = "",
            nokinvToken = "",
            nokinvCredentialGeneration = 0,
            serverName = "",
            requestHost = "",
            path = "",
            alpn = "",
            publicKey = "",
            shortId = "",
            flow = "",
            multiHop = null,
        )
    }

    fun isCachedProfileUsable(settings: StoredSettings): Boolean {
        if (settings.advancedSettings.multiHop.enabled || settings.advancedSettings.ruRelayEnabled) return false
        val endpointCode = settings.profile.endpointCode.trim()
        if (endpointCode.isBlank()) return false
        if (settings.endpointOptions.none { it.code == endpointCode }) return false
        return VpnProfileValidator.isUsable(settings)
    }

    private fun VlessProfile.withoutRuntimeCredentials(): VlessProfile {
        return copy(
            endpointCode = "",
            uuid = "",
            vpnUsername = "",
            nokinvToken = "",
            nokinvCredentialGeneration = 0,
            serverName = "",
            requestHost = "",
            path = "",
            alpn = "",
            publicKey = "",
            shortId = "",
            flow = "",
            multiHop = null,
        )
    }
}
