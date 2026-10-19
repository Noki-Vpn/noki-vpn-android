package com.noki.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.PlanCode
import com.noki.vpn.data.VpnConnectionState

object VpnStartStateReducer {
    fun freeTrafficLimitReached(state: AppUiState): Boolean {
        val profile = state.userProfile
        if (!state.isAuthenticated || profile.freeLocationsOnly) return false
        val free = profile.selectedPlanCode == PlanCode.FREE ||
            profile.selectedPlanCodeRaw.equals("free", ignoreCase = true) ||
            profile.selectedPlanCodeRaw.startsWith("free-", ignoreCase = true)
        return free && profile.subscriptionStatus.equals("limited", ignoreCase = true)
    }

    fun notAuthenticatedState(current: AppUiState): AppUiState {
        val language = current.personalizationSettings.language
        return current.copy(
            connectionState = VpnConnectionState.FAILED,
            connectionReason = tr(language, "Нужно войти в аккаунт", "You need to sign in"),
            inlineMessage = tr(language, "Сначала войдите в аккаунт", "Please sign in first"),
        )
    }

    fun freeTrafficLimitState(current: AppUiState): AppUiState {
        val language = current.personalizationSettings.language
        return current.copy(
            connectionState = VpnConnectionState.FAILED,
            connectedAtMillis = null,
            connectionReason = tr(language, "Бесплатный трафик закончился", "Free traffic limit reached"),
            inlineMessage = null,
        )
    }

    fun connectingState(current: AppUiState): AppUiState {
        val language = current.personalizationSettings.language
        return current.copy(
            connectionState = VpnConnectionState.CONNECTING,
            connectedAtMillis = null,
            connectionReason = "",
            inlineMessage = tr(language, "Подключение…", "Connecting…"),
        )
    }

    private fun tr(
        language: AppLanguage,
        russian: String,
        english: String,
    ): String {
        return if (language == AppLanguage.RU) russian else english
    }
}
