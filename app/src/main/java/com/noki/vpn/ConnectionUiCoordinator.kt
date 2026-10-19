package com.noki.vpn

import com.noki.vpn.data.DailyStats
import com.noki.vpn.data.VpnConnectionState

internal class ConnectionUiCoordinator {
    data class LimitNoticeDecision(
        val showNotification: Boolean,
        val showDialog: Boolean,
    )

    private var dialogShown = false
    private var notificationShown = false

    @Synchronized
    fun limitNotice(
        limitReached: Boolean,
        forceDialog: Boolean,
        hasActiveDialog: Boolean,
    ): LimitNoticeDecision {
        if (!limitReached) {
            dialogShown = false
            notificationShown = false
            return LimitNoticeDecision(false, false)
        }
        val showNotification = !notificationShown
        val showDialog = forceDialog || (!dialogShown && !hasActiveDialog)
        if (showNotification) notificationShown = true
        if (showDialog) dialogShown = true
        return LimitNoticeDecision(showNotification, showDialog)
    }

    fun reduce(
        current: AppUiState,
        state: VpnConnectionState,
        reason: ConnectionReason,
        connectedAtMillis: Long?,
        nowMillis: Long,
        dailyStats: List<DailyStats>,
        endpointRating: String,
        latencyMs: Int? = null,
    ): ConnectionStateReducer.Transition = ConnectionStateReducer.reduce(
        current = current,
        state = state,
        reason = reason,
        connectedAtMillis = connectedAtMillis,
        nowMillis = nowMillis,
        dailyStats = dailyStats,
        endpointRating = endpointRating,
        latencyMs = latencyMs,
    )
}
