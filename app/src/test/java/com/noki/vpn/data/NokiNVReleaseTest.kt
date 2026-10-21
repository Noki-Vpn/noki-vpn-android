package com.noki.vpn.data

import com.noki.vpn.vpn.VpnConnectionPreparer
import com.noki.vpn.vpn.VpnPreparationStrategy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NokiNVReleaseTest {
    private val nv = tcpCandidate("nv").copy(proxyType = "nokinv", transport = "nokinv", security = "tls",
        publicKey = null, shortId = null, flow = null)
    private val hy = hysteriaCandidate("hy")

    @Test fun cachedHysteriaFallbackReconsidersPrimaryButRemainsUsableWhenApiIsOffline() = runBlocking {
        val hyProfile = EndpointSelector.profileFromCandidate(hy, "12345678-1234-1234-1234-123456789abc")
        var stored = DefaultStoredSettingsFactory.create().copy(
            backendAccessToken = "fixture-token", isAuthenticated = true,
            advancedSettings = AdvancedSettings(ruRelayEnabled = false), profile = hyProfile,
            endpointOptions = listOf(hy, nv).map { VpnEndpointOption(code = it.code, host = it.entryHost, port = it.entryPort,
                proxyType = it.proxyType, transport = it.transport, security = it.security) },
        )
        assertTrue(RuntimeProfilePolicy.isCachedProfileUsable(stored))
        var attempts = 0
        val store = object : AtomicStoredSettingsStore {
            override fun load() = stored
            override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
                val (next, result) = transform(stored); stored = next; return result
            }
        }
        val preparer = VpnConnectionPreparer(store, { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, allowCached, _ ->
                attempts++; assertTrue(allowCached)
                VpnStartCoordinator.StartDecision.CachedFallback(BackendException("offline", 503))
            }, refreshAccessToken = { error("refresh must not run") })
        assertTrue(preparer.prepare(VpnPreparationStrategy.CachedFirst) is VpnConnectionPreparer.Outcome.Success)
        assertEquals(1, attempts)
        stored = stored.copy(
            advancedSettings = stored.advancedSettings.copy(protocol = VpnProtocol.AUTO, endpointSelectionMode = EndpointSelectionMode.AUTO),
            profile = hyProfile.copy(endpointCode = "nv", proxyType = "nokinv", transport = "nokinv",
                nokinvToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 }),
                nokinvCredentialGeneration = 1),
        )
        assertTrue(preparer.prepare(VpnPreparationStrategy.CachedFirst) is VpnConnectionPreparer.Outcome.Success)
        assertEquals(2, attempts)
        stored = stored.copy(profile = hyProfile, advancedSettings = stored.advancedSettings.copy(protocol = VpnProtocol.TLS,
            endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointGroupKey = EndpointGroupPolicy.groupKey(hy)))
        assertTrue(preparer.prepare(VpnPreparationStrategy.CachedFirst) is VpnConnectionPreparer.Outcome.Success)
        assertEquals(2, attempts)
    }

    @Test fun autoPrefersNokiNVDespiteFasterHysteriaAndFallsBackAfterFailure() {
        for (network in EndpointRankingPolicy.NetworkKind.entries) {
            val health = mapOf("hy" to EndpointHealth(score = 100, latencyEwmaMs = 1, latencyUpdatedAtMillis = 1000))
            val candidates = listOf(hy, nv)
            assertEquals(listOf("nv", "hy"), EndpointRankingPolicy.rankCandidates(candidates, health, network, 1000).map { it.code })
            val failed = health + ("nv" to EndpointRankingPolicy.updateAfterResult(success = false, nowMillis = 1000))
            assertEquals("nv", EndpointRankingPolicy.select(candidates, failed, network, 1000, { 0 })?.candidate?.code)
            assertEquals("hy", EndpointRankingPolicy.select(candidates, failed, network, 1000, { 0 }, excludedCodes = setOf("nv"))?.candidate?.code)
            assertEquals("hy", EndpointRankingPolicy.select(candidates, health, network, 1000, { 0 }, excludedCodes = setOf("nv"))?.candidate?.code)
        }
    }

    @Test fun sessionCarriesBothCredentialsAndManualChoiceNeverChangesTransport() {
        val xhttp = tcpCandidate("xhttp").copy(transport = "xhttp", transportMode = "stream-up", path = "/tunnel", flow = null)
        val session = BackendVpnSession(canConnect = true, profileCode = "auto", locationCode = "lv1", locationName = "Latvia",
            endpointCode = "nv", entryHost = "nv.example.com", entryPort = 443, serverName = "nv.example.com",
            proxyType = "nokinv", transport = "nokinv", transportMode = null, security = "tls", fingerprint = null,
            requestHost = null, path = "/tunnel", alpn = null, allowInsecure = false, enableMux = false,
            randomUserAgent = false, publicKey = null, shortId = null, vpnUsername = "fixture", flow = null, planCode = "plus",
            endpointCandidates = listOf(tcpCandidate("vless"), xhttp, hy, nv),
            vpnSecret = "12345678-1234-1234-1234-123456789abc",
            nokinvToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 }),
            nokinvCredentialGeneration = 1)
        fun select(settings: AdvancedSettings, candidates: List<BackendEndpointCandidate> = session.endpointCandidates) =
            EndpointSelector.selectionForSession(RuntimeEnvironment.getApplication(), session.copy(endpointCandidates = candidates),
                settings, emptyMap(), { 0 }, networkKind = EndpointRankingPolicy.NetworkKind.WIFI)
        val auto = AdvancedSettings(ruRelayEnabled = false)
        val profile = select(auto).profile
        assertEquals("nokinv", profile.proxyType)
        assertEquals(session.nokinvToken, profile.nokinvToken)
        assertTrue(VpnProfileValidator.isUsable(profile, auto))
        val fallback = select(auto, listOf(hy)).profile
        assertEquals("hysteria", fallback.proxyType)
        assertEquals(session.vpnSecret, fallback.uuid)
        assertTrue(VpnProfileValidator.isUsable(fallback, auto))
        val manual = auto.copy(protocol = VpnProtocol.NOKINV, endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointGroupKey = EndpointGroupPolicy.groupKey(nv))
        assertEquals("nv", select(manual).endpointCode)
        assertFalse(VpnProfileValidator.isUsable(fallback, manual))
        assertEquals(setOf("nv", "hy", "xhttp"), EndpointSelector.optionsFromSession(session).map { it.code }.toSet())
        assertFalse(VpnProfileValidator.isUsable(EndpointSelector.profileFromCandidate(tcpCandidate("vless"), session.vpnSecret), auto))
        val reality = auto.copy(protocol = VpnProtocol.REALITY, endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointGroupKey = EndpointGroupPolicy.groupKey(xhttp))
        assertEquals("xhttp", select(reality).endpointCode)
        assertFalse(VpnProfileValidator.isUsable(profile, reality))
        val gaming = reality.copy(gamingModeEnabled = true)
        assertEquals("nv", select(gaming).endpointCode)
        assertTrue(VpnProfileValidator.isUsable(profile, gaming))
        assertFalse(VpnProfileValidator.isUsable(fallback, gaming))
        assertEquals(gaming, EndpointGroupPolicy.settingsAfterSelection(gaming, "nv", EndpointSelector.optionsFromSession(session)))
        val pair = MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "11111111-1111-4111-8111-111111111111"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "22222222-2222-4222-8222-222222222222"))
        val route = BackendMultiHopSession(pair, pair.entry!!.nodeId!!, pair.exit!!.nodeId!!, "8.8.8.8", "a".repeat(64))
        val hopCandidate = nv.copy(nodeId = route.exitNodeId, entryHost = route.entryIp,
            connectIp = route.entryIp, entryPort = 20000)
        val multiHopGaming = gaming.copy(multiHop = pair)
        val hopProfile = EndpointSelector.selectionForSession(RuntimeEnvironment.getApplication(),
            session.copy(multiHop = route, endpointCandidates = listOf(hopCandidate)), multiHopGaming,
            emptyMap(), { 0 }, networkKind = EndpointRankingPolicy.NetworkKind.WIFI).profile
        assertEquals(route.entryIp, hopProfile.host)
        assertEquals("20000", hopProfile.port)
        assertEquals(session.nokinvToken, hopProfile.nokinvToken)
        assertTrue(VpnProfileValidator.isUsable(hopProfile, multiHopGaming))
    }

    @Test fun ordinaryReleasePreservesRuMultiHopAndTheSavedDirectSelection() {
        val previous = AdvancedSettings(protocol = VpnProtocol.REALITY, ruRelayEnabled = true,
            multiHop = MultiHopSettings(enabled = true), endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointCode = "old", manualEndpointGroupKey = "reality|tcp|")
        val normalized = RuntimeProfilePolicy.normalizeAdvancedSettings(previous, UserProfile())
        assertEquals(previous, normalized)
        assertEquals(VpnProtocol.WIREGUARD, normalized.effectiveProtocol)
        assertEquals(VpnProtocol.AUTO, normalized.copy(ruRelayEnabled = false).effectiveProtocol)
    }
}
