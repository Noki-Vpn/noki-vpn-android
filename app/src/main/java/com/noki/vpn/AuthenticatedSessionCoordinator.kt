package com.noki.vpn

import com.noki.vpn.data.BackendAuthTokens
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.BackendRetryPolicy
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.VpnRuntimeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class AuthenticatedSessionCoordinator(
    private val authSessionCoordinator: AuthSessionCoordinator,
    private val registerCurrentDevice: suspend (String) -> BackendDevice,
    private val bindTokensToRegisteredDevice: suspend (BackendAuthTokens, BackendDevice) -> BackendAuthTokens =
        { tokens, device -> authSessionCoordinator.bindToDevice(tokens, device.id) },
    private val stageTemporaryVpnForRevoke: suspend () -> Unit,
    private val stopTemporaryVpn: () -> Unit,
    private val syncBootstrap: suspend (String, AppUiState) -> AppUiState,
    private val publishAuthenticatedState: (AppUiState) -> Unit,
) {
    data class Result(
        val tokens: BackendAuthTokens,
        val device: BackendDevice,
        val state: AppUiState,
    )

    suspend fun complete(
        tokens: BackendAuthTokens,
        preparedState: AppUiState,
    ): Result {
        val (device, boundTokens) = try {
            val registeredDevice = registerCurrentDevice(tokens.accessToken)
            registeredDevice to bindTokensToRegisteredDevice(tokens, registeredDevice)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { authSessionCoordinator.revokeProvisional(tokens) }
            }
            throw error
        }

        val sessionTokens = boundTokens.copy(credentialsSetupRequired = tokens.credentialsSetupRequired || boundTokens.credentialsSetupRequired)
        authSessionCoordinator.commit(sessionTokens)
        stageTemporaryVpnForRevoke()
        stopTemporaryVpn()

        val fallbackState = AuthStepReducer.requireCredentialsSetup(preparedState.copy(
            userProfile = preparedState.userProfile.copy(credentialsSetupRequired = sessionTokens.credentialsSetupRequired),
            connectionState = VpnConnectionState.DISCONNECTED,
            connectedAtMillis = null,
            vpnRuntimeMode = VpnRuntimeMode.ACCOUNT,
        ))
        val syncedState = try {
            syncBootstrap(boundTokens.accessToken, fallbackState)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (BackendRetryPolicy.isTransient(error)) fallbackState else throw error
        }
        val state = AuthStepReducer.requireCredentialsSetup(syncedState, fallbackState)
        publishAuthenticatedState(state)
        return Result(
            tokens = sessionTokens,
            device = device,
            state = state,
        )
    }
}
