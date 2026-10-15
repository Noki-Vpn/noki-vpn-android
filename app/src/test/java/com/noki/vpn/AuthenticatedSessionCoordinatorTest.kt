package com.noki.vpn

import com.noki.vpn.data.AuthRefreshApi
import com.noki.vpn.data.AuthTokenRefresher
import com.noki.vpn.data.BackendAuthTokens
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.InMemoryAtomicStoredSettingsStore
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.VpnRuntimeMode
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedSessionCoordinatorTest {
    @Test
    fun `pending credentials survive device binding bootstrap failure and durable token commit`() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(DefaultStoredSettingsFactory.create())
        val authSession = AuthSessionCoordinator(store, AuthTokenRefresher(store, NoRefreshApi))
        var published: AppUiState? = null
        val coordinator = AuthenticatedSessionCoordinator(
            authSessionCoordinator = authSession,
            registerCurrentDevice = { device() },
            bindTokensToRegisteredDevice = { tokens, _ -> tokens.copy(credentialsSetupRequired = false) },
            stageTemporaryVpnForRevoke = {
                assertTrue(store.load().userProfile.credentialsSetupRequired)
            },
            stopTemporaryVpn = {},
            syncBootstrap = { _, prepared ->
                assertEquals(AuthStep.REGISTRATION_PROFILE, prepared.authStep)
                assertEquals(AppDestination.REGISTRATION, prepared.currentDestination)
                throw IOException("bootstrap unavailable")
            },
            publishAuthenticatedState = { published = it },
        )

        val result = coordinator.complete(
            tokens().copy(credentialsSetupRequired = true),
            AppUiState(isAuthenticated = true, screenStack = listOf(AppDestination.HOME)),
        )

        assertTrue(result.tokens.credentialsSetupRequired)
        assertTrue(result.state.userProfile.credentialsSetupRequired)
        assertTrue(store.load().userProfile.credentialsSetupRequired)
        assertEquals(listOf(AppDestination.REGISTRATION), result.state.screenStack)
        assertEquals(AuthStep.REGISTRATION_PROFILE, result.state.authStep)
        assertEquals(result.state, published)
    }

    @Test
    fun `authoritative completed bootstrap removes pending setup and clears its form`() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(DefaultStoredSettingsFactory.create())
        val authSession = AuthSessionCoordinator(store, AuthTokenRefresher(store, NoRefreshApi))
        val coordinator = AuthenticatedSessionCoordinator(
            authSessionCoordinator = authSession,
            registerCurrentDevice = { device() },
            bindTokensToRegisteredDevice = { tokens, _ -> tokens },
            stageTemporaryVpnForRevoke = {},
            stopTemporaryVpn = {},
            syncBootstrap = { _, state -> state.copy(userProfile = state.userProfile.copy(credentialsSetupRequired = false, hasPassword = true)) },
            publishAuthenticatedState = {},
        )

        val result = coordinator.complete(
            tokens().copy(credentialsSetupRequired = true),
            AppUiState(isAuthenticated = true),
        )

        assertFalse(result.state.userProfile.credentialsSetupRequired)
        assertEquals(listOf(AppDestination.HOME), result.state.screenStack)
        assertEquals(RegistrationFormState(), result.state.registrationForm)
    }

    @Test
    fun `success persists tokens before durable temporary revoke bootstrap and publish`() = runBlocking {
        val events = mutableListOf<String>()
        val store = InMemoryAtomicStoredSettingsStore(DefaultStoredSettingsFactory.create())
        val authSession = AuthSessionCoordinator(store, AuthTokenRefresher(store, NoRefreshApi))
        var pendingRevokeSessionId: String? = null
        val coordinator = AuthenticatedSessionCoordinator(
            authSessionCoordinator = authSession,
            registerCurrentDevice = { token ->
                assertEquals("access", token)
                events += "register"
                device()
            },
            bindTokensToRegisteredDevice = { tokens, device ->
                assertEquals("device-1", device.id)
                events += "bind"
                tokens.copy(accessToken = "bound-access", refreshToken = "bound-refresh")
            },
            stageTemporaryVpnForRevoke = {
                assertEquals("bound-access", store.load().backendAccessToken)
                pendingRevokeSessionId = "auth-temp-session"
                events += "stage-revoke"
            },
            stopTemporaryVpn = {
                assertEquals("auth-temp-session", pendingRevokeSessionId)
                events += "stop-temp"
            },
            syncBootstrap = { token, state ->
                assertEquals("bound-access", token)
                events += "bootstrap"
                state.copy(userProfile = state.userProfile.copy(username = "telegram-user"))
            },
            publishAuthenticatedState = { state ->
                assertEquals("telegram-user", state.userProfile.username)
                events += "publish"
            },
        )

        val result = coordinator.complete(tokens(), AppUiState(isAuthenticated = true))

        assertEquals(
            listOf("register", "bind", "stage-revoke", "stop-temp", "bootstrap", "publish"),
            events,
        )
        assertTrue(store.load().isAuthenticated)
        assertEquals("bound-refresh", store.load().backendRefreshToken)
        assertEquals("auth-temp-session", pendingRevokeSessionId)
        assertEquals("telegram-user", result.state.userProfile.username)
    }

    @Test
    fun `failure before device registration leaves auth session and temporary vpn untouched`() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(DefaultStoredSettingsFactory.create())
        val authSession = AuthSessionCoordinator(store, AuthTokenRefresher(store, NoRefreshApi))
        var staged = false
        var stopped = false
        val coordinator = AuthenticatedSessionCoordinator(
            authSessionCoordinator = authSession,
            registerCurrentDevice = { error("device registration failed") },
            stageTemporaryVpnForRevoke = { staged = true },
            stopTemporaryVpn = { stopped = true },
            syncBootstrap = { _, state -> state },
            publishAuthenticatedState = {},
        )

        val result = runCatching { coordinator.complete(tokens(), AppUiState()) }

        assertTrue(result.isFailure)
        assertFalse(store.load().isAuthenticated)
        assertFalse(staged)
        assertFalse(stopped)
    }

    @Test
    fun `bootstrap failure still publishes authenticated state and disconnects temporary vpn`() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(DefaultStoredSettingsFactory.create())
        val authSession = AuthSessionCoordinator(store, AuthTokenRefresher(store, NoRefreshApi))
        var stopped = false
        var published: AppUiState? = null
        val coordinator = AuthenticatedSessionCoordinator(
            authSessionCoordinator = authSession,
            registerCurrentDevice = { device() },
            bindTokensToRegisteredDevice = { authTokens, _ -> authTokens },
            stageTemporaryVpnForRevoke = {},
            stopTemporaryVpn = { stopped = true },
            syncBootstrap = { _, _ -> throw IOException("bootstrap unavailable") },
            publishAuthenticatedState = { published = it },
        )
        val temporaryState = AppUiState(
            isAuthenticated = true,
            connectionState = VpnConnectionState.CONNECTED,
            connectedAtMillis = 123L,
            vpnRuntimeMode = VpnRuntimeMode.AUTH_TEMP,
        )

        val result = coordinator.complete(tokens(), temporaryState)

        assertTrue(stopped)
        assertTrue(store.load().isAuthenticated)
        assertEquals(VpnConnectionState.DISCONNECTED, result.state.connectionState)
        assertEquals(null, result.state.connectedAtMillis)
        assertEquals(VpnRuntimeMode.ACCOUNT, result.state.vpnRuntimeMode)
        assertEquals(result.state, published)
    }

    @Test
    fun `bind failure revokes provisional refresh and never commits it`() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(DefaultStoredSettingsFactory.create())
        val refreshApi = RecordingRefreshApi()
        val authSession = AuthSessionCoordinator(store, AuthTokenRefresher(store, refreshApi))
        val coordinator = AuthenticatedSessionCoordinator(
            authSessionCoordinator = authSession,
            registerCurrentDevice = { device() },
            bindTokensToRegisteredDevice = { _, _ -> error("bind failed") },
            stageTemporaryVpnForRevoke = {},
            stopTemporaryVpn = {},
            syncBootstrap = { _, state -> state },
            publishAuthenticatedState = {},
        )

        val result = runCatching { coordinator.complete(tokens(), AppUiState()) }

        assertTrue(result.isFailure)
        assertEquals(listOf("refresh"), refreshApi.revokedTokens)
        assertFalse(store.load().isAuthenticated)
        assertEquals(null, store.load().backendRefreshToken)
    }

    private object NoRefreshApi : AuthRefreshApi {
        override suspend fun refreshAuthToken(
            refreshToken: String,
            deviceId: String?,
            requestId: String?,
        ): BackendAuthTokens = error("refresh is not expected")
    }

    private class RecordingRefreshApi : AuthRefreshApi {
        val revokedTokens = mutableListOf<String>()

        override suspend fun refreshAuthToken(
            refreshToken: String,
            deviceId: String?,
            requestId: String?,
        ): BackendAuthTokens = error("refresh is not expected")

        override suspend fun revokeRefreshToken(refreshToken: String) {
            revokedTokens += refreshToken
        }
    }

    private fun tokens() = BackendAuthTokens(
        accessToken = "access",
        refreshToken = "refresh",
        expiresInSeconds = 3_600,
        refreshExpiresAt = "2026-07-22T00:00:00Z",
    )

    private fun device() = BackendDevice(
        id = "device-1",
        deviceKey = "device-key",
        deviceName = "Phone",
        platform = "android",
        accessRole = "owner",
        isActive = true,
        lastSeenAt = null,
    )
}
