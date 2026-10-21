package com.noki.vpn.data

import java.util.Locale

object EndpointGroupPolicy {
    fun groupKey(option: VpnEndpointOption): String {
        return groupKey(
            security = option.security,
            transport = normalizeTransport(option.transport),
            transportMode = normalizeTransportMode(option.transport, option.transportMode),
        )
    }

    fun groupKey(candidate: BackendEndpointCandidate): String =
        groupKey(
            security = candidate.security,
            transport = candidate.normalizedTransport(),
            transportMode = candidate.normalizedTransportMode(),
        )

    fun groupKey(profile: VlessProfile): String = groupKey(
        profile.security, normalizeTransport(profile.transport),
        normalizeTransportMode(profile.transport, profile.transportMode),
    )

    fun manualOptions(options: List<VpnEndpointOption>): List<VpnEndpointOption> {
        val grouped = linkedMapOf<String, MutableList<VpnEndpointOption>>()
        options.filter { !it.canaryOnly && EndpointSecurityPolicy.isAllowedOption(it) &&
            EndpointTransportPolicy.isDirectOption(it) }.forEach { option ->
            grouped.getOrPut(groupKey(option)) { mutableListOf() }.add(option)
        }
        return grouped.map { (key, group) ->
            val first = group.first()
            val ports = group.map { it.port }.distinct().sorted()
            first.copy(
                label = displayLabelFor(first),
                endpointGroupKey = key,
                groupSize = group.size,
                groupPorts = ports,
            )
        }
    }

    fun resolveManualGroupKey(
        settings: AdvancedSettings,
        endpointOptions: List<VpnEndpointOption>,
        profile: VlessProfile,
    ): String {
        settings.manualEndpointGroupKey.trim().takeIf { it.isNotBlank() }?.let { return normalizedGroupKey(it) }
        val code = settings.manualEndpointCode.ifBlank { profile.endpointCode }
        return endpointOptions.firstOrNull { it.code == code }?.let(::groupKey).orEmpty()
    }

    fun settingsAfterSelection(
        settings: AdvancedSettings,
        endpointCode: String,
        endpointOptions: List<VpnEndpointOption>,
    ): AdvancedSettings {
        if (settings.gamingModeEnabled || settings.endpointSelectionMode != EndpointSelectionMode.MANUAL) return settings
        val groupKey = endpointOptions.firstOrNull { it.code == endpointCode }?.let(::groupKey)
            ?: settings.manualEndpointGroupKey
        return settings.copy(
            manualEndpointCode = endpointCode,
            manualEndpointGroupKey = groupKey,
        )
    }

    fun manualCandidates(
        candidates: List<BackendEndpointCandidate>,
        settings: AdvancedSettings,
    ): List<BackendEndpointCandidate> {
        val targetGroup = normalizedGroupKey(settings.manualEndpointGroupKey).ifBlank {
            candidates.firstOrNull { it.code == settings.manualEndpointCode.trim() }
                ?.let(::groupKey)
                .orEmpty()
        }
        if (targetGroup.isNotBlank()) {
            return candidates.filter { groupKey(it) == targetGroup }
        }
        val legacyCode = settings.manualEndpointCode.trim()
        return candidates.firstOrNull { it.code == legacyCode }?.let(::listOf) ?: candidates
    }

    fun selectManualCandidate(
        candidates: List<BackendEndpointCandidate>,
        settings: AdvancedSettings,
        health: Map<String, EndpointHealth>,
        nowMillis: Long,
        rotationIndex: (String) -> Int,
    ): EndpointRankingPolicy.Selection? {
        val groupCandidates = manualCandidates(candidates, settings)
        return EndpointRankingPolicy.select(
            candidates = groupCandidates,
            health = health,
            networkKind = EndpointRankingPolicy.NetworkKind.OTHER,
            nowMillis = nowMillis,
            rotationIndex = rotationIndex,
        )
    }

    private fun groupKey(
        security: String,
        transport: String,
        transportMode: String,
    ): String = listOf(
        security.lowercase(Locale.ROOT).ifBlank { "tls" },
        transport.lowercase(Locale.ROOT).ifBlank { "tcp" },
        transportMode.lowercase(Locale.ROOT),
    ).joinToString("|")

    private fun normalizedGroupKey(raw: String): String {
        val parts = raw.trim().split('|')
        // Saved selections before 1.9.5 also included location and priority.
        val transport = when (parts.size) {
            5 -> parts.subList(1, 4)
            3 -> parts
            else -> return ""
        }
        return groupKey(transport[0], normalizeTransport(transport[1]),
            normalizeTransportMode(transport[1], transport[2]))
    }

    fun displayLabelFor(option: VpnEndpointOption): String {
        if (option.proxyType == "nokinv") return "NokiNV / Auto"
        if (option.proxyType.equals("hysteria", ignoreCase = true) || normalizeTransport(option.transport) == "hysteria") {
            return "Hysteria2 / UDP"
        }
        val security = when (val normalized = option.security.lowercase(Locale.ROOT).ifBlank { "tls" }) {
            "tls" -> "TLS"
            "reality" -> "Reality"
            else -> normalized.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
        val transport = normalizeTransport(option.transport)
        val transportLabel = when (transport) {
            "xhttp" -> "XHTTP ${normalizeTransportMode(option.transport, option.transportMode)}".trim()
            "tcp" -> "TCP"
            else -> transport.uppercase(Locale.ROOT)
        }
        return "$security / $transportLabel"
    }

    private fun normalizeTransport(raw: String): String {
        return when (raw.lowercase(Locale.ROOT).trim()) {
            "raw" -> "tcp"
            "hysteria2", "hy2" -> "hysteria"
            "" -> "tcp"
            else -> raw.lowercase(Locale.ROOT).trim()
        }
    }

    private fun normalizeTransportMode(
        transport: String,
        rawMode: String,
    ): String {
        val mode = rawMode.lowercase(Locale.ROOT).trim()
        if (mode.isNotBlank()) return mode
        return if (normalizeTransport(transport) == "xhttp") "stream-up" else ""
    }
}
