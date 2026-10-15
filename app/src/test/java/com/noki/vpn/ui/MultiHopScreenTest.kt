package com.noki.vpn.ui

import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.AppUiState
import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.VpnServer
import com.noki.vpn.data.ServerLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiHopScreenTest {
    private val nodes = listOf(
        VpnServer("a", "NL overlay", "NL", "nl", "", null, true),
        VpnServer("b", "LV dual", "LV", "lv", "", null, true),
    )

    @Test fun concreteServerPairMustResolveToDifferentAvailableNodes() {
        assertNull(multiHopSelectionError(MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "a"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "b")), nodes))
        assertEquals("choose_entry", multiHopSelectionError(MultiHopSettings(true,
            HopSelection(ServerSelectionMode.COUNTRY, countryCode = "RU"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "b")), nodes))
        assertEquals("same_node", multiHopSelectionError(MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "a"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "a")), nodes))
        assertEquals("exit_unavailable", multiHopSelectionError(MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "a"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "offline")), nodes))
    }

    @Test fun russianOrWithdrawnParticipantCannotBeTheExplicitForeignEntry() {
        val selection = MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "a"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "b"))
        val state = AppUiState(advancedSettings = AdvancedSettings(ruRelayEnabled = true),
            ruForeignNodes = nodes.map { RuForeignNode(it.id, it.name, if (it.id == "a") "RU" else it.countryCode) })
        assertEquals("entry_unavailable", multiHopSelectionError(selection, multiHopNodes(state, true)))
        assertEquals("entry_unavailable", multiHopSelectionError(selection,
            nodes.filterNot { it.id == "a" }))
    }

    @Test fun noRuPickerUsesOnlyFreshNatEntryAndExitRoles() {
        val servers = listOf(nodes[0].copy(multiHopEntryAvailable = true),
            nodes[1].copy(multiHopExitAvailable = true),
            nodes[0].copy(id = "stale", isOnline = false, multiHopEntryAvailable = true),
            nodes[0].copy(id = "overlay-only"))
        val state = AppUiState(advancedSettings = AdvancedSettings(ruRelayEnabled = false),
            locations = listOf(ServerLocation(code = "lv", country = "Latvia", city = "", host = "", isOnline = true, servers = servers)),
            ruForeignNodes = listOf(RuForeignNode("overlay-only", "Overlay only", "NL")))
        assertEquals(listOf("a"), multiHopNodes(state, true).map { it.id })
        assertEquals(listOf("b"), multiHopNodes(state, false).map { it.id })
        val unavailableRuDiscovery = state.copy(advancedSettings = state.advancedSettings.copy(ruRelayEnabled = true),
            ruForeignNodes = emptyList())
        assertEquals(listOf("a"), multiHopNodes(unavailableRuDiscovery, true).map { it.id })
        assertEquals(listOf("b"), multiHopNodes(unavailableRuDiscovery, false).map { it.id })
        val selection = MultiHopSettings(true, HopSelection(ServerSelectionMode.SERVER, nodeId = "a"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "b"))
        assertNull(multiHopSelectionError(selection, multiHopNodes(state, true), multiHopNodes(state, false)))
        assertEquals("entry_unavailable", multiHopSelectionError(selection,
            multiHopNodes(state, false), multiHopNodes(state, true)))
    }
}
