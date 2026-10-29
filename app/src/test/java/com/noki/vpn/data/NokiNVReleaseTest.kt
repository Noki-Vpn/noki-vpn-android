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

    @Test fun manualNokiNvUsesOnlyQuicAcrossCatalogRefreshAndCountryChange() {
        val option = VpnEndpointOption(code = nv.code, host = nv.entryHost, port = nv.entryPort,
            proxyType = "nokinv", transport = "nokinv", security = "tls")
        val options = EndpointGroupPolicy.manualOptions(listOf(option, option.copy(code = "other-node", locationCode = "other")))
        assertEquals(listOf("NokiNV"), options.map { it.label })
        for (mode in listOf("quic")) {
            val selected = options.single { it.transportMode == mode }
            val settings = AdvancedSettings(protocol = VpnProtocol.NOKINV, endpointSelectionMode = EndpointSelectionMode.MANUAL,
                manualEndpointCode = selected.code, manualEndpointGroupKey = selected.endpointGroupKey)
            val candidate = EndpointGroupPolicy.manualCandidates(listOf(nv, hy), settings).single()
            assertEquals(nv.code, candidate.code)
            assertEquals(mode, candidate.transportMode)
            val profile = EndpointSelector.profileFromCandidate(candidate, "").copy(
                nokinvToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 }),
                nokinvCredentialGeneration = 1)
            assertTrue(VpnProfileValidator.isUsable(profile, settings, endpointOptions = listOf(option)))
            assertFalse(VpnProfileValidator.isUsable(profile.copy(transportMode = "tcp"),
                settings, endpointOptions = listOf(option)))
            assertFalse(VpnProfileValidator.isUsable(profile, settings,
                endpointOptions = listOf(option.copy(transportMode = "tcp"))))
            assertEquals(settings.manualEndpointGroupKey,
                EndpointGroupPolicy.settingsAfterSelection(settings, candidate.code, listOf(option)).manualEndpointGroupKey)
            assertEquals(settings.manualEndpointGroupKey,
                EndpointGroupPolicy.settingsAfterSelection(settings, "other-node",
                    listOf(option, option.copy(code = "other-node"))).manualEndpointGroupKey)
            assertEquals(mode, EndpointGroupPolicy.manualCandidates(listOf(nv.copy(code = "different-node")), settings).single().transportMode)
            val config = org.json.JSONObject(com.noki.vpn.vpn.VpnConfigFactory.buildProbe(profile))
            assertEquals(mode, config.getJSONArray("outbounds").getJSONObject(0).getString("transport"))
            val session = BackendVpnSession(canConnect = true, profileCode = "auto", locationCode = "lv1", locationName = "Latvia",
                endpointCode = nv.code, entryHost = nv.entryHost, entryPort = nv.entryPort, serverName = nv.serverName,
                proxyType = "nokinv", transport = "nokinv", transportMode = null, security = "tls", fingerprint = null,
                requestHost = null, path = "/tunnel", alpn = null, allowInsecure = false, enableMux = false,
                randomUserAgent = false, publicKey = null, shortId = null, vpnUsername = "fixture", flow = null, planCode = "plus",
                vpnSecret = "", endpointCandidates = listOf(nv))
            val device = BackendDevice("fixture", "fixture", "fixture", platform = "android", accessRole = "primary",
                isActive = true, lastSeenAt = null)
            val refreshed = com.noki.vpn.EndpointOptionsStateReducer.applyEndpointOptions(
                com.noki.vpn.AppUiState(advancedSettings = settings, profile = profile),
                VpnSessionCoordinator.EndpointOptionsResult(session, listOf(option), device, "fixture", "fixture", "primary"),
                emptyList())!!.state
            assertEquals(mode, refreshed.profile.transportMode)
            assertEquals(selected.endpointGroupKey, refreshed.advancedSettings.manualEndpointGroupKey)
        }
        assertEquals("quic", EndpointGroupPolicy.manualCandidates(listOf(nv), AdvancedSettings(
            endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointGroupKey = "tls|nokinv|")).single().transportMode)
        assertEquals("quic", EndpointGroupPolicy.manualCandidates(listOf(nv), AdvancedSettings(
            endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointGroupKey = "tls|nokinv|tcp")).single().transportMode)
    }

    @Test fun retiredNokiNvTcpAndAutoNeverEnterThePickerOrAutomaticSelection() {
        val candidates = listOf(nv.copy(code = "retired-tcp", transportMode = "tcp"),
            nv.copy(code = "retired-auto", transportMode = "auto"), nv, hy)
        for (network in EndpointRankingPolicy.NetworkKind.entries) {
            val ranked = EndpointRankingPolicy.rankCandidates(candidates, emptyMap(), network, 1000)
            assertEquals(listOf(hy.code, nv.code), ranked.map { it.code })
        }
        val options = candidates.map { VpnEndpointOption(code = it.code, host = it.entryHost, port = it.entryPort,
            proxyType = it.proxyType, transport = it.transport, transportMode = it.transportMode.orEmpty(), security = it.security) }
        assertEquals(listOf("NokiNV", "Hysteria2"), EndpointGroupPolicy.manualOptions(options).map { it.label })
        assertEquals("quic", EndpointSelector.profileFromCandidate(nv, "").transportMode)
    }

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

    @Test fun autoRanksByHealthAndLatencyWithoutNokiNVPreferenceAndRespectsCooldown() {
        for (network in EndpointRankingPolicy.NetworkKind.entries) {
            val health = mapOf("hy" to EndpointHealth(score = 100, latencyEwmaMs = 1, latencyUpdatedAtMillis = 1000))
            val candidates = listOf(hy, nv)
            assertEquals(listOf("hy", "nv"), EndpointRankingPolicy.rankCandidates(candidates, health, network, 1000).map { it.code })
            val equalScores = health + ("nv" to EndpointHealth(score = 100, latencyEwmaMs = 20, latencyUpdatedAtMillis = 1000))
            assertEquals("hy", EndpointRankingPolicy.select(candidates, equalScores, network, 1000, { 0 })?.candidate?.code)
            val fasterNv = equalScores + ("hy" to EndpointHealth(score = 100, latencyEwmaMs = 40, latencyUpdatedAtMillis = 1000))
            assertEquals("nv", EndpointRankingPolicy.select(candidates, fasterNv, network, 1000, { 0 })?.candidate?.code)
            val failed = health + ("nv" to EndpointRankingPolicy.updateAfterResult(success = false, nowMillis = 1000))
            assertEquals("hy", EndpointRankingPolicy.select(candidates, failed, network, 1000, { 0 })?.candidate?.code)
            assertEquals(listOf("hy"), EndpointRankingPolicy.rankCandidates(candidates, failed, network, 1000).map { it.code })
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
            endpointCandidates = listOf(tcpCandidate("vless").copy(path = null), xhttp, hy, nv),
            vpnSecret = "12345678-1234-1234-1234-123456789abc",
            nokinvToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 }),
            nokinvCredentialGeneration = 1)
        fun select(settings: AdvancedSettings, candidates: List<BackendEndpointCandidate> = session.endpointCandidates) =
            EndpointSelector.selectionForSession(RuntimeEnvironment.getApplication(), session.copy(endpointCandidates = candidates),
                settings, emptyMap(), { 0 }, networkKind = EndpointRankingPolicy.NetworkKind.WIFI)
        val auto = AdvancedSettings(ruRelayEnabled = false)
        assertEquals("vless", select(auto).endpointCode)
        val profile = select(auto.copy(protocol = VpnProtocol.NOKINV)).profile
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
        val options = EndpointSelector.optionsFromSession(session)
        assertEquals(setOf("nv", "hy", "xhttp", "vless"), options.map { it.code }.toSet())
        val tcpOption = EndpointGroupPolicy.manualOptions(options).single { it.transport == "tcp" }
        assertEquals("Reality / TCP", tcpOption.label)
        val tcpSettings = auto.copy(protocol = VpnProtocol.REALITY, endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointGroupKey = tcpOption.endpointGroupKey)
        val tcpProfile = select(tcpSettings).profile
        assertEquals("vless", tcpProfile.endpointCode)
        assertTrue(VpnProfileValidator.isUsable(tcpProfile, tcpSettings, endpointOptions = options))
        val tcpOutbound = org.json.JSONObject(com.noki.vpn.vpn.VpnConfigFactory.buildProbe(tcpProfile))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", tcpOutbound.getString("type"))
        assertEquals("xtls-rprx-vision", tcpOutbound.getString("flow"))
        assertEquals(tcpProfile.publicKey, tcpOutbound.getJSONObject("tls").getJSONObject("reality").getString("public_key"))
        assertFalse(tcpOutbound.has("transport"))
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
        assertEquals(VpnProtocol.REALITY, normalized.copy(ruRelayEnabled = false).effectiveProtocol)
    }
}
