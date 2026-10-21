package com.noki.vpn.data

object EndpointTransportPolicy {
    fun isDirectCandidate(candidate: BackendEndpointCandidate): Boolean = isDirectTransport(
        candidate.proxyType, candidate.security, candidate.normalizedTransport(), candidate.normalizedTransportMode(),
    )

    fun isDirectProfile(profile: VlessProfile): Boolean = isDirectTransport(
        profile.proxyType, profile.security, profile.transport, profile.transportMode,
    )

    fun isDirectOption(option: VpnEndpointOption): Boolean = isDirectTransport(
        option.proxyType, option.security, option.transport, option.transportMode,
    )

    private fun isDirectTransport(proxyType: String, security: String, transport: String, mode: String): Boolean =
        proxyType.equals("nokinv", true) || proxyType.equals("hysteria", true) ||
            (proxyType.equals("vless", true) && security.equals("reality", true) &&
                transport.equals("xhttp", true) && (mode.isBlank() || mode.equals("stream-up", true)))

    fun isHysteria(candidate: BackendEndpointCandidate): Boolean {
        return candidate.proxyType.equals("hysteria", ignoreCase = true) ||
            candidate.normalizedTransport() == "hysteria"
    }

    fun requiresTcpPrecheck(candidate: BackendEndpointCandidate): Boolean {
        return !isHysteria(candidate) && !candidate.proxyType.equals("nokinv", ignoreCase = true)
    }
}
