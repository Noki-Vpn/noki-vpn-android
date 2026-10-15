package com.noki.vpn

import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.BackendVpnSession
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.data.VpnSessionCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointOptionsRefreshPolicyTest {
    @Test
    fun `manual picker retains four native profiles and rejects retired or unknown transports`() {
        val reality = VpnEndpointOption(code = "vision", locationCode = "lv", host = "example.test",
            proxyType = "vless", security = "reality", transport = "tcp")
        val stream = reality.copy(code = "stream", transport = "xhttp", transportMode = "stream-up")
        val tls = reality.copy(code = "tls", security = "tls")
        val hy2 = reality.copy(code = "hy2", proxyType = "hysteria", security = "tls", transport = "hysteria")
        val rejected = listOf(stream.copy(code = "packet", transportMode = "packet-up"),
            reality.copy(code = "upgrade", transport = "httpupgrade"),
            reality.copy(code = "unknown", transport = "new-protocol"),
            stream.copy(code = "missing-mode", transportMode = ""))

        assertEquals(setOf("vision", "stream", "tls", "hy2"),
            EndpointGroupPolicy.manualOptions(listOf(reality, stream, tls, hy2) + rejected).map { it.code }.toSet())
    }

    @Test
    fun `cached endpoint options are reused only for the same country`() {
        assertFalse(
            shouldRefreshEndpointOptions(
                force = false,
                optionsCount = 2,
                loadedCountryCode = "PL",
                selectedCountryCode = "PL",
            ),
        )
        assertTrue(
            shouldRefreshEndpointOptions(
                force = false,
                optionsCount = 2,
                loadedCountryCode = "PL",
                selectedCountryCode = "LV",
            ),
        )
        assertTrue(
            shouldRefreshEndpointOptions(
                force = true,
                optionsCount = 2,
                loadedCountryCode = "PL",
                selectedCountryCode = "PL",
            ),
        )
    }

    @Test
    fun `manual endpoint is selectable only from the list loaded for current country`() {
        val stalePolish = VpnEndpointOption(
            code = "pl-1",
            locationCode = "pl-1",
            host = "192.0.2.10",
        )
        val currentLatvian = VpnEndpointOption(
            code = "lv-1",
            locationCode = "lv-1",
            host = "192.0.2.20",
            security = "reality",
            transport = "tcp",
            port = 443,
        )
        val currentLatvianAlternatePort = currentLatvian.copy(code = "lv-2", port = 8443)
        val groupedLatvian = EndpointGroupPolicy.manualOptions(
            listOf(currentLatvian, currentLatvianAlternatePort),
        ).single()

        assertFalse(
            isManualEndpointSelectable(
                option = stalePolish,
                endpointOptions = listOf(stalePolish),
                loadedCountryCode = "PL",
                selectedCountryCode = "LV",
            ),
        )
        assertFalse(
            isManualEndpointSelectable(
                option = stalePolish,
                endpointOptions = listOf(currentLatvian),
                loadedCountryCode = "LV",
                selectedCountryCode = "LV",
            ),
        )
        assertTrue(
            isManualEndpointSelectable(
                option = groupedLatvian,
                endpointOptions = listOf(currentLatvian, currentLatvianAlternatePort),
                loadedCountryCode = "LV",
                selectedCountryCode = "LV",
            ),
        )
    }

    @Test
    fun `manual endpoint list is hidden until its country identity is proven`() {
        val option = VpnEndpointOption(
            code = "pl-1",
            locationCode = "pl-1",
            host = "192.0.2.10",
        )
        val cachedWithoutIdentity = AppUiState(
            userProfile = com.noki.vpn.data.UserProfile(selectedCountryCode = "PL"),
            endpointOptions = listOf(option),
            endpointOptionsCountryCode = null,
        )
        val proven = cachedWithoutIdentity.copy(endpointOptionsCountryCode = "PL")

        assertTrue(manualEndpointOptionsForCurrentCountry(cachedWithoutIdentity).isEmpty())
        assertTrue(manualEndpointOptionsForCurrentCountry(proven).isNotEmpty())
    }

    @Test
    fun `late normal endpoint result cannot replace Reality preference after route mode changes`() {
        val reality = VpnEndpointOption(code = "lv-reality", locationCode = "lv", security = "reality")
        val tls = VpnEndpointOption(code = "lv-tls", locationCode = "lv", security = "tls")
        val advanced = com.noki.vpn.data.AdvancedSettings(
            protocol = VpnProtocol.REALITY,
            endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointCode = reality.code,
            ruRelayEnabled = true,
        )
        val state = AppUiState(
            advancedSettings = advanced,
            userProfile = UserProfile(selectedCountryCode = "LV"),
            endpointOptions = listOf(reality),
            endpointOptionsCountryCode = "LV",
        )
        val device = BackendDevice("device-id", "device-key", "phone", null, "android", "owner", true, null)
        val result = VpnSessionCoordinator.EndpointOptionsResult(
            session = BackendVpnSession(
                canConnect = true, profileCode = "test", locationCode = "lv", locationName = "Latvia",
                endpointCode = tls.code, entryHost = "example.test", entryPort = 443,
                serverName = "example.test", proxyType = "vless", transport = "tcp",
                transportMode = null, security = "tls", fingerprint = null, requestHost = null,
                path = null, alpn = null, allowInsecure = false, enableMux = false,
                randomUserAgent = false, publicKey = null, shortId = null,
                vpnUsername = "id", vpnSecret = "secret", flow = null, planCode = null,
                endpointCandidates = emptyList(),
            ),
            endpointOptions = listOf(tls), currentDevice = device,
            backendDeviceKey = device.deviceKey, backendDeviceId = device.id,
            backendDeviceAccessRole = device.accessRole,
        )

        assertNull(EndpointOptionsStateReducer.applyEndpointOptions(state, result, listOf(device)))
        assertEquals(VpnProtocol.REALITY, state.advancedSettings.protocol)
        assertEquals(reality.code, state.advancedSettings.manualEndpointCode)
        assertFalse(isManualEndpointSelectable(tls, listOf(tls), "LV", "LV", ruRelayEnabled = true))
        val multiHopState = state.copy(advancedSettings = advanced.copy(
            ruRelayEnabled = false,
            multiHop = com.noki.vpn.data.MultiHopSettings(enabled = true),
        ))
        assertNull(EndpointOptionsStateReducer.applyEndpointOptions(multiHopState, result, listOf(device)))
    }
}
