package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.PersonalizationSettings
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateReducerTest {
    @Test
    fun tunnelLatencyFollowsConnectedServiceSnapshotsAndClearsOnEveryOtherState() {
        val coordinator = ConnectionUiCoordinator()
        val current = AppUiState(connectionState = VpnConnectionState.CONNECTED, connectionLatencyMs = 39)
        for (state in VpnConnectionState.entries) {
            for (latency in listOf(87, 0, -1, null)) {
                val transition = coordinator.reduce(current, state, ConnectionReason.Unknown(""),
                    1234L, 1234L, emptyList(), "", latency) as ConnectionStateReducer.Transition.Normal
                assertEquals(latency?.takeIf { state == VpnConnectionState.CONNECTED && it > 0 },
                    transition.state.connectionLatencyMs)
            }
        }
        for (reason in listOf("traffic_limit", "device_limit", "empty_selected_apps")) {
            val transition = coordinator.reduce(current, VpnConnectionState.FAILED, ConnectionReason.parse(reason),
                null, 1234L, emptyList(), "", 87)
            val failed = when (transition) {
                is ConnectionStateReducer.Transition.Normal -> transition.state
                is ConnectionStateReducer.Transition.TrafficLimit -> transition.state
                is ConnectionStateReducer.Transition.DeviceLimit -> transition.state
                is ConnectionStateReducer.Transition.EmptySelectedApps -> transition.state
            }
            assertEquals(null, failed.connectionLatencyMs)
        }
    }

    @Test
    fun requiredClientUpdateBlocksWithModalWithoutInlineErrorOrLostDiagnosticCode() {
        for (language in listOf(AppLanguage.RU, AppLanguage.EN)) {
            val result = ConnectionStateReducer.reduce(
                current = AppUiState(
                    personalizationSettings = PersonalizationSettings(language = language),
                    connectedAtMillis = 1234L,
                    screenStack = listOf(AppDestination.HOME),
                ),
                state = VpnConnectionState.FAILED,
                reason = ConnectionReason.parse("client_update_required"),
                connectedAtMillis = null, nowMillis = 1L, dailyStats = emptyList(), endpointRating = "",
            ) as ConnectionStateReducer.Transition.Normal

            assertEquals("", result.state.connectionReason)
            assertEquals(null, result.state.inlineMessage)
            assertTrue(result.state.androidUpdate.requiresUpdate)
            assertEquals(null, result.state.androidUpdate.update)
            assertEquals(null, result.state.connectedAtMillis)
            assertEquals(ConnectionStateReducer.RuntimeAction.Clear, result.runtimeAction)
            assertEquals("client_update_required", result.logAction?.errorType)
            assertEquals(listOf(AppDestination.HOME), result.state.screenStack)
        }
    }

    @Test
    fun explicitStopClearsPreviousNetworkWait() {
        val stopped = ConnectionStateReducer.reduce(
            current = AppUiState(connectionState = VpnConnectionState.FAILED, connectionReason = "Ожидание сети"),
            state = VpnConnectionState.DISCONNECTED,
            reason = ConnectionReason.Unknown("user_stop"),
            connectedAtMillis = null, nowMillis = 1L, dailyStats = emptyList(), endpointRating = "",
        ) as ConnectionStateReducer.Transition.Normal
        assertEquals(VpnConnectionState.DISCONNECTED, stopped.state.connectionState)
        assertEquals("", stopped.state.connectionReason)
        assertEquals(null, stopped.state.inlineMessage)
        assertEquals(ConnectionStateReducer.RuntimeAction.Clear, stopped.runtimeAction)
    }

    @Test
    fun unavailableUnderlayExplainsNetworkWaitInBothLanguages() {
        for ((language, label) in listOf(AppLanguage.RU to "Ожидание сети", AppLanguage.EN to "Waiting for network")) {
            val result = ConnectionStateReducer.reduce(
                current = AppUiState(personalizationSettings = PersonalizationSettings(language = language)),
                state = VpnConnectionState.FAILED,
                reason = ConnectionReason.Unknown("underlay_unavailable"),
                connectedAtMillis = null, nowMillis = 1L, dailyStats = emptyList(), endpointRating = "",
            ) as ConnectionStateReducer.Transition.Normal
            assertEquals(label, result.state.connectionReason)
            assertTrue(result.state.inlineMessage.orEmpty().isNotBlank())
            assertTrue(!result.state.inlineMessage.orEmpty().contains("underlay_unavailable"))
        }
    }

    @Test
    fun ruExitProbeFailureIsReadableWithoutLosingDiagnosticCode() {
        val result = ConnectionStateReducer.reduce(
            current = AppUiState(personalizationSettings = PersonalizationSettings(language = AppLanguage.RU)),
            state = VpnConnectionState.FAILED,
            reason = ConnectionReason.Unknown("ru_wireguard_exit_probe_failed"),
            connectedAtMillis = null, nowMillis = 1L, dailyStats = emptyList(), endpointRating = "",
        ) as ConnectionStateReducer.Transition.Normal

        assertEquals("Нет связи с выходом", result.state.connectionReason)
        assertEquals("RU-реле подключено, но выходная нода недоступна", result.state.inlineMessage)
        assertEquals("ru_wireguard_exit_probe_failed", result.logAction?.details)
    }

    @Test
    fun trafficLimitFailureRequestsTrafficLimitHandling() {
        val transition = ConnectionStateReducer.reduce(
            current = AppUiState(
                personalizationSettings = PersonalizationSettings(language = AppLanguage.EN),
                userProfile = UserProfile(subscriptionStatus = "active"),
            ),
            state = VpnConnectionState.FAILED,
            reason = ConnectionReason.TrafficLimit,
            connectedAtMillis = null,
            nowMillis = 9999L,
            dailyStats = emptyList(),
            endpointRating = "rating=bad",
        )

        assertTrue(transition is ConnectionStateReducer.Transition.TrafficLimit)
        val trafficLimit = transition as ConnectionStateReducer.Transition.TrafficLimit
        assertEquals(VpnConnectionState.FAILED, trafficLimit.state.connectionState)
        assertEquals("limited", trafficLimit.state.userProfile.subscriptionStatus)
        assertEquals("Free traffic limit reached", trafficLimit.state.connectionReason)
    }

    @Test
    fun failedStateClearsRuntimeStateAndLogsFailure() {
        val transition = ConnectionStateReducer.reduce(
            current = AppUiState(
                personalizationSettings = PersonalizationSettings(language = AppLanguage.EN),
                connectedAtMillis = 1234L,
            ),
            state = VpnConnectionState.FAILED,
            reason = ConnectionReason.Unknown("core_start_error"),
            connectedAtMillis = null,
            nowMillis = 9999L,
            dailyStats = emptyList(),
            endpointRating = "rating=bad",
        )

        assertTrue(transition is ConnectionStateReducer.Transition.Normal)
        val normal = transition as ConnectionStateReducer.Transition.Normal
        assertEquals(null, normal.state.connectedAtMillis)
        assertEquals("Core start error", normal.state.connectionReason)
        assertEquals("Failed to start VPN runtime", normal.state.inlineMessage)
        assertEquals(ConnectionStateReducer.RuntimeAction.Clear, normal.runtimeAction)
        assertEquals("connect_fail", normal.logAction?.message)
        assertEquals("core_start_error", normal.logAction?.errorType)
    }

    @Test
    fun disconnectedCleanupDoesNotEraseShownFailure() {
        val failed = ConnectionStateReducer.reduce(
            current = AppUiState(
                personalizationSettings = PersonalizationSettings(language = AppLanguage.EN),
            ),
            state = VpnConnectionState.FAILED,
            reason = ConnectionReason.Unknown("core_start_error"),
            connectedAtMillis = null,
            nowMillis = 9999L,
            dailyStats = emptyList(),
            endpointRating = "rating=bad",
        ) as ConnectionStateReducer.Transition.Normal

        val cleanup = ConnectionStateReducer.reduce(
            current = failed.state,
            state = VpnConnectionState.DISCONNECTED,
            reason = ConnectionReason.Unknown(""),
            connectedAtMillis = null,
            nowMillis = 10000L,
            dailyStats = emptyList(),
            endpointRating = "rating=bad",
        ) as ConnectionStateReducer.Transition.Normal

        assertEquals(VpnConnectionState.FAILED, cleanup.state.connectionState)
        assertEquals("Core start error", cleanup.state.connectionReason)
        assertEquals("Failed to start VPN runtime", cleanup.state.inlineMessage)
        assertEquals(ConnectionStateReducer.RuntimeAction.Clear, cleanup.runtimeAction)
        assertEquals(null, cleanup.logAction)
    }
}
