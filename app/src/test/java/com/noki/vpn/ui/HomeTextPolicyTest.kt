package com.noki.vpn.ui

import com.noki.vpn.AppUiState
import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.PersonalizationSettings
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VpnServer
import com.noki.vpn.data.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTextPolicyTest {
    @Test
    fun multiHopCountryMenuGroupsRealNodesAndExcludesPeerBeforeGrouping() {
        val first = VpnServer("lv-1", "Latvia 1", "LV", "lv1", "probe.example", 443, true, latencyMs = 25)
        val second = first.copy(id = "lv-2", name = "Latvia 2", latencyMs = 45)
        val austria = first.copy(id = "at-1", name = "Austria 1", countryCode = "AT", latencyMs = null)
        val menu = serverMenuLocations(nodes = listOf(first, second, first, austria,
            austria.copy(id = "offline", isOnline = false)), language = AppLanguage.RU, excludedNodeId = first.id)
        assertEquals(listOf("AT", "LV"), menu.map { it.countryCode })
        assertEquals(listOf("Австрия", "Латвия"), menu.map { it.country })
        assertEquals(listOf(austria, second), menu.flatMap { it.servers })
        assertEquals(listOf(null, 45), menu.map { it.latencyMs })
        assertTrue(menu.all { it.location == null && it.foreignNodes.isEmpty() })
        assertTrue(serverMenuLocations(nodes = listOf(first), language = AppLanguage.RU,
            excludedNodeId = first.id).isEmpty())
    }

    @Test
    fun missingRuDiscoveryKeepsRealBootstrapCountriesAndNodesVisible() {
        val node = VpnServer("lv-2", "Latvia 2", "LV", "lv2", "probe.example", 443, true)
        val catalog = listOf(location(code = "LV").copy(servers = listOf(node)))
        val menu = serverMenuLocations(catalog, emptyList(), ruRelayEnabled = true)
        assertEquals(listOf("LV"), menu.map { it.key })
        assertEquals(listOf(node), menu.single().location?.servers)
        assertTrue(menu.single().foreignNodes.isEmpty())
    }

    @Test
    fun `RU protocol label stays Auto in either language and endpoint mode`() {
        for (language in AppLanguage.entries) {
            val state = AppUiState(
                advancedSettings = AdvancedSettings(ruRelayEnabled = true),
                personalizationSettings = PersonalizationSettings(language = language),
            )
            assertEquals("Auto", protocolCardLabel(state, autoEndpointSelection = true))
            assertEquals("Auto", protocolCardLabel(state, autoEndpointSelection = false))
        }
    }

    @Test
    fun `duration formatter preserves minute hour and day forms`() {
        assertEquals("00:00", formatConnectionDuration(-1L))
        assertEquals("01:02", formatConnectionDuration(62_000L))
        assertEquals("01:01:01", formatConnectionDuration(3_661_000L))
        assertEquals("01:01:01:01", formatConnectionDuration(90_061_000L))
    }

    @Test
    fun `country marker accepts only normalized two-letter country code`() {
        assertEquals("LV", countryMarkerCode(location(countryCode = " lv ")))
        assertNull(countryMarkerCode(location(countryCode = "LVA")))
        assertNull(countryMarkerCode(location(countryCode = "1v")))
    }

    @Test
    fun `country flag resource names support arbitrary ISO alpha two countries`() {
        mapOf(
            "lv" to "flag_lv",
            "FR" to "flag_fr",
            "jp" to "flag_jp",
            "BR" to "flag_br",
            "za" to "flag_za",
        ).forEach { (code, expectedResourceName) ->
            assertEquals(expectedResourceName, countryFlagResourceName(location(countryCode = code)))
        }
        assertNull(countryFlagResourceName(location(countryCode = "LVA")))
        assertNull(countryFlagResourceName(location(countryCode = "1v")))
    }

    @Test
    fun `server menu keeps source order and code identity`() {
        val first = location(code = "lv", latencyMs = 25)
        val second = location(code = "de")

        val menu = serverMenuLocations(listOf(first, second))

        assertEquals(listOf("lv", "de"), menu.map { it.key })
        assertEquals(listOf(first, second), menu.map { it.location })
        assertEquals(listOf(25, null), menu.map { it.latencyMs })
    }

    @Test
    fun `RU menu groups real overlay nodes without direct locations`() {
        val nodes = listOf(RuForeignNode("nl", "NL overlay", "NL"),
            RuForeignNode("lv", "LV dual", "LV", latencyMs = 25),
            RuForeignNode("lv2", "LV second", "LV", latencyMs = 60))
        val menu = serverMenuLocations(emptyList(), nodes, ruRelayEnabled = true, language = AppLanguage.EN)
        assertEquals(listOf("LV", "NL"), menu.map { it.key })
        assertEquals(listOf(null, null), menu.map { it.location })
        assertEquals(setOf("nl", "lv", "lv2"), menu.flatMap { it.foreignNodes }.map { it.id }.toSet())
        assertEquals(listOf(25, null), menu.map { it.latencyMs })
        assertEquals(emptyList<HomeServerMenuEntry>(), serverMenuLocations(emptyList(), nodes))
    }

    @Test
    fun `home location resolves selected node when country and location codes differ`() {
        val selectedServer = VpnServer(
            id = "lv4",
            name = "Latvia 4",
            countryCode = "LV",
            locationCode = "lv4",
            host = "lv4.example",
            probePort = 443,
            isOnline = true,
        )
        val location = location(code = "lv4").copy(servers = listOf(selectedServer))
        val profile = UserProfile(
            serverSelectionMode = ServerSelectionMode.SERVER,
            selectedCountryCode = "LV",
            selectedNodeId = "lv4",
        )

        assertEquals(location, homeSelectedLocation(listOf(location), profile))
    }

    @Test
    fun `metrics expose traffic only while connected but keep server health`() {
        val node = VpnServer("node2", "Latvia 2", "LV", "lv2", "lv2.example", 443, true, latencyMs = 39)
        val server = location(latencyMs = 24, loadPercent = 31).copy(servers = listOf(node))
        val state = AppUiState(
            locations = listOf(server),
            userProfile = UserProfile(selectedServerCode = "lv2"),
            connectionState = VpnConnectionState.CONNECTING,
        )
        val traffic = HomeDeviceTrafficSnapshot(downloadMbps = "2.5", uploadMbps = "1", sessionBytes = 1_048_576L)

        assertEquals(
            HomeMetricsSnapshot(download = null, upload = null, latency = "24", sessionBytes = null),
            currentMetrics(state, traffic),
        )
        assertEquals(
            HomeMetricsSnapshot(download = "2.5", upload = "1", latency = "39", sessionBytes = 1_048_576L),
            currentMetrics(state.copy(connectionState = VpnConnectionState.CONNECTED), traffic),
        )
        for (mode in ServerSelectionMode.entries) {
            val connected = state.copy(
                connectionState = VpnConnectionState.CONNECTED,
                userProfile = state.userProfile.copy(serverSelectionMode = mode, selectedNodeId = node.id),
            )
            assertEquals(node.latencyMs.toString(), currentMetrics(connected, traffic).latency)
            assertNull(currentMetrics(connected.copy(locations = listOf(server.copy(
                servers = listOf(node.copy(latencyMs = null)),
            ))), traffic).latency)
            assertNull(currentMetrics(connected.copy(userProfile = connected.userProfile.copy(
                selectedServerCode = "missing",
            )), traffic).latency)
        }
        val manual = state.copy(userProfile = state.userProfile.copy(
            serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = node.id,
        ))
        assertEquals("39", currentMetrics(manual, traffic).latency)
        assertEquals("39", currentMetrics(manual.copy(connectionState = VpnConnectionState.DISCONNECTED), traffic).latency)
        val relay = manual.copy(advancedSettings = manual.advancedSettings.copy(ruRelayEnabled = true),
            userProfile = manual.userProfile.copy(selectedServerCode = "stale-xray-profile"))
        for (connection in VpnConnectionState.entries) {
            assertEquals("39", currentMetrics(relay.copy(connectionState = connection), traffic).latency)
        }
    }

    private fun location(
        code: String = "lv",
        countryCode: String = "LV",
        latencyMs: Int? = null,
        loadPercent: Int? = null,
    ) = ServerLocation(
        code = code,
        countryCode = countryCode,
        country = "Latvia",
        city = "Riga",
        host = "vpn.example",
        latencyMs = latencyMs,
        loadPercent = loadPercent,
        isOnline = true,
    )
}
