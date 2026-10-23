package com.noki.vpn

import com.noki.vpn.data.clientLatencyTargetKey
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.VpnServer
import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.ServerSelectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientLatencyRequestTargetTest {
    @Test
    fun `direct and relay requests cannot reuse the same latency job`() {
        val locations = listOf(location("lv", "lv.example"))
        assertNotEquals(clientLatencyRequestTarget(locations), clientLatencyRequestTarget(locations, true,
            foreignNodes = listOf(RuForeignNode("overlay", "LV overlay", "LV"))))
    }

    @Test
    fun `relay latency belongs to the exact explicit entry exit tuple`() {
        val entryA = "11111111-1111-4111-8111-111111111111"
        val entryB = "22222222-2222-4222-8222-222222222222"
        val exit = "33333333-3333-4333-8333-333333333333"
        val server = VpnServer(exit, "Exit", "LV", "lv", "exit.example", 443, true,
            multiHopExitAvailable = true)
        val locations = listOf(location("lv", "lv.example").copy(servers = listOf(server,
            server.copy(id = "44444444-4444-4444-8444-444444444444", name = "Other"))))
        val nodes = listOf(RuForeignNode(exit, "Exit", "LV"),
            RuForeignNode("44444444-4444-4444-8444-444444444444", "Other", "NL"))
        fun chain(entry: String) = MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = entry),
            HopSelection(ServerSelectionMode.SERVER, nodeId = exit))

        val first = clientLatencyRequestTarget(emptyList(), true, chain(entryA), nodes)
        assertEquals(1, first.size)
        assertNotEquals(first, clientLatencyRequestTarget(emptyList(), true, chain(entryB), nodes))
        assertNotEquals(first, clientLatencyRequestTarget(emptyList(), true, foreignNodes = nodes))
        assertEquals(clientLatencyRequestTarget(locations), clientLatencyRequestTarget(locations, false, chain(entryA)))
        assertEquals(clientLatencyRequestTarget(locations),
            clientLatencyRequestTarget(locations, false, MultiHopSettings(enabled = true)))
        assertTrue(clientLatencyRequestTarget(locations, true, chain(exit)).isEmpty())
        assertTrue(clientLatencyRequestTarget(locations, true,
            chain(entryA).copy(exit = HopSelection(ServerSelectionMode.COUNTRY, countryCode = "LV"))).isEmpty())
        assertTrue(clientLatencyRequestTarget(locations, true,
            chain(entryA).copy(exit = HopSelection(ServerSelectionMode.SERVER,
                nodeId = "55555555-5555-4555-8555-555555555555")), nodes).isEmpty())
    }

    @Test
    fun `latency target ignores order but changes with endpoint host`() {
        val pl = location("pl", "pl.example")
        val lv = location("lv", "lv.example")

        assertEquals(
            clientLatencyRequestTarget(listOf(pl, lv)),
            clientLatencyRequestTarget(listOf(lv, pl)),
        )
        assertNotEquals(
            clientLatencyRequestTarget(listOf(pl)),
            clientLatencyRequestTarget(listOf(location("pl", "new-pl.example"))),
        )
    }

    @Test
    fun `latency cache belongs to exact code and host`() {
        val oldHost = location("pl", "old-pl.example")
        val newHost = location("pl", "new-pl.example")
        val cache = mapOf(requireNotNull(clientLatencyTargetKey(oldHost)) to 20)

        val sameHostState = BackendSyncCoordinator.withCachedClientLatencies(
            state = AppUiState(locations = listOf(oldHost)),
            clientLatencyByTarget = cache,
        )
        val newHostState = BackendSyncCoordinator.withCachedClientLatencies(
            state = AppUiState(locations = listOf(newHost)),
            clientLatencyByTarget = cache,
        )

        assertEquals(20, sameHostState.locations.single().latencyMs)
        assertFalse(BackendSyncCoordinator.hasMissingClientLatency(sameHostState.locations, cache))
        assertNull(newHostState.locations.single().latencyMs)
        assertTrue(BackendSyncCoordinator.hasMissingClientLatency(newHostState.locations, cache))
    }

    @Test
    fun `overlay latency presentation does not require a direct location`() {
        val node = RuForeignNode("overlay", "NL overlay", "NL")
        val state = BackendSyncCoordinator.withCachedClientLatencies(AppUiState(ruForeignNodes = listOf(node)),
            mapOf(clientLatencyTargetKey(node) to 34))
        assertEquals(34, state.ruForeignNodes.single().latencyMs)
        assertTrue(state.locations.isEmpty())
        assertNull(BackendSyncCoordinator.withCachedClientLatencies(state, emptyMap()).ruForeignNodes.single().latencyMs)
    }

    private fun location(code: String, host: String) = ServerLocation(
        code = code,
        country = code.uppercase(),
        city = code.uppercase(),
        host = host,
        isOnline = true,
    )
}
